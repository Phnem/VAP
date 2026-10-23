package com.phnem.vap.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты чистой логики: правило «просмотрено» и сортировка. Android-типы тут не создаются. */
class LibraryProjectorTest {

    @Test
    fun watchedUsesLargerOfThirtySecondsAndFivePercent() {
        // 2 часа: 5% = 6 мин, поэтому за 5 минут до конца уже «просмотрено».
        assertTrue(WatchRules.isWatched(positionMs = 7_200_000 - 300_000, durationMs = 7_200_000))
        assertFalse(WatchRules.isWatched(positionMs = 7_200_000 - 400_000, durationMs = 7_200_000))
        // 10 минут: 5% = 30 с, порог — 30 с.
        assertTrue(WatchRules.isWatched(positionMs = 600_000 - 30_000, durationMs = 600_000))
        assertFalse(WatchRules.isWatched(positionMs = 600_000 - 31_000, durationMs = 600_000))
    }

    @Test
    fun unknownDurationIsNeverWatched() {
        assertFalse(WatchRules.isWatched(positionMs = 1_000, durationMs = 0))
    }

    @Test
    fun seasonsAreGroupedWhenMostNamesAreEpisodes() {
        val sections = SeriesGrouping.group(listOf("S01E01.mkv", "S01E02.mkv", "S02E01.mkv")) { it }
        assertEquals(listOf(1, 2), sections.map { it.season })
        assertEquals(2, sections[0].files.size)
    }

    @Test
    fun plainFilesStayInOneSection() {
        val sections = SeriesGrouping.group(listOf("a.mp4", "b.mp4", "c.mp4")) { it }
        assertEquals(1, sections.size)
        assertEquals(null, sections[0].season)
    }
}
