package com.phnem.vap.player.subtitles

import java.util.Locale

data class SubtitleLine(val startMs: Long, val endMs: Long, val text: String)

/** Prefix end times keep random seeks and long overlapping cues correct without a full scan per frame. */
class SubtitleTimeline(private val lines: List<SubtitleLine>) {
    private val ends = LongArray(lines.size)
    init { var end = Long.MIN_VALUE; lines.forEachIndexed { i, cue -> end = maxOf(end, cue.endMs); ends[i] = end } }
    fun active(positionMs: Long, offsetMs: Long = 0, scale: Double = 1.0): List<SubtitleLine> {
        val time = (positionMs - offsetMs) / scale
        var lo = 0; var hi = lines.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (lines[mid].startMs <= time) lo = mid + 1 else hi = mid }
        val result = mutableListOf<SubtitleLine>()
        var i = lo - 1
        while (i >= 0 && ends[i] > time) { if (time < lines[i].endMs) result += lines[i]; i-- }
        return result.asReversed()
    }
}

/** Plain text timeline. Advanced ASS layout and animation belong to the libass backend (6.3). */
object SubtitleDocument {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_LINES = 50_000
    fun parse(source: String, allowEmpty: Boolean = false): List<SubtitleLine> {
        val text = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val result = if (text.contains("[Events]", ignoreCase = true)) parseAss(text) else parseBlocks(text)
        require(allowEmpty || result.isNotEmpty()) { "No supported subtitle cues" }
        require(result.size <= MAX_LINES) { "Too many subtitle cues" }
        return result.sortedWith(compareBy<SubtitleLine> { it.startMs }.thenBy { it.endMs })
    }
    private fun parseBlocks(text: String): List<SubtitleLine> = text.split(Regex("\n[ \t]*\n")).mapNotNull { block ->
        val lines = block.lines()
        val index = lines.indexOfFirst { "-->" in it }
        if (index < 0) return@mapNotNull null
        val times = lines[index].split("-->", limit = 2)
        val start = time(times[0].trim()) ?: return@mapNotNull null
        val end = time(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
        val body = lines.drop(index + 1).joinToString("\n").replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&").trim()
        if (end > start && body.isNotEmpty()) SubtitleLine(start, end, body) else null
    }
    private fun parseAss(text: String): List<SubtitleLine> {
        var events = false
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val result = mutableListOf<SubtitleLine>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith('[')) events = line.equals("[Events]", true)
            if (!events) return@forEach
            if (line.startsWith("Format:", true)) fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
            if (!line.startsWith("Dialogue:", true)) return@forEach
            val parts = line.substringAfter(':').trimStart().split(',', limit = fields.size)
            fun field(name: String) = fields.indexOf(name).takeIf { it in parts.indices }?.let { parts[it] }
            val start = time(field("start").orEmpty()) ?: return@forEach
            val end = time(field("end").orEmpty()) ?: return@forEach
            // Drawing events must not turn vector instructions into visible text.
            val rawBody = field("text").orEmpty()
            if (Regex("\\\\p[1-9]").containsMatchIn(rawBody)) return@forEach
            val body = rawBody.replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
            if (end > start && body.isNotEmpty()) result += SubtitleLine(start, end, body)
        }
        return result
    }
    fun time(value: String): Long? {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        if (!seconds.isFinite() || seconds !in 0.0..<60.0 || minutes !in 0..59 || hours !in 0..9999) return null
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }
    fun timestamp(ms: Long, separator: Char = ','): String {
        val n = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", n / 3_600_000, n / 60_000 % 60, n / 1000 % 60, separator, n % 1000)
    }
    fun assTimestamp(ms: Long): String {
        val centiseconds = ((ms.coerceAtLeast(0) + 5) / 10)
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", centiseconds / 360_000, centiseconds / 6000 % 60, centiseconds / 100 % 60, centiseconds % 100)
    }
    /**
     * [entities] — экранировать `&`, `<`, `>` как HTML-сущности. Нужно WebVTT и внутренней копии (её читает [parse],
     * вырезающий теги); в SRT, который открывают другие плееры, сущности показались бы буквально.
     */
    fun export(lines: List<SubtitleLine>, vtt: Boolean = false, entities: Boolean = true): String =
        (if (vtt) "WEBVTT\n\n" else "") + lines.mapIndexed { index, line ->
            val text = if (entities) line.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") else line.text
            "${index + 1}\n${timestamp(line.startMs, if (vtt) '.' else ',')} --> ${timestamp(line.endMs, if (vtt) '.' else ',')}\n$text\n"
        }.joinToString("\n")

    fun active(lines: List<SubtitleLine>, positionMs: Long, offsetMs: Long = 0, scale: Double = 1.0): List<SubtitleLine> {
        val time = (positionMs - offsetMs) / scale
        return lines.filter { time >= it.startMs && time < it.endMs }
    }
    /** Affine mapping: playbackTime = subtitleTime * scale + offset. */
    fun synchronize(subtitleA: Long, videoA: Long, subtitleB: Long, videoB: Long): Pair<Double, Long> {
        require(subtitleB > subtitleA && videoB > videoA) { "Second anchor must follow the first" }
        val scale = (videoB - videoA).toDouble() / (subtitleB - subtitleA)
        require(scale in 0.5..2.0) { "Timing ratio outside supported range" }
        return scale to (videoA - subtitleA * scale).toLong()
    }
    fun adjusted(lines: List<SubtitleLine>, offsetMs: Long, scale: Double): List<SubtitleLine> = lines.mapNotNull {
        val start = (it.startMs * scale + offsetMs).toLong().coerceAtLeast(0)
        val end = (it.endMs * scale + offsetMs).toLong().coerceAtLeast(0)
        if (end > start) it.copy(startMs = start, endMs = end) else null
    }
}

data class SubtitleSource(val id: String, val title: String, val language: String = "", val format: String = "text", val embeddedTrackId: String = "", val originStamp: String = "")
data class SubtitleSettings(
    val selection: String = "auto", // auto, off, embedded:<key>, or imported source id
    val secondary: String? = null,
    val language: String = "",
    val sources: List<SubtitleSource> = emptyList(),
    val discovered: Boolean = false,
    val assNative: Boolean = true,
    val delayMs: Long = 0,
    val scale: Double = 1.0,
    val sizeSp: Float = 22f,
    val font: String = "sans",
    val color: Int = -1,
    val opacity: Float = 1f,
    val background: Boolean = true,
    val edge: String = "outline",
    val bottom: Float = .08f,
) {
    fun sanitized() = copy(delayMs = delayMs.coerceIn(-600_000, 600_000),
        scale = if (scale.isFinite()) scale.coerceIn(.5, 2.0) else 1.0,
        sizeSp = if (sizeSp.isFinite()) sizeSp.coerceIn(12f, 60f) else 22f,
        opacity = if (opacity.isFinite()) opacity.coerceIn(.1f, 1f) else 1f,
        bottom = if (bottom.isFinite()) bottom.coerceIn(0f, .8f) else .08f,
        language = language.trim().take(35), sources = sources.distinctBy { it.id }.take(32), secondary = secondary?.takeIf { it != selection })
}
