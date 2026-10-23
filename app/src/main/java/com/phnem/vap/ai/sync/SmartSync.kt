package com.phnem.vap.ai.sync

import com.phnem.vap.player.subtitles.SubtitleLine
import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

data class SyncWord(val key: String, val startMs: Long, val endMs: Long)
data class SyncAnchor(val subtitleMs: Long, val audioMs: Long, val cue: Int)
data class SyncFit(val delayMs: Long, val scale: Double, val residualMs: Long, val anchors: Int)
data class SyncAnalysis(val constant: SyncFit, val drift: SyncFit?, val matchedWords: Int, val speechWords: Int, val anchors: List<SyncAnchor>) {
    val confidence get() = matchedWords.toDouble() / speechWords.coerceAtLeast(1)
    fun preferred(allowDrift: Boolean) = if (allowDrift && drift != null && drift.residualMs + 150 < constant.residualMs) drift else constant
}

/** Three-gram monotonic chain adapted from MIT Vetro BookAudioAligner; robust fits are VAP-specific. */
object SmartSync {
    private val cjk = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
    private data class Token(val key: String, val cue: Int, val ordinal: Int, val count: Int)
    fun tokens(text: String): List<String> {
        val normalized = Normalizer.normalize(text.replace(Regex("<[^>]{1,120}>|\\{[^}]{1,120}\\}"), " "), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        val result = mutableListOf<String>(); val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { result += word.toString(); word.setLength(0) } }
        var offset = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset); offset += Character.charCount(cp)
            val script = Character.UnicodeScript.of(cp)
            when {
                script in cjk -> { flush(); result += String(Character.toChars(cp)) }
                Character.isLetterOrDigit(cp) -> word.appendCodePoint(cp)
                else -> flush()
            }
        }
        flush(); return result
    }
    fun analyze(lines: List<SubtitleLine>, speech: List<SyncWord>): SyncAnalysis? {
        require(lines.size <= 40_000 && speech.size <= 4000)
        if (speech.size < 8 || lines.isEmpty()) return null
        require(lines.all { it.startMs in 0..172_800_000 && it.endMs >= it.startMs })
        val text = ArrayList<Token>()
        lines.forEachIndexed { cue, line ->
            val words = tokens(line.text)
            require(text.size + words.size <= 200_000) { "Subtitle text exceeds alignment limit" }
            words.forEachIndexed { i, key -> text += Token(key, cue, i, words.size) }
        }
        if (text.size < 8) return null
        val index = HashMap<String, MutableList<Int>>()
        for (b in 0..text.size - 3) {
            val positions = index.getOrPut(gram(text[b].key, text[b + 1].key, text[b + 2].key)) { ArrayList() }
            if (positions.size <= 4) positions += b // A fifth occurrence marks an ambiguous/common phrase.
        }
        val grams = ArrayList<Pair<Int, Int>>()
        for (a in 0..speech.size - 3) {
            if (speech[a + 2].startMs - speech[a].endMs > 3000) continue
            val positions = index[gram(speech[a].key, speech[a + 1].key, speech[a + 2].key)] ?: continue
            if (positions.size > 4) continue
            positions.forEach { grams += a to it }
        }
        if (grams.isEmpty()) return null
        grams.sortWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val score = FloatArray(grams.size); val previous = IntArray(grams.size) { -1 }; var best = 0
        for (i in grams.indices) {
            score[i] = 1f; val (ai, bi) = grams[i]
            for (j in i - 1 downTo maxOf(0, i - 400)) {
                val (aj, bj) = grams[j]
                if (aj < ai && bj < bi) {
                    val da = ai - aj; val db = bi - bj
                    val penalty = if (abs(db - da) <= 30 + max(da, db) / 2) 0f else 3f
                    val next = score[j] + 1 - penalty
                    if (next > score[i]) { score[i] = next; previous[i] = j }
                }
            }
            if (score[i] > score[best]) best = i
        }
        fun offset(pair: Pair<Int, Int>): Double {
            val token = text[pair.second]; val line = lines[token.cue]
            val estimated = line.startMs + (line.endMs - line.startMs) * token.ordinal.toDouble() / token.count.coerceAtLeast(1)
            return speech[pair.first].startMs - estimated
        }
        // Two near-equal chains at different offsets are an ambiguous repeated scene/quote.
        val bestOffset = offset(grams[best])
        if (grams.indices.any { it != best && score[it] >= score[best] * .9f && abs(offset(grams[it]) - bestOffset) > 3000 }) return null
        val picked = ArrayList<Pair<Int, Int>>(); var cursor = best
        while (cursor >= 0) { picked += grams[cursor]; cursor = previous[cursor] }
        picked.reverse()
        val supported = picked.filterIndexed { i, (a, b) ->
            picked.getOrNull(i - 1)?.let { it.first == a - 1 && it.second == b - 1 } == true ||
                picked.getOrNull(i + 1)?.let { it.first == a + 1 && it.second == b + 1 } == true
        }
        val pairs = linkedMapOf<Int, Int>()
        for ((a, b) in supported) for (d in 0..2) if (a + d !in pairs && (pairs.values.lastOrNull() ?: -1) < b + d) pairs[a + d] = b + d
        if (pairs.size < 8 || pairs.size.toDouble() / speech.size < .25) return null
        val anchors = pairs.entries.mapNotNull { (a, b) ->
            val token = text[b]
            if (token.ordinal != 0) null else SyncAnchor(lines[token.cue].startMs, speech[a].startMs, token.cue)
        }.distinctBy { it.cue }.sortedBy { it.subtitleMs }
        if (anchors.size < 2) return null
        val constant = fit(anchors, false) ?: return null
        val drift = fit(anchors, true)
        return SyncAnalysis(constant, drift, pairs.size, speech.size, anchors)
    }
    fun fit(anchors: List<SyncAnchor>, drift: Boolean): SyncFit? {
        if (anchors.size < 2) return null
        val selected = if (anchors.size <= 48) anchors.sortedBy { it.subtitleMs } else anchors.sortedBy { it.subtitleMs }.let { all -> List(48) { all[it * (all.size - 1) / 47] } }
        var scale = 1.0
        if (drift) {
            if (selected.size < 6 || selected.last().subtitleMs - selected.first().subtitleMs < 180_000) return null
            val slopes = mutableListOf<Double>()
            for (a in selected.indices) for (b in a + 1 until selected.size) {
                val dx = selected[b].subtitleMs - selected[a].subtitleMs
                if (dx >= 30_000) slopes += (selected[b].audioMs - selected[a].audioMs).toDouble() / dx
            }
            if (slopes.isEmpty()) return null
            scale = median(slopes)
            if (scale !in .95..1.05) return null
        }
        val delay = median(selected.map { it.audioMs - it.subtitleMs * scale })
        if (abs(delay) > 600_000) return null
        val errors = selected.map { abs(it.audioMs - (it.subtitleMs * scale + delay)) }
        return SyncFit(delay.roundToLong(), scale, median(errors).roundToLong(), selected.size)
    }
    private fun gram(a: String, b: String, c: String) = "$a\u0001$b\u0001$c"
    private fun median(values: List<Double>): Double = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
}
