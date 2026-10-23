package com.phnem.vap

import android.app.Instrumentation
import android.app.Activity
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.player.*
import com.phnem.vap.player.audio.*
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.ui.PlayerOrientation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runAudioUiDeviceTests() {
    val prefs = PlayerPreferences(targetContext)
    val uri = Uri.fromFile(File(targetContext.filesDir, "audio-fixtures/dual-audio.mkv")).toString()
    val original = runBlocking { prefs.settings.first() }; val saved = runBlocking { prefs.fileSettings(uri).first() }
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var activity: PlayerActivity? = null; var failure: Throwable? = null
    try {
        runBlocking { prefs.resetFile(uri); prefs.updateSettings { it.copy(orientation = PlayerOrientation.LANDSCAPE, preservePitch = true, preferredAudioLanguage = "") } }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }; player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player!!
        runOnMainSync { controller.setMediaItem(PlayableItem(uri, "Audio UI test").toMediaItem()); controller.prepare(); controller.pause(); controller.seekTo(5000) }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a com.phnem.vap.player.RESUME").use { java.io.FileInputStream(it.fileDescriptor).use { s -> s.readBytes() } }
        activity = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity; removeMonitor(monitor); check(activity != null)
        awaitUi("audio UI ready") { uiRead { controller.playbackState == Player.STATE_READY && activity!!.resources.configuration.orientation == 2 } }
        waitForIdleSync(); SystemClock.sleep(500)
        val display = uiAutomation.takeScreenshot(); val w = display.width.toFloat(); val h = display.height.toFloat(); display.recycle()
        if (findUiNode { it.contentDescription?.toString() in listOf("Audio track", "Аудиодорожка") } == null) { uiTap(w * .05f, h * .4f); SystemClock.sleep(400) }
        clickUi(awaitNode { it.contentDescription?.toString() in listOf("Audio track", "Аудиодорожка") })
        labelNode(targetContext.getString(R.string.audio_tools))
        fun reveal(label: String, minHeight: Int = 30): AccessibilityNodeInfo {
            repeat(12) {
                val node = findUiNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
                if (node != null) { val b = Rect(); node.getBoundsInScreen(b); if (b.height() > minHeight) return node }
                uiDrag(w * .8f, h * .72f, w * .8f, h * .55f); SystemClock.sleep(200)
            }
            error("Not visible: $label")
        }
        val english = uiRead { controller.currentTracks.audioChoices().first { it.language in listOf("en", "eng") } }
        clickUi(labelNode(english.label))
        awaitUi("UI manual track persisted") { runBlocking { prefs.fileSettings(uri).first() }.audioKey == english.key }
        reveal(targetContext.getString(R.string.audio_language_code))
        val field = awaitNode { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "ru") }))
        clickUi(reveal(targetContext.getString(R.string.audio_apply_language)))
        clickUi(labelNode(targetContext.getString(R.string.audio_output)))
        clickUi(labelNode(targetContext.getString(R.string.audio_tracks)))
        clickUi(labelNode(targetContext.getString(R.string.audio_automatic)))
        awaitUi("UI language auto track") { uiRead { controller.currentTracks.audioChoices().any { it.selected && it.language in listOf("ru", "rus") } } }
        uiScreenshot("audio-ui-tracks.png")
        clickUi(labelNode(targetContext.getString(R.string.audio_processing)))
        clickUi(awaitNode { it.contentDescription?.toString() == targetContext.getString(R.string.audio_preserve_pitch) })
        awaitUi("UI pitch preference") { !runBlocking { prefs.settings.first() }.preservePitch }
        val delay = labelNode(targetContext.getString(R.string.audio_delay)); val bounds = Rect(); delay.getBoundsInScreen(bounds)
        uiDrag(bounds.centerX().toFloat(), bounds.centerY().toFloat(), bounds.left + bounds.width() * .65f, bounds.centerY().toFloat())
        awaitUi("UI delay changed") { runBlocking { prefs.fileSettings(uri).first() }.audio.delayMs > 100 }
        reveal(targetContext.getString(R.string.audio_mono), 60)
        clickUi(awaitNode { it.contentDescription?.toString() == targetContext.getString(R.string.audio_mono) })
        awaitUi("UI mono changed") { runBlocking { prefs.fileSettings(uri).first() }.audio.mono }
        val eq = reveal("100 Hz", 80); eq.getBoundsInScreen(bounds)
        uiDrag(bounds.centerX().toFloat(), bounds.centerY().toFloat(), bounds.left + bounds.width() * .7f, bounds.centerY().toFloat())
        awaitUi("UI EQ changed") { runBlocking { prefs.fileSettings(uri).first() }.audio.equalizer[0] > 1f }
        uiScreenshot("audio-ui-sound.png")
        clickUi(labelNode(targetContext.getString(R.string.audio_output)))
        labelNode(targetContext.getString(R.string.audio_system_output)); uiScreenshot("audio-ui-output.png")
        clickUi(labelNode(targetContext.getString(R.string.audio_tracks)))
        clickUi(reveal(targetContext.getString(R.string.audio_add_external)))
        awaitNode { it.packageName?.toString()?.let { name -> "documentsui" in name || "fileexplorer" in name } == true }
        waitForIdleSync(); SystemClock.sleep(700)
        if (findUiNode { it.packageName?.toString() == "com.android.fileexplorer" } != null) {
            runOnMainSync { activity!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            SystemClock.sleep(800)
        }
        if (findUiNode { it.text?.toString() == "VAP-stage61-external.mka" } == null) {
            clickUi(awaitNode { it.contentDescription?.toString() in listOf("Show roots", "Показать корневые папки") })
            clickUi(awaitNode { it.text?.toString() in listOf("Downloads", "Загрузки") })
        }
        findUiNode { it.contentDescription?.toString() in listOf("List view", "Список") }?.let(::clickUi)
        waitForIdleSync(); SystemClock.sleep(400)
        val externalNode = labelNode("VAP-stage61-external.mka")
        if (externalNode.packageName?.toString()?.contains("documentsui") == true) clickUi(externalNode)
        else {
            externalNode.getBoundsInScreen(bounds)
            uiTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        findUiNode { it.viewIdResourceName == "com.android.fileexplorer:id/btnConfirm" && it.isEnabled }?.let(::clickUi)
        awaitUi("SAF audio attached") { runBlocking { prefs.fileSettings(uri).first() }.audio.externalUri?.startsWith("content:") == true }
        awaitUi("SAF audio decoded") { uiRead { controller.currentTracks.audioChoices().any { it.selected && it.language in listOf("fr", "fra") } && controller.playerError == null } }
        check(targetContext.contentResolver.persistedUriPermissions.any { it.uri.toString() == runBlocking { prefs.fileSettings(uri).first() }.audio.externalUri && it.isReadPermission })
        awaitUi("picker closed and player landscape restored") {
            uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName &&
                uiRead { activity!!.resources.configuration.orientation == 2 }
        }
        waitForIdleSync(); SystemClock.sleep(500)
        uiScreenshot("audio-ui-external.png")
        clickUi(reveal(targetContext.getString(R.string.audio_remove_external)))
        awaitUi("UI embedded audio restored") { uiRead { controller.currentTracks.audioChoices().size == 2 && controller.playerError == null } }
    } catch (error: Throwable) { failure = error; runCatching { uiScreenshot("audio-ui-failure.png") } }
    finally {
        runOnMainSync { player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking {
            prefs.updateSettings { original }; prefs.resetFile(uri); prefs.setSpeed(uri, saved.speed); prefs.setFit(uri, saved.fit)
            prefs.setPicture(uri, saved.picture); prefs.setAudioSettings(uri, saved.audio)
            saved.audioKey?.let { prefs.setAudioKey(uri, it) }; saved.audioLabel?.let { prefs.setAudio(uri, it) }
        }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: native audio UI track/language selection, pitch switch, delay and EQ sliders, mono, output list, real SAF external MKA selection/persistent grant/decode, embedded audio restoration\n"
        else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    })
}
