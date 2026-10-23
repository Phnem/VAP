package com.phnem.vap.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import com.phnem.vap.R
import com.phnem.vap.player.audio.AudioCommands
import com.phnem.vap.player.audio.AudioSettings
import com.phnem.vap.player.audio.audioChoices
import com.phnem.vap.player.audio.audioInfo
import com.phnem.vap.player.audio.selectAudio
import com.phnem.vap.player.settings.FilePlaybackSettings
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSliderCard
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelTabs
import com.phnem.vap.ui.components.PanelTextField
import com.phnem.vap.ui.components.VapSheet
import kotlinx.coroutines.delay
import java.util.Locale

/** Звук: дорожки, обработка (задержка, усиление, эквалайзер) и выход. Шторка Collection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AudioToolsDialog(player: Player, uri: String, global: PlayerSettings, file: FilePlaybackSettings,
    onGlobal: ((PlayerSettings) -> PlayerSettings) -> Unit, onAudio: (AudioSettings) -> Unit,
    onSelection: (String?) -> Unit, onExternal: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf(player.currentTracks.audioChoices()) }
    var info by remember { mutableStateOf(Bundle.EMPTY) }
    var tab by remember { mutableIntStateOf(0) }
    var pendingVideo by rememberSaveable { mutableStateOf(uri) }
    val pickerError = stringResource(R.string.audio_file_permission)
    // A concrete primary MIME avoids OEM catch-all pickers which cannot retain SAF grants.
    val audioDocument = remember { object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
            val intent = super.createIntent(context, input).setType("audio/*")
            // Prefer the platform SAF browser when present. Xiaomi's generic picker hides
            // its file list in landscape; DocumentsUI supports landscape and persistent grants.
            for (provider in listOf("com.google.android.documentsui", "com.android.documentsui")) {
                val explicit = Intent(intent).setPackage(provider)
                if (explicit.resolveActivity(context.packageManager) != null) return explicit
            }
            return intent
        }
    } }
    val picker = rememberLauncherForActivityResult(audioDocument) { audio ->
        if (audio != null) try {
            context.contentResolver.takePersistableUriPermission(audio, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onExternal(pendingVideo, audio.toString())
        } catch (_: SecurityException) { android.widget.Toast.makeText(context, pickerError, android.widget.Toast.LENGTH_LONG).show() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { choices = tracks.audioChoices() }
        }
        player.addListener(listener); onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            info = runCatching { (player as? MediaController)?.audioInfo() ?: Bundle.EMPTY }.getOrDefault(Bundle.EMPTY)
            delay(750)
        }
    }
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.audio_tools), onClose = close)
        PanelTabs(listOf(R.string.audio_tracks, R.string.audio_processing, R.string.audio_output).map { stringResource(it) }, tab, { tab = it })
        key(tab) {
            PanelBody {
                when (tab) {
                    0 -> {
                        PanelHint(stringResource(R.string.audio_file_settings))
                        PanelRow(stringResource(R.string.audio_automatic), { onSelection(null) }, selected = file.audioKey == null)
                        choices.firstOrNull { it.selected }?.let { PanelHint(stringResource(R.string.audio_playing, it.label)) }
                        choices.forEach { choice ->
                            PanelRow(choice.label, { player.selectAudio(choice); onSelection(choice.key) },
                                subtitle = if (!choice.supported) stringResource(R.string.audio_unsupported) else null,
                                selected = file.audioKey == choice.key, enabled = choice.supported)
                        }
                        if (choices.isEmpty()) PanelHint(stringResource(R.string.audio_no_tracks))
                        PanelRow(stringResource(R.string.audio_add_external), {
                            pendingVideo = uri; picker.launch(arrayOf("audio/*", "video/x-matroska", "application/ogg"))
                        })
                        if (file.audio.externalUri != null) {
                            PanelHint(stringResource(R.string.audio_external_active))
                            PanelRow(stringResource(R.string.audio_remove_external), { onExternal(uri, null) }, danger = true)
                        }
                        PanelSection(stringResource(R.string.audio_language))
                        PanelHint(stringResource(R.string.audio_language_hint))
                        var language by remember(global.preferredAudioLanguage) { mutableStateOf(global.preferredAudioLanguage) }
                        PanelTextField(language, { language = it.take(35) }, stringResource(R.string.audio_language_code))
                        PanelButton(stringResource(R.string.audio_apply_language),
                            { onGlobal { it.copy(preferredAudioLanguage = language.trim().lowercase(Locale.ROOT)) } })
                    }
                    1 -> {
                        PanelSwitchRow(stringResource(R.string.audio_preserve_pitch), global.preservePitch, { c -> onGlobal { it.copy(preservePitch = c) } })
                        AudioSliderCard(stringResource(R.string.audio_delay), file.audio.delayMs.toFloat(), -2000f..2000f, "ms",
                            hint = stringResource(R.string.audio_delay_hint)) { onAudio(file.audio.copy(delayMs = (it / 10).toInt() * 10)) }
                        AudioSliderCard(stringResource(R.string.audio_gain), file.audio.gainDb, 0f..12f, "dB") { onAudio(file.audio.copy(gainDb = it)) }
                        AudioSliderCard(stringResource(R.string.audio_balance), file.audio.balance, -1f..1f, "",
                            hint = stringResource(R.string.audio_balance_hint)) { onAudio(file.audio.copy(balance = it)) }
                        PanelSwitchRow(stringResource(R.string.audio_mono), file.audio.mono, { onAudio(file.audio.copy(mono = it)) })
                        PanelSwitchRow(stringResource(R.string.audio_normalize), file.audio.normalize, { onAudio(file.audio.copy(normalize = it)) },
                            hint = stringResource(R.string.audio_normalize_hint))
                        PanelSection(stringResource(R.string.audio_equalizer))
                        AudioSettings.FREQUENCIES.forEachIndexed { i, hz ->
                            AudioSliderCard("$hz Hz", file.audio.equalizer[i], -12f..12f, "dB") { db ->
                                onAudio(file.audio.copy(equalizer = file.audio.equalizer.toMutableList().apply { set(i, db) }))
                            }
                        }
                    }
                    2 -> {
                        PanelHint(stringResource(R.string.audio_route_hint))
                        fun route(id: Int) { (player as? MediaController)?.sendCustomCommand(AudioCommands.ROUTE, Bundle().apply { putInt("id", id) }) }
                        PanelRow(stringResource(R.string.audio_system_output), { route(0) }, selected = info.getInt("preferredDevice") == 0)
                        @Suppress("DEPRECATION") val devices = info.getParcelableArrayList<Bundle>("devices").orEmpty()
                        devices.forEach { device ->
                            val id = device.getInt("id")
                            val kind = when (device.getInt("type")) {
                                1, 2 -> stringResource(R.string.audio_device_speaker)
                                3, 4 -> stringResource(R.string.audio_device_wired)
                                7, 8, 26, 27, 30 -> "Bluetooth"
                                11, 12, 22 -> "USB"
                                9, 10, 29 -> "HDMI"
                                else -> ""
                            }
                            PanelRow(listOf(device.getString("name").orEmpty(), kind).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                                { route(id) }, selected = info.getInt("preferredDevice") == id)
                        }
                    }
                }
            }
        }
        if (tab == 1) PanelFooter {
            PanelButton(stringResource(R.string.audio_reset), { onAudio(AudioSettings(externalUri = file.audio.externalUri)) })
        }
    }
}

@Composable
private fun AudioSliderCard(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String,
    hint: String? = null, action: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    PanelSliderCard(label, "${String.format(Locale.ROOT, if (unit == "ms") "%.0f" else "%.2f", draft)} $unit".trim(), draft, { draft = it }, range,
        hint = hint, onValueChangeFinished = { action(draft) })
}
