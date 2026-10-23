package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.session.MediaController
import com.phnem.vap.ai.sync.*
import com.phnem.vap.ai.whisper.*
import com.phnem.vap.network.NetworkPlayback
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runSmartSyncDeviceTests() {
    val report = Bundle(); var failure: Throwable? = null
    val ai = AiRuntime.get(targetContext); val runtime = SmartSyncRuntime.get(targetContext)
    val fixtures = File(targetContext.filesDir, "whisper-fixtures")
    val video = File(fixtures, "sync-${SystemClock.elapsedRealtime()}.mp4")
    val uri = Uri.fromFile(video).toString()
    val preferences = PlayerPreferences(targetContext)
    var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var activity: Activity? = null
    var previousItems = emptyList<MediaItem>(); var previousIndex = 0; var previousPosition = 0L
    var original: SubtitleSource? = null; var saved: SubtitleSource? = null
    try {
        uiAutomation.executeShellCommand("am start -W -n com.phnem.vap/.MainActivity").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        File(fixtures, "jfk.mp4").copyTo(video)
        val raw = runBlocking {
            val samples = AudioExtractor(targetContext, File(targetContext.cacheDir, "sync-truth-audio")).extract(
                MediaItem.fromUri(Uri.fromFile(File(fixtures, "jfk.wav"))), NetworkPlayback.sources(targetContext), 0, 11_000)
            ai.engine.transcribe(File(fixtures, WhisperModel.TINY.fileName), samples, "en", words = true)
        }
        val words = raw.lines.flatMap { line -> SmartSync.tokens(line.text).map { SyncWord(it, line.startMs, line.endMs) } }
        check(words.size >= 16) { "Too few actual word timestamps: ${words.size}" }
        val lines = words.chunked(8).filter { it.size >= 4 }.map { part -> SubtitleLine(part.first().startMs + 3000, part.last().endMs + 3000, part.joinToString(" ") { it.key }) }
        check(lines.size >= 2)
        original = runBlocking { SubtitleStorage(targetContext).create("Smart Sync shifted fixture", "en", lines) }
        runBlocking { preferences.setSubtitles(uri, SubtitleSettings(sources = listOf(original!!), selection = original!!.id)) }
        runOnMainSync { connection = PlaybackConnection.connect(targetContext) }
        player = connection!!.get(15, TimeUnit.SECONDS)
        runOnMainSync {
            previousItems = (0 until player!!.mediaItemCount).map(player!!::getMediaItemAt)
            previousIndex = player!!.currentMediaItemIndex.coerceAtLeast(0); previousPosition = player!!.currentPosition
            player!!.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(MediaMetadata.Builder().setTitle("Smart Sync fixture").build()).build())
            player!!.prepare(); player!!.pause()
        }
        awaitUi("sync video ready") { uiRead { player!!.playbackState == Player.STATE_READY } }
        runOnMainSync {
            val group = player!!.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).language == "en" }
            player!!.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        }
        awaitUi("sync selected speech") { uiRead { player!!.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).language == "en" && it.isSelected } } }
        val owned = File(targetContext.filesDir, "whisper-models/${WhisperModel.TINY.fileName}")
        if (!owned.exists()) { owned.parentFile!!.mkdirs(); File(fixtures, owned.name).copyTo(owned) }
        val monitor = addMonitor("com.phnem.vap.player.PlayerActivity", null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x18000000 -n com.phnem.vap/.player.PlayerActivity -a ${com.phnem.vap.player.PlaybackService.ACTION_RESUME}").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        activity = waitForMonitorWithTimeout(monitor, 10_000); removeMonitor(monitor)
        awaitUi("sync visible Activity") { uiRead { activity?.hasWindowFocus() == true } }
        runOnMainSync { runtime.analyze(player!!, original!!, WhisperModel.TINY, "en", false) }
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (ai.state.value.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(!ai.state.value.running && ai.state.value.error == null) { "Sync task: ${ai.state.value}" }
        val result = runtime.result.value ?: error("No sync result")
        val fit = result.analysis?.constant ?: error("No correspondence: words=$words")
        check(fit.delayMs in -3800..-2200 && fit.residualMs <= 800 && fit.anchors >= 2) { "Wrong actual sync fit: $fit" }
        check(runBlocking { preferences.fileSettings(uri).first().subtitles.delayMs } == 0L) { "Analysis changed stored timing" }
        report.putString("fit", fit.toString())
        fun reveal(label: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(12) {
                findUiNode { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
                val shot = uiAutomation.takeScreenshot(); uiDrag(shot.width * .75f, shot.height * .82f, shot.width * .75f, shot.height * .35f); shot.recycle()
            }
            return labelNode(label)
        }
        fun openSync() {
            if (findUiNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") } == null) {
                val shot = uiAutomation.takeScreenshot(); uiTap(shot.width * .05f, shot.height * .4f); shot.recycle()
            }
            clickUi(awaitNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") })
            // Вкладка последняя в горизонтально прокручиваемом ряду: дожидаемся шторки и прокручиваем ряд.
            labelNode(targetContext.getString(R.string.sub_tracks)); waitForIdleSync(); SystemClock.sleep(900) // шторка выезжает на пружине
            val first = android.graphics.Rect().also { labelNode(targetContext.getString(R.string.sub_tracks)).getBoundsInScreen(it) }
            repeat(4) {
                if (findUiNode { it.text?.toString() == targetContext.getString(R.string.sync_title) } == null) {
                    val shot = uiAutomation.takeScreenshot(); uiDrag(shot.width * .7f, first.exactCenterY(), shot.width * .2f, first.exactCenterY()); shot.recycle()
                    SystemClock.sleep(250)
                }
            }
            clickUi(labelNode(targetContext.getString(R.string.sync_title)))
        }
        val at = words.first().startMs + 300
        runOnMainSync { player!!.seekTo(at) }
        openSync(); clickUi(reveal(targetContext.getString(R.string.sync_preview)))
        labelNode(targetContext.getString(R.string.sync_apply_preview))
        awaitUi("preview renders shifted caption") { findUiNode { it.contentDescription?.toString()?.contains(lines.first().text) == true } != null }
        check(runBlocking { preferences.fileSettings(uri).first().subtitles.delayMs } == 0L)
        uiScreenshot("smart-sync-preview.png")
        clickUi(labelNode(targetContext.getString(R.string.sync_cancel_preview)))
        awaitUi("preview cancellation restores caption") { findUiNode { it.contentDescription?.toString()?.contains(lines.first().text) == true } == null }
        check(runBlocking { preferences.fileSettings(uri).first().subtitles.delayMs } == 0L)
        openSync(); clickUi(reveal(targetContext.getString(R.string.sync_preview)))
        clickUi(labelNode(targetContext.getString(R.string.sync_apply_preview)))
        awaitUi("preview apply stored") { runBlocking { preferences.fileSettings(uri).first().subtitles.delayMs } == fit.delayMs }
        openSync(); clickUi(reveal(targetContext.getString(R.string.sync_save)))
        awaitUi("corrected track saved") { runBlocking { preferences.fileSettings(uri).first().subtitles.sources.size } == 2 }
        val settings = runBlocking { preferences.fileSettings(uri).first().subtitles }
        saved = settings.sources.first { it.id != original!!.id }
        check(settings.selection == saved!!.id && settings.delayMs == 0L && settings.scale == 1.0)
        val corrected = runBlocking { SubtitleStorage(targetContext).read(saved!!.id) }
        check(corrected.first().startMs <= 800 && corrected.map { it.text } == lines.map { it.text })
        check(runBlocking { SubtitleStorage(targetContext).read(original!!.id) } == lines)
        check(SubtitleDocument.parse(SubtitleDocument.export(corrected, true)) == corrected)
        runBlocking {
            SubtitleStorage(targetContext).write(original!!.id, lines.map { it.copy(text = it.text + " edited") })
            check(runCatching { runtime.save(result, fit) }.isFailure) { "Stale result applied after editing source" }
        }
        report.putString("stream", "PASS: actual Whisper word timestamps and selected MP4 audio, automatic ~-3s shift, transient rendered preview/cancel, explicit timing apply, corrected SRT/VTT copy without double shift, original preserved and stale-result rejection; $fit\n")
    } catch (error: Throwable) { failure = error; report.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}\n"); runCatching { uiScreenshot("smart-sync-failure.png") } }
    finally {
        runOnMainSync { ai.cancel(); activity?.finish() }
        runBlocking { preferences.resetFile(uri); original?.let { SubtitleStorage(targetContext).delete(it.id) }; saved?.let { SubtitleStorage(targetContext).delete(it.id) } }
        runOnMainSync { player?.apply { if (previousItems.isNotEmpty()) { setMediaItems(previousItems, previousIndex, previousPosition); prepare(); pause() } }; connection?.let(MediaController::releaseFuture) }
        video.delete()
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
}
