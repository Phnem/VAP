package com.phnem.vap.ai.whisper

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AiState(val running: Boolean = false, val downloading: Boolean = false, val uri: String = "", val percent: Int = 0,
    val coveredMs: Long = 0, val durationMs: Long = 0, val language: String = "", val source: SubtitleSource? = null,
    val error: String? = null, val cancelled: Boolean = false, val slow: Boolean = false,
    val operation: String = "whisper", val title: Int = com.phnem.vap.R.string.ai_recognizing)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AiRuntime private constructor(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val models = WhisperModels(File(context.filesDir, "whisper-models"))
    val cache = TranscriptCache(File(context.cacheDir, "whisper-transcripts"))
    val engine = WhisperEngine()
    private val mutable = MutableStateFlow(AiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var extraCancel: (() -> Unit)? = null

    /** Called only from visible UI. Android rejects arbitrary background foreground-service starts. */
    fun download(model: WhisperModel) = begin(AiState(running = true, downloading = true)) { models.download(model) }
    fun delete(model: WhisperModel) { check(!state.value.running); models.delete(model) }
    fun cancel() { models.cancel(); extraCancel?.invoke(); job?.cancel() }
    internal fun backgroundFailed() { mutable.update { it.copy(error = context.getString(com.phnem.vap.R.string.ai_background_failed)) }; cancel() }
    internal fun runTask(uri: String, title: Int, download: Boolean = false, onCancel: () -> Unit = {}, action: suspend ((Int) -> Unit) -> Unit) =
        begin(AiState(running = true, downloading = download, uri = uri, operation = "translation", title = title), onCancel) {
            action { percent -> mutable.update { it.copy(percent = percent.coerceIn(0, 100)) } }
        }
    private fun begin(initial: AiState, onCancel: () -> Unit = {}, action: suspend () -> Unit) {
        check(!state.value.running) { "Another AI task is running" }
        extraCancel = onCancel
        mutable.value = initial
        try { ContextCompat.startForegroundService(context, Intent(context, AiProcessingService::class.java).putExtra("download", initial.downloading)) }
        catch (error: Exception) { extraCancel = null; mutable.value = initial.copy(running = false, error = "Cannot start background task"); return }
        job = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { mutable.update { it.copy(cancelled = true) }; throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = safeError(error)) } }
            finally { mutable.update { it.copy(running = false) }; job = null; extraCancel = null }
        }
    }
    fun transcribe(player: Player, model: WhisperModel, language: String) {
        val input = SpeechInput.capture(player)
        val uri = input.uri; val duration = input.duration; val from = input.position
        SpeechInput.requireMemory(context, model)
        begin(AiState(running = true, uri = uri, durationMs = duration)) {
            val modelFile = models.ready(model) ?: error("Download a model first")
            val preferences = PlayerPreferences(context)
            val prepared = input.open(context)
            val audio = prepared.audio
            val identity = listOf("whisper-v1", uri, prepared.stamp, duration, input.audioIdentity, audio.externalUri, audio.delayMs, model.name, language).joinToString("\n")
            val key = cache.key(identity)
            var transcript = withContext(Dispatchers.IO) { cache.read(key) } ?: Transcript()
            val extractor = AudioExtractor(context, File(context.cacheDir, "whisper-audio"))
            var slow = false
            for (chunk in WhisperChunks.plan(duration, from, transcript.covered)) {
                currentCoroutineContext().ensureActive()
                val started = android.os.SystemClock.elapsedRealtime()
                val samples = extractor.extract(prepared.item, prepared.factory, chunk.first, chunk.last + 1, input.tracks)
                val base = transcript.covered.sumOf { minOf(WhisperChunks.LENGTH, duration - it).coerceAtLeast(0) }
                val result = if (samples.isEmpty()) Transcription(transcript.language, emptyList()) else engine.transcribe(modelFile, samples,
                    if (language == "auto") transcript.language.ifBlank { "auto" } else language) { progress ->
                    mutable.update { it.copy(percent = ((base + (chunk.last + 1 - chunk.first) * progress / 100) * 100 / duration).toInt().coerceIn(0, 99)) }
                }
                val lines = result.lines.mapNotNull { cue ->
                    val start = (cue.startMs + chunk.first + audio.delayMs).coerceIn(0, duration)
                    val end = (minOf(cue.endMs + chunk.first, chunk.last + 1) + audio.delayMs).coerceIn(0, duration)
                    if (end <= start || cue.text in NOISE) null else SubtitleLine(start, end, cue.text)
                }
                transcript = Transcript(transcript.language.ifBlank { result.language }, transcript.covered + chunk.first, (transcript.lines + lines).sortedBy { it.startMs })
                withContext(Dispatchers.IO) { cache.write(key, transcript) }
                slow = slow || android.os.SystemClock.elapsedRealtime() - started > chunk.last + 1 - chunk.first
                val covered = transcript.covered.sumOf { minOf(WhisperChunks.LENGTH, duration - it).coerceAtLeast(0) }
                mutable.update { it.copy(percent = (covered * 100 / duration).toInt(), coveredMs = covered, language = transcript.language, slow = slow) }
            }
            if (transcript.lines.isNotEmpty()) {
                val current = preferences.fileSettings(uri).first().subtitles
                val storage = SubtitleStorage(context)
                val old = current.sources.firstOrNull { it.originStamp == "whisper:$key" }
                val source = if (old != null) { storage.write(old.id, transcript.lines); old } else {
                    require(current.sources.size < 32) { "Remove a subtitle track before attaching Whisper" }
                    storage.create("Whisper · ${model.name.lowercase()} · ${transcript.language}", transcript.language, transcript.lines, originStamp = "whisper:$key")
                }
                preferences.setSubtitles(uri, current.copy(sources = (current.sources + source).distinctBy { it.id }, selection = source.id))
                mutable.update { it.copy(source = source, percent = 100) }
            } else mutable.update { it.copy(percent = 100) }
        }
    }
    private fun safeError(error: Exception): String = when (error) {
        is java.io.IOException -> "Cannot read audio or download model. Check source and retry."
        is org.json.JSONException -> context.getString(com.phnem.vap.R.string.tr_invalid_reply)
        else -> error.message?.takeUnless { it.contains("://") }?.take(180) ?: "Whisper processing failed"
    }
    companion object {
        private val NOISE = setOf("[BLANK_AUDIO]", "[МУЗЫКА]", "[Музыка]", "[Music]", "[MUSIC]", "(music)")
        @Volatile private var instance: AiRuntime? = null
        fun get(context: Context): AiRuntime = instance ?: synchronized(this) { instance ?: AiRuntime(context.applicationContext).also { instance = it } }
    }
}
