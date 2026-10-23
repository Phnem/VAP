package com.phnem.vap.player.subtitles

import androidx.media3.common.*
import java.util.Locale

data class SubtitleChoice(val key: String, val label: String, val supported: Boolean, val selected: Boolean, val group: Int, val track: Int, val trackId: String, val ass: Boolean)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Tracks.subtitleChoices(): List<SubtitleChoice> = groups.flatMapIndexed { g, group ->
    if (group.type != C.TRACK_TYPE_TEXT) emptyList() else (0 until group.length).map { t ->
        val f = group.getTrackFormat(t)
        SubtitleChoice("embedded:" + listOf(f.id, f.language, f.label, f.sampleMimeType, g, t).joinToString("|"),
            listOfNotNull(f.label ?: f.language?.let { Locale.forLanguageTag(it).displayLanguage } ?: "${g + 1}.${t + 1}", f.sampleMimeType?.substringAfter('/')).joinToString(" · "),
            group.isTrackSupported(t), group.isTrackSelected(t), g, t, f.id.orEmpty(), f.sampleMimeType == MimeTypes.TEXT_SSA || f.codecs == MimeTypes.TEXT_SSA)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Player.applySubtitleSelection(settings: SubtitleSettings) {
    val b = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, settings.selection != "auto" && !settings.selection.startsWith("embedded:"))
        .setPreferredTextLanguage(settings.language.ifBlank { null })
        .setSelectUndeterminedTextLanguage(true)
    currentTracks.subtitleChoices().firstOrNull { it.key == settings.selection && it.supported }?.let { choice ->
        b.setOverrideForType(TrackSelectionOverride(currentTracks.groups[choice.group].mediaTrackGroup, listOf(choice.track)))
    }
    val p = b.build()
    if (p != trackSelectionParameters) trackSelectionParameters = p
}
