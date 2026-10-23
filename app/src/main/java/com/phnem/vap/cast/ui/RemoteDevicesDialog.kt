package com.phnem.vap.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.R
import com.phnem.vap.cast.RemoteAudioTrack
import com.phnem.vap.cast.RemoteConnection
import com.phnem.vap.cast.RemoteRuntime
import com.phnem.vap.cast.RemoteStatus
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelBusy
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSlider
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import com.phnem.vap.ui.components.VapSheet
import kotlinx.coroutines.launch

/** Трансляция: устройства и управление воспроизведением на приёмнике. Шторка Collection. */
@Composable
fun RemoteDevicesDialog(runtime: RemoteRuntime, onDismiss: () -> Unit) {
    val manager = runtime.manager
    val devices by manager.devices.collectAsStateWithLifecycle()
    val media by manager.media.collectAsStateWithLifecycle()
    val active = media != null
    val connection by manager.connection.collectAsStateWithLifecycle()
    val state by manager.playback.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<RemoteAudioTrack>>(emptyList()) }
    LaunchedEffect(active) { while (active) { tracks = runCatching { manager.audioTracks() }.getOrDefault(emptyList()); kotlinx.coroutines.delay(2000) } }
    var subtitles by remember { mutableStateOf(true) }
    val scope = runtime.scope
    DisposableEffect(manager) { manager.startDiscovery(); onDispose { manager.stopDiscovery() } }
    fun action(block: suspend () -> Unit) { scope.launch { busy = true; error = false
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true }
        finally { busy = false }
    } }
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.cast_title), onClose = close, closeLabel = stringResource(R.string.cast_close))
        PanelBody {
            if (busy || connection is RemoteConnection.Connecting) PanelBusy()
            if (error || connection is RemoteConnection.Failed) PanelError(stringResource(R.string.cast_failed))
            if (active) {
                val name = (connection as? RemoteConnection.Connected)?.device?.name ?: media?.title.orEmpty()
                PanelText(stringResource(R.string.cast_on, name), bold = true)
                PanelCard {
                    PanelText("${time(state.positionAt(android.os.SystemClock.elapsedRealtime()))} / ${time(state.durationMs ?: 0)}")
                    if (state.durationMs != null && state.durationMs!! > 0) {
                        var seeking by remember { mutableStateOf<Float?>(null) }
                        PanelSlider(seeking ?: (state.positionAt(android.os.SystemClock.elapsedRealtime()).toDouble() / state.durationMs!!).toFloat().coerceIn(0f, 1f),
                            { seeking = it }, 0f..1f, enabled = !busy, description = stringResource(R.string.cast_title),
                            onValueChangeFinished = { val target = seeking; seeking = null; if (target != null) action { manager.seek((target * state.durationMs!!).toLong()) } })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton("−10 s", { action { manager.seek((manager.currentPositionMs() - 10_000).coerceAtLeast(0)) } }, Modifier.weight(1f), enabled = !busy)
                        PanelButton(stringResource(if (state.status == RemoteStatus.PLAYING) R.string.cast_pause else R.string.cast_play),
                            { action { if (state.status == RemoteStatus.PLAYING) manager.pause() else manager.play() } }, Modifier.weight(1.4f), enabled = !busy, primary = true)
                        PanelButton("+10 s", { action { manager.seek(manager.currentPositionMs() + 10_000) } }, Modifier.weight(1f), enabled = !busy)
                    }
                }
                val service = (connection as? RemoteConnection.Connected)?.service
                if (service?.capabilities?.volume == true) {
                    PanelCard {
                        PanelText(stringResource(R.string.cast_volume), bold = true)
                        var volume by remember(state.volume) { mutableFloatStateOf(state.volume ?: .5f) }
                        PanelSlider(volume, { volume = it }, 0f..1f, enabled = !busy, description = stringResource(R.string.cast_volume),
                            onValueChangeFinished = { action { manager.setVolume(volume) } })
                    }
                }
                if (service?.capabilities?.audioTracks == true && tracks.isNotEmpty()) {
                    PanelSection(stringResource(R.string.cast_audio))
                    tracks.forEach { track ->
                        PanelRow(track.label, { action { manager.selectAudio(track.id); tracks = manager.audioTracks() } }, enabled = !busy, selected = track.selected)
                    }
                }
                PanelHint(stringResource(R.string.cast_tracks_note))
                PanelRow(stringResource(R.string.cast_stop), { action { runtime.returnToPhone(false); close() } }, enabled = !busy, danger = true)
            } else {
                PanelHint(stringResource(R.string.cast_network_note))
                PanelSwitchRow(stringResource(R.string.cast_subtitles), subtitles, { subtitles = it })
                if (devices.isEmpty()) PanelText(stringResource(R.string.cast_empty))
                devices.forEach { device ->
                    PanelRow(device.name, { action { runtime.cast(device, subtitles) } }, subtitle = device.protocolLabel, enabled = !busy)
                }
                PanelTextField(address, { address = it }, stringResource(R.string.cast_renderer_url))
                PanelButton(stringResource(R.string.cast_add_renderer), { action { runtime.dlna.addRenderer(address.trim()) } },
                    enabled = !busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth())
                PanelHint(stringResource(R.string.cast_compatibility))
            }
        }
        if (active) PanelFooter {
            PanelButton(stringResource(R.string.cast_return), { action { runtime.returnToPhone(); close() } }, enabled = !busy, primary = true)
        }
    }
}

private fun time(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
