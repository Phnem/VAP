package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.player.*
import com.phnem.vap.player.model.PlayableItem
import java.io.File
import java.util.concurrent.TimeUnit

fun Instrumentation.runResumeDeviceTests() {
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var library: MainActivity? = null; var screen: PlayerActivity? = null
    var failure: Throwable? = null
    fun openLibrary(): MainActivity {
        targetContext.getSharedPreferences("vap_app", 0).edit().putBoolean("first_run_done", true).commit() // the first-run screen would cover the library under test
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.MainActivity").close()
        val activity = waitForMonitorWithTimeout(monitor, 10000) as? MainActivity
        removeMonitor(monitor); return checkNotNull(activity)
    }
    try {
        val uri = android.net.Uri.fromFile(File(targetContext.filesDir, "vap-test.mp4")).toString()
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }; player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player!!
        runOnMainSync { controller.setMediaItem(PlayableItem(uri, "VAP resume fixture").toMediaItem()); controller.prepare(); controller.pause() }
        awaitUi("resume source ready") { uiRead { controller.playbackState == Player.STATE_READY && controller.duration > 100_000 } }
        runOnMainSync { controller.seekTo(12_000) }
        awaitUi("resume source seek settled") { uiRead { controller.currentPosition in 11_900..12_100 } }
        SystemClock.sleep(250)
        library = openLibrary()
        labelNode(targetContext.getString(R.string.resume_session_title))
        uiScreenshot("resume-offer.png")
        clickUi(labelNode(targetContext.getString(R.string.resume_session_later)))
        check(uiRead { !controller.playWhenReady && controller.currentPosition in 11_900..12_100 })
        runOnMainSync { library!!.recreate() }; SystemClock.sleep(800)
        check(findUiNode { it.text?.toString() == targetContext.getString(R.string.resume_session_title) } == null) { "Rotation repeated dismissed offer" }
        // Finish the recreated activity, then launch a genuinely new library visit.
        uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close(); SystemClock.sleep(400)
        library = openLibrary()
        labelNode(targetContext.getString(R.string.resume_session_title))
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        clickUi(labelNode(targetContext.getString(R.string.resume_session_action)))
        screen = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity; removeMonitor(monitor); check(screen != null)
        awaitUi("explicit resume starts from saved position") { uiRead { controller.isPlaying && controller.currentPosition in 12_000..20_000 } }
    } catch (e: Throwable) { failure = e; runCatching { uiScreenshot("resume-failure.png") } }
    finally { runOnMainSync { player?.pause(); screen?.finish(); library?.finish(); future?.let { MediaController.releaseFuture(it) } } }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: resume prompt, Later keeps paused position, rotation does not repeat dismissed offer, Continue opens player at saved position\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}
