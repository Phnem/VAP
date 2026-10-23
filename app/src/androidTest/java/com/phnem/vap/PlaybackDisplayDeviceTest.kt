package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlaybackSettingsViewModel
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.toMediaItem
import com.phnem.vap.player.ui.PlayerOrientation
import com.phnem.vap.player.ui.VideoFit
import com.phnem.vap.player.ui.labelResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Actual rendered pixels and native multi-pointer input, rather than only changes to UI state. */
fun Instrumentation.runPlaybackDisplayTests() {
    val preferences = PlayerPreferences(targetContext)
    val original = runBlocking { preferences.settings.first() }
    val uri = Uri.fromFile(java.io.File(targetContext.filesDir, "vap-test.mp4")).toString()
    val originalFile = runBlocking { preferences.fileSettings(uri).first() }
    var activity: PlayerActivity? = null
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null
    var failure: Throwable? = null
    try {
        runBlocking {
            preferences.updateSettings { it.copy(orientation = PlayerOrientation.LANDSCAPE, pinchZoom = true, holdToSpeed = true) }
            preferences.setFit(uri, VideoFit.FIT)
            preferences.setSpeed(uri, 1f)
        }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player
        runOnMainSync {
            controller.setMediaItem(PlayableItem(uri, "Display test").toMediaItem())
            controller.prepare(); controller.pause(); controller.seekTo(35_000)
        }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a com.phnem.vap.player.RESUME").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        activity = waitForMonitorWithTimeout(monitor, 10_000) as? PlayerActivity
        removeMonitor(monitor)
        val screen = requireNotNull(activity)
        var model: PlaybackSettingsViewModel? = null
        runOnMainSync { model = ViewModelProvider(screen)[PlaybackSettingsViewModel::class.java] }
        awaitUi("display ready") { uiRead { controller.playbackState == Player.STATE_READY && screen.resources.configuration.orientation == 2 } }
        awaitUi("display settings") { uiRead { model!!.file.value?.value?.fit == VideoFit.FIT } }
        waitForIdleSync(); SystemClock.sleep(500)
        val display = uiAutomation.takeScreenshot()
        val w = display.width.toFloat(); val h = display.height.toFloat(); display.recycle()
        fun visibleControls(show: Boolean) {
            val visible = findUiNode { it.contentDescription?.toString() == targetContext.getString(R.string.player_frame_mode) } != null
            if (visible != show) { uiTap(w * 0.05f, h * 0.4f); SystemClock.sleep(500) }
        }
        fun chooseMode(mode: VideoFit) {
            visibleControls(true)
            tapDisplayLabel(targetContext.getString(R.string.player_frame_mode))
            selectDisplayChoice(targetContext.getString(mode.labelResource()))
            awaitUi("frame mode $mode") { uiRead { model!!.file.value?.value?.fit == mode } }
            SystemClock.sleep(300); visibleControls(false); SystemClock.sleep(300)
        }
        for (mode in VideoFit.entries) {
            uiMark("frame mode $mode")
            chooseMode(mode)
            val bitmap = uiAutomation.takeScreenshot()
            val bounds = coloredBounds(bitmap)
            val expectedWidth = when (mode) { VideoFit.FIT -> h * 16 / 9; VideoFit.ORIGINAL -> 320f; else -> w }
            val expectedHeight = when (mode) { VideoFit.ORIGINAL -> 180f; else -> h }
            check(abs(bounds.width() - expectedWidth) < 35 && abs(bounds.height() - expectedHeight) < 35) {
                "$mode rendered $bounds, expected ${expectedWidth}x$expectedHeight"
            }
            java.io.File(targetContext.filesDir, "display-${mode.name.lowercase()}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        chooseMode(VideoFit.FIT)
        uiMark("free zoom / pan")
        val position = uiRead { controller.currentPosition }
        val audio = targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val brightness = uiRead { screen.window.attributes.screenBrightness }
        pinch(w / 2, h * 0.42f, 200f, 440f)
        val reset = targetContext.getString(R.string.player_zoom_reset)
        labelNode(reset)
        uiScreenshot("display-zoom.png")
        val beforePan = uiAutomation.takeScreenshot()
        uiDrag(w * 0.5f, h * 0.5f, w * 0.65f, h * 0.6f)
        val afterPan = uiAutomation.takeScreenshot()
        check(!beforePan.sameAs(afterPan)) { "Panning did not change rendered pixels" }
        beforePan.recycle(); afterPan.recycle()
        uiScreenshot("display-pan.png")
        check(uiRead { controller.currentPosition == position && screen.window.attributes.screenBrightness == brightness && controller.playbackParameters.speed == 1f } &&
            audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == volume) {
            "Pinch/pan leaked into seek, volume or hold speed"
        }
        tapDisplayLabel(reset)
        awaitUi("zoom reset") { findUiNode { it.contentDescription?.toString() == reset } == null }
        pinch(w * 0.7f, h * 0.42f, 150f, 200f, hold = {
            check(uiRead { controller.playbackParameters.speed == 1f }) { "Two stationary fingers triggered hold speed" }
        })
        tapDisplayLabel(reset)
        runBlocking { preferences.updateSettings { it.copy(pinchZoom = false) } }
        awaitUi("disabled pinch collected") { uiRead { !model!!.settings.value.pinchZoom } }
        pinch(w / 2, h * 0.42f, 200f, 440f)
        check(findUiNode { it.contentDescription?.toString() == reset } == null) { "Disabled pinch still zooms" }
        check(uiRead { controller.currentPosition == position }) { "Disabled pinch leaked into seeking" }
        runBlocking { preferences.updateSettings { it.copy(pinchZoom = true) } }
        awaitUi("pinch enabled") { uiRead { model!!.settings.value.pinchZoom } }
        // A cancelled multi-touch gesture must release arbitration for the following seek.
        pinch(w / 2, h * 0.42f, 180f, 260f, cancel = true)
        tapDisplayLabel(reset); SystemClock.sleep(350)
        uiDrag(w * 0.25f, h * 0.4f, w * 0.40f, h * 0.4f)
        awaitUi("seek after cancelled pinch") { uiRead { controller.currentPosition > position } }
        visibleControls(true)
        uiMark("orientation")
        tapDisplayLabel(targetContext.getString(R.string.player_orientation))
        selectDisplayChoice(targetContext.getString(R.string.player_orientation_portrait))
        awaitUi("portrait layout") { uiRead { screen.resources.configuration.orientation == 1 } }
        check(uiRead { controller.currentPosition > position })
        SystemClock.sleep(1_000)
        uiScreenshot("display-portrait.png")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val cutouts = uiRead { screen.window.decorView.rootWindowInsets.displayCutout?.boundingRects.orEmpty() }
            check(cutouts.isNotEmpty()) { "Expected the phone camera cutout" }
            for (label in listOf(R.string.player_orientation, R.string.player_frame_mode, R.string.player_options)) {
                val bounds = android.graphics.Rect().also { labelNode(targetContext.getString(label)).getBoundsInScreen(it) }
                check(cutouts.none { android.graphics.Rect.intersects(it, bounds) }) { "Control overlaps camera cutout" }
            }
        }
        tapDisplayLabel(targetContext.getString(R.string.player_orientation))
        selectDisplayChoice(targetContext.getString(R.string.player_orientation_auto))
        awaitUi("system auto-rotate policy") { uiRead { screen.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER } }
        // Override system rotation through UiAutomation; restore the device policy afterwards.
        try {
            check(uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0))
            awaitUi("auto portrait") { uiRead { screen.resources.configuration.orientation == 1 } }
            check(uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
            awaitUi("auto landscape") { uiRead { screen.resources.configuration.orientation == 2 } }
        } finally { uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
        check(runBlocking { PlayerPreferences(targetContext).settings.first().orientation } == PlayerOrientation.AUTO)
        // Restore manual landscape using UI, then inspect actual WindowInsets.
        SystemClock.sleep(500)
        tapDisplayLabel(targetContext.getString(R.string.player_orientation))
        selectDisplayChoice(targetContext.getString(R.string.player_orientation_landscape))
        awaitUi("manual landscape") { uiRead { screen.resources.configuration.orientation == 2 } }
        awaitUi("immersive bars") { uiRead {
            androidx.core.view.ViewCompat.getRootWindowInsets(screen.window.decorView)?.isVisible(WindowInsetsCompat.Type.systemBars()) == false
        } }
        uiScreenshot("display-final.png")
    } catch (error: Throwable) {
        failure = error
        uiScreenshot("display-failure.png")
    }
    finally {
        runOnMainSync { player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking {
            preferences.updateSettings { original }
            preferences.setFit(uri, originalFile.fit)
            preferences.setSpeed(uri, originalFile.speed)
        }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: rendered Fit/Fill/Crop/Stretch/1:1 pixels, native pinch/pan/reset/cancel, seek arbitration, portrait/landscape/auto policy and display rotation, hidden system bars\n"
        else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    })
}

private fun Instrumentation.tapDisplayLabel(label: String) {
    val bounds = android.graphics.Rect().also { labelNode(label).getBoundsInScreen(it) }
    uiTap(bounds.exactCenterX(), bounds.exactCenterY())
    waitForIdleSync(); SystemClock.sleep(300)
}

private fun Instrumentation.selectDisplayChoice(label: String) {
    waitForIdleSync(); SystemClock.sleep(250)
    repeat(5) {
        val choice = findUiNode { it.text?.toString() == label }
        if (choice != null) {
            val bounds = android.graphics.Rect().also { choice.getBoundsInScreen(it) }
            val scroll = findUiNode { it.isScrollable }
            val visible = android.graphics.Rect().also { scroll?.getBoundsInScreen(it) }
            if (bounds.height() >= 40 && (scroll == null || visible.contains(bounds))) {
                var clickable: AccessibilityNodeInfo? = choice
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                    uiTap(bounds.exactCenterX(), bounds.exactCenterY())
                }
                SystemClock.sleep(300)
                return
            }
        }
        check(findUiNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) { "Missing choice: $label" }
        waitForIdleSync(); SystemClock.sleep(700)
    }
    error("Choice not visible: $label")
}

private fun coloredBounds(bitmap: Bitmap): android.graphics.Rect {
    val bounds = android.graphics.Rect(bitmap.width, bitmap.height, 0, 0)
    for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
        val pixel = bitmap.getPixel(x, y)
        if (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) > 90) {
            bounds.left = minOf(bounds.left, x); bounds.top = minOf(bounds.top, y)
            bounds.right = maxOf(bounds.right, x + 2); bounds.bottom = maxOf(bounds.bottom, y + 2)
        }
    }
    return bounds
}

private fun Instrumentation.pinch(cx: Float, cy: Float, startRadius: Float, endRadius: Float, cancel: Boolean = false, hold: (() -> Unit)? = null) {
    val downTime = SystemClock.uptimeMillis()
    fun event(action: Int, radius: Float, count: Int = 2) {
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply { x = cx + if (index == 0) -radius else radius; y = cy; pressure = 1f; size = 1f } }
        val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(uiAutomation.injectInputEvent(motion, true)) } finally { motion.recycle() }
    }
    event(MotionEvent.ACTION_DOWN, startRadius, 1)
    event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), startRadius)
    if (hold != null) { SystemClock.sleep(650); hold() }
    repeat(12) { i ->
        SystemClock.sleep(20)
        event(MotionEvent.ACTION_MOVE, startRadius + (endRadius - startRadius) * (i + 1) / 12)
    }
    if (cancel) event(MotionEvent.ACTION_CANCEL, endRadius)
    else {
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), endRadius)
        event(MotionEvent.ACTION_UP, endRadius, 1)
    }
    SystemClock.sleep(350)
}
