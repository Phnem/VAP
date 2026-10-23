package com.phnem.vap.player.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.phnem.vap.R
import com.phnem.vap.player.subtitles.AssNative
import com.phnem.vap.player.subtitles.EmbeddedSubtitleExtractor
import com.phnem.vap.player.subtitles.MatroskaFonts
import com.phnem.vap.player.subtitles.SubtitleDocument
import com.phnem.vap.player.subtitles.SubtitleLine
import com.phnem.vap.player.subtitles.SubtitleOrigin
import com.phnem.vap.player.subtitles.SubtitleSettings
import com.phnem.vap.player.subtitles.SubtitleStorage
import com.phnem.vap.player.subtitles.subtitleChoices
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelBusy
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.LocalPanelFillButtons
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSliderCard
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelTabs
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import com.phnem.vap.ui.components.VapSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Субтитры: дорожки, стиль, тайминг, текст, онлайн, Whisper, перевод и Smart Sync. Шторка Collection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SubtitleToolsDialog(player: Player, uri: String, settings: SubtitleSettings, onSettings: (SubtitleSettings) -> Unit,
    onEdited: () -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit,
    onPreview: (SubtitleSettings) -> Unit = {}, canPreview: Boolean = true) {
    val context = LocalContext.current
    val storage = remember { SubtitleStorage(context) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(settings)
    var tab by remember { mutableIntStateOf(0) }
    var choices by remember { mutableStateOf(player.currentTracks.subtitleChoices()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var textSource by remember { mutableStateOf(settings.sources.firstOrNull { it.id == settings.selection }?.id ?: settings.sources.firstOrNull()?.id) }
    var lines by remember { mutableStateOf(emptyList<SubtitleLine>()) }
    var revision by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val failure = stringResource(R.string.sub_failed)
    val noSiblings = stringResource(R.string.sub_no_siblings)
    fun job(action: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "$failure: ${e.message.orEmpty().take(180)}" }
            finally { busy = false }
        }
    }
    val document = remember { object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
            val intent = super.createIntent(context, input)
            for (provider in listOf("com.google.android.documentsui", "com.android.documentsui")) {
                val candidate = Intent(intent).setPackage(provider)
                if (candidate.resolveActivity(context.packageManager) != null) return candidate
            }
            return intent
        }
    } }
    val picker = rememberLauncherForActivityResult(document) { selected ->
        if (selected != null) job {
            val source = storage.import(selected)
            val current = latest
            if (current.sources.size >= 32) error("Too many subtitle files")
            onSettings(current.copy(sources = current.sources + source, selection = source.id, assNative = if (source.format == "ass") true else current.assNative))
            textSource = source.id
        }
    }
    val outputDocument = remember { object : ActivityResultContracts.CreateDocument("application/x-subrip") {
        override fun createIntent(context: android.content.Context, input: String): Intent =
            super.createIntent(context, input).setType(if (input.endsWith(".vtt", true)) "text/vtt" else "application/x-subrip")
    } }
    val exporter = rememberLauncherForActivityResult(outputDocument) { target ->
        val id = exportId; exportId = null
        if (id != null) job {
            withContext(Dispatchers.IO) {
                val file = java.io.File(context.cacheDir, "subtitle-export-$id.txt")
                try { if (target != null) context.contentResolver.openOutputStream(target, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Cannot write subtitles") }
                finally { file.delete() }
            }
        }
    }
    val treeDocument = remember { object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: android.content.Context, input: android.net.Uri?): Intent {
            val intent = super.createIntent(context, input)
            for (provider in listOf("com.google.android.documentsui", "com.android.documentsui")) {
                val candidate = Intent(intent).setPackage(provider)
                if (candidate.resolveActivity(context.packageManager) != null) return candidate
            }
            return intent
        }
    } }
    val siblingPicker = rememberLauncherForActivityResult(treeDocument) { tree ->
        if (tree != null) job {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val videoName = player.mediaMetadata.title?.toString().orEmpty().substringAfterLast('/')
            val found = storage.discoverInTree(tree, videoName)
            val sources = found.map { storage.import(it) }
            val current = latest
            val all = (current.sources + sources).distinctBy { it.title }
            onSettings(current.copy(sources = all, selection = if (current.selection == "auto") all.firstOrNull()?.id ?: "auto" else current.selection))
            if (sources.isEmpty()) error = noSiblings
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onTracksChanged(tracks: Tracks) { choices = tracks.subtitleChoices() } }
        player.addListener(listener); onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(textSource, revision) {
        lines = emptyList()
        textSource?.let { id ->
            try { lines = storage.read(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "$failure: ${e.message}" }
        }
    }
    LaunchedEffect(settings.sources) { if (textSource == null) textSource = settings.sources.firstOrNull()?.id }

    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.sub_tools), onClose = close)
        PanelTabs(listOf(R.string.sub_tracks, R.string.sub_style, R.string.sub_timing, R.string.sub_text, R.string.os_online,
            R.string.ai_whisper, R.string.tr_title, R.string.sync_title).map { stringResource(it) }, tab, { tab = it })
        if (busy) PanelBusy(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp))
        error?.let { PanelError(it, Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)) }
        key(tab) {
            when (tab) {
                7 -> SmartSyncPanel(player, uri, settings, onPreview, { tab = 5 }, canPreview)
                6 -> TranslationPanel(uri, settings)
                5 -> WhisperPanel(player, uri)
                4 -> OpenSubtitlesPanel(uri, player.mediaMetadata.title?.toString().orEmpty(), settings, onAttach = { source ->
                    val current = latest
                    onSettings(current.copy(sources = (current.sources + source).distinctBy { it.id }, selection = source.id))
                })
                3 -> {
                    val edit = editing?.let { index -> lines.getOrNull(index)?.let { index to it } }
                    if (edit != null) {
                        val (index, original) = edit
                        var text by remember(index) { mutableStateOf(original.text) }
                        var start by remember(index) { mutableStateOf(SubtitleDocument.timestamp(original.startMs)) }
                        var end by remember(index) { mutableStateOf(SubtitleDocument.timestamp(original.endMs)) }
                        val startMs = SubtitleDocument.time(start); val endMs = SubtitleDocument.time(end)
                        PanelBody {
                            PanelSection(stringResource(R.string.sub_edit))
                            PanelTextField(text, { text = it.take(10_000) }, stringResource(R.string.sub_text), singleLine = false, minLines = 2)
                            PanelTextField(start, { start = it }, stringResource(R.string.sub_start), error = startMs == null)
                            PanelTextField(end, { end = it }, stringResource(R.string.sub_end), error = endMs == null || (startMs != null && endMs <= startMs))
                        }
                        PanelFooter {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompositionLocalProvider(LocalPanelFillButtons provides false) {
                                    PanelButton(stringResource(R.string.close), { editing = null }, Modifier.weight(1f))
                                    PanelButton(stringResource(R.string.sub_save), {
                                        val id = textSource ?: return@PanelButton
                                        val edited = lines.toMutableList().apply { set(index, SubtitleLine(startMs!!, endMs!!, text)) }.sortedBy { it.startMs }
                                        job {
                                            storage.write(id, edited)
                                            onSettings(latest.copy(sources = latest.sources.map { if (it.id == id) it.copy(format = "text") else it }))
                                            revision++; onEdited(); editing = null
                                        }
                                    }, Modifier.weight(1f), enabled = startMs != null && endMs != null && endMs > startMs && text.isNotBlank() && !busy, primary = true)
                                }
                            }
                        }
                    } else {
                        // Весь список — одна прокрутка: в альбомной ориентации элементы управления не должны съедать шторку.
                        LazyColumn(Modifier.weight(1f, false).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                PanelPillRow {
                                    settings.sources.forEach { entry -> PanelPill(entry.title, entry.id == textSource, { textSource = entry.id }) }
                                    if (settings.sources.isEmpty()) PanelHint(stringResource(R.string.sub_no_text))
                                }
                            }
                            if (textSource != null) {
                                item { PanelTextField(search, { search = it }, stringResource(R.string.sub_search)) }
                                item {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(false, true).forEach { vtt ->
                                            PanelButton(if (vtt) "VTT ↓" else "SRT ↓", {
                                                val text = SubtitleDocument.export(SubtitleDocument.adjusted(lines, settings.delayMs, settings.scale), vtt, entities = vtt)
                                                job {
                                                    val id = java.util.UUID.randomUUID().toString()
                                                    withContext(Dispatchers.IO) { java.io.File(context.cacheDir, "subtitle-export-$id.txt").writeText(text) }
                                                    exportId = id
                                                    exporter.launch(if (vtt) "subtitles.vtt" else "subtitles.srt")
                                                }
                                            }, Modifier.weight(1f), enabled = lines.isNotEmpty() && !busy)
                                        }
                                    }
                                }
                            } else item { PanelHint(stringResource(R.string.sub_text_external)) }
                            itemsIndexed(lines.withIndex().filter { search.isBlank() || it.value.text.contains(search, true) }, key = { _, item -> item.index }) { _, item ->
                                val cue = item.value
                                PanelCard {
                                    PanelRow("${SubtitleDocument.timestamp(cue.startMs)}  ${cue.text}",
                                        { player.seekTo((cue.startMs * settings.scale + settings.delayMs).toLong().coerceAtLeast(0)) })
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PanelButton(stringResource(R.string.sub_edit), { editing = item.index }, Modifier.weight(1f))
                                        PanelButton(stringResource(R.string.sub_copy), {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Subtitles", cue.text))
                                        }, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                else -> PanelBody {
                    when (tab) {
                        0 -> {
                            PanelRow(stringResource(R.string.sub_auto), { onSettings(settings.copy(selection = "auto")) }, selected = settings.selection == "auto")
                            PanelRow(stringResource(R.string.sub_off), { onSettings(settings.copy(selection = "off")) }, selected = settings.selection == "off")
                            choices.forEach { choice ->
                                PanelRow(choice.label, { onSettings(settings.copy(selection = choice.key)) }, enabled = choice.supported,
                                    selected = settings.selection == choice.key)
                            }
                            settings.sources.forEach { source ->
                                PanelRow(source.title, { onSettings(settings.copy(selection = source.id)) }, selected = settings.selection == source.id)
                            }
                            settings.sources.firstOrNull { it.id == settings.selection }?.let { source ->
                                PanelRow(stringResource(R.string.sub_remove_copy), { onRemove(source.id) }, danger = true)
                            }
                            PanelRow(stringResource(R.string.sub_import), { picker.launch(arrayOf("*/*")) }, enabled = !busy)
                            PanelRow(stringResource(R.string.sub_extract), {
                                job {
                                    val extracted = EmbeddedSubtitleExtractor.extract(context, android.net.Uri.parse(uri))
                                    val fonts = try { MatroskaFonts.read(context, android.net.Uri.parse(uri)) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                                    val stamp = SubtitleOrigin.stamp(context, uri)
                                    val sources = extracted.map { storage.create(it.title, it.language, it.lines, it.assDocument, fonts, it.trackId, stamp) }
                                    val current = latest
                                    onSettings(current.copy(sources = (current.sources + sources).take(32)))
                                    textSource = sources.firstOrNull()?.id
                                }
                            }, enabled = !busy)
                            PanelRow(stringResource(R.string.sub_find_siblings), { siblingPicker.launch(null) }, enabled = !busy)
                            PanelHint(stringResource(R.string.sub_saf_hint))
                            PanelSection(stringResource(R.string.sub_secondary))
                            PanelRow(stringResource(R.string.sub_off), { onSettings(settings.copy(secondary = null)) }, selected = settings.secondary == null)
                            settings.sources.filter { it.id != settings.selection }.forEach { source ->
                                PanelRow(source.title, { onSettings(settings.copy(secondary = source.id)) }, selected = settings.secondary == source.id)
                            }
                            var language by remember(settings.language) { mutableStateOf(settings.language) }
                            PanelTextField(language, { language = it.take(35) }, stringResource(R.string.sub_language))
                            PanelButton(stringResource(R.string.sub_apply), { onSettings(settings.copy(language = language.trim())) })
                        }
                        1 -> {
                            val nativeStyle = settings.sources.any { it.id == settings.selection && it.format == "ass" } && AssNative.available
                            if (nativeStyle) PanelSwitchRow(stringResource(R.string.sub_ass_native), settings.assNative, { onSettings(settings.copy(assNative = it)) })
                            if (nativeStyle && settings.assNative) PanelHint(stringResource(R.string.sub_original_style_hint)) else {
                                SubtitleSlider(stringResource(R.string.sub_size), settings.sizeSp, 12f..60f) { onSettings(settings.copy(sizeSp = it)) }
                                SubtitleSlider(stringResource(R.string.sub_opacity), settings.opacity, .1f..1f) { onSettings(settings.copy(opacity = it)) }
                                SubtitleSlider(stringResource(R.string.sub_position), settings.bottom, 0f..0.8f) { onSettings(settings.copy(bottom = it)) }
                                PanelPillRow {
                                    listOf("sans" to R.string.sub_sans, "serif" to R.string.sub_serif, "mono" to R.string.sub_mono).forEach { (font, label) ->
                                        PanelPill(stringResource(label), settings.font == font, { onSettings(settings.copy(font = font)) })
                                    }
                                }
                                PanelPillRow {
                                    listOf(-1 to R.string.sub_white, 0xffffff00.toInt() to R.string.sub_yellow, 0xff00ffff.toInt() to R.string.sub_cyan).forEach { (color, label) ->
                                        PanelPill(stringResource(label), settings.color == color, { onSettings(settings.copy(color = color)) })
                                    }
                                }
                                PanelSwitchRow(stringResource(R.string.sub_background), settings.background, { onSettings(settings.copy(background = it)) })
                                PanelPillRow {
                                    listOf("outline" to R.string.sub_outline, "shadow" to R.string.sub_shadow, "none" to R.string.sub_no_edge).forEach { (edge, label) ->
                                        PanelPill(stringResource(label), settings.edge == edge, { onSettings(settings.copy(edge = edge)) })
                                    }
                                }
                            }
                        }
                        2 -> {
                            PanelHint(stringResource(R.string.sub_timing_external))
                            var offset by remember(settings.delayMs) { mutableStateOf(settings.delayMs.toString()) }
                            PanelTextField(offset, { offset = it.take(8) }, stringResource(R.string.sub_delay), error = offset.toLongOrNull() == null)
                            PanelButton(stringResource(R.string.sub_apply), { offset.toLongOrNull()?.let { onSettings(settings.copy(delayMs = it)) } },
                                enabled = offset.toLongOrNull() != null)
                            PanelSection(stringResource(R.string.sub_two_anchors))
                            var a by remember { mutableStateOf("00:00:00.000") }; var va by remember { mutableStateOf("00:00:00.000") }
                            var b by remember { mutableStateOf("00:01:00.000") }; var vb by remember { mutableStateOf("00:01:00.000") }
                            PanelTextField(a, { a = it }, stringResource(R.string.sub_anchor_sub, 1))
                            PanelTextField(va, { va = it }, stringResource(R.string.sub_anchor_video, 1))
                            PanelButton(stringResource(R.string.sub_use_position), { va = SubtitleDocument.timestamp(player.currentPosition, '.') })
                            PanelTextField(b, { b = it }, stringResource(R.string.sub_anchor_sub, 2))
                            PanelTextField(vb, { vb = it }, stringResource(R.string.sub_anchor_video, 2))
                            PanelButton(stringResource(R.string.sub_use_position), { vb = SubtitleDocument.timestamp(player.currentPosition, '.') })
                            PanelButton(stringResource(R.string.sub_sync), {
                                runCatching {
                                    val (scale, delay) = SubtitleDocument.synchronize(SubtitleDocument.time(a) ?: error("Invalid time"), SubtitleDocument.time(va) ?: error("Invalid time"), SubtitleDocument.time(b) ?: error("Invalid time"), SubtitleDocument.time(vb) ?: error("Invalid time"))
                                    onSettings(settings.copy(scale = scale, delayMs = delay))
                                }.onFailure { error = "$failure: ${it.message}" }
                            }, primary = true, modifier = Modifier.fillMaxWidth())
                            PanelText("×${settings.scale}", color = PanelColors.Muted)
                            PanelButton(stringResource(R.string.sub_reset_timing), { onSettings(settings.copy(delayMs = 0, scale = 1.0)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, action: (Float) -> Unit) {
    var preview by remember(value) { androidx.compose.runtime.mutableFloatStateOf(value) }
    PanelSliderCard(label, String.format(Locale.ROOT, "%.2f", preview), preview, { preview = it }, range, onValueChangeFinished = { action(preview) })
}
