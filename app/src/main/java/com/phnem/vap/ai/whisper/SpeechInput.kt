package com.phnem.vap.ai.whisper

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.source.MediaSource
import com.phnem.vap.network.NetworkPlayback
import com.phnem.vap.player.audio.AudioMediaSources
import com.phnem.vap.player.audio.AudioSettings
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.SubtitleOrigin
import kotlinx.coroutines.flow.first

/** Immutable main-looper snapshot shared by ASR and Smart Sync, including the selected audio track. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class SpeechInput(val item: MediaItem, val uri: String, val duration: Long, val position: Long,
    val tracks: TrackSelectionParameters, val audioIdentity: String) {
    suspend fun open(context: Context): PreparedSpeech {
        val audio = PlayerPreferences(context).fileSettings(uri).first().audio
        val factory = AudioMediaSources(NetworkPlayback.sources(context))
        audio.externalUri?.let { factory.external[uri] = it }
        return PreparedSpeech(item.buildUpon().setUri(uri).build(), factory, audio, SubtitleOrigin.stamp(context, uri))
    }
    companion object {
        fun capture(player: Player): SpeechInput {
            val item = player.currentMediaItem ?: error("No video")
            val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
            require(uri.isNotBlank())
            val duration = player.duration
            require(duration in 1..86_400_000 && !player.isCurrentMediaItemLive) { "A finite video up to 24 hours is required" }
            val selected = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            var parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            selected?.let { group ->
                val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                if (index != null) parameters = parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
            val identity = selected?.let { group -> (0 until group.length).filter(group::isTrackSelected).joinToString { group.getTrackFormat(it).let { f -> "${f.id}/${f.language}/${f.label}/${f.sampleMimeType}" } } }.orEmpty()
            return SpeechInput(item, uri, duration, player.currentPosition, parameters.build(), identity)
        }
        fun requireMemory(context: Context, model: WhisperModel) {
            val memory = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
            require(!memory.lowMemory && memory.availMem >= (model.ramMb + 100L) * 1024 * 1024) { "Not enough available RAM; choose a smaller model or close other apps" }
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class PreparedSpeech(val item: MediaItem, val factory: MediaSource.Factory, val audio: AudioSettings, val stamp: String)
