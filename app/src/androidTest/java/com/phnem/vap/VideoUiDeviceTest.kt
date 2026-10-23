package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlaybackSettingsViewModel
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.toMediaItem
import com.phnem.vap.player.ui.PlayerOrientation
import com.phnem.vap.player.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runVideoUiDeviceTests() {
    val preferences = PlayerPreferences(targetContext)
    val original = runBlocking { preferences.settings.first() }
    val uri = Uri.fromFile(File(targetContext.filesDir, "vap-test.mp4")).toString()
    val originalFile = runBlocking { preferences.fileSettings(uri).first() }
    var activity: PlayerActivity? = null
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null
    var failure: Throwable? = null
    try {
        runBlocking {
            preferences.updateSettings { it.copy(orientation = PlayerOrientation.LANDSCAPE, decoderMode = DecoderMode.AUTO, hdrOutput = HdrOutput.AUTO) }
            preferences.setPicture(uri, PictureSettings())
        }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player!!
        runOnMainSync {
            controller.setMediaItem(PlayableItem(uri, "Video tools test").toMediaItem())
            controller.prepare(); controller.pause()
        }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a com.phnem.vap.player.RESUME").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        activity = waitForMonitorWithTimeout(monitor, 10_000) as? PlayerActivity
        removeMonitor(monitor)
        val screen = requireNotNull(activity)
        var settingsModel: PlaybackSettingsViewModel? = null
        var toolsModel: VideoToolsModel? = null
        runOnMainSync {
            settingsModel = ViewModelProvider(screen)[PlaybackSettingsViewModel::class.java]
            toolsModel = ViewModelProvider(screen)[VideoToolsModel::class.java]
        }
        awaitUi("video UI ready") { uiRead { controller.playbackState == Player.STATE_READY && controller.videoSize.width > 0 } }
        runOnMainSync { controller.seekTo(35_000) }
        SystemClock.sleep(700)
        fun playerView(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) repeat(view.childCount) { playerView(view.getChildAt(it))?.let { found -> return found } }
            return null
        }
        val surface = uiRead { playerView(screen.window.decorView)!!.videoSurfaceView as SurfaceView }
        val before = runBlocking { MediaExports.frame(surface, 320, 180) }
        val baseLuma = bitmapMean(before)
        File(targetContext.filesDir, "video-ui-before.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
        before.recycle()
        val brightness = uiRead { screen.window.attributes.screenBrightness }
        clickUi(labelNode(targetContext.getString(R.string.player_options)))
        clickUi(labelNode(targetContext.getString(R.string.video_tools)))
        clickUi(labelNode(targetContext.getString(R.string.video_info)))
        labelNode("c2.qti.avc.decoder")
        uiScreenshot("video-ui-info.png")
        clickUi(labelNode(targetContext.getString(R.string.video_picture)))
        val slider = revealVideoTool(targetContext.getString(R.string.video_brightness))
        val bounds = android.graphics.Rect().also { slider.getBoundsInScreen(it) }
        uiDrag(bounds.left + bounds.width() * 0.5f, bounds.exactCenterY(), bounds.left + bounds.width() * 0.8f, bounds.exactCenterY())
        awaitUi("picture slider saved") { uiRead { (settingsModel!!.file.value?.value?.picture?.brightness ?: 0f) > 0.1f } }
        awaitUi("picture decoder ready") { uiRead { controller.playbackState == Player.STATE_READY && controller.playerError == null } }
        SystemClock.sleep(600)
        val after = runBlocking { MediaExports.frame(surface, 320, 180) }
        val nextLuma = bitmapMean(after)
        File(targetContext.filesDir, "video-ui-after.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(nextLuma > baseLuma + 8) { "GPU brightness did not change rendered video pixels: $baseLuma → $nextLuma, saved ${uiRead { settingsModel!!.file.value?.value?.picture }}" }
        after.recycle()
        check(uiRead { screen.window.attributes.screenBrightness == brightness }) { "Video brightness changed window brightness" }
        uiScreenshot("video-ui-picture.png")
        clickUi(revealVideoTool(targetContext.getString(R.string.video_capture)))
        awaitUi("PNG generated") { uiRead { toolsModel!!.state.value.output?.extension == "png" && !toolsModel!!.state.value.busy } }
        val png = uiRead { toolsModel!!.state.value.output!! }
        val bitmap = BitmapFactory.decodeFile(png.absolutePath)
        check(bitmap.width == 320 && bitmap.height == 180)
        val center = bitmap.getPixel(160, 90)
        check(!(android.graphics.Color.red(center) > 240 && android.graphics.Color.green(center) > 240 && android.graphics.Color.blue(center) > 240)) { "PNG contains the white play overlay" }
        bitmap.recycle()
        val saved = File(targetContext.filesDir, "video-results/ui-saved.png").apply { parentFile!!.mkdirs() }
        runOnMainSync { toolsModel!!.save(Uri.fromFile(saved)) }
        awaitUi("PNG copied to destination") { uiRead { toolsModel!!.state.value.saved } }
        check(saved.length() > 100)

        clickUi(labelNode(targetContext.getString(R.string.video_export)))
        setVideoTime("00:00:35", "00:00:01")
        setVideoTime("00:00:45", "00:00:16")
        clickUi(revealVideoTool(targetContext.getString(R.string.video_export_gif)))
        awaitUi("GIF job started") { uiRead { toolsModel!!.state.value.busy } }
        clickUi(labelNode(targetContext.getString(R.string.video_cancel)))
        awaitUi("GIF cancelled") { uiRead { !toolsModel!!.state.value.busy && toolsModel!!.state.value.output == null } }
        check(File(targetContext.cacheDir, "exports").listFiles().orEmpty().none { it.extension == "gif" }) { "Cancelled GIF left a partial export" }
        setVideoTime("00:00:16", "00:00:03")
        clickUi(revealVideoTool(targetContext.getString(R.string.video_export_mp4)))
        awaitUi("MP4 UI completed") { uiRead { toolsModel!!.state.value.output?.extension == "mp4" && !toolsModel!!.state.value.busy } }
        uiScreenshot("video-ui-export.png")
        check(uiRead { toolsModel!!.state.value.error == null })
        // Launch the real system document picker, then cancel; generated result stays available.
        clickUi(labelNode(targetContext.getString(R.string.video_save)))
        awaitUi("system document picker") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        // DocumentsUI may first close IME or navigate parent directories remembered by another picker.
        repeat(8) {
            if (uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { stream -> stream.readBytes() }
                }
                SystemClock.sleep(400)
            }
        }
        awaitUi("picker cancelled") { uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName }
        check(uiRead { toolsModel!!.state.value.output?.extension == "mp4" })
        uiMark("HDR surface and SDR playback")
        val hdr = File(targetContext.filesDir, "video-fixtures/hdr10.mp4")
        runOnMainSync {
            controller.repeatMode = Player.REPEAT_MODE_ONE
            controller.setMediaItem(PlayableItem(Uri.fromFile(hdr).toString(), "HDR surface test").toMediaItem())
            controller.prepare(); controller.play()
        }
        fun currentInfo() = runBlocking { withContext(Dispatchers.Main) { controller.videoInfo() } }
        try {
            awaitUi("HDR surface playback") { uiRead { controller.isPlaying && controller.playerError == null } && currentInfo().getBoolean("hdr") }
        } catch (error: Exception) {
            error("HDR state=${uiRead { controller.playbackState }}, playing=${uiRead { controller.isPlaying }}, reason=${uiRead { controller.playbackSuppressionReason }}, error=${uiRead { controller.playerError?.errorCodeName }}, info=${currentInfo()}")
        }
        SystemClock.sleep(600)
        val hdrFrame = runBlocking { MediaExports.frame(surface, 320, 180) }
        check(bitmapMean(hdrFrame) > 1) { "HDR rendered a black frame" }
        File(targetContext.filesDir, "video-ui-hdr.png").outputStream().use { hdrFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        hdrFrame.recycle()
        runBlocking { preferences.updateSettings { it.copy(hdrOutput = HdrOutput.SDR, matchFrameRate = false) } }
        awaitUi("HDR to SDR playback") { currentInfo().let { it.getBoolean("toneMap") && !it.getBoolean("matchFrameRate") } && uiRead { controller.isPlaying && controller.playerError == null } }
        SystemClock.sleep(500)
        val sdrFrame = runBlocking { MediaExports.frame(surface, 320, 180) }
        check(bitmapMean(sdrFrame) > 1) { "Tone mapping rendered a black frame" }
        File(targetContext.filesDir, "video-ui-sdr.png").outputStream().use { sdrFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sdrFrame.recycle()

    } catch (error: Throwable) { failure = error; uiScreenshot("video-ui-failure.png") }
    finally {
        runOnMainSync { player?.repeatMode = Player.REPEAT_MODE_OFF; player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking { preferences.updateSettings { original }; preferences.setPicture(uri, originalFile.picture) }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: native tools/diagnostics, slider changes actual GPU pixels while paused, PNG without controls, destination copy, GIF cancellation cleanup, MP4 UI export, real save picker cancellation\n"
            else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    })
}

private fun Instrumentation.setVideoTime(old: String, value: String) {
    val node = awaitNode { it.isEditable && it.text?.toString() == old }
    check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
    }))
    SystemClock.sleep(150)
}

private fun Instrumentation.revealVideoTool(label: String): AccessibilityNodeInfo {
    repeat(12) {
        val node = findUiNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
        val scroll = findUiNode { it.isScrollable }
        if (node != null) {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val visible = android.graphics.Rect().also { scroll?.getBoundsInScreen(it) }
            if (bounds.height() > 25 && (scroll == null || visible.contains(bounds))) return node
        }
        check(scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) { "Missing video tool $label" }
        waitForIdleSync(); SystemClock.sleep(650)
    }
    error("Video tool not visible: $label")
}

private fun bitmapMean(bitmap: Bitmap): Double {
    var sum = 0L; var count = 0
    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
        val pixel = bitmap.getPixel(x, y)
        sum += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel); count += 3
    }
    return sum.toDouble() / count
}
