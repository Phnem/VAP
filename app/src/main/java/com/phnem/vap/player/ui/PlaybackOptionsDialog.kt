package com.phnem.vap.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phnem.vap.R
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.VapSheet

/** Настройки воспроизведения: шторка Collection вместо `AlertDialog` Material 3. */
@Composable
fun PlaybackOptionsDialog(
    settings: PlayerSettings,
    onChange: ((PlayerSettings) -> PlayerSettings) -> Unit,
    onDismiss: () -> Unit,
    onVideoTools: () -> Unit,
) {
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.player_options), onClose = close)
        PanelBody {
            PanelRow(stringResource(R.string.video_tools), onVideoTools)
            PanelSection(stringResource(R.string.player_seek_step))
            PanelPillRow {
                listOf(5, 10, 15, 30, 60).forEach { seconds ->
                    PanelPill(stringResource(R.string.player_seek_seconds, seconds), settings.seekStepSeconds == seconds,
                        { onChange { it.copy(seekStepSeconds = seconds) } }, Modifier.testTag("player_seek_step_$seconds"))
                }
            }
            PanelSwitchRow(stringResource(R.string.player_auto_next), settings.autoNext, { v -> onChange { it.copy(autoNext = v) } })
            PanelSwitchRow(stringResource(R.string.player_frame_preview), settings.framePreview, { v -> onChange { it.copy(framePreview = v) } })
            PanelSection(stringResource(R.string.player_gestures), Modifier.padding(top = 6.dp))
            PanelSwitchRow(stringResource(R.string.player_double_tap), settings.doubleTapSeek, { v -> onChange { it.copy(doubleTapSeek = v) } })
            PanelSwitchRow(stringResource(R.string.player_swipe_seek), settings.swipeSeek, { v -> onChange { it.copy(swipeSeek = v) } })
            PanelSwitchRow(stringResource(R.string.player_swipe_brightness), settings.swipeBrightness, { v -> onChange { it.copy(swipeBrightness = v) } })
            PanelSwitchRow(stringResource(R.string.player_swipe_volume), settings.swipeVolume, { v -> onChange { it.copy(swipeVolume = v) } })
            PanelSwitchRow(stringResource(R.string.player_hold_speed), settings.holdToSpeed, { v -> onChange { it.copy(holdToSpeed = v) } })
            PanelSwitchRow(stringResource(R.string.player_pinch), settings.pinchZoom, { v -> onChange { it.copy(pinchZoom = v) } })
            PanelHint(stringResource(R.string.player_file_settings_note))
        }
        PanelFooter {
            PanelButton(stringResource(R.string.player_reset_gestures), { onChange { PlayerSettings() } })
        }
    }
}
