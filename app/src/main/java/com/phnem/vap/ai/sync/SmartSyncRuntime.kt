package com.phnem.vap.ai.sync

import android.content.Context
import androidx.media3.common.Player
import com.phnem.vap.R
import com.phnem.vap.ai.whisper.*
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SyncResult(val uri: String, val source: SubtitleSource, val sourceHash: String, val analysis: SyncAnalysis?, val language: String)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmartSyncRuntime private constructor(private val context: Context) {
    private val ai = AiRuntime.get(context)
    private val mutable = MutableStateFlow<SyncResult?>(null)
    val result = mutable.asStateFlow()
    fun analyze(player: Player, source: SubtitleSource, model: WhisperModel, language: String, drift: Boolean) {
        check(!ai.state.value.running)
        val input = SpeechInput.capture(player)
        SpeechInput.requireMemory(context, model)
        val file = ai.models.ready(model) ?: error("Download a Whisper model first")
        mutable.value = null
        ai.runTask(input.uri, R.string.sync_analyzing) { progress ->
            val lines = SubtitleStorage(context).read(source.id)
            require(lines.isNotEmpty()) { "Choose a text subtitle track" }
            val sourceHash = hash(lines)
            val prepared = input.open(context)
            val windows = windows(input.duration, input.position, drift)
            val words = mutableListOf<SyncWord>()
            var detected = ""
            val extractor = AudioExtractor(context, File(context.cacheDir, "smart-sync-audio"))
            for ((index, window) in windows.withIndex()) {
                currentCoroutineContext().ensureActive()
                val samples = extractor.extract(prepared.item, prepared.factory, window.first, window.last + 1, input.tracks)
                if (samples.isNotEmpty()) {
                    val transcript = ai.engine.transcribe(file, samples, if (language == "auto") detected.ifBlank { "auto" } else language, words = true) {
                        progress((index * 100 + it) / windows.size)
                    }
                    detected = detected.ifBlank { transcript.language }
                    transcript.lines.forEach { cue ->
                        val start = (cue.startMs + window.first + prepared.audio.delayMs).coerceAtLeast(0)
                        val end = (cue.endMs + window.first + prepared.audio.delayMs).coerceAtLeast(start + 1)
                        SmartSync.tokens(cue.text).forEach { words += SyncWord(it, start, end) }
                    }
                }
            }
            val analysis = withContext(Dispatchers.Default) { SmartSync.analyze(lines, words.sortedBy { it.startMs }) }
            mutable.value = SyncResult(input.uri, source, sourceHash, analysis, detected)
            progress(100)
        }
    }
    suspend fun save(result: SyncResult, fit: SyncFit): SubtitleSource {
        require(fit in listOf(result.analysis?.constant, result.analysis?.drift) && fit.residualMs <= 800 && fit.anchors >= 2)
        val storage = SubtitleStorage(context)
        val lines = storage.read(result.source.id)
        require(hash(lines) == result.sourceHash) { "Subtitle text changed. Analyze again" }
        val preferences = PlayerPreferences(context)
        val current = preferences.fileSettings(result.uri).first().subtitles
        require(current.sources.any { it.id == result.source.id } && current.sources.size < 32) { "Subtitle track removed or track limit reached" }
        val fixed = SubtitleDocument.adjusted(lines, fit.delayMs, fit.scale)
        val source = storage.create("Smart Sync · ${result.source.title}", result.source.language, fixed)
        preferences.setSubtitles(result.uri, current.copy(sources = current.sources + source, selection = source.id, secondary = null, delayMs = 0, scale = 1.0))
        return source
    }
    suspend fun unchanged(result: SyncResult) = hash(SubtitleStorage(context).read(result.source.id)) == result.sourceHash
    private fun hash(lines: List<SubtitleLine>) = MessageDigest.getInstance("SHA-256").digest(SubtitleDocument.export(lines).toByteArray()).joinToString("") { "%02x".format(it) }
    companion object {
        @Volatile private var instance: SmartSyncRuntime? = null
        fun get(context: Context): SmartSyncRuntime = instance ?: synchronized(this) { instance ?: SmartSyncRuntime(context.applicationContext).also { instance = it } }
        fun windows(duration: Long, position: Long, drift: Boolean): List<LongRange> {
            require(duration in 1..86_400_000)
            val last = (duration - 30_000).coerceAtLeast(0)
            val starts = mutableSetOf((position - 10_000).coerceIn(0, last))
            if (drift && duration >= 180_000) { starts += 0L; starts += last }
            return starts.sorted().map { it until minOf(duration, it + 30_000) }
        }
    }
}
