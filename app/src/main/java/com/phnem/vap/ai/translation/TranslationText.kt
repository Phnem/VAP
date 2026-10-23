package com.phnem.vap.ai.translation

/** Literal terminology is inserted after translating surrounding spans, so names cannot drift. */
data class TranslationPiece(val text: String, val literal: Boolean)
object TranslationText {
    fun glossary(text: String): Map<String, String> {
        require(text.length <= 16_000)
        val result = linkedMapOf<String, String>()
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.forEach { line ->
            val fields = line.split('=', limit = 2)
            require(fields.size == 2 && fields.all { it.isNotBlank() && it.length <= 120 }) { "Use one source=translation term per line" }
            val key = fields[0].trim(); val value = fields[1].trim()
            require(result.keys.none { it.equals(key, true) }) { "Duplicate glossary term" }
            result[key] = value
        }
        require(result.size <= 64) { "At most 64 glossary terms" }
        return result
    }
    fun plan(text: String, glossary: Map<String, String>): List<TranslationPiece> {
        if (glossary.isEmpty()) return split(text)
        val pattern = glossary.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val matches = Regex("(?<![\\p{L}\\p{N}_])(?:$pattern)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).findAll(text)
        val result = mutableListOf<TranslationPiece>(); var offset = 0
        matches.forEach { match ->
            result += split(text.substring(offset, match.range.first))
            result += TranslationPiece(glossary.entries.first { it.key.equals(match.value, true) }.value, true)
            offset = match.range.last + 1
        }
        result += split(text.substring(offset))
        return result
    }
    private fun split(text: String): List<TranslationPiece> {
        val pieces = mutableListOf<TranslationPiece>(); var rest = text
        while (rest.isNotEmpty()) {
            var end = if (rest.codePointCount(0, rest.length) > 200) rest.offsetByCodePoints(0, 200) else rest.length
            if (end < rest.length) rest.lastIndexOf(' ', end - 1).takeIf { it > end / 2 }?.let { end = it + 1 }
            val part = rest.substring(0, end)
            pieces += TranslationPiece(part, part.none(Char::isLetterOrDigit))
            rest = rest.substring(end)
        }
        return pieces
    }
    fun restoreSpacing(source: String, translated: String): String = source.takeWhile(Char::isWhitespace) + translated.trim() + source.takeLastWhile(Char::isWhitespace)
    fun punctuation(text: String): String {
        val value = text.trim().replace(Regex("[ \\t]+"), " ").replace(Regex("\\s+([,.;!?])"), "$1")
        if (value.isBlank()) return value
        val first = value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
        return first + if (first.last().isLetterOrDigit()) "." else ""
    }
}
