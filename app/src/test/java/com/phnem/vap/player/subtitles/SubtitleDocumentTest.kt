package com.phnem.vap.player.subtitles

import org.junit.Assert.*
import org.junit.Test

class SubtitleDocumentTest {
    @Test fun parsesBomCrLfAndSrtMarkup() {
        val cues = SubtitleDocument.parse("\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\n<i>Hello</i> &amp; мир\r\nsecond line\r\n")
        assertEquals(listOf(SubtitleLine(1250, 3500, "Hello & мир\nsecond line")), cues)
    }
    @Test fun parsesVttSettingsAndIdentifiers() {
        val cues = SubtitleDocument.parse("WEBVTT\n\nNOTE ignored\n\ncue-a\n00:01.000 --> 00:02.250 align:start position:20%\nHello\n")
        assertEquals(SubtitleLine(1000, 2250, "Hello"), cues.single())
    }
    @Test fun parsesAssEventsAndIgnoresDrawingsCommentsAndStyles() {
        val cues = SubtitleDocument.parse("""
            [Script Info]
            Title: test
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.25,0:00:04.00,Default,,0,0,0,,{\i1}Hello, world\NМир
            Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,comment
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\p1}m 0 0 l 1 1
        """.trimIndent())
        assertEquals(listOf(SubtitleLine(1250, 4000, "Hello, world\nМир")), cues)
    }
    @Test fun ssaUsesItsDeclaredFieldOrder() {
        val cues = SubtitleDocument.parse("[Events]\nFormat: Marked, End, Start, Text\nDialogue: Marked=0,0:00:05.00,0:00:02.00,one,two")
        assertEquals(SubtitleLine(2000, 5000, "one,two"), cues.single())
    }
    @Test fun filtersMalformedAndReversedTimes() {
        assertEquals(listOf(SubtitleLine(1000, 2000, "valid")), SubtitleDocument.parse("1\n00:00:05,000 --> 00:00:04,000\nwrong\n\n2\n00:99:01,000 --> 00:00:03,000\nwrong\n\n3\n00:00:01,000 --> 00:00:02,000\nvalid"))
        assertNull(SubtitleDocument.time("NaN")); assertNull(SubtitleDocument.time("00:00:NaN"))
    }
    @Test fun activeCuesUseExclusiveEndAndHandleOverlapAndOffsets() {
        val lines = listOf(SubtitleLine(1000, 4000, "a"), SubtitleLine(2000, 3000, "b"))
        assertEquals(listOf("a", "b"), SubtitleDocument.active(lines, 2500).map { it.text })
        assertEquals(listOf("a"), SubtitleDocument.active(lines, 3000).map { it.text })
        assertTrue(SubtitleDocument.active(lines, 4500, 500).isEmpty())
        assertEquals(listOf("a", "b"), SubtitleDocument.active(lines, 5500, 500, 2.0).map { it.text })
    }
    @Test fun twoAnchorsCorrectBothOffsetAndDriftAndExportUsesThem() {
        val (scale, offset) = SubtitleDocument.synchronize(10_000, 12_000, 110_000, 114_000)
        assertEquals(1.02, scale, .000001); assertEquals(1800, offset)
        val cues = SubtitleDocument.adjusted(listOf(SubtitleLine(10_000, 11_000, "a")), offset, scale)
        assertEquals(SubtitleLine(12_000, 13_020, "a"), cues.single())
        assertEquals(cues, SubtitleDocument.parse(SubtitleDocument.export(cues)))
        assertEquals(cues, SubtitleDocument.parse(SubtitleDocument.export(cues, true)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsImpossibleAnchors() { SubtitleDocument.synchronize(1000, 2000, 1000, 3000) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedFile() { SubtitleDocument.parse("not a subtitle") }
    @Test fun sanitizedSettingsProtectRanges() {
        val s = SubtitleSettings(delayMs = Long.MAX_VALUE, scale = Double.NaN, sizeSp = Float.NaN, opacity = -1f, bottom = Float.POSITIVE_INFINITY).sanitized()
        assertEquals(600_000L, s.delayMs); assertEquals(1.0, s.scale, 0.0); assertEquals(22f, s.sizeSp); assertEquals(.1f, s.opacity); assertEquals(.08f, s.bottom)
    }
    @Test fun indexedTimelineRetainsLongOverlapsAfterShortCueEndsAndRandomSeeks() {
        val cues = (listOf(SubtitleLine(0, 100_000, "long")) + (1..5000).map { SubtitleLine(it * 20L, it * 20L + 5, "short $it") }).sortedBy { it.startMs }
        val timeline = SubtitleTimeline(cues)
        listOf(10_000L, 50L, 80_003L, 100_000L, 0L).forEach { time ->
            assertEquals(SubtitleDocument.active(cues, time), timeline.active(time))
        }
    }
    @Test fun exportRetainsLiteralMarkupAndEntityText() {
        val lines = listOf(SubtitleLine(1000, 2000, "<hello> &amp; & Мир"))
        assertEquals(lines, SubtitleDocument.parse(SubtitleDocument.export(lines)))
        assertEquals(lines, SubtitleDocument.parse(SubtitleDocument.export(lines, true)))
    }
    @Test fun plainSrtExportKeepsTextLiteralForOtherPlayers() {
        val lines = listOf(SubtitleLine(1000, 2000, ">> Tom & Jerry"))
        val srt = SubtitleDocument.export(lines, vtt = false, entities = false)
        assertTrue(srt.contains(">> Tom & Jerry")); assertFalse(srt.contains("&amp;")); assertFalse(srt.contains("&gt;"))
        assertTrue(SubtitleDocument.export(lines, vtt = true).contains("&gt;&gt; Tom &amp; Jerry"))
        assertTrue(SubtitleDocument.export(lines).contains("&amp;")) // internal copy stays reversible
    }
    @Test fun assTimeUsesCentisecondsWithCarry() {
        assertEquals("0:00:01.02", SubtitleDocument.assTimestamp(1023))
        assertEquals("1:00:00.00", SubtitleDocument.assTimestamp(3_599_999))
    }
}
