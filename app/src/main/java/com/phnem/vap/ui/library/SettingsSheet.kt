package com.phnem.vap.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.R
import com.phnem.vap.ai.translation.LocalTranslationModel
import com.phnem.vap.ai.translation.TranslationRuntime
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.ai.whisper.WhisperModel
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.SpringPillRow
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.VapSheet
import com.phnem.vap.player.ui.findActivity
import com.phnem.vap.ui.onboarding.AppLocale
import com.phnem.vap.ui.onboarding.AppPrefs
import com.phnem.vap.ui.onboarding.LanguageChoice
import com.phnem.vap.ui.onboarding.ThemeChoice
import com.phnem.vap.ui.theme.isAppInDarkTheme
import com.phnem.vap.ui.update.UpdateSettingsRow

/**
 * Настройки приложения: тема и скачивание локальных ИИ-моделей (распознавание речи Whisper и перевод
 * субтитров). Модели общие с панелями плеера: `AiRuntime` один на процесс, поэтому загрузку можно
 * начать здесь и увидеть её ход в плеере.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SettingsSheet(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, onOpenUpdate: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ai = remember { AiRuntime.get(context) }
    val translation = remember { TranslationRuntime.get(context) }
    val state by ai.state.collectAsStateWithLifecycle()
    val transfer by ai.models.progress.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke(); pending = null
    }
    fun action(block: () -> Unit) { try { error = null; block(); revision++ } catch (e: Exception) { error = e.message?.take(180) } }
    // Загрузка идёт в сервисе переднего плана, поэтому на Android 13+ сначала нужно разрешение на уведомления.
    fun start(block: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pending = { action(block) }; notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else action(block)
    }

    // Подсветка пункта меняется сразу по нажатию, не дожидаясь, пока приложение перерисуется в новой теме.
    var pickedTheme by remember { mutableStateOf(theme) }
    val appPrefs = remember { AppPrefs(context) }
    var pickedLanguage by remember { mutableStateOf(appPrefs.language) }
    val activity = remember(context) { context.findActivity() }

    VapSheet(onDismiss, isDark = isAppInDarkTheme(), immersive = false) { close ->
        PanelHeader(stringResource(R.string.settings_title), onClose = close)
        PanelBody {
            UpdateSettingsRow(onClick = onOpenUpdate)

            PanelSection(stringResource(R.string.settings_language))
            SpringPillRow(
                labels = LanguageChoice.entries.map { stringResource(languageLabel(it)) },
                selected = LanguageChoice.entries.indexOf(pickedLanguage),
                onSelect = { index ->
                    val choice = LanguageChoice.entries[index]
                    if (choice != pickedLanguage && activity != null) {
                        pickedLanguage = choice
                        // Окно пересоздаётся на новом языке, шторка закрывается вместе с ним.
                        AppLocale.change(activity, choice)
                    }
                },
                inactiveFill = PanelColors.Chip,
                activeFill = PanelColors.Inverse,
                activeText = PanelColors.OnInverse,
                inactiveText = PanelColors.Muted,
                fontSize = 14.sp,
                verticalPadding = 10.dp,
            )

            PanelSection(stringResource(R.string.settings_theme))
            SpringPillRow(
                labels = ThemeChoice.entries.map { stringResource(themeLabel(it)) },
                selected = ThemeChoice.entries.indexOf(pickedTheme),
                onSelect = { index ->
                    val choice = ThemeChoice.entries[index]
                    if (choice != pickedTheme) {
                        pickedTheme = choice
                        onTheme(choice)
                    }
                },
                inactiveFill = PanelColors.Chip,
                activeFill = PanelColors.Inverse,
                activeText = PanelColors.OnInverse,
                inactiveText = PanelColors.Muted,
                fontSize = 14.sp,
                verticalPadding = 10.dp,
            )

            PanelSection(stringResource(R.string.settings_ai))
            PanelHint(stringResource(R.string.settings_ai_hint))
            PanelHint(stringResource(R.string.settings_ai_speech))
            WhisperModel.entries.forEach { entry ->
                val ready = remember(entry, state.running, revision) { ai.models.ready(entry) != null }
                ModelRow("${entry.name.lowercase()} · ${entry.bytes / 1_000_000} MB", ready, !state.running,
                    onDownload = { start { ai.download(entry) } }, onDelete = { action { ai.delete(entry) } })
            }
            PanelHint(stringResource(R.string.settings_ai_translation))
            LocalTranslationModel.entries.forEach { entry ->
                val ready = remember(entry, state.running, revision) { translation.models.ready(entry) }
                ModelRow("${entry.from} → ${entry.to} · ${translation.models.bytes(entry) / 1_000_000} MB", ready, !state.running,
                    onDownload = { start { translation.download(entry) } }, onDelete = { action { translation.delete(entry) } })
            }

            if (state.running && state.downloading) {
                // Ход загрузки Whisper идёт по принятым байтам, у моделей перевода — по проценту задачи.
                val percent = if (state.operation == "whisper" && transfer.file != null) (transfer.received * 100 / transfer.file!!.bytes).toInt() else state.percent
                PanelProgress(percent / 100f)
                PanelText("${stringResource(R.string.ai_downloading)} · $percent%" + if (transfer.verifying) " · SHA-256" else "")
                PanelButton(stringResource(R.string.ai_cancel), { ai.cancel() })
            }
            (error ?: state.error?.takeIf { state.downloading })?.let { PanelError(it) }
        }
    }
}

@Composable
private fun ModelRow(title: String, ready: Boolean, enabled: Boolean, onDownload: () -> Unit, onDelete: () -> Unit) {
    PanelCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelText(title, bold = true, modifier = Modifier.weight(1f))
            if (ready) PanelButton(stringResource(R.string.ai_delete), onDelete, enabled = enabled)
            else PanelButton(stringResource(R.string.ai_download), onDownload, enabled = enabled)
        }
    }
}

private fun languageLabel(choice: LanguageChoice): Int = when (choice) {
    LanguageChoice.SYSTEM -> R.string.theme_system
    LanguageChoice.RU -> R.string.language_ru
    LanguageChoice.EN -> R.string.language_en
}

private fun themeLabel(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.SYSTEM -> R.string.theme_system
    ThemeChoice.DARK -> R.string.theme_dark
    ThemeChoice.LIGHT -> R.string.theme_light
}
