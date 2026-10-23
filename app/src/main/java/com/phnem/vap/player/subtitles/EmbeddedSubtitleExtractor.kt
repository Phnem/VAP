package com.phnem.vap.player.subtitles

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException

data class ExtractedSubtitles(val title: String, val language: String, val lines: List<SubtitleLine>, val assDocument: String? = null, val trackId: String = "")

/** Media3 demuxes text; video/audio samples are discarded without decoding. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object EmbeddedSubtitleExtractor {
    suspend fun extract(context: Context, uri: Uri): List<ExtractedSubtitles> = withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("file", "content")) { "Choose a local video" }
        val data = DefaultDataSource.Factory(context).createDataSource()
        val parsers = DefaultSubtitleParserFactory()
        val factory = object : SubtitleParser.Factory by parsers {
            override fun supportsFormat(format: Format): Boolean = format.sampleMimeType != MimeTypes.TEXT_SSA && parsers.supportsFormat(format)
        }
        val extractors = DefaultExtractorsFactory().setTextTrackTranscodingEnabled(true).setSubtitleParserFactory(factory).createExtractors(uri, emptyMap())
        var extractor: Extractor? = null
        val budget = ExtractionBudget()
        val tracks = linkedMapOf<Int, TextTrack>()
        fun open(position: Long): DefaultExtractorInput {
            val length = data.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            return DefaultExtractorInput(data, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
        }
        try {
            var input = open(0)
            for (candidate in extractors) {
                currentCoroutineContext().ensureActive()
                val matches = try { candidate.sniff(input) } catch (_: EOFException) { false }
                input.resetPeekPosition()
                if (matches) { extractor = candidate; break }
            }
            val selected = extractor ?: error("Unsupported video container")
            selected.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_TEXT) tracks.getOrPut(id) {
                    require(tracks.size < 32) { "Too many subtitle tracks" }; TextTrack(budget)
                } else DiscardingTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            val seek = PositionHolder()
            while (true) {
                currentCoroutineContext().ensureActive()
                when (selected.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> { data.close(); input = open(seek.position) }
                }
            }
            tracks.values.mapNotNull { track ->
                val ass = track.assDocument()
                val lines = if (ass == null) track.lines.distinct().sortedBy { it.startMs } else runCatching { SubtitleDocument.parse(ass) }.getOrDefault(emptyList())
                if (lines.isEmpty() && ass == null) null else ExtractedSubtitles(track.format?.label ?: track.format?.language ?: "Subtitles", track.format?.language.orEmpty(), lines, ass, track.format?.id.orEmpty())
            }.also { require(it.isNotEmpty()) { "No extractable text subtitles; bitmap subtitles cannot be edited as text" } }
        } finally {
            data.close()
            extractors.forEach { it.release() }
        }
    }
    private class ExtractionBudget(var bytes: Int = 0, var count: Int = 0)
    private class TextTrack(private val budget: ExtractionBudget) : TrackOutput {
        var format: Format? = null
        val lines = mutableListOf<SubtitleLine>()
        private val pending = ByteArrayOutputStream()
        private val decoder = CueDecoder()
        private val assEvents = StringBuilder()
        fun assDocument(): String? {
            val format = format ?: return null
            if (format.sampleMimeType != MimeTypes.TEXT_SSA || assEvents.isEmpty()) return null
            val initialization = format.initializationData
            val header = initialization.lastOrNull()?.toString(Charsets.UTF_8).orEmpty().split(Regex("(?i)\\[Events\\]"), limit = 2).first()
            val fields = initialization.firstOrNull()?.toString(Charsets.UTF_8) ?: return null
            return "$header\n[Events]\n$fields\n$assEvents"
        }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(minOf(length, 8192))
            val count = input.read(buffer, 0, buffer.size)
            if (count == -1) { if (allowEndOfInput) return -1; throw EOFException() }
            pending.write(buffer, 0, count)
            require(pending.size() <= SubtitleDocument.MAX_BYTES) { "Subtitle sample exceeds 5 MB" }
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            require(pending.size().toLong() + length <= SubtitleDocument.MAX_BYTES) { "Subtitle sample exceeds 5 MB" }
            val bytes = ByteArray(length); data.readBytes(bytes, 0, length); pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray(); val start = bytes.size - size - offset
            require(start >= 0 && offset >= 0) { "Invalid subtitle sample" }
            if (format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES && cryptoData == null) {
                val cues = decoder.decode(timeUs, bytes, start, size)
                val text = cues.cues.mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }.joinToString("\n")
                if (text.isNotBlank() && cues.durationUs > 0 && cues.endTimeUs > cues.startTimeUs) {
                    budget.bytes += text.toByteArray(Charsets.UTF_8).size; budget.count++
                    require(budget.count <= SubtitleDocument.MAX_LINES && budget.bytes <= SubtitleDocument.MAX_BYTES) { "Subtitle tracks are too large" }
                    lines += SubtitleLine(cues.startTimeUs.coerceAtLeast(0) / 1000, cues.endTimeUs / 1000, text)
                }
            } else if (format?.sampleMimeType == MimeTypes.TEXT_SSA && cryptoData == null) {
                val event = String(bytes, start, size, Charsets.UTF_8).trimEnd('\u0000', '\n', '\r')
                if (event.startsWith("Dialogue:")) {
                    val parts = event.substringAfter(':').trimStart().split(',', limit = 11)
                    fun relative(value: String): Long? {
                        val last = value.lastIndexOf(':')
                        val normalized = if (value.count { it == ':' } == 3) value.substring(0, last) + "." + value.substring(last + 1) else value
                        return SubtitleDocument.time(normalized)
                    }
                    if (parts.size == 11) {
                        val base = timeUs / 1000
                        val first = relative(parts[0]); val end = relative(parts[1])
                        if (first != null && end != null && end > first) {
                            val line = "Dialogue: ${SubtitleDocument.assTimestamp(base + first)},${SubtitleDocument.assTimestamp(base + end)},${parts.drop(2).joinToString(",")}\n"
                            budget.bytes += line.toByteArray().size; budget.count++
                            require(budget.bytes <= SubtitleDocument.MAX_BYTES && budget.count <= SubtitleDocument.MAX_LINES)
                            assEvents.append(line)
                        }
                    }
                }
            }
            pending.reset()
            if (offset > 0) pending.write(bytes, bytes.size - offset, offset)
        }
    }
}
