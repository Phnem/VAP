package com.phnem.vap.player.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.R
import com.phnem.vap.player.parseTimecode
import com.phnem.vap.player.video.DecoderMode
import com.phnem.vap.player.video.ExportRange
import com.phnem.vap.player.video.HdrOutput
import com.phnem.vap.player.video.PictureSettings
import com.phnem.vap.player.video.VideoToolsModel
import com.phnem.vap.player.video.videoInfo
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelBusy
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSliderCard
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelTabs
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import com.phnem.vap.ui.components.VapSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** Видео: изображение, экспорт кадра/фрагмента/GIF и сведения о потоке. Шторка Collection. */
@Composable
fun VideoToolsDialog(player: Player, uri: String, duration: Long, settings: PlayerSettings, picture: PictureSettings,
    onSettings: ((PlayerSettings) -> PlayerSettings) -> Unit, onPicture: (PictureSettings) -> Unit,
    onCapture: (VideoToolsModel) -> Unit, canCapture: Boolean, onDismiss: () -> Unit, model: VideoToolsModel = viewModel()) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(Bundle.EMPTY) }
    var metadata by remember(uri) { mutableStateOf(Bundle.EMPTY) }
    var start by remember(uri) { mutableStateOf(formatRangeTime(player.currentPosition)) }
    var end by remember(uri) { mutableStateOf(formatRangeTime((player.currentPosition + 10_000).coerceAtMost(duration.coerceAtLeast(0)))) }
    var invalidRange by remember { mutableStateOf(false) }
    val pngSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let(model::save) }
    val mp4Save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { it?.let(model::save) }
    val gifSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { it?.let(model::save) }
    LaunchedEffect(player, uri) {
        while (true) {
            info = runCatching { (player as? MediaController)?.videoInfo() ?: Bundle.EMPTY }.getOrDefault(Bundle.EMPTY)
            delay(750)
        }
    }
    LaunchedEffect(uri) {
        metadata = withContext(Dispatchers.IO) {
            val result = Bundle()
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                result.putString("container", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
                result.putString("bitrate", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE))
            } catch (_: Exception) { /* Playback diagnostics remain available if metadata is missing. */ }
            finally { retriever.release() }
            result
        }
    }
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.video_tools), onClose = close)
        PanelTabs(listOf(R.string.video_picture, R.string.video_export, R.string.video_info).map { stringResource(it) }, tab, { tab = it })
        key(tab) {
            PanelBody {
                when (tab) {
                    0 -> {
                        PanelSection(stringResource(R.string.video_decoder))
                        PanelPillRow {
                            DecoderMode.entries.forEach { mode ->
                                PanelPill(when (mode) { DecoderMode.AUTO -> "Auto"; DecoderMode.HARDWARE -> "HW"; DecoderMode.SOFTWARE -> "SW" },
                                    settings.decoderMode == mode, { onSettings { it.copy(decoderMode = mode) } }, enabled = !state.busy)
                            }
                        }
                        PanelHint(stringResource(R.string.video_decoder_hint))
                        PanelSwitchRow(stringResource(R.string.video_sdr), settings.hdrOutput == HdrOutput.SDR,
                            { checked -> onSettings { it.copy(hdrOutput = if (checked) HdrOutput.SDR else HdrOutput.AUTO) } },
                            enabled = android.os.Build.VERSION.SDK_INT >= 29 && !state.busy)
                        PanelSwitchRow(stringResource(R.string.video_match_fps), settings.matchFrameRate,
                            { checked -> onSettings { it.copy(matchFrameRate = checked) } }, enabled = !state.busy)
                        PanelHint(stringResource(R.string.video_color_hint))
                        val pictureEnabled = !state.busy && (!info.getBoolean("hdr") || android.os.Build.VERSION.SDK_INT >= 29)
                        var draft by remember(uri, picture) { mutableStateOf(picture) }
                        PictureSlider(R.string.video_brightness, draft.brightness, -0.5f..0.5f, pictureEnabled,
                            { draft = draft.copy(brightness = it) }, { onPicture(draft) })
                        PictureSlider(R.string.video_contrast, draft.contrast, 0.5f..1.5f, pictureEnabled,
                            { draft = draft.copy(contrast = it) }, { onPicture(draft) })
                        PictureSlider(R.string.video_saturation, draft.saturation, 0f..2f, pictureEnabled,
                            { draft = draft.copy(saturation = it) }, { onPicture(draft) })
                        PictureSlider(R.string.video_gamma, draft.gamma, 0.5f..2.5f, pictureEnabled,
                            { draft = draft.copy(gamma = it) }, { onPicture(draft) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelButton(stringResource(R.string.video_reset_picture), { onPicture(PictureSettings()) },
                                Modifier.weight(1f), enabled = !state.busy)
                            PanelButton(stringResource(R.string.video_capture), { onCapture(model) }, Modifier.weight(1f),
                                enabled = !state.busy && canCapture)
                        }
                    }
                    1 -> {
                        PanelHint(stringResource(R.string.video_export_hint))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelTextField(start, { start = it.take(32); invalidRange = false }, stringResource(R.string.video_range_start),
                                Modifier.weight(1f), enabled = !state.busy, error = invalidRange)
                            PanelTextField(end, { end = it.take(32); invalidRange = false }, stringResource(R.string.video_range_end),
                                Modifier.weight(1f), enabled = !state.busy, error = invalidRange)
                        }
                        if (invalidRange) PanelError(stringResource(R.string.video_range_error))
                        fun export(gif: Boolean) {
                            val range = ExportRange(parseTimecode(start) ?: -1, parseTimecode(end) ?: -1)
                            if (!range.valid(duration, if (gif) 15_000 else 120_000)) { invalidRange = true; return }
                            if (gif) model.gif(Uri.parse(uri), range, picture) else model.clip(Uri.parse(uri), range, picture)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelButton(stringResource(R.string.video_export_mp4), { export(false) }, Modifier.weight(1f), enabled = !state.busy)
                            PanelButton(stringResource(R.string.video_export_gif), { export(true) }, Modifier.weight(1f), enabled = !state.busy)
                        }
                    }
                    else -> {
                        val unknown = "—"
                        PanelCard {
                            InfoField(R.string.video_container, info.getString("container") ?: metadata.getString("container") ?: unknown)
                            InfoField(R.string.video_codec, listOfNotNull(info.getString("mime"), info.getString("codecs")).joinToString(" · ").ifBlank { unknown })
                            InfoField(R.string.video_resolution, if (info.getInt("width") > 0) "${info.getInt("width")} × ${info.getInt("height")}" else unknown)
                            InfoField(R.string.video_fps, info.getFloat("fps", -1f).takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.3f", it) } ?: unknown)
                            InfoField(R.string.video_bitrate, (info.getInt("bitrate").takeIf { it > 0 }?.toLong() ?: metadata.getString("bitrate")?.toLongOrNull())?.let { "$it bit/s" } ?: unknown)
                            InfoField(R.string.video_decoder, info.getString("decoder", unknown))
                            InfoField(R.string.video_hdr, if (info.getBoolean("hdr")) "HDR · ${if (info.getBoolean("toneMap")) "SDR (GL)" else "Auto"}" else "SDR")
                            val display = context.findActivity()?.window?.decorView?.display
                            InfoField(R.string.video_display_hz, display?.refreshRate?.let { String.format(Locale.ROOT, "%.2f Hz", it) } ?: unknown)
                            InfoField(R.string.video_dropped, info.getInt("dropped").toString())
                            InfoField(R.string.video_recoveries, info.getInt("recoveries").toString())
                        }
                        if (info.getBoolean("unsupportedVideo")) PanelError(stringResource(R.string.video_unsupported_track))
                        info.getString("error")?.takeIf { it.isNotBlank() }?.let { PanelError(it) }
                    }
                }
            }
        }
        val output = state.output?.takeIf { !state.busy }
        if (state.busy || state.error != null || state.saved || output != null) {
            PanelFooter {
                if (state.busy) {
                    state.progress?.let { PanelProgress(it / 100f) } ?: PanelBusy()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        PanelText(state.progress?.let { "$it%" } ?: stringResource(R.string.video_working), Modifier.weight(1f))
                        androidx.compose.runtime.CompositionLocalProvider(com.phnem.vap.ui.components.LocalPanelFillButtons provides false) {
                            PanelButton(stringResource(R.string.video_cancel), model::cancel)
                        }
                    }
                }
                state.error?.let { PanelError(it) }
                if (state.saved) PanelHint(stringResource(R.string.video_saved))
                output?.let { file ->
                    PanelButton(stringResource(R.string.video_save), {
                        val name = "VAP-${System.currentTimeMillis()}.${file.extension}"
                        when (file.extension) { "mp4" -> mp4Save.launch(name); "gif" -> gifSave.launch(name); else -> pngSave.launch(name) }
                    })
                }
            }
        }
    }
}

private fun formatRangeTime(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

@Composable
private fun PictureSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onChange: (Float) -> Unit, onFinished: () -> Unit) {
    PanelSliderCard(stringResource(label), String.format(Locale.ROOT, "%.2f", value), value, onChange, range,
        enabled = enabled, onValueChangeFinished = onFinished)
}

@Composable
private fun InfoField(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelText(stringResource(label), Modifier.weight(0.42f), color = PanelColors.Muted, size = 13)
        PanelText(value, Modifier.weight(0.58f), size = 14)
    }
}
