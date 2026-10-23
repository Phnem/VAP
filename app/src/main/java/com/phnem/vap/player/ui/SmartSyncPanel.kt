package com.phnem.vap.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.phnem.vap.R
import com.phnem.vap.ai.sync.SmartSyncRuntime
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.ai.whisper.WhisperModel
import com.phnem.vap.player.subtitles.SubtitleSettings
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelBusy
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Smart Sync: автоматический поиск сдвига и дрейфа субтитров (панель шторки субтитров). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ColumnScope.SmartSyncPanel(player: Player, uri: String, settings: SubtitleSettings, onPreview: (SubtitleSettings) -> Unit,
    onModels: () -> Unit, canPreview: Boolean) {
    val context = LocalContext.current
    val runtime = remember { SmartSyncRuntime.get(context) }
    val ai = remember { AiRuntime.get(context) }
    val state by ai.state.collectAsStateWithLifecycle()
    val result by runtime.result.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sourceId by remember(uri) { mutableStateOf(settings.sources.firstOrNull { it.id == settings.secondary }?.id
        ?: settings.sources.firstOrNull { it.id == settings.selection }?.id ?: settings.sources.firstOrNull()?.id) }
    val source = settings.sources.firstOrNull { it.id == sourceId }
    var model by remember { mutableStateOf(WhisperModel.TINY) }
    var language by remember { mutableStateOf("auto") }
    var drift by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending?.invoke(); pending = null }
    val staleMessage = stringResource(R.string.sync_stale)
    fun start() {
        try {
            error = null; saved = false
            runtime.analyze(player, source ?: error("Choose a text subtitle track"), model, language, drift)
        } catch (e: Exception) { error = e.message?.take(180) }
    }
    LaunchedEffect(settings.sources) { if (source == null) sourceId = settings.sources.firstOrNull()?.id }
    val matching = result?.takeIf { it.uri == uri && it.source.id == sourceId }
    val analysis = matching?.analysis
    val fit = analysis?.preferred(drift)
    val reliable = fit != null && fit.residualMs <= 800 && fit.anchors >= 2
    val own = state.title == R.string.sync_analyzing
    PanelBody {
        PanelHint(stringResource(R.string.sync_hint))
        PanelSection(stringResource(R.string.tr_choose_source))
        PanelPillRow {
            settings.sources.forEach { entry -> PanelPill(entry.title, entry.id == sourceId, { sourceId = entry.id }, enabled = !state.running && !busy) }
        }
        PanelSection(stringResource(R.string.ai_models))
        PanelPillRow {
            WhisperModel.entries.forEach { option -> PanelPill(option.name.lowercase(), model == option, { model = option }, enabled = !state.running && !busy) }
        }
        if (ai.models.ready(model) == null) PanelRow(stringResource(R.string.sync_models), onModels)
        PanelTextField(language, { language = it.lowercase(java.util.Locale.ROOT).take(8) }, stringResource(R.string.ai_language), enabled = !state.running && !busy)
        PanelHint(stringResource(R.string.ai_language_hint))
        PanelSwitchRow(stringResource(R.string.sync_drift), drift, { drift = it }, enabled = !state.running && !busy)
        PanelButton(stringResource(R.string.sync_analyze), {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pending = { start() }; notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else start()
        }, primary = true, modifier = Modifier.fillMaxWidth(),
            enabled = source != null && !state.running && !busy && ai.models.ready(model) != null && (language == "auto" || language.matches(Regex("[a-z]{2,3}"))))
        if (state.running && own) {
            PanelProgress(state.percent / 100f)
            PanelText("${stringResource(state.title)} · ${state.percent}%")
            PanelButton(stringResource(R.string.ai_cancel), { ai.cancel() })
        }
        if (matching != null && !state.running) {
            if (analysis == null) PanelText(stringResource(R.string.sync_no_match))
            else {
                PanelText(stringResource(R.string.sync_evidence, analysis.matchedWords, analysis.speechWords, analysis.anchors.size))
                if (fit != null) PanelText(stringResource(R.string.sync_result, fit.delayMs, fit.scale, fit.residualMs), bold = true)
                if (!reliable) PanelError(stringResource(R.string.sync_unreliable))
                PanelButton(stringResource(R.string.sync_preview), {
                    scope.launch {
                        busy = true
                        try {
                            if (!runtime.unchanged(matching)) error(staleMessage)
                            onPreview(settings.copy(selection = matching.source.id, secondary = settings.secondary?.takeUnless { it == matching.source.id }, delayMs = fit!!.delayMs, scale = fit.scale))
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message?.take(180) }
                        finally { busy = false }
                    }
                }, enabled = reliable && canPreview && !busy, modifier = Modifier.fillMaxWidth())
                PanelButton(stringResource(R.string.sync_save), {
                    scope.launch {
                        busy = true
                        try { runtime.save(matching, fit!!); saved = true }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message?.take(180) }
                        finally { busy = false }
                    }
                }, enabled = reliable && !busy, modifier = Modifier.fillMaxWidth())
                PanelHint(stringResource(R.string.sync_save_hint))
            }
        }
        if (saved) PanelText(stringResource(R.string.sync_saved))
        if (busy) PanelBusy()
        (error ?: state.error?.takeIf { own })?.let { PanelError(it) }
        PanelHint(stringResource(R.string.sync_limits))
    }
}

/** Плашка предпросмотра сдвига поверх кадра: тёмная капсула в языке шторок Collection. */
@Composable
fun SyncPreviewBar(settings: SubtitleSettings, onApply: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.statusBarsPadding().displayCutoutPadding().padding(8.dp).widthIn(max = 800.dp)
            .clip(RoundedCornerShape(26.dp)).background(PanelColors.Sheet.copy(alpha = 0.94f))
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelText(stringResource(R.string.sync_preview_active, settings.delayMs, settings.scale), Modifier.weight(1f), size = 13)
        PanelButton(stringResource(R.string.sync_apply_preview), onApply, primary = true)
        PanelButton(stringResource(R.string.sync_cancel_preview), onCancel)
    }
}
