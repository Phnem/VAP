package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.toMediaItem
import com.phnem.vap.player.settings.PlaybackSettingsViewModel
import com.phnem.vap.player.settings.PlayerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Нативные касания и дерево доступности: тестирует UI, а не заменяющие его вызовы seekTo. */
fun Instrumentation.runPlaybackUiTests() {
    val report = Bundle()
    val preferences = PlayerPreferences(targetContext)
    val original = runBlocking { preferences.settings.first() }
    var activity: PlayerActivity? = null
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null
    var failure: Throwable? = null
    try {
        val source = java.io.File(targetContext.filesDir, "vap-test.mp4")
        check(source.isFile)
        runBlocking { preferences.updateSettings { it.copy(seekStepSeconds = 30, doubleTapSeek = false,
            swipeSeek = false, holdToSpeed = false, framePreview = true) } }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player
        runOnMainSync {
            controller.setMediaItem(PlayableItem(Uri.fromFile(source).toString(), "UI controls test").toMediaItem())
            controller.prepare(); controller.pause()
        }
        uiMark("launch")
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a com.phnem.vap.player.RESUME").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        activity = waitForMonitorWithTimeout(monitor, 10_000) as? PlayerActivity
        removeMonitor(monitor)
        check(activity != null) { "PlayerActivity did not launch" }
        uiMark("connect")
        var settingsModel: PlaybackSettingsViewModel? = null
        runOnMainSync { settingsModel = ViewModelProvider(requireNotNull(activity))[PlaybackSettingsViewModel::class.java] }
        awaitUi("player ready") { uiRead { controller.duration > 0 && controller.playbackState == androidx.media3.common.Player.STATE_READY } }
        runOnMainSync { controller.pause(); controller.seekTo(0) }
        awaitUi("settings collected") { uiRead { settingsModel!!.settings.value.let { !it.doubleTapSeek && !it.swipeSeek && it.seekStepSeconds == 30 } } }
        uiMark("timecode")
        clickUi(labelNode(targetContext.getString(R.string.player_goto_time)))
        val field = awaitNode { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "3:00")
        }))
        clickUi(labelNode(targetContext.getString(R.string.player_seek)))
        val error = labelNode(targetContext.getString(R.string.player_timecode_out_of_range))
        check(error.isVisibleToUser)
        uiScreenshot("controls-ui-timecode-error.png")
        check(uiRead { controller.currentPosition == 0L })
        check(awaitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "00:35")
        }))
        clickUi(labelNode(targetContext.getString(R.string.player_seek)))
        awaitUi("UI timecode seek") { uiRead { controller.currentPosition == 35_000L } }
        uiMark("disabled gestures")
        waitForIdleSync()
        SystemClock.sleep(250)
        val display = uiAutomation.takeScreenshot()
        val width = display.width.toFloat()
        val height = display.height.toFloat()
        display.recycle()
        uiTap(width * 0.8f, height * 0.38f)
        SystemClock.sleep(70)
        uiTap(width * 0.8f, height * 0.38f)
        SystemClock.sleep(600)
        check(uiRead { controller.currentPosition == 35_000L }) { "Disabled double tap still seeks" }
        uiDrag(width * 0.25f, height * 0.4f, width * 0.45f, height * 0.4f)
        check(uiRead { controller.currentPosition == 35_000L }) { "Disabled swipe still seeks" }

        runBlocking { preferences.updateSettings { it.copy(doubleTapSeek = true, swipeSeek = true) } }
        uiMark("enabled gestures")
        awaitUi("enabled gestures collected") { uiRead { settingsModel!!.settings.value.doubleTapSeek && settingsModel!!.settings.value.swipeSeek } }
        waitForIdleSync(); SystemClock.sleep(150)
        uiTap(width * 0.8f, height * 0.38f)
        SystemClock.sleep(70)
        uiTap(width * 0.8f, height * 0.38f)
        awaitUi("30 second double tap") { uiRead { controller.currentPosition == 65_000L } }
        SystemClock.sleep(850)
        // Возвращаем контролы одиночным тапом, если предыдущее выключение жестов их спрятало.
        if (findUiNode { it.contentDescription?.toString() == targetContext.getString(R.string.player_seek_bar) } == null) {
            uiTap(width * 0.05f, height * 0.4f); SystemClock.sleep(400)
        }
        val bar = labelNode(targetContext.getString(R.string.player_seek_bar))
        uiMark("preview")
        val bounds = Rect().also { bar.getBoundsInScreen(it) }
        val x = bounds.left + bounds.width() * 0.4f
        val y = bounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        uiPointer(downTime, MotionEvent.ACTION_DOWN, x, y)
        try {
            uiPointer(downTime, MotionEvent.ACTION_MOVE, x + 30, y)
            // Превью рисуется вне layout-границ шкалы и не обязано входить в её accessibility subtree.
            // Сохраняем реальный экран для визуальной проверки, не выдаём наличие state за кадр.
            SystemClock.sleep(700)
            check(uiRead { controller.currentPosition == 65_000L }) { "Preview changed playback before release" }
            uiScreenshot("controls-ui-preview.png")
        } finally { uiPointer(downTime, MotionEvent.ACTION_UP, x + 30, y) }
        awaitUi("timeline release") { uiRead { controller.currentPosition < 65_000L && controller.currentPosition > 0 } }
        runOnMainSync { controller.play() }
        awaitUi("playing for auto hide") { uiRead { controller.isPlaying } }
        SystemClock.sleep(4_100)
        check(findUiNode { it.contentDescription?.toString() == targetContext.getString(R.string.player_goto_time) } == null) {
            "Controls did not auto-hide"
        }
    } catch (error: Throwable) { failure = error }
    finally {
        runOnMainSync { player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking { preferences.updateSettings { original } }
    }
    report.putString("stream", if (failure == null)
        "PASS: UI timecode validation/seek, disabled double tap/swipe, configured 30s double tap, preview screenshot, seek commits on release, auto-hide\n"
        else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
}

internal fun Instrumentation.findUiNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (predicate(node) && node.isVisibleToUser) return node
        repeat(node.childCount) { index -> node.getChild(index)?.let { child -> visit(child)?.let { return it } } }
        return null
    }
    if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
    return uiAutomation.rootInActiveWindow?.also { it.refresh() }?.let(::visit)
}
internal fun Instrumentation.awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
    var result: AccessibilityNodeInfo? = null
    awaitUi("accessibility node") { findUiNode(predicate).also { result = it } != null }
    return result!!
}
internal fun Instrumentation.labelNode(label: String) = awaitNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
internal fun clickUi(node: AccessibilityNodeInfo) {
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isClickable) target = target.parent
    check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click ${node.text ?: node.contentDescription}" }
}
internal fun Instrumentation.uiTap(x: Float, y: Float) {
    val time = SystemClock.uptimeMillis()
    uiPointer(time, MotionEvent.ACTION_DOWN, x, y)
    uiPointer(time, MotionEvent.ACTION_UP, x, y)
}
internal fun Instrumentation.uiDrag(x: Float, y: Float, toX: Float, toY: Float) {
    val time = SystemClock.uptimeMillis()
    uiPointer(time, MotionEvent.ACTION_DOWN, x, y)
    repeat(10) { i ->
        SystemClock.sleep(20)
        val fraction = (i + 1) / 10f
        uiPointer(time, MotionEvent.ACTION_MOVE, x + (toX - x) * fraction, y + (toY - y) * fraction)
    }
    uiPointer(time, MotionEvent.ACTION_UP, toX, toY)
}
internal fun Instrumentation.uiPointer(downTime: Long, action: Int, x: Float, y: Float) {
    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
    try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
}
internal fun Instrumentation.uiScreenshot(name: String) {
    val bitmap = uiAutomation.takeScreenshot()
    java.io.File(targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
internal fun Instrumentation.uiMark(label: String) = sendStatus(0, Bundle().apply { putString("stream", "UI: $label\n") })
internal fun <T> Instrumentation.uiRead(block: () -> T): T {
    var result: T? = null
    runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST") return result as T
}
internal fun awaitUi(label: String, condition: () -> Boolean) {
    val until = SystemClock.elapsedRealtime() + 15_000
    while (SystemClock.elapsedRealtime() < until) {
        if (condition()) return
        SystemClock.sleep(100)
    }
    error("Timed out: $label")
}
