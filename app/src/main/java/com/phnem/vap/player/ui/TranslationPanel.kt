package com.phnem.vap.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.R
import com.phnem.vap.ai.translation.LocalTranslationModel
import com.phnem.vap.ai.translation.TranslationCloudConfig
import com.phnem.vap.ai.translation.TranslationRuntime
import com.phnem.vap.ai.translation.TranslationText
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.player.subtitles.SubtitleSettings
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelProgress
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Перевод субтитров: локальные модели и облачный режим (панель шторки субтитров). */
@Composable
fun ColumnScope.TranslationPanel(uri: String, settings: SubtitleSettings) {
    val context = LocalContext.current
    val runtime = remember { TranslationRuntime.get(context) }
    val ai = remember { AiRuntime.get(context) }
    val state by ai.state.collectAsStateWithLifecycle()
    val result by runtime.result.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val options = remember { context.getSharedPreferences("translation_options", 0) }
    var selected by remember(uri) { mutableStateOf(settings.sources.firstOrNull { it.id == settings.secondary }?.id
        ?: settings.sources.firstOrNull { it.id == settings.selection && !it.originStamp.startsWith("translation:") }?.id
        ?: settings.sources.firstOrNull { !it.originStamp.startsWith("translation:") }?.id ?: settings.sources.firstOrNull()?.id) }
    val source = settings.sources.firstOrNull { it.id == selected }
    var from by remember { mutableStateOf(options.getString("from", "en").orEmpty()) }
    var to by remember { mutableStateOf(options.getString("to", "ru").orEmpty()) }
    var glossary by remember { mutableStateOf(options.getString("glossary", "").orEmpty()) }
    var parallel by remember { mutableStateOf(options.getBoolean("parallel", true)) }
    var punctuation by remember { mutableStateOf(options.getBoolean("punctuation", false)) }
    var repair by remember { mutableStateOf(false) }
    var cloud by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("https://openrouter.ai/api/v1/chat/completions") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val credentialFailure by rememberUpdatedState(stringResource(R.string.tr_credentials_failed))
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending?.invoke(); pending = null }
    fun action(block: () -> Unit) { try { error = null; block(); revision++ } catch (e: Exception) { error = e.message?.take(180) } }
    fun start(block: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pending = { action(block) }; notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else action(block)
    }
    LaunchedEffect(settings.sources) { if (source == null) selected = settings.sources.firstOrNull()?.id }
    LaunchedEffect(selected) {
        source?.language?.lowercase(java.util.Locale.ROOT)?.let { raw ->
            val code = when (raw) { "eng" -> "en"; "rus" -> "ru"; else -> raw }
            if (code.matches(Regex("[a-z]{2,3}"))) { from = code; if (to == from) to = if (from == "en") "ru" else "en" }
        }
    }
    LaunchedEffect(runtime) {
        try { endpoint = runtime.vault.get("endpoint") ?: endpoint; key = runtime.vault.get("key").orEmpty(); model = runtime.vault.get("model").orEmpty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = credentialFailure }
        finally { loading = false }
    }
    val local = LocalTranslationModel.entries.firstOrNull { it.from == from && it.to == to }
    // Состояние AiRuntime общее с Whisper и Smart Sync: ошибки и остановка чужой задачи здесь не показываются.
    val own = state.operation == "translation" && state.title != R.string.sync_analyzing
    PanelBody {
        PanelHint(stringResource(R.string.tr_hint))
        PanelSection(stringResource(R.string.tr_choose_source))
        PanelPillRow {
            settings.sources.forEach { entry -> PanelPill(entry.title, entry.id == selected, { selected = entry.id }, enabled = !state.running) }
        }
        PanelPillRow {
            PanelPill(stringResource(R.string.tr_local), !cloud, { cloud = false; repair = false }, enabled = !state.running)
            PanelPill(stringResource(R.string.tr_cloud), cloud, { cloud = true }, enabled = !state.running)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelTextField(from, { from = it.lowercase(java.util.Locale.ROOT).take(3) }, stringResource(R.string.tr_from), Modifier.weight(1f), enabled = !state.running)
            PanelTextField(to, { to = it.lowercase(java.util.Locale.ROOT).take(3) }, stringResource(R.string.tr_to), Modifier.weight(1f), enabled = !state.running)
        }
        if (!cloud) {
            PanelHint(stringResource(R.string.tr_local_hint))
            LocalTranslationModel.entries.forEach { entry ->
                val ready = remember(entry, state.running, revision) { runtime.models.ready(entry) }
                PanelCard(active = entry == local) {
                    PanelText("${entry.from} → ${entry.to} · ${runtime.models.bytes(entry) / 1_000_000} MB · ${runtime.models.occupied(entry) / 1_000_000} MB", bold = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PanelButton(stringResource(R.string.tr_select_pair), { from = entry.from; to = entry.to }, Modifier.weight(1f), enabled = !state.running)
                        if (ready) PanelButton(stringResource(R.string.ai_delete), { action { runtime.delete(entry) } }, Modifier.weight(1f), enabled = !state.running)
                        else PanelButton(stringResource(R.string.ai_download), { start { runtime.download(entry) } }, Modifier.weight(1f), enabled = !state.running)
                    }
                }
            }
        } else {
            PanelHint(stringResource(R.string.tr_cloud_hint))
            PanelTextField(endpoint, { endpoint = it.take(2000) }, stringResource(R.string.tr_endpoint), enabled = !state.running)
            PanelTextField(model, { model = it.take(200) }, stringResource(R.string.tr_model), enabled = !state.running)
            PanelTextField(key, { key = it.take(4096) }, stringResource(R.string.tr_key), enabled = !state.running, password = true)
            PanelRow(stringResource(R.string.tr_forget_key), {
                scope.launch { try { runtime.vault.save(mapOf("endpoint" to null, "model" to null, "key" to null)); key = ""; model = "" } catch (e: Exception) { error = e.message } }
            }, enabled = !state.running && !loading, danger = true)
            PanelSwitchRow(stringResource(R.string.tr_repair), repair, { repair = it }, enabled = !state.running)
        }
        PanelTextField(glossary, { glossary = it.take(16_000) }, stringResource(R.string.tr_glossary), enabled = !state.running,
            singleLine = false, minLines = 2, modifier = Modifier.fillMaxWidth())
        PanelHint(stringResource(R.string.tr_glossary_hint))
        PanelSwitchRow(stringResource(R.string.tr_parallel), parallel, { parallel = it }, enabled = !state.running)
        PanelSwitchRow(stringResource(R.string.tr_punctuation), punctuation, { punctuation = it }, enabled = !state.running)
        PanelButton(stringResource(R.string.sub_save), {
            scope.launch {
                try {
                    TranslationText.glossary(glossary)
                    if (cloud) { val config = TranslationCloudConfig(endpoint.trim(), key.trim(), model.trim()).validated(); runtime.vault.save(mapOf("endpoint" to config.endpoint, "key" to config.key, "model" to config.model)) }
                    options.edit().putString("from", from).putString("to", to).putString("glossary", glossary).putBoolean("parallel", parallel).putBoolean("punctuation", punctuation).apply()
                    error = null
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message?.take(180) }
            }
        }, enabled = !state.running && !loading, modifier = Modifier.fillMaxWidth())
        PanelButton(stringResource(if (cloud) R.string.tr_send_translate else R.string.tr_translate), {
            start {
                val chosen = source ?: error("Choose a text subtitle track")
                runtime.translate(uri, chosen, from, to, glossary, punctuation, repair, if (cloud) TranslationCloudConfig(endpoint.trim(), key.trim(), model.trim()) else null, parallel)
            }
        }, primary = true, modifier = Modifier.fillMaxWidth(),
            enabled = !state.running && !loading && source != null && (cloud && key.isNotBlank() && model.isNotBlank() || !cloud && local != null && runtime.models.ready(local) && runtime.engine.available))
        if (state.running && own) {
            PanelProgress(state.percent / 100f)
            PanelText("${stringResource(if (state.downloading) R.string.ai_downloading else state.title)} · ${state.percent}%")
            PanelButton(stringResource(R.string.ai_cancel), { ai.cancel() })
        }
        if (result.uri == uri && result.source != null && !state.running) PanelText(stringResource(R.string.tr_attached))
        if (own && state.cancelled) PanelHint(stringResource(R.string.ai_stopped))
        (error ?: state.error?.takeIf { own })?.let { PanelError(it) }
        PanelRow(stringResource(R.string.tr_clear_cache), { scope.launch { withContext(Dispatchers.IO) { runtime.cache.clear() }; revision++ } }, enabled = !state.running)
        PanelHint(stringResource(R.string.ai_export_hint))
    }
}
