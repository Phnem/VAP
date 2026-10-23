package com.phnem.vap.player.ui

import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.phnem.vap.R
import com.phnem.vap.player.parseTimecode
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelTextField
import com.phnem.vap.ui.components.VapSheet

/** Переход к таймкоду: шторка с полем ввода и главной кнопкой. */
@Composable
fun TimecodeDialog(durationMs: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val parsed = remember(input) { parseTimecode(input) }
    val valid = parsed != null && durationMs > 0 && parsed <= durationMs
    val focus = remember { FocusRequester() }
    // Альбомная ориентация с клавиатурой оставляет ~100dp: заголовок и подпись не помещаются,
    // поэтому остаётся одна строка «поле + Перейти» (как у прежнего диалога).
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val compact = androidx.compose.foundation.layout.WindowInsets.isImeVisible &&
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    VapSheet(onDismiss, grabber = !compact) { close ->
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun submit() {
            attempted = true
            if (valid) { onSeek(parsed!!); close() }
        }
        val message = when {
            attempted && parsed == null -> R.string.player_timecode_invalid
            attempted && !valid -> R.string.player_timecode_out_of_range
            else -> R.string.player_timecode_format
        }
        if (compact) {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PanelTextField(input, { input = it.take(32); attempted = false }, stringResource(R.string.player_timecode_label),
                        Modifier.weight(1f).testTag("player_timecode_input"), placeholder = "01:23:45", error = attempted && !valid,
                        imeAction = ImeAction.Go, onImeAction = ::submit, focusRequester = focus, showLabel = false)
                    PanelButton(stringResource(R.string.player_seek), ::submit, enabled = durationMs > 0, primary = true,
                        modifier = Modifier.testTag("player_timecode_submit"))
                    PanelButton(stringResource(R.string.close), close)
                }
                if (attempted && !valid) PanelError(stringResource(message)) else PanelHint(stringResource(message))
            }
        } else {
            PanelHeader(stringResource(R.string.player_goto_time), onClose = close)
            PanelBody {
                PanelTextField(input, { input = it.take(32); attempted = false }, stringResource(R.string.player_timecode_label),
                    placeholder = "01:23:45", error = attempted && !valid, imeAction = ImeAction.Go, onImeAction = ::submit,
                    focusRequester = focus, modifier = Modifier.testTag("player_timecode_input"))
                if (attempted && !valid) PanelError(stringResource(message)) else PanelHint(stringResource(message))
            }
            PanelFooter {
                PanelButton(stringResource(R.string.player_seek), ::submit, enabled = durationMs > 0, primary = true,
                    modifier = Modifier.testTag("player_timecode_submit"))
            }
        }
    }
}
