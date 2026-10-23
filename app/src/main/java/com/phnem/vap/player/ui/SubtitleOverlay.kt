package com.phnem.vap.player.ui

import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.phnem.vap.player.subtitles.*
import kotlinx.coroutines.delay

/** Separate from the scaled video surface, so zoom/crop never scales caption text. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SubtitleOverlay(player: Player, uri: String, settings: SubtitleSettings, revision: Int, styledPrimary: Boolean = false) {
    val context = LocalContext.current
    val storage = remember { SubtitleStorage(context) }
    var primary by remember(uri) { mutableStateOf(emptyList<SubtitleLine>()) }
    var secondary by remember(uri) { mutableStateOf(emptyList<SubtitleLine>()) }
    var native by remember(uri) { mutableStateOf(player.currentCues.cues.toList()) }
    var shown by remember(uri) { mutableStateOf(emptyList<Cue>()) }
    var spoken by remember(uri) { mutableStateOf("") }
    val primaryTimeline = remember(primary) { SubtitleTimeline(primary) }
    val secondaryTimeline = remember(secondary) { SubtitleTimeline(secondary) }
    val external = settings.sources.any { it.id == settings.selection }
    LaunchedEffect(uri, settings.selection, settings.secondary, revision) {
        primary = emptyList(); secondary = emptyList()
        primary = if (external) runCatching { storage.read(settings.selection) }.getOrDefault(emptyList()) else emptyList()
        secondary = settings.secondary?.let { runCatching { storage.read(it) }.getOrDefault(emptyList()) }.orEmpty()
    }
    DisposableEffect(player, uri) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { native = cueGroup.cues.toList() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, uri, settings, primary, secondary, native, styledPrimary) {
        while (true) {
            val position = player.currentPosition
            val first = when {
                settings.selection == "off" || styledPrimary -> emptyList()
                external -> primaryTimeline.active(position, settings.delayMs, settings.scale).map { Cue.Builder().setText(it.text).build() }
                else -> native
            }
            val second = if (settings.selection == "off") emptyList() else secondaryTimeline.active(position, settings.delayMs, settings.scale)
                .map { Cue.Builder().setText(it.text).setLine(.08f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build() }
            shown = first + second
            val styledText = if (styledPrimary) primaryTimeline.active(position, settings.delayMs, settings.scale).map { it.text } else emptyList()
            spoken = (styledText + shown.mapNotNull { it.text?.toString() }).joinToString("\n")
            delay(40)
        }
    }
    val fontScale = LocalDensity.current.fontScale
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { SubtitleView(it).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES } }, update = { view ->
        view.contentDescription = spoken
        val typeface = when (settings.font) { "serif" -> Typeface.SERIF; "mono" -> Typeface.MONOSPACE; else -> Typeface.SANS_SERIF }
        val edge = when (settings.edge) { "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; "none" -> CaptionStyleCompat.EDGE_TYPE_NONE; else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE }
        val color = (settings.color and 0x00ffffff) or ((settings.opacity * 255).toInt() shl 24)
        view.setApplyEmbeddedStyles(false)
        view.setStyle(CaptionStyleCompat(color, if (settings.background) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, typeface))
        view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, settings.sizeSp / fontScale)
        view.setBottomPaddingFraction(settings.bottom)
        view.setCues(shown)
    })
}
