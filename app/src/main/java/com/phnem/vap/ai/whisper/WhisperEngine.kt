package com.phnem.vap.ai.whisper

import androidx.annotation.Keep
import com.phnem.vap.player.subtitles.SubtitleLine
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class Transcription(val language: String, val lines: List<SubtitleLine>)

/** JNI entry points and callback names are part of the native ABI. */
@Keep
internal object WhisperNative {
    val available = runCatching { System.loadLibrary("vap_whisper") }.isSuccess
    external fun create(path: String): Long
    external fun transcribe(handle: Long, samples: FloatArray, language: String, threads: Int, words: Boolean, progress: Progress): Array<String>
    external fun cancel(handle: Long)
    external fun close(handle: Long)
    @Keep class Progress(private val callback: (Int) -> Unit) {
        @Keep fun onProgress(value: Int) = callback(value.coerceIn(0, 100))
    }
}

/** One bounded native worker. Cancellation requests abort; that worker always frees its context. */
class WhisperEngine {
    val available get() = WhisperNative.available
    suspend fun transcribe(model: File, samples: FloatArray, language: String = "auto", words: Boolean = false, onProgress: (Int) -> Unit = {}): Transcription {
        check(available) { "Whisper is unavailable on this device" }
        require(samples.isNotEmpty() && samples.size <= 16_000 * 61)
        val handle = AtomicLong()
        return com.phnem.vap.ai.nativeWork(executor, { handle.get().takeIf { it != 0L }?.let(WhisperNative::cancel) }) { active ->
            try {
                val id = WhisperNative.create(model.absolutePath); handle.set(id)
                if (!active()) { WhisperNative.cancel(id); throw kotlinx.coroutines.CancellationException() }
                val raw = WhisperNative.transcribe(id, samples, language, (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4), words,
                    WhisperNative.Progress { if (active()) onProgress(it) })
                val lines = raw.drop(1).mapNotNull { row ->
                    val parts = row.split('\t', limit = 3)
                    if (parts.size != 3) null else {
                        val start = parts[0].toLongOrNull(); val end = parts[1].toLongOrNull()
                        if (start == null || end == null || start < 0 || end <= start || parts[2].isBlank()) null
                        else SubtitleLine(start, end, parts[2].trim())
                    }
                }
                Transcription(raw.firstOrNull().orEmpty(), lines)
            } finally { handle.getAndSet(0).takeIf { it != 0L }?.let(WhisperNative::close) }
        }
    }
    private companion object {
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { Thread(it, "VAP-Whisper").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
