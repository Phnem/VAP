package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.player.*
import com.phnem.vap.player.subtitles.*
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.ui.PlayerOrientation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

private val styledAssFixture = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
ScaledBorderAndShadow: yes
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Roboto,30,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,0,7,10,10,10,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,{\an7\move(20,100,300,100,0,4000)\t(0,4000,\fscx150)\fad(200,200)}ASS VAP
""".trimIndent()

fun Instrumentation.runAssDeviceTests(keepFixture: Boolean = false) {
    var failure: Throwable? = null; var handle = 0L; var p95 = 0L
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var activity: PlayerActivity? = null
    val prefs = PlayerPreferences(targetContext)
    val uri = android.net.Uri.fromFile(File(targetContext.filesDir, "subtitle-fixtures/black.mp4")).toString()
    val original = runBlocking { prefs.settings.first() }; val saved = runBlocking { prefs.fileSettings(uri).first() }
    try {
        check(AssNative.available) { "Native libass cannot load" }
        val font = listOf("Roboto-Regular.ttf", "RobotoVariable.ttf", "NotoSans-Regular.ttf").map { File("/system/fonts", it) }.first { it.isFile }
        handle = AssNative.create(styledAssFixture.toByteArray(), font.absolutePath); check(handle != 0L)
        val first = checkNotNull(AssNative.render(handle, 500, 640, 360)); check(first.bitmap != null)
        val second = checkNotNull(AssNative.render(handle, 2500, 640, 360)); check(second.bitmap != null)
        check(second.x > first.x + 90) { "ASS move did not change rendered coordinates: ${first.x} -> ${second.x}" }
        check(second.bitmap!!.width > first.bitmap!!.width) { "ASS transform did not scale text" }
        check(AssNative.render(handle, 2500, 640, 360) == null) { "Unchanged frame allocated a bitmap" }
        check(AssNative.render(handle, 5000, 640, 360)?.bitmap == null) { "Expired caption remained visible" }
        check(AssNative.render(handle, 500, 640, 360)?.bitmap != null) { "Backward seek lost caption" }
        first.bitmap?.recycle(); second.bitmap?.recycle()
        val timings = (0 until 90).map { index ->
            val start = System.nanoTime()
            AssNative.render(handle, 800 + index * 25L, 1920, 1080)?.bitmap?.recycle()
            System.nanoTime() - start
        }.sorted()
        p95 = timings[(timings.size * .95).toInt()] / 1000
        check(p95 < 80_000) { "ASS render is too slow: p95 $p95 us" }
        AssNative.release(handle); handle = 0

        val events = styledAssFixture.substringBefore("Dialogue:")
        val karaoke = events + "Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,{\\an7\\pos(20,100)}{\\kf100}Ka{\\kf100}ra{\\kf100}oke"
        handle = AssNative.create(karaoke.toByteArray(), font.absolutePath); check(handle != 0L)
        fun colours(bitmap: android.graphics.Bitmap): Pair<Int, Int> {
            var yellow = 0; var red = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y); val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
                if (android.graphics.Color.alpha(c) > 128 && r > 150 && b < 40) { if (g > 150) yellow++ else if (g < 40) red++ }
            }
            return yellow to red
        }
        val early = checkNotNull(AssNative.render(handle, 500, 640, 360)?.bitmap)
        val late = checkNotNull(AssNative.render(handle, 2500, 640, 360)?.bitmap)
        val earlyColours = colours(early); val lateColours = colours(late)
        check(earlyColours.first > 5 && earlyColours.second > 5 && lateColours.first > earlyColours.first) { "Karaoke did not advance colour: $earlyColours -> $lateColours" }
        early.recycle(); late.recycle(); AssNative.release(handle); handle = 0
        val vector = events + "Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,{\\an7\\pos(20,100)\\p1}m 0 0 l 80 0 80 40 0 40"
        handle = AssNative.create(vector.toByteArray(), font.absolutePath); check(handle != 0L)
        val drawing = checkNotNull(AssNative.render(handle, 1000, 640, 360)?.bitmap)
        check(drawing.width >= 75 && drawing.height >= 35); drawing.recycle(); AssNative.release(handle); handle = 0

        val embedded = runBlocking { EmbeddedSubtitleExtractor.extract(targetContext, android.net.Uri.fromFile(File(targetContext.filesDir, "subtitle-fixtures/styled.mkv"))) }.single()
        val raw = checkNotNull(embedded.assDocument)
        check(raw.contains("\\move(20,100,300,100,0,4000)") && raw.contains("Style: Default,VAPFixture")) { "Embedded ASS styles were lost" }
        val fonts = runBlocking { MatroskaFonts.read(targetContext, android.net.Uri.fromFile(File(targetContext.filesDir, "subtitle-fixtures/styled.mkv"))) }
        check(fonts.size == 1 && fonts.single().name == "VAPFixture.ttf" && fonts.single().bytes.size > 100_000)
        val fontCopy = File(targetContext.cacheDir, "vap-fixture.ttf").apply { writeBytes(fonts.single().bytes) }
        handle = AssNative.create(raw.toByteArray(), font.absolutePath, arrayOf(fontCopy.absolutePath)); check(handle != 0L)
        val embeddedFrame = AssNative.render(handle, 500, 640, 360)
        check(embeddedFrame?.bitmap != null && kotlin.math.abs(embeddedFrame.x - first.x) < 5) { "Embedded ASS timing/layout differs: $raw" }
        check(embeddedFrame.bitmap!!.width != first.bitmap!!.width) { "Attached custom font fell back to system Roboto" }
        embeddedFrame.bitmap?.recycle(); AssNative.release(handle); handle = 0

        val storage = SubtitleStorage(targetContext)
        val assFile = File(targetContext.filesDir, "subtitle-fixtures/styled.ass").apply { writeText(styledAssFixture) }
        val external = runBlocking { storage.import(android.net.Uri.fromFile(assFile)) }
        check(external.format == "ass" && runBlocking { storage.readAss(external.id) }.isNotEmpty())
        val source = runBlocking { storage.create(embedded.title, embedded.language, embedded.lines, embedded.assDocument, fonts) }
        val secondary = runBlocking { storage.create("Secondary", "en", listOf(SubtitleLine(0, 4000, "Secondary VAP"))) }
        runBlocking {
            prefs.updateSettings { it.copy(orientation = PlayerOrientation.LANDSCAPE) }
            prefs.setSubtitles(uri, SubtitleSettings(selection = source.id, secondary = secondary.id, sources = listOf(source, secondary), discovered = true))
        }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }; player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player!!
        runOnMainSync { controller.setMediaItem(PlayableItem(uri, "ASS render test").toMediaItem()); controller.prepare(); controller.pause() }
        awaitUi("ASS video ready") { uiRead { controller.playbackState == Player.STATE_READY } }
        runOnMainSync { controller.seekTo(2000) }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a ${PlaybackService.ACTION_RESUME}").close()
        activity = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity; removeMonitor(monitor); check(activity != null)
        awaitUi("second language alongside ASS") { findUiNode { it.contentDescription?.toString()?.contains("Secondary VAP") == true } != null }
        awaitUi("actual yellow ASS pixels over black video") {
            val bitmap = uiAutomation.takeScreenshot()
            var count = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
                if (r > 50 && g > 50 && kotlin.math.abs(r-g) < 25 && b < 35) count++
            }
            bitmap.recycle(); count > 40
        }
        uiScreenshot("ass-styles-dual.png")
        val embeddedUri = android.net.Uri.fromFile(File(targetContext.filesDir, "subtitle-fixtures/styled.mkv")).toString()
        runBlocking { prefs.setSubtitles(embeddedUri, SubtitleSettings(language = "en", discovered = true)) }
        runOnMainSync { controller.setMediaItem(PlayableItem(embeddedUri, "Automatic embedded ASS").toMediaItem()); controller.prepare(); controller.play() }
        awaitUi("automatic native ASS preparation") {
            runBlocking { prefs.fileSettings(embeddedUri).first() }.subtitles.let { value ->
                value.sources.any { it.id == value.selection && it.format == "ass" && it.embeddedTrackId.isNotBlank() }
            }
        }
        val auto = runBlocking { prefs.fileSettings(embeddedUri).first() }.subtitles
        check(runBlocking { storage.fonts(auto.selection) }.size == 1)
        val originalStamp = auto.sources.first { it.id == auto.selection }.originStamp
        val media = File(targetContext.filesDir, "subtitle-fixtures/styled.mkv")
        check(media.setLastModified(media.lastModified() + 5000))
        val recreateMonitor = addMonitor(PlayerActivity::class.java.name, null, false)
        runOnMainSync { activity!!.recreate() }
        activity = waitForMonitorWithTimeout(recreateMonitor, 10000) as? PlayerActivity; removeMonitor(recreateMonitor); check(activity != null)
        awaitUi("modified video invalidates extracted ASS cache") {
            runBlocking { prefs.fileSettings(embeddedUri).first() }.subtitles.let { next ->
                next.selection != auto.selection && next.sources.any { it.id == next.selection && it.originStamp != originalStamp }
            }
        }
    } catch (e: Throwable) { failure = e; runCatching { uiScreenshot("ass-failure.png") } }
    finally {
        if (handle != 0L) AssNative.release(handle)
        runOnMainSync { player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking { prefs.updateSettings { original }; if (!keepFixture) prefs.setSubtitles(uri, saved.subtitles) }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: libass styles/move/transform/fade/karaoke/vector/seek, p95=${p95}us at 1080p, UI pixels + SRT, embedded ASS + attached font, automatic selection and cache invalidation\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}
