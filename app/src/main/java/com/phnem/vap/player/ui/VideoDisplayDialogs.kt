package com.phnem.vap.player.ui

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phnem.vap.R
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.VapSheet

enum class PlayerOrientation(val requested: Int, val label: Int) {
    /** По кадру: вертикальное видео — вертикально, остальное — горизонтально (`requested` — пока размер кадра неизвестен). */
    VIDEO(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, R.string.player_orientation_video),
    AUTO(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, R.string.player_orientation_auto),
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, R.string.player_orientation_portrait),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, R.string.player_orientation_landscape),
}

fun VideoFit.labelResource(): Int = when (this) {
    VideoFit.FIT -> R.string.player_fit
    VideoFit.FILL -> R.string.player_fill
    VideoFit.CROP -> R.string.player_crop
    VideoFit.STRETCH -> R.string.player_stretch
    VideoFit.ORIGINAL -> R.string.player_original
}

@Composable
fun VideoFitDialog(fit: VideoFit, onSelect: (VideoFit) -> Unit, onDismiss: () -> Unit) {
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.player_frame_mode), onClose = close)
        PanelBody {
            VideoFit.entries.forEach { mode ->
                val detail = when (mode) {
                    VideoFit.FIT -> R.string.player_fit_detail
                    VideoFit.FILL -> R.string.player_fill_detail
                    VideoFit.CROP -> R.string.player_crop_detail
                    VideoFit.STRETCH -> R.string.player_stretch_detail
                    VideoFit.ORIGINAL -> R.string.player_original_detail
                }
                PanelRow(stringResource(mode.labelResource()), { onSelect(mode); close() },
                    subtitle = stringResource(detail), selected = mode == fit)
            }
        }
    }
}

@Composable
fun OrientationDialog(value: PlayerOrientation, onSelect: (PlayerOrientation) -> Unit, onDismiss: () -> Unit) {
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.player_orientation), onClose = close)
        PanelBody {
            PlayerOrientation.entries.forEach { mode ->
                PanelRow(stringResource(mode.label), { onSelect(mode); close() },
                    subtitle = when (mode) {
                        PlayerOrientation.AUTO -> stringResource(R.string.player_orientation_auto_detail)
                        PlayerOrientation.VIDEO -> stringResource(R.string.player_orientation_video_detail)
                        else -> null
                    },
                    selected = mode == value)
            }
        }
    }
}
