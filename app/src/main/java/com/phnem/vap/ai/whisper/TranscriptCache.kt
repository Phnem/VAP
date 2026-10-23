package com.phnem.vap.ai.whisper

import android.util.AtomicFile
import com.phnem.vap.player.subtitles.SubtitleLine
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Chunk planning adapted from MIT Vetro WhisperSubtitleManager. End times are exclusive. */
object WhisperChunks {
    const val LENGTH = 30_000L
    fun plan(duration: Long, from: Long, covered: Set<Long>): List<LongRange> {
        require(duration in 1..86_400_000) { "A finite video up to 24 hours is required" }
        val all = (0 until duration step LENGTH).map { it until minOf(duration, it + LENGTH) }
        val index = (from.coerceIn(0, duration - 1) / LENGTH).toInt()
        return (all.drop(index) + all.take(index)).filter { it.first !in covered }
    }
}
data class Transcript(val language: String = "", val covered: Set<Long> = emptySet(), val lines: List<SubtitleLine> = emptyList())
class TranscriptCache(private val directory: File) {
    fun key(identity: String) = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[0-9a-f]{64}")))
        directory.mkdirs(); return AtomicFile(File(directory, "$key.json"))
    }
    fun read(key: String): Transcript? = runCatching {
        val source = file(key).baseFile
        require(source.length() <= 8 * 1024 * 1024)
        val json = JSONObject(String(file(key).readFully(), Charsets.UTF_8))
        val ranges = json.getJSONArray("covered"); val cues = json.getJSONArray("lines")
        require(cues.length() <= 40_000 && ranges.length() <= 2880)
        Transcript(json.optString("language"), (0 until ranges.length()).map { ranges.getLong(it) }.toSet(),
            (0 until cues.length()).map { cues.getJSONArray(it).let { cue -> SubtitleLine(cue.getLong(0), cue.getLong(1), cue.getString(2)) } })
    }.getOrNull()
    fun write(key: String, value: Transcript) {
        require(value.lines.size <= 40_000 && value.covered.size <= 2880)
        val json = JSONObject().put("language", value.language).put("covered", JSONArray(value.covered.sorted())).put("lines", JSONArray().apply {
            value.lines.forEach { put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) }
        }).toString().toByteArray()
        require(json.size <= 8 * 1024 * 1024) { "Transcript cache exceeds limit" }
        val target = file(key); val stream = target.startWrite()
        try { stream.write(json); target.finishWrite(stream) } catch (error: Exception) { target.failWrite(stream); throw error }
        // Cache can be regenerated; cap completed/partial transcripts by age and total bytes.
        var total = 0L
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.forEach { f ->
            total += f.length(); if (total > 64L * 1024 * 1024 && f != target.baseFile) f.delete()
        }
    }
    fun clear() { directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() } }
    fun bytes() = directory.listFiles().orEmpty().sumOf { it.length() }
}
