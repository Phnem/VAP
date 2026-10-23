package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.data.VapData
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.toMediaItem
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.phnem.vap.player.settings.PlayerPreferences

/** Реальная MediaSessionService + декодер телефона; без подмены Player. */
class PlaybackDeviceTest : Instrumentation() {
    private var controlsOnly = false
    private var uiOnly = false
    private var displayOnly = false
    private var videoOnly = false
    private var videoUiOnly = false
    private var audioOnly = false
    private var audioUiOnly = false
    private var libraryOnly = false
    private var subtitlesOnly = false
    private var resumeOnly = false
    private var assOnly = false
    private var keepAssFixture = false
    private var onlineOnly = false
    private var networkOnly = false
    private var protocolsOnly = false
    private var serversOnly = false
    private var translationOnly = false
    private var whisperOnly = false
    private var syncOnly = false
    private var castingOnly = false
    override fun onCreate(arguments: Bundle?) {
        controlsOnly = arguments?.getString("suite") == "controls"
        uiOnly = arguments?.getString("suite") == "ui"
        displayOnly = arguments?.getString("suite") == "display"
        videoOnly = arguments?.getString("suite") == "video"
        videoUiOnly = arguments?.getString("suite") == "video-ui"
        audioOnly = arguments?.getString("suite") == "audio"
        audioUiOnly = arguments?.getString("suite") == "audio-ui"
        libraryOnly = arguments?.getString("suite") == "library"
        subtitlesOnly = arguments?.getString("suite") == "subtitles"
        resumeOnly = arguments?.getString("suite") == "resume"
        assOnly = arguments?.getString("suite") == "ass"
        keepAssFixture = arguments?.getString("keepFixture") == "true"
        onlineOnly = arguments?.getString("suite") == "online"
        networkOnly = arguments?.getString("suite") == "network"
        protocolsOnly = arguments?.getString("suite") == "protocols"
        translationOnly = arguments?.getString("suite") == "translation"
        whisperOnly = arguments?.getString("suite") == "whisper"
        syncOnly = arguments?.getString("suite") == "sync"
        castingOnly = arguments?.getString("suite") == "casting"
        serversOnly = arguments?.getString("suite") == "servers"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        if (translationOnly) { runTranslationDeviceTests(); return }
        if (whisperOnly) { runWhisperDeviceTests(); return }
        if (syncOnly) { runSmartSyncDeviceTests(); return }
        if (castingOnly) { runCastDeviceTests(); return }
        if (serversOnly) { runServerDeviceTests(); return }
        if (protocolsOnly) { runFileProtocolDeviceTests(keepAssFixture); return }
        if (networkOnly) { runNetworkDeviceTests(); return }
        if (onlineOnly) { runOnlineSubtitleTests(); return }
        if (assOnly) { runAssDeviceTests(keepAssFixture); return }
        if (resumeOnly) { runResumeDeviceTests(); return }
        if (subtitlesOnly) { runSubtitleDeviceTests(); return }
        if (libraryOnly) { runLibraryDeviceTests(); return }
        if (audioUiOnly) { runAudioUiDeviceTests(); return }
        if (audioOnly) { runAudioDeviceTests(); return }
        if (videoUiOnly) { runVideoUiDeviceTests(); return }
        if (videoOnly) { runVideoDeviceTests(); return }
        if (displayOnly) { runPlaybackDisplayTests(); return }
        if (uiOnly) { runPlaybackUiTests(); return }
        if (controlsOnly) { runPlaybackControlsTests(); return }
        val report = Bundle()
        var controller: MediaController? = null
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        val preferences = PlayerPreferences(targetContext)
        val original = runBlocking { preferences.settings.first() }
        var result = Activity.RESULT_OK
        try {
            runBlocking { preferences.updateSettings { it.copy(autoNext = true) } }
            val file = java.io.File(targetContext.filesDir, "vap-test.mp4")
            check(file.isFile) { "Push the generated sample to files/vap-test.mp4 before running" }
            val uri = android.net.Uri.fromFile(file).toString()
            val first = PlayableItem(uri, "VAP device test A").toMediaItem()
            val otherFile = java.io.File(targetContext.filesDir, "vap-test-b.mp4")
            file.copyTo(otherFile, overwrite = true)
            val second = PlayableItem(android.net.Uri.fromFile(otherFile).toString(), "VAP device test B").toMediaItem()
            main { future = PlaybackConnection.connect(targetContext) }
            controller = future!!.get(15, TimeUnit.SECONDS)
            val player = controller
            main {
                player.setMediaItems(listOf(first, second), 0, 0)
                player.prepare()
                player.play()
            }
            await("ready/playing") { player.isPlaying && player.duration > 0 }
            val duration = read { player.duration }
            check(duration >= 100_000) { "Expected the two-minute test sample" }
            main { player.seekTo(35_000) }
            await("seek") { player.currentPosition in 35_000..45_000 }
            main { player.pause() }
            await("pause") { !player.isPlaying }
            main { player.setPlaybackSpeed(1.5f) }
            await("speed") { player.playbackParameters.speed == 1.5f }

            // Новый UI-контроллер видит тот же движок, очередь и позицию.
            var nextFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { nextFuture = PlaybackConnection.connect(targetContext) }
            val secondController = nextFuture!!.get(15, TimeUnit.SECONDS)
            check(read { secondController.mediaItemCount == 2 && secondController.currentPosition >= 35_000 })
            main { MediaController.releaseFuture(nextFuture!!) }
            main { PlaybackConnection.appendIfActive(targetContext, listOf(PlayableItem(uri, "VAP device test C"))) }
            await("append queue") { player.mediaItemCount == 3 }

            main { player.seekTo(0, duration - 1_000); player.play() }
            await("automatic next") { player.currentMediaItemIndex == 1 && player.isPlaying }
            await("history for previous item") {
                VapData.history(targetContext).get(uri)?.watched == true
            }
            main { player.pause(); player.seekTo(1, 22_000) }
            await("new item seek") { player.currentPosition == 22_000L }
            // Снятие UI не должно уничтожить сессию; она остаётся доступна новому контроллеру.
            main { MediaController.releaseFuture(future!!) }
            future = null
            main { nextFuture = PlaybackConnection.connect(targetContext) }
            val restored = nextFuture!!.get(15, TimeUnit.SECONDS)
            check(read { restored.mediaItemCount == 3 && restored.currentMediaItemIndex == 1 && restored.currentPosition == 22_000L })
            main { restored.setPlaybackSpeed(1f); restored.pause(); MediaController.releaseFuture(nextFuture!!) }
            await("persisted snapshot") {
                val raw = targetContext.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
                    .getString("session", null)
                raw != null && org.json.JSONObject(raw).let {
                    it.getInt("index") == 1 && it.getLong("position") == 22_000L &&
                        it.getDouble("speed") == 1.0 && it.getJSONArray("items").length() == 3
                }
            }
            report.putString("stream", "PASS: real decoder, play/pause, seek, speed, shared session, queue append, auto-next, previous-item history, controller reconnect\n")
        } catch (error: Throwable) {
            result = Activity.RESULT_CANCELED
            main { controller?.pause(); future?.let { MediaController.releaseFuture(it) } }
            report.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}\n")
        } finally {
            runBlocking { preferences.updateSettings { original } }
        }
        finish(result, report)
    }

    private fun main(block: () -> Unit) = runOnMainSync(block)
    private fun <T> read(block: () -> T): T {
        var value: T? = null
        main { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (read(condition)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }
}
