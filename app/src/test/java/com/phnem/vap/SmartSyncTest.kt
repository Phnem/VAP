package com.phnem.vap

import com.phnem.vap.ai.sync.*
import com.phnem.vap.player.subtitles.SubtitleLine
import org.junit.Assert.*
import org.junit.Test

class SmartSyncTest {
    private val sentences = listOf("a curious traveller found the ancient gate", "she opened it and saw the bright ocean", "the quiet village welcomed her at sunset")
    @Test fun exactSpeechRecoversConstantOffsetAcrossCues() {
        val lines = sentences.mapIndexed { i, text -> SubtitleLine(i * 5000L + 3000, i * 5000L + 7000, text) }
        val words = sentences.flatMapIndexed { cue, text -> SmartSync.tokens(text).mapIndexed { index, word -> SyncWord(word, cue * 5000L + index * 400, cue * 5000L + index * 400 + 300) } }
        val result = SmartSync.analyze(lines, words)!!
        assertEquals(-3000L, result.constant.delayMs); assertEquals(1.0, result.constant.scale, 0.00001)
        assertEquals(3, result.anchors.size); assertTrue(result.confidence > .9)
    }
    @Test fun unrelatedLanguageDoesNotProduceAnOffset() {
        val lines = sentences.mapIndexed { i, text -> SubtitleLine(i * 5000L, i * 5000L + 4000, text) }
        val speech = SmartSync.tokens("совершенно другой текст без совпадающих слов для проверки ложных результатов").mapIndexed { i, word -> SyncWord(word, i * 400L, i * 400L + 300) }
        assertNull(SmartSync.analyze(lines, speech))
    }
    @Test fun driftFitRejectsSingleOutlierAndRecoversFrameRateDifference() {
        val anchors = (0..12).map { i -> val t = i * 60_000L; SyncAnchor(t, (.96 * t + 1500).toLong() + if (i == 4) 90_000 else 0, i) }
        val fit = SmartSync.fit(anchors, true)!!
        assertEquals(.96, fit.scale, .0001); assertEquals(1500L, fit.delayMs); assertTrue(fit.residualMs < 20)
        assertTrue(SmartSync.fit(anchors, false)!!.residualMs > 1000)
    }
    @Test fun driftRequiresSeparatedEvidenceAndBoundedScale() {
        assertNull(SmartSync.fit((0..5).map { SyncAnchor(it * 1000L, it * 1000L + 300, it) }, true))
        assertNull(SmartSync.fit((0..6).map { SyncAnchor(it * 60_000L, it * 80_000L, it) }, true))
    }
    @Test fun repeatedAmbiguousSceneIsRejected() {
        val text = "the same repeated phrase appears in both scenes today"
        val lines = listOf(SubtitleLine(0, 4000, text), SubtitleLine(5000, 9000, sentences[0]), SubtitleLine(60_000, 64_000, text), SubtitleLine(65_000, 69_000, sentences[0]))
        val words = SmartSync.tokens(text + " " + sentences[0]).mapIndexed { i, word -> SyncWord(word, i * 400L, i * 400L + 300) }
        assertNull(SmartSync.analyze(lines, words))
    }
    @Test fun normalizationHandlesMarksContractionsAndCjk() {
        assertEquals(listOf("елка", "don", "t", "東", "京"), SmartSync.tokens("Ёлка, don’t 東京"))
    }
}
