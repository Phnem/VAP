package com.phnem.vap.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.R
import com.phnem.vap.ai.translation.LocalTranslationModel
import com.phnem.vap.ai.translation.TranslationRuntime
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.ai.whisper.WhisperModel
import com.phnem.vap.ui.components.GrabberReservedTop
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.SpringPillRow
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelFloatingClose
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.VapSheet
import com.phnem.vap.ui.components.extendUnderGrabber
import com.phnem.vap.player.ui.findActivity
import com.phnem.vap.ui.onboarding.AppLocale
import com.phnem.vap.ui.onboarding.AppPrefs
import com.phnem.vap.ui.onboarding.LanguageChoice
import com.phnem.vap.ui.onboarding.ThemeChoice
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.isAppInDarkTheme
import com.phnem.vap.ui.update.UpdateSettingsRow

/**
 * Настройки приложения: язык, тема, цветные папки и скачивание локальных ИИ-моделей (распознавание речи
 * Whisper и перевод субтитров; модели свёрнуты в одну карточку). Модели общие с панелями плеера:
 * `AiRuntime` один на процесс, поэтому загрузку можно начать здесь и увидеть её ход в плеере.
 *
 * Шапки-подложки нет: заголовок — первая строка прокручиваемого содержимого, а «Закрыть» плавает
 * поверх него и остаётся на месте при прокрутке.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SettingsSheet(
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    coloredFolders: Boolean,
    onColoredFolders: (Boolean) -> Unit,
    onOpenUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
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

    val downloaded = remember(state.running, revision) {
        WhisperModel.entries.count { ai.models.ready(it) != null } + LocalTranslationModel.entries.count { translation.models.ready(it) }
    }
    val totalModels = WhisperModel.entries.size + LocalTranslationModel.entries.size
    var modelsOpen by rememberSaveable { mutableStateOf(false) }

    VapSheet(onDismiss, isDark = isAppInDarkTheme(), immersive = false) { close ->
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                Modifier
                    .extendUnderGrabber()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(GrabberReservedTop))
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PanelText(stringResource(R.string.settings_title), size = 22, bold = true, modifier = Modifier.weight(1f))
                    // Место под плавающую кнопку «Закрыть».
                    Spacer(Modifier.width(96.dp))
                }
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

                PanelSwitchRow(
                    title = stringResource(R.string.settings_colored_folders),
                    checked = coloredFolders,
                    onChange = onColoredFolders,
                    hint = stringResource(R.string.settings_colored_folders_hint),
                    modifier = Modifier.padding(top = 6.dp),
                )

                ModelsCard(
                    title = stringResource(R.string.settings_ai),
                    summary = stringResource(R.string.settings_ai_summary, downloaded, totalModels),
                    open = modelsOpen,
                    onToggle = { modelsOpen = !modelsOpen },
                ) {
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
            PanelFloatingClose(close, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 20.dp))
        }
    }
}

/** Все ИИ-модели в одной карточке: свёрнута до названия и числа скачанных, по нажатию раскрывается списком. */
@Composable
private fun ModelsCard(title: String, summary: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "modelsChevron")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelColors.Card)
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics { role = Role.Button; stateDescription = summary }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(summary, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp)
            }
            Icon(
                painterResource(R.drawable.ic_vap_chevron_down), contentDescription = null, tint = PanelColors.Muted,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn },
            )
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ModelRow(title: String, ready: Boolean, enabled: Boolean, onDownload: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelColors.Chip).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelText(title, bold = true, modifier = Modifier.weight(1f))
        if (ready) PanelButton(stringResource(R.string.ai_delete), onDelete, enabled = enabled)
        else PanelButton(stringResource(R.string.ai_download), onDownload, enabled = enabled)
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
