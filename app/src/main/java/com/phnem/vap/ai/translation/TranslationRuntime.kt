package com.phnem.vap.ai.translation

import android.content.Context
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.ai.whisper.Transcript
import com.phnem.vap.ai.whisper.TranscriptCache
import com.phnem.vap.network.CredentialVault
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import com.phnem.vap.R
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TranslationResult(val uri: String = "", val source: SubtitleSource? = null)
class TranslationRuntime private constructor(private val context: Context) {
    val models = TranslationModels(context)
    val engine = LocalTranslator()
    val vault = CredentialVault(context, "subtitle_translation")
    val cache = TranscriptCache(File(context.cacheDir, "translation-transcripts"))
    private val mutable = MutableStateFlow(TranslationResult())
    val result = mutable.asStateFlow()
    private val ai = AiRuntime.get(context)
    fun download(model: LocalTranslationModel) = ai.runTask("", R.string.tr_downloading, true, models::cancel) { report -> models.download(model, report) }
    fun delete(model: LocalTranslationModel) { check(!ai.state.value.running); models.delete(model) }
    fun translate(uri: String, source: SubtitleSource, from: String, to: String, glossary: String, punctuation: Boolean, repair: Boolean,
        cloud: TranslationCloudConfig? = null, parallel: Boolean = true) {
        require(from.matches(Regex("[a-z]{2,3}")) && to.matches(Regex("[a-z]{2,3}"))) { "Use a language code such as en or ru" }
        require(from != to || repair) { "Choose a different target language" }
        val terms = TranslationText.glossary(glossary)
        val model = if (cloud == null) LocalTranslationModel.entries.firstOrNull { it.from == from && it.to == to }
            ?: error("No local model for this language pair") else null
        cloud?.validated()
        if (model != null) require(models.ready(model) && engine.available) { "Download a local translation model first" }
        require(cloud != null || !repair) { "Speech-recognition spelling repair requires the selected cloud model" }
        mutable.value = TranslationResult(uri)
        ai.runTask(uri, R.string.tr_translating) { progress ->
            val storage = SubtitleStorage(context)
            val input = storage.read(source.id)
            require(input.isNotEmpty() && input.size <= 40_000)
            val backendId = if (model != null) "ct2-${model.id}-v1" else "${cloud!!.endpoint}/${cloud.model}"
            val identity = listOf("translation-v1", backendId, from, to, glossary, punctuation, repair, SubtitleDocument.export(input)).joinToString("\n")
            val key = cache.key(identity)
            var cached = withContext(Dispatchers.IO) { cache.read(key) } ?: Transcript(to)
            // Keep the local model loaded for the entire job; cache each completed batch.
            val batches = (input.indices step 16).filter { it.toLong() !in cached.covered }
                .map { offset -> offset to input.subList(offset, minOf(offset + 16, input.size)) }
            fun saveBatch(index: Int, translated: List<String>) {
                val (offset, chunk) = batches[index]
                require(translated.size == chunk.size)
                val lines = chunk.zip(translated).map { (original, text) ->
                    original.copy(text = if (punctuation) TranslationText.punctuation(text) else text)
                }
                cached = Transcript(to, cached.covered + offset.toLong(), (cached.lines + lines).sortedBy { it.startMs })
                cache.write(key, cached)
            }
            if (model != null && batches.isNotEmpty()) {
                val memory = android.app.ActivityManager.MemoryInfo()
                context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                require(!memory.lowMemory && memory.availMem >= 500L * 1024 * 1024) { "Not enough available RAM for local translation" }
                val plans = batches.map { (_, chunk) -> chunk.map { TranslationText.plan(it.text, terms) } }
                val segments = plans.map { it.flatten().filterNot { piece -> piece.literal } }
                engine.batches(models.directory(model), segments.map { batch -> batch.map { it.text.trim() } }, progress) { batch, output ->
                    var index = 0
                    val translated = plans[batch].map { parts -> parts.joinToString("") { if (it.literal) it.text else TranslationText.restoreSpacing(it.text, output[index++]) } }
                    saveBatch(batch, translated)
                }
            } else for ((index, batch) in batches.withIndex()) {
                currentCoroutineContext().ensureActive()
                val translated = CloudTranslator().translate(cloud!!, batch.second.map { it.text }, from, to, terms, repair)
                withContext(Dispatchers.IO) { saveBatch(index, translated) }
                progress(minOf(input.size, batch.first + 16) * 100 / input.size)
            }
            val current = PlayerPreferences(context).fileSettings(uri).first().subtitles
            val existing = current.sources.firstOrNull { it.originStamp == "translation:$key" }
            val output = if (existing != null) { storage.write(existing.id, cached.lines); existing } else {
                require(current.sources.size < 32) { "Remove a subtitle track before attaching a translation" }
                storage.create("${context.getString(R.string.tr_translation)} · $from → $to · ${source.title}", to, cached.lines, originStamp = "translation:$key")
            }
            PlayerPreferences(context).setSubtitles(uri, current.copy(sources = (current.sources + output).distinctBy { it.id },
                selection = output.id, secondary = source.id.takeIf { parallel }))
            mutable.value = TranslationResult(uri, output)
            progress(100)
        }
    }
    companion object {
        @Volatile private var instance: TranslationRuntime? = null
        fun get(context: Context): TranslationRuntime = instance ?: synchronized(this) { instance ?: TranslationRuntime(context.applicationContext).also { instance = it } }
    }
}
