package com.phnem.vap.ai.translation

import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Keep
internal object TranslationNative {
    val available = runCatching { System.loadLibrary("vap_translation") }.isSuccess
    external fun create(path: String): Long
    external fun translate(handle: Long, utf8: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

/** Native model/context lives for one job, never for the lifetime of the UI. */
class LocalTranslator {
    val available get() = TranslationNative.available
    suspend fun translate(directory: File, texts: List<String>, progress: (Int) -> Unit = {}): List<String> {
        var result = emptyList<String>()
        batches(directory, listOf(texts), progress) { _, output -> result = output }
        return result
    }
    suspend fun batches(directory: File, batches: List<List<String>>, progress: (Int) -> Unit = {}, completed: (Int, List<String>) -> Unit) {
        check(available) { "Local translation is unavailable on this device" }
        require(batches.sumOf { it.size } <= 40_000)
        val handle = AtomicLong()
        com.phnem.vap.ai.nativeWork(executor, { handle.get().takeIf { it != 0L }?.let(TranslationNative::cancel) }) { active ->
            try {
                val id = TranslationNative.create(directory.absolutePath); handle.set(id)
                if (!active()) { TranslationNative.cancel(id); throw kotlinx.coroutines.CancellationException() }
                var done = 0
                val total = batches.sumOf { it.size }.coerceAtLeast(1)
                for ((batchIndex, batch) in batches.withIndex()) {
                    if (!active()) break
                    val output = ArrayList<String>(batch.size)
                    for (text in batch) {
                        if (!active()) break
                        val bytes = TranslationNative.translate(id, text.toByteArray(Charsets.UTF_8))
                        output += String(bytes, Charsets.UTF_8)
                        progress(++done * 100 / total)
                    }
                    if (active()) completed(batchIndex, output)
                }
            } finally { handle.getAndSet(0).takeIf { it != 0L }?.let(TranslationNative::close) }
        }
    }
    companion object {
        private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1), { Thread(it, "VAP-Translate").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
