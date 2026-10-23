package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.session.MediaController
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.PlayerFactory
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.toMediaItem
import com.phnem.vap.player.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/** Real platform codecs and exports; one adapter deliberately releases a running HW codec. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runVideoDeviceTests() {
    var failure: Throwable? = null
    val preferences = PlayerPreferences(targetContext)
    val original = runBlocking { preferences.settings.first() }
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
    var local: ExoPlayer? = null
    val results = File(targetContext.filesDir, "video-results").apply { mkdirs() }
    val fixtures = File(targetContext.filesDir, "video-fixtures")
    fun info() = runBlocking { withContext(Dispatchers.Main) { controller!!.videoInfo() } }
    try {
        check(fixtures.isDirectory) { "Prepare and push video fixtures first" }
        runBlocking { preferences.updateSettings { it.copy(decoderMode = DecoderMode.AUTO, hdrOutput = HdrOutput.AUTO) } }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        controller = future!!.get(15, TimeUnit.SECONDS)
        val player = controller!!
        fun play(name: String) {
            val source = File(fixtures, name)
            check(source.isFile)
            runOnMainSync {
                player.setMediaItem(PlayableItem(Uri.fromFile(source).toString(), name).toMediaItem())
                player.prepare(); player.play()
            }
            awaitUi("decode $name") { uiRead { player.playerError != null || player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED } }
            check(uiRead { player.playerError == null }) { "$name: ${uiRead { player.playerError }}" }
            awaitUi("codec named $name") { info().getString("decoder")?.let { it != "—" && it.isNotBlank() } == true }
            val data = info()
            uiMark("$name · ${data.getString("decoder")} · ${data.getFloat("fps")} FPS · HDR=${data.getBoolean("hdr")}")
            runOnMainSync { player.pause() }
        }
        for (name in listOf("h264-24.mp4", "h264.mkv", "mpeg4.avi", "vp9.webm", "hevc-25.mp4", "av1-30.mp4", "h264-60.mp4", "hdr10.mp4")) play(name)
        check(info().getBoolean("hdr")) { "HDR fixture lost PQ metadata" }
        runBlocking { preferences.updateSettings { it.copy(hdrOutput = HdrOutput.SDR) } }
        awaitUi("tone map configured") { info().getBoolean("toneMap") && uiRead { player.playerError == null && player.playbackState == Player.STATE_READY } }
        runBlocking { preferences.updateSettings { it.copy(hdrOutput = HdrOutput.AUTO, decoderMode = DecoderMode.SOFTWARE) } }
        play("h264-24.mp4")
        awaitUi("real software codec") { info().getString("decoder")?.let { "android" in it || "google" in it } == true }
        runBlocking { preferences.updateSettings { it.copy(decoderMode = DecoderMode.HARDWARE) } }
        awaitUi("real hardware codec") { info().getString("decoder")?.let { it != "—" && "android" !in it && "google" !in it } == true }
        val position = uiRead { player.currentPosition }
        check(position >= 0)
        runOnMainSync { player.stop() }

        // Release the actual synchronous MediaCodec mid-input, then let it produce the native error.
        val base = DefaultMediaCodecAdapterFactory(targetContext).forceDisableAsynchronous()
        var failedName = ""
        var injected = 0
        val fault = MediaCodecAdapter.Factory { configuration ->
            val adapter = base.createAdapter(configuration)
            if (configuration.format.sampleMimeType?.startsWith("video/") != true || !configuration.codecInfo.hardwareAccelerated) adapter
            else {
                failedName = configuration.codecInfo.name
                var queued = 0; var released = false
                Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader, arrayOf(MediaCodecAdapter::class.java)) { _, method, args ->
                    if (method.name == "queueInputBuffer" && ++queued == 4) {
                        injected++; adapter.release(); released = true
                    }
                    if (method.name == "release" && released) null else try { method.invoke(adapter, *(args ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                } as MediaCodecAdapter
            }
        }
        var runtime: DecoderRuntime? = null
        runOnMainSync {
            val policy = DecoderPolicy()
            local = PlayerFactory.create(targetContext, policy, fault)
            runtime = DecoderRuntime(local!!, policy)
            local!!.setMediaItem(PlayableItem(Uri.fromFile(File(targetContext.filesDir, "vap-test.mp4")).toString(), "runtime recovery").toMediaItem())
            local!!.prepare(); local!!.play()
        }
        try {
            awaitUi("runtime native codec recovery") { uiRead { runtime!!.recoveries > 0 && local!!.isPlaying && local!!.currentPosition > 200 && ("android" in runtime!!.decoder || "google" in runtime!!.decoder) } }
        } catch (error: Throwable) {
            throw IllegalStateException(uiRead { "Recovery: injected=$injected failed=$failedName info=${runtime!!.info()} state=${local!!.playbackState} error=${local!!.playerError}" }, error)
        }
        check(injected >= 1)
        uiMark("recovery: $injected failed HW codecs → ${uiRead { runtime!!.decoder }}")
        runOnMainSync { local!!.release(); local = null }

        uiMark("exports")
        val source = Uri.fromFile(File(targetContext.filesDir, "vap-test.mp4"))
        val clip = File(results, "clip.mp4")
        runBlocking { MediaExports.clip(targetContext, source, ExportRange(5_000, 7_000), PictureSettings(brightness = 0.1f), clip) {} }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(clip.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            check(duration in 1_850..2_200) { "Clip duration $duration" }
            check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() == 720)
            check(metadata.getFrameAtTime(500_000)?.let { bitmap -> bitmap.recycle(); true } == true)
        } finally { metadata.release() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(clip.absolutePath)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString("mime") }
            check("video/avc" in mimes && "audio/mp4a-latm" in mimes) { "Export codecs $mimes" }
        } finally { extractor.release() }
        val gif = File(results, "clip.gif")
        runBlocking { MediaExports.gif(targetContext, source, ExportRange(1_000, 2_000), PictureSettings(), gif) {} }
        check(gif.length() > 1_000)
        val decoder = android.graphics.Movie.decodeFile(gif.absolutePath)
        check(decoder != null && decoder.width() <= 480 && decoder.duration() in 900..1_100) { "Invalid GIF" }
        uiMark("SDR export from HDR")
        val hdrClip = File(results, "hdr-sdr.mp4")
        runBlocking { MediaExports.clip(targetContext, Uri.fromFile(File(fixtures, "hdr10.mp4")), ExportRange(0, 1_000), PictureSettings(), hdrClip) {} }
        val hdrExtractor = MediaExtractor()
        try {
            hdrExtractor.setDataSource(hdrClip.absolutePath)
            val video = (0 until hdrExtractor.trackCount).map { hdrExtractor.getTrackFormat(it) }.first { it.getString("mime")?.startsWith("video/") == true }
            check(!video.containsKey("color-transfer") || video.getInteger("color-transfer") !in listOf(6, 7)) { "Output is still HDR" }
        } finally { hdrExtractor.release() }

        runBlocking { preferences.updateSettings { it.copy(decoderMode = DecoderMode.AUTO) } }
        runOnMainSync {
            player.setMediaItem(PlayableItem(Uri.fromFile(File(fixtures, "corrupt.mp4")).toString(), "corrupt").toMediaItem())
            player.prepare(); player.play()
        }
        awaitUi("corrupt media error") { uiRead { player.playerError != null } }
        check(info().getInt("recoveries") == 0) { "Non-codec error entered recovery loop" }
        play("h264-24.mp4")
        runOnMainSync {
            player.setMediaItem(PlayableItem(Uri.fromFile(File(fixtures, "partial.mp4")).toString(), "partial").toMediaItem())
            player.prepare(); player.play()
        }
        awaitUi("partial media bounded outcome") { uiRead { player.playerError != null || player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED } }
        if (uiRead { player.playerError != null }) check(info().getInt("recoveries") == 0)
        play("h264-24.mp4")
    } catch (error: Throwable) { failure = error }
    finally {
        runOnMainSync { local?.release(); controller?.pause(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking { preferences.updateSettings { original } }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: MP4/MKV/WebM/AVI, AVC/HEVC/VP9/AV1, 23.976/25/30/60 FPS, HDR input and SDR export, actual HW/SW decoders, recovery after native running-codec failure, MP4/GIF exports, corrupt and partial media\n"
            else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    })
}
