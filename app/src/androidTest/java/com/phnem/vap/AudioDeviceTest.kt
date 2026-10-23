package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.session.MediaController
import com.phnem.vap.player.*
import com.phnem.vap.player.audio.*
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runAudioDeviceTests() {
    val prefs = PlayerPreferences(targetContext)
    val source = File(targetContext.filesDir, "audio-fixtures/dual-audio.mkv")
    val second = File(targetContext.filesDir, "audio-fixtures/dual-b.mkv")
    val external = File(targetContext.filesDir, "audio-fixtures/external.mka")
    check(source.isFile && external.isFile); source.copyTo(second, true)
    val uri = Uri.fromFile(source).toString(); val other = Uri.fromFile(second).toString()
    val original = runBlocking { prefs.settings.first() }
    val originalFile = runBlocking { prefs.fileSettings(uri).first() }
    val originalOther = runBlocking { prefs.fileSettings(other).first() }
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null
    var failure: Throwable? = null
    fun info() = runBlocking { withContext(Dispatchers.Main) { player!!.audioInfo() } }
    fun selected() = uiRead { player!!.currentTracks.audioChoices().firstOrNull { it.selected } }
    try {
        uiMark("PCM positive and negative delay")
        fun samples(delay: Int): List<Short> {
            val p = PlaybackAudioProcessor(); p.settings = AudioSettings(delayMs = delay)
            p.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
            p.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val input = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder()).apply { repeat(10) { putShort((it + 1).toShort()) }; flip() }
            val result = mutableListOf<Short>()
            while (input.hasRemaining()) {
                p.queueInput(input); val out = p.output.order(ByteOrder.nativeOrder())
                while (out.hasRemaining()) result += out.short
            }
            if (delay == 3) {
                p.flush(AudioProcessor.StreamMetadata.DEFAULT)
                input.clear(); repeat(10) { input.putShort((it + 1).toShort()) }; input.flip()
                p.queueInput(input); check(p.output.remaining() == 20) { "Speed flush accumulated delay" }
                p.resetDelay(); p.flush(AudioProcessor.StreamMetadata.DEFAULT)
                input.clear(); repeat(10) { input.putShort((it + 1).toShort()) }; input.flip()
                p.queueInput(input); check(p.output.remaining() == 6) { "Seek did not reapply delay" }
            }
            p.reset(); return result
        }
        check(samples(3) == List(3) { 0.toShort() } + (1..10).map(Int::toShort))
        check(samples(-3) == (4..10).map(Int::toShort))
        runBlocking {
            prefs.updateSettings { it.copy(preferredAudioLanguage = "ru", preservePitch = true, autoNext = false) }
            prefs.resetFile(uri); prefs.resetFile(other)
        }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player!!
        runOnMainSync {
            controller.setMediaItems(listOf(PlayableItem(uri, "Two audio tracks").toMediaItem(), PlayableItem(other, "Audio B").toMediaItem()))
            controller.prepare(); controller.play()
        }
        awaitUi("Russian language preference") { selected()?.language in listOf("ru", "rus") && uiRead { controller.isPlaying } }
        check(uiRead { controller.currentTracks.audioChoices().size == 2 })
        awaitUi("actual decoded PCM reaches sink") { info().getLong("processedFrames") > 1000 && info().getInt("channels") == 2 && info().getFloat("rightRms") > .01 }
        val english = uiRead { controller.currentTracks.audioChoices().first { it.language in listOf("en", "eng") } }
        runBlocking { prefs.setAudioKey(uri, english.key) }
        awaitUi("manual English track") { selected()?.language in listOf("en", "eng") }
        runOnMainSync { controller.seekToNextMediaItem(); controller.play() }
        awaitUi("next file language without UI") { uiRead { controller.currentMediaItemIndex == 1 } && selected()?.language in listOf("ru", "rus") }
        runOnMainSync { controller.seekToPreviousMediaItem(); controller.play() }
        awaitUi("per-file selection restored") { uiRead { controller.currentMediaItemIndex == 0 } && selected()?.language in listOf("en", "eng") }
        runBlocking { prefs.setAudioKey(uri, null) }
        awaitUi("automatic selection restored") { selected()?.language in listOf("ru", "rus") }
        uiMark("real PCM balance, gain and mono")
        runBlocking { prefs.setAudioSettings(uri, AudioSettings(balance = 1f)) }
        awaitUi("left channel muted") { info().getFloat("leftRms") == 0f && info().getFloat("rightRms") > .01f }
        val baseline = info().getFloat("rightRms")
        runBlocking { prefs.setAudioSettings(uri, AudioSettings(balance = 1f, gainDb = 6f)) }
        awaitUi("boost changes actual PCM") { info().getFloat("rightRms") > baseline * 1.7f }
        runBlocking { prefs.setAudioSettings(uri, AudioSettings(mono = true)) }
        awaitUi("mono PCM channels equal") { val data = info(); data.getFloat("leftRms") > .01f && abs(data.getFloat("leftRms") - data.getFloat("rightRms")) < .00001f }
        runBlocking { prefs.setAudioSettings(uri, AudioSettings(delayMs = 500, normalize = true, equalizer = listOf(3f, 0f, -2f, 0f, 0f))) }
        awaitUi("delay restart preserves intent") { info().getInt("delayMs") == 500 && uiRead { controller.isPlaying && controller.playerError == null } }
        runOnMainSync { controller.setPlaybackSpeed(1.5f) }
        awaitUi("pitch maintained") { info().getFloat("speed") == 1.5f && info().getFloat("pitch") == 1f }
        runBlocking { prefs.updateSettings { it.copy(preservePitch = false) } }
        awaitUi("pitch follows speed") { info().getFloat("pitch") == 1.5f }
        runBlocking { prefs.updateSettings { it.copy(preservePitch = true) } }
        awaitUi("pitch restored") { info().getFloat("pitch") == 1f }
        uiMark("external MKA source and restoration")
        runBlocking { prefs.setExternalAudio(uri, Uri.fromFile(external).toString()) }
        awaitUi("external French FLAC") { selected()?.language in listOf("fra", "fr") && uiRead { controller.isPlaying && controller.playerError == null } }
        check(uiRead { controller.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } })
        check(uiRead { controller.mediaItemCount == 2 && controller.duration in 29_000..31_000 })
        val fresh = PlayerPreferences(targetContext)
        check(runBlocking { fresh.fileSettings(uri).first() }.let { it.audio.externalUri == Uri.fromFile(external).toString() && it.audio.delayMs == 500 && it.audio.normalize })
        runBlocking { prefs.setExternalAudio(uri, null) }
        awaitUi("embedded tracks restored") { uiRead { controller.currentTracks.audioChoices().size == 2 && controller.isPlaying } && selected()?.language in listOf("ru", "rus") }
        val devices = info()
        @Suppress("DEPRECATION") val outputs = devices.getParcelableArrayList<Bundle>("devices").orEmpty()
        check(outputs.isNotEmpty())
        val deviceId = outputs.first().getInt("id")
        val routeFuture = uiRead { controller.sendCustomCommand(AudioCommands.ROUTE, Bundle().apply { putInt("id", deviceId) }) }
        check(routeFuture.get(10, TimeUnit.SECONDS).resultCode == 0)
        awaitUi("preferred output request") { info().getInt("preferredDevice") == deviceId }
        val invalid = uiRead { controller.sendCustomCommand(AudioCommands.ROUTE, Bundle().apply { putInt("id", Int.MAX_VALUE) }) }
        check(invalid.get(10, TimeUnit.SECONDS).resultCode != 0)
        val system = uiRead { controller.sendCustomCommand(AudioCommands.ROUTE, Bundle().apply { putInt("id", 0) }) }
        check(system.get(10, TimeUnit.SECONDS).resultCode == 0)
        uiMark("service controller reconnect")
        runOnMainSync { MediaController.releaseFuture(future!!); future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS)
        check(info().getString("language") == "ru")
    } catch (error: Throwable) { failure = error }
    finally {
        runOnMainSync { player?.pause(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking {
            prefs.updateSettings { original }
            listOf(uri to originalFile, other to originalOther).forEach { (key, saved) ->
                prefs.resetFile(key); prefs.setSpeed(key, saved.speed); prefs.setFit(key, saved.fit)
                prefs.setPicture(key, saved.picture); prefs.setAudioSettings(key, saved.audio)
                saved.audioKey?.let { prefs.setAudioKey(key, it) }; saved.audioLabel?.let { prefs.setAudio(key, it) }
            }
        }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: actual dual-track decode/selection/language without UI, per-file restoration, decoded PCM balance/gain/mono, delay insertion/drop, pitch settings, external MKA/FLAC, preference roundtrip, output request, controller reconnect\n"
        else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    })
}
