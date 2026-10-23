package com.phnem.vap.player.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.phnem.vap.player.settings.FilePlaybackSettings
import com.phnem.vap.player.ui.PlayerSettings

/** Main-looper configuration and routing; PCM processing runs exclusively on the audio thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioRuntime(context: Context, private val player: ExoPlayer,
    private val processor: PlaybackAudioProcessor, private val sources: AudioMediaSources) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var preferredDevice = 0
    private var uri: String? = null
    private var file = FilePlaybackSettings()
    private var global = PlayerSettings()
    private val devicesCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            if (devices.any { it.id == preferredDevice }) route(0)
        }
    }
    init {
        manager.registerAudioDeviceCallback(devicesCallback, Handler(player.applicationLooper))
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { selectSavedTrack() }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { applyPitch() }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_INTERNAL) processor.resetDelay()
            }
        })
    }
    fun configure(currentUri: String, settings: PlayerSettings, value: FilePlaybackSettings) {
        val sameFile = uri == currentUri
        val delayChanged = processor.settings.delayMs != value.audio.delayMs
        val languageChanged = global.preferredAudioLanguage != settings.preferredAudioLanguage
        val selectionChanged = !sameFile || file.audioKey != value.audioKey || languageChanged
        uri = currentUri; global = settings; file = value
        processor.settings = value.audio.sanitized()
        if (!sameFile || delayChanged) processor.resetDelay()
        applyPitch()
        if (selectionChanged) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(settings.preferredAudioLanguage.takeIf { it.isNotBlank() })
                .build()
            selectSavedTrack()
        }
        val external = value.audio.externalUri
        val changedSource = sources.external[currentUri] != external
        if (external == null) sources.external.remove(currentUri) else sources.external[currentUri] = external
        if (changedSource && player.mediaItemCount > 0) {
            val playing = player.playWhenReady; val position = player.currentPosition.coerceAtLeast(0)
            val index = player.currentMediaItemIndex
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            player.setMediaItems(items, index, position); player.prepare(); player.playWhenReady = playing
        } else if (delayChanged && player.playbackState != Player.STATE_IDLE) {
            val position = player.currentPosition.coerceAtLeast(0); val playing = player.playWhenReady
            player.stop(); player.seekTo(player.currentMediaItemIndex, position); player.prepare(); player.playWhenReady = playing
        }
    }
    private fun selectSavedTrack() {
        val choices = player.currentTracks.audioChoices()
        val saved = file.audioKey?.let { key -> choices.firstOrNull { it.key == key } }
            ?: file.audioLabel?.let { label -> choices.firstOrNull { it.label.substringBefore(" · ") == label } }
        saved?.let(player::selectAudio)
    }
    private fun applyPitch() {
        val p = player.playbackParameters
        val pitch = if (global.preservePitch) 1f else p.speed
        if (p.pitch != pitch) player.playbackParameters = PlaybackParameters(p.speed, pitch)
    }
    fun route(id: Int): Boolean {
        val target = if (id == 0) null else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id } ?: return false
        return try { player.setPreferredAudioDevice(target); preferredDevice = id; true }
        catch (_: RuntimeException) { false }
    }
    fun info() = Bundle().apply {
        putInt("preferredDevice", preferredDevice)
        putParcelableArrayList("devices", ArrayList(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            Bundle().apply { putInt("id", it.id); putInt("type", it.type); putString("name", it.productName.toString()) }
        }))
        putLong("processedFrames", processor.processedFrames); putInt("channels", processor.activeChannels)
        putFloat("leftRms", processor.leftRms); putFloat("rightRms", processor.rightRms)
        putInt("delayMs", processor.settings.delayMs)
        putFloat("speed", player.playbackParameters.speed); putFloat("pitch", player.playbackParameters.pitch)
        putString("language", global.preferredAudioLanguage)
        putString("external", file.audio.externalUri)
    }
    fun release() { manager.unregisterAudioDeviceCallback(devicesCallback) }
}
