package com.phnem.vap.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.R
import com.phnem.vap.library.ThumbnailPipeline
import com.phnem.vap.ui.components.LocalPanelDark
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.isAppInDarkTheme

/**
 * Экран первого запуска: язык и тема. Пока владелец выбирает, в фоне уже идёт подготовка превью всей
 * библиотеки ([ThumbnailPipeline]); внизу — её ход. Дизайн — панели Vetro Collection (`ui/components`):
 * строки-карточки с точкой у выбранного и белая капсульная кнопка.
 */
@Composable
fun FirstRunScreen(
    language: LanguageChoice,
    theme: ThemeChoice,
    onLanguage: (LanguageChoice) -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    val progress by ThumbnailPipeline.progress.collectAsState()
    val shownLanguage = if (language == LanguageChoice.SYSTEM) LanguageChoice.suggested(java.util.Locale.getDefault().language) else language
    CompositionLocalProvider(LocalPanelDark provides dark) {
        Box(
            modifier
                .fillMaxSize()
                // Фон панелей: на нём рассчитаны полупрозрачные карточки `PanelColors.Card`, на чисто чёрном они не видны.
                .background(PanelColors.Sheet)
                // Экран лежит поверх библиотеки: нажатия не должны доходить до неё.
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        stringResource(R.string.first_run_title),
                        color = PanelColors.Ink,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                    )
                    PanelText(stringResource(R.string.first_run_subtitle), color = PanelColors.Muted)
                    Spacer(Modifier.height(16.dp))
                    PanelSection(stringResource(R.string.first_run_language))
                    PanelRow(stringResource(R.string.language_ru), { onLanguage(LanguageChoice.RU) }, selected = shownLanguage == LanguageChoice.RU)
                    PanelRow(stringResource(R.string.language_en), { onLanguage(LanguageChoice.EN) }, selected = shownLanguage == LanguageChoice.EN)
                    Spacer(Modifier.height(8.dp))
                    PanelSection(stringResource(R.string.first_run_theme))
                    PanelRow(stringResource(R.string.theme_dark), { onTheme(ThemeChoice.DARK) }, selected = theme == ThemeChoice.DARK)
                    PanelRow(stringResource(R.string.theme_light), { onTheme(ThemeChoice.LIGHT) }, selected = theme == ThemeChoice.LIGHT)
                    PanelRow(stringResource(R.string.theme_system), { onTheme(ThemeChoice.SYSTEM) }, selected = theme == ThemeChoice.SYSTEM)
                }
                Column(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (progress.total > 0) {
                        PanelProgress(progress.done.toFloat() / progress.total)
                        PanelHint(
                            if (progress.running) stringResource(R.string.first_run_progress, progress.done, progress.total)
                            else stringResource(R.string.first_run_ready),
                        )
                    }
                    PanelButton(stringResource(R.string.first_run_continue), onContinue, Modifier.fillMaxWidth(), primary = true)
                }
            }
        }
    }
}
