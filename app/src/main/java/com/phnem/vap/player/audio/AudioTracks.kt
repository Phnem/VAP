package com.phnem.vap.player.audio

import androidx.media3.common.*
import java.util.Locale

data class AudioChoice(val key: String, val label: String, val language: String?, val supported: Boolean,
    val selected: Boolean, val group: Int, val track: Int)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Tracks.audioChoices(): List<AudioChoice> = groups.flatMapIndexed { g, group ->
    if (group.type != C.TRACK_TYPE_AUDIO) emptyList() else (0 until group.length).map { t ->
        val f = group.getTrackFormat(t)
        val name = f.label ?: f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage } ?: "${g + 1}.${t + 1}"
        val details = listOfNotNull(f.sampleMimeType?.substringAfter('/'), f.channelCount.takeIf { it > 0 }?.let { "$it ch" },
            f.sampleRate.takeIf { it > 0 }?.let { "${it / 1000f} kHz" }).joinToString(" · ")
        AudioChoice(listOf(f.id, f.language, f.label, f.sampleMimeType, f.channelCount).joinToString("|"),
            "$name · $details", f.language, group.isTrackSupported(t), group.isTrackSelected(t), g, t)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Player.selectAudio(choice: AudioChoice) {
    if (!choice.supported || choice.selected) return
    val group = currentTracks.groups.getOrNull(choice.group) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.track))).build()
}
