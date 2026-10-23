package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.FilePlaybackSettings
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.toMediaItem
import com.phnem.vap.player.ui.PlayerSettings
import com.phnem.vap.player.ui.SeekFrameLoader
import com.phnem.vap.player.ui.VideoFit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Настройки и реальные кадры проверяются на устройстве, сервис и декодер не подменяются. */
fun Instrumentation.runPlaybackControlsTests() {
        val report = Bundle()
        val preferences = PlayerPreferences(targetContext)
        val source = java.io.File(targetContext.filesDir, "vap-test.mp4")
        val second = java.io.File(targetContext.filesDir, "vap-test-b.mp4")
        val corrupt = java.io.File(targetContext.filesDir, "vap-test-corrupt.mp4")
        val uri = Uri.fromFile(source).toString()
        val otherUri = Uri.fromFile(second).toString()
        var savedGlobal: PlayerSettings? = null
        var savedA: FilePlaybackSettings? = null
        var savedB: FilePlaybackSettings? = null
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        var failure: Throwable? = null
        try {
            check(source.isFile) { "Two-minute test MP4 missing" }
            source.copyTo(second, overwrite = true)
            corrupt.writeText("not a video")
            runBlocking {
                savedGlobal = preferences.settings.first()
                savedA = preferences.fileSettings(uri).first()
                savedB = preferences.fileSettings(otherUri).first()
                preferences.updateSettings { it.copy(seekStepSeconds = 30, doubleTapSeek = false,
                    swipeBrightness = false, autoNext = false) }
                preferences.setSpeed(uri, 1.25f)
                preferences.setFit(uri, VideoFit.CROP)
                preferences.setAudio(uri, "English")
                preferences.setSpeed(otherUri, 0.75f)
                val fresh = PlayerPreferences(targetContext)
                check(fresh.settings.first().let { it.seekStepSeconds == 30 && !it.doubleTapSeek && !it.swipeBrightness && !it.autoNext })
                check(fresh.fileSettings(uri).first().let { it.speed == 1.25f && it.fit == VideoFit.CROP && it.audioLabel == "English" })
                check(fresh.fileSettings(otherUri).first().speed == 0.75f)
                val bitmap = SeekFrameLoader.load(targetContext, uri, 15_000)
                check(bitmap != null && bitmap.width in 1..320 && bitmap.height in 1..180)
                check(SeekFrameLoader.load(targetContext, uri, 15_500) === bitmap)
                check(SeekFrameLoader.load(targetContext, Uri.fromFile(corrupt).toString(), 0) == null)
                check(SeekFrameLoader.load(targetContext, "https://invalid.example/video.mp4", 0) == null)
            }
            main { future = PlaybackConnection.connect(targetContext) }
            controller = future!!.get(15, TimeUnit.SECONDS)
            val player = controller
            main {
                player.setMediaItems(listOf(PlayableItem(uri, "Controls test A").toMediaItem(),
                    PlayableItem(otherUri, "Controls test B").toMediaItem()), 0, 0)
                player.prepare(); player.play()
            }
            await("file A speed") { player.isPlaying && player.playbackParameters.speed == 1.25f }
            val duration = read { player.duration }
            main { player.seekTo(duration - 700) }
            await("auto-next disabled") { !player.playWhenReady }
            check(read { player.currentMediaItemIndex == 0 })
            main { player.seekToNextMediaItem(); player.play() }
            await("file B speed") { player.currentMediaItemIndex == 1 && player.playbackParameters.speed == 0.75f && player.isPlaying }
            runBlocking { preferences.setSpeed(uri, 1.75f) }
            check(read { player.playbackParameters.speed == 0.75f })
            main { player.seekToPreviousMediaItem(); player.play() }
            await("return to file A") { player.currentMediaItemIndex == 0 && player.playbackParameters.speed == 1.75f }
            main { player.setPlaybackSpeed(2f) }
            runBlocking { preferences.updateSettings { it.copy(swipeVolume = !it.swipeVolume) } }
            check(read { player.playbackParameters.speed == 2f }) { "Unrelated setting reset temporary hold speed" }
        } catch (error: Throwable) { failure = error }
        finally {
            main { controller?.pause() }
            runBlocking {
                savedGlobal?.let { original -> preferences.updateSettings { original } }
                listOf(uri to savedA, otherUri to savedB).forEach { (key, original) ->
                    if (original != null) {
                        preferences.resetFile(key)
                        preferences.setSpeed(key, original.speed)
                        preferences.setFit(key, original.fit)
                        original.audioLabel?.let { preferences.setAudio(key, it) }
                    }
                }
            }
            main { future?.let { MediaController.releaseFuture(it) } }
            corrupt.delete()
        }
        report.putString("stream", if (failure == null)
            "PASS: preferences roundtrip, per-file speed/scale/audio, real preview frame/cache, invalid media fallback, auto-next disabled, background per-file speed, temporary speed isolation\n"
            else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

private fun Instrumentation.main(block: () -> Unit) = runOnMainSync(block)
private fun <T> Instrumentation.read(block: () -> T): T {
        var value: T? = null
        main { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
private fun Instrumentation.await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (read(condition)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }
