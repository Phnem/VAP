package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.ai.whisper.*
import com.phnem.vap.network.NetworkPlayback
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.buffer
import okio.source

/** Actual CPU inference and Transformer; no fake recognition output. JFK fixture from whisper.cpp. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runWhisperDeviceTests() {
    val report = Bundle(); var failure: Throwable? = null
    var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null
    var previousItems = emptyList<MediaItem>(); var previousIndex = 0; var previousPosition = 0L
    val runtime = AiRuntime.get(targetContext)
    val fixtures = File(targetContext.filesDir, "whisper-fixtures")
    val model = File(fixtures, WhisperModel.TINY.fileName)
    val video = File(fixtures, "jfk-${SystemClock.elapsedRealtime()}.mp4").also { File(fixtures, "jfk.mp4").copyTo(it) }
    val uri = Uri.fromFile(video).toString()
    val preferences = PlayerPreferences(targetContext)
    val original = runBlocking { preferences.fileSettings(uri).first() }
    var source: SubtitleSource? = null
    val modelDir = File(targetContext.cacheDir, "whisper-model-test").apply { mkdirs() }
    try {
        check(model.length() == WhisperModel.TINY.bytes && video.isFile)
        runBlocking {
            // The downloader really resumes, hashes 32 MB, renames, and removes the owned model.
            var range: String? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                range = chain.request().header("Range")
                val offset = range?.substringAfter("bytes=")?.substringBefore('-')?.toLong() ?: 0L
                val body = object : ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = model.length() - offset
                    override fun source(): okio.BufferedSource = model.inputStream().apply { skip(offset) }.source().buffer()
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test").code(if (offset > 0) 206 else 200)
                    .apply { if (offset > 0) header("Content-Range", "bytes $offset-${model.length() - 1}/${model.length()}") }.body(body).build()
            }.build()
            val part = File(modelDir, model.name + ".part")
            model.inputStream().use { input -> part.outputStream().use { out -> out.write(input.readNBytes(1_048_576)) } }
            val store = WhisperModels(modelDir, client)
            val downloaded = store.download(WhisperModel.TINY)
            check(range == "bytes=1048576-" && downloaded.length() == model.length() && !part.exists())
            store.delete(WhisperModel.TINY); check(store.ready(WhisperModel.TINY) == null)

            val cache = TranscriptCache(File(targetContext.cacheDir, "whisper-cache-test"))
            val key = cache.key("test fixture")
            val expected = Transcript("en", setOf(0L), listOf(SubtitleLine(100, 900, "Unicode 🙂 Привет")))
            cache.write(key, expected); check(cache.read(key) == expected); cache.clear(); check(cache.read(key) == null)
            val samples = withTimeout(40_000) { AudioExtractor(targetContext, File(targetContext.cacheDir, "whisper-test-audio")).extract(
                MediaItem.fromUri(Uri.fromFile(File(fixtures, "jfk.wav"))), NetworkPlayback.sources(targetContext), 0, 11_000) }
            check(samples.size in 160_000..177_000) { "Wrong PCM size: ${samples.size}" }
            check(samples.any { kotlin.math.abs(it) > .05f })
            val engine = WhisperEngine(); check(engine.available)
            var progress = 0
            val started = SystemClock.elapsedRealtime()
            val result = withTimeout(90_000) { engine.transcribe(model, samples) { progress = it } }
            val text = result.lines.joinToString(" ") { it.text }
            check(result.language == "en" && text.contains("country", true) && text.contains("ask", true)) { "Actual Whisper result: ${result.language}: $text" }
            check(result.lines.all { it.endMs > it.startMs } && progress > 0)
            report.putString("transcript", text)
            report.putLong("inferenceMs", SystemClock.elapsedRealtime() - started)
            // Cancellation aborts a genuine native decode; the following request must still work.
            val cancelled = launch { engine.transcribe(model, samples.copyOf(480_000)) }
            delay(500); cancelled.cancelAndJoin()
            val after = withTimeout(90_000) { engine.transcribe(model, samples, "en") }
            check(after.lines.any { it.text.contains("country", true) })
        }
        runOnMainSync { connection = PlaybackConnection.connect(targetContext) }
        player = connection!!.get(15, TimeUnit.SECONDS)
        runOnMainSync {
            previousItems = (0 until player!!.mediaItemCount).map(player!!::getMediaItemAt)
            previousIndex = player!!.currentMediaItemIndex.coerceAtLeast(0); previousPosition = player!!.currentPosition
            player!!.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("VAP Whisper test").build()).build())
            player!!.prepare(); player!!.pause()
        }
        awaitUi("Whisper fixture ready") { uiRead { player!!.playbackState == Player.STATE_READY && player!!.duration > 0 } }
        runOnMainSync {
            val group = player!!.currentTracks.groups.first { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).language == "en" }
            player!!.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        }
        awaitUi("Whisper selected speech track") { uiRead {
            player!!.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).language == "en" && it.isSelected }
        } }
        val ownedModel = File(targetContext.filesDir, "whisper-models/${model.name}")
        if (!ownedModel.exists()) { ownedModel.parentFile!!.mkdirs(); model.copyTo(ownedModel) }
        // Start from a visible Activity, as required by Android foreground-service rules.
        val monitor = addMonitor("com.phnem.vap.player.PlayerActivity", null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x18000000 -n com.phnem.vap/.player.PlayerActivity -a ${com.phnem.vap.player.PlaybackService.ACTION_RESUME}").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        val activity = waitForMonitorWithTimeout(monitor, 10_000)
        try {
            awaitUi("Whisper foreground Activity") { uiRead { activity?.hasWindowFocus() == true } }
            runOnMainSync { runtime.transcribe(player!!, WhisperModel.TINY, "auto") }
            awaitUi("Whisper foreground service") {
                targetContext.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
                    .any { it.service.className == AiProcessingService::class.java.name && it.foreground }
            }
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val deadline = SystemClock.elapsedRealtime() + 100_000
            while (runtime.state.value.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val state = runtime.state.value
            check(!state.running && state.error == null && state.percent == 100 && state.source != null) { "Whisper runtime: $state" }
            source = state.source
            val attached = runBlocking { preferences.fileSettings(uri).first().subtitles }
            check(attached.selection == source!!.id)
            val lines = runBlocking { SubtitleStorage(targetContext).read(source!!.id) }
            check(lines.any { it.text.contains("country", true) })
            check(SubtitleDocument.parse(SubtitleDocument.export(lines, true)).size == lines.size)
            check(SubtitleDocument.parse(SubtitleDocument.export(lines, false)).size == lines.size)
            awaitUi("Whisper service released") {
                targetContext.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
                    .none { it.service.className == AiProcessingService::class.java.name }
            }
            uiAutomation.executeShellCommand("am start -W -n com.phnem.vap/.player.PlayerActivity -a ${com.phnem.vap.player.PlaybackService.ACTION_RESUME}").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            awaitUi("Whisper Activity restored") { uiRead { activity?.hasWindowFocus() == true } }
            // Cached retry produces the same track without a second inference or duplicate attachment.
            runOnMainSync { runtime.transcribe(player!!, WhisperModel.TINY, "auto") }
            val cacheDeadline = SystemClock.elapsedRealtime() + 10_000
            while (runtime.state.value.running && SystemClock.elapsedRealtime() < cacheDeadline) SystemClock.sleep(50)
            check(!runtime.state.value.running && runtime.state.value.source?.id == source!!.id)
            if (findUiNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") } == null) {
                val shot = uiAutomation.takeScreenshot(); uiTap(shot.width * .05f, shot.height * .4f); shot.recycle()
            }
            clickUi(awaitNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") })
            clickUi(labelNode("Whisper"))
            labelNode(targetContext.getString(R.string.ai_models))
            uiScreenshot("whisper-panel.png")
            repeat(8) {
                if (findUiNode { it.text?.toString() == targetContext.getString(R.string.ai_start) } == null) {
                    val shot = uiAutomation.takeScreenshot(); uiDrag(shot.width * .7f, shot.height * .8f, shot.width * .7f, shot.height * .35f); shot.recycle()
                }
            }
            labelNode(targetContext.getString(R.string.ai_start))
            uiScreenshot("whisper-actions.png")
            clickUi(labelNode(targetContext.getString(R.string.close)))
        } finally { removeMonitor(monitor); runOnMainSync { activity?.finish() } }
        report.putString("stream", "PASS: actual Tiny CPU inference/auto language/timestamps/progress, Transformer PCM, native cancellation/reuse, model resume/SHA/delete, Unicode atomic cache, real foreground runtime and attached SRT/VTT, cached retry; inference=${report.getLong("inferenceMs")}ms / 11s audio\n")
    } catch (error: Throwable) { failure = error; report.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}\n") }
    finally {
        runOnMainSync { runtime.cancel() }
        runBlocking { preferences.setSubtitles(uri, original.subtitles); source?.let { SubtitleStorage(targetContext).delete(it.id) } }
        runOnMainSync {
            player?.apply { if (previousItems.isNotEmpty()) { setMediaItems(previousItems, previousIndex, previousPosition); prepare(); pause() } }
            connection?.let(MediaController::releaseFuture)
        }
        video.delete()
        modelDir.listFiles().orEmpty().forEach { it.delete() }; modelDir.delete()
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
}
