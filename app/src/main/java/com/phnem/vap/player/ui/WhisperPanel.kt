package com.phnem.vap.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.phnem.vap.R
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.ai.whisper.WhisperModel
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whisper: модели, запуск и состояние распознавания (панель шторки субтитров). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ColumnScope.WhisperPanel(player: Player, uri: String) {
    val context = LocalContext.current
    val runtime = remember { AiRuntime.get(context) }
    val state by runtime.state.collectAsStateWithLifecycle()
    val transfer by runtime.models.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(WhisperModel.TINY) }
    var language by remember { mutableStateOf("auto") }
    var revision by remember { mutableIntStateOf(0) }
    var cacheBytes by remember { mutableLongStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke(); pending = null
    }
    LaunchedEffect(state.running, revision) { cacheBytes = withContext(Dispatchers.IO) { runtime.cache.bytes() } }
    fun action(block: () -> Unit) { try { error = null; block(); revision++ } catch (e: Exception) { error = e.message?.take(180) } }
    fun start(block: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pending = { action(block) }; notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else action(block)
    }
    // Состояние AiRuntime общее для Whisper, перевода и Smart Sync: чужие ошибки и язык здесь не показываем.
    val own = state.operation == "whisper"
    PanelBody {
        PanelHint(stringResource(R.string.ai_offline_hint))
        PanelSection(stringResource(R.string.ai_models))
        WhisperModel.entries.forEach { entry ->
            val ready = remember(entry, state.running, revision) { runtime.models.ready(entry) != null }
            PanelCard(active = entry == model) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    PanelPill("${entry.name.lowercase()} · ${entry.bytes / 1_000_000} MB", entry == model, { model = entry }, enabled = !state.running)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    if (ready) PanelButton(stringResource(R.string.ai_delete), { action { runtime.delete(entry) } }, enabled = !state.running)
                    else PanelButton(stringResource(R.string.ai_download), { start { runtime.download(entry) } }, enabled = !state.running)
                }
                if (entry == model) PanelHint(stringResource(R.string.ai_ram, entry.ramMb, runtime.models.occupied(entry) / 1_000_000))
            }
        }
        PanelTextField(language, { language = it.lowercase(java.util.Locale.ROOT).take(8) }, stringResource(R.string.ai_language), enabled = !state.running)
        PanelHint(stringResource(R.string.ai_language_hint))
        PanelButton(stringResource(R.string.ai_start), { start { runtime.transcribe(player, model, language) } }, primary = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running && runtime.engine.available && runtime.models.ready(model) != null && (language == "auto" || language.matches(Regex("[a-z]{2,3}"))))
        if (state.running) {
            val percent = if (state.downloading && transfer.file != null) (transfer.received * 100 / transfer.file!!.bytes).toInt() else state.percent
            PanelProgress(percent / 100f)
            PanelText("${stringResource(if (state.downloading) R.string.ai_downloading else state.title)} · $percent%" + if (transfer.verifying) " · SHA-256" else "")
            PanelButton(stringResource(R.string.ai_cancel), { runtime.cancel() })
        }
        if (own && state.uri == uri && !state.running && state.percent == 100) PanelText(stringResource(if (state.source != null) R.string.ai_attached else R.string.ai_no_speech))
        if (own && state.language.isNotBlank()) PanelHint(stringResource(R.string.ai_detected, state.language))
        if (own && state.slow) PanelHint(stringResource(R.string.ai_slow))
        if (own && state.cancelled) PanelHint(stringResource(R.string.ai_stopped))
        (error ?: state.error?.takeIf { own || state.downloading })?.let { PanelError(it) }
        PanelSection(stringResource(R.string.ai_cache, cacheBytes / 1_000_000))
        PanelRow(stringResource(R.string.ai_clear_cache), { scope.launch { withContext(Dispatchers.IO) { runtime.cache.clear() }; revision++ } }, enabled = !state.running)
        PanelHint(stringResource(R.string.ai_export_hint))
    }
}
