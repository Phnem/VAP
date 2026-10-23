package com.phnem.vap.library

import android.net.TestUri
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ключи превью, версия формата, что надо сделать и что удалить, порядок «сначала то, что видно». */
class ThumbnailModelTest {
    private fun file(name: String, size: Long = 100, modified: Long = 5) = VideoFile(TestUri("content://media/$name"), name, size, modified)

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun allNames(vararg files: VideoFile): Set<String> =
        files.flatMap { f -> ThumbTier.entries.map { f.thumbKey.fileName(it) } }.toSet()

    @Test
    fun keyIsStableAndIncludesUriSizeModifiedAndFormatVersion() {
        val key = ThumbKey("content://a", 10, 20)
        assertEquals(key.id, ThumbKey("content://a", 10, 20).id)
        assertEquals(sha1("${ThumbFormat.VERSION}|content://a|10|20"), key.id)
        // Без версии формата в ключе картинки старого формата подошли бы к новому.
        assertNotEquals(sha1("content://a|10|20"), key.id)
        assertEquals(40, key.id.length)
    }

    @Test
    fun changedSizeOrModifiedOrUriGivesAnotherKey() {
        val base = ThumbKey("content://a", 10, 20)
        assertNotEquals(base.id, ThumbKey("content://a", 11, 20).id)
        assertNotEquals(base.id, ThumbKey("content://a", 10, 21).id)
        assertNotEquals(base.id, ThumbKey("content://b", 10, 20).id)
    }

    @Test
    fun videoFileKeyComesFromItsFields() {
        val f = file("x.mp4", size = 7, modified = 9)
        assertEquals(ThumbKey(f.key, 7, 9), f.thumbKey)
    }

    @Test
    fun fileNamesDifferByTierAndRoundTripToId() {
        val key = ThumbKey("content://a", 1, 2)
        val names = ThumbTier.entries.map { key.fileName(it) }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertEquals(key.id, ThumbKey.idOf(it)) }
        assertEquals(key.id, ThumbKey.idOf(key.failedName))
        assertNull(ThumbKey.idOf("notes.txt"))
        assertNull(ThumbKey.idOf("abc.webp"))
    }

    @Test
    fun tiersGrowAndMatchRealCellWidths() {
        val widths = ThumbTier.entries.map { it.widthPx }
        assertEquals(widths.sorted(), widths)
        // Лист карточки (около 265 px), ячейка сетки (474 px), «Продолжить» (605 px).
        assertTrue(ThumbTier.CARD.widthPx >= 265)
        assertTrue(ThumbTier.GRID.widthPx >= 474)
        assertTrue(ThumbTier.LARGE.widthPx >= 605)
    }

    @Test
    fun readyOnlyWhenAllTiersPresentOrMarkedFailed() {
        val f = file("a.mp4")
        val key = f.thumbKey
        assertFalse(ThumbnailPlan.isReady(key, emptySet()))
        val partial = setOf(key.fileName(ThumbTier.CARD), key.fileName(ThumbTier.GRID))
        assertFalse(ThumbnailPlan.isReady(key, partial))
        assertTrue(ThumbnailPlan.isReady(key, allNames(f)))
        assertTrue(ThumbnailPlan.isReady(key, setOf(key.failedName)))
    }

    @Test
    fun missingKeepsOrderSkipsReadyAndDuplicates() {
        val a = file("a.mp4")
        val b = file("b.mp4")
        val c = file("c.mp4")
        val names = allNames(b) + c.thumbKey.fileName(ThumbTier.CARD)
        val result = ThumbnailPlan.missing(listOf(c, a, b, a), names)
        assertEquals(listOf(c, a), result)
    }

    @Test
    fun replacedFileGetsNewKeyAndOldPicturesBecomeStale() {
        val before = file("a.mp4", size = 100, modified = 5)
        val after = file("a.mp4", size = 120, modified = 6)
        val names = allNames(before)
        // Новая версия файла считается ненайденной, а картинки старой версии — лишними.
        assertEquals(listOf(after), ThumbnailPlan.missing(listOf(after), names))
        assertEquals(names, ThumbnailPlan.stale(names, setOf(after.thumbKey.id)).toSet())
    }

    @Test
    fun staleDropsVanishedTempAndForeignButKeepsLive() {
        val live = file("live.mp4")
        val gone = file("gone.mp4")
        val names = allNames(live, gone) + gone.thumbKey.failedName +
            "${live.thumbKey.fileName(ThumbTier.GRID)}.${ThumbFormat.TEMP_EXTENSION}" + "readme.txt"
        val stale = ThumbnailPlan.stale(names, setOf(live.thumbKey.id)).toSet()
        val expected = allNames(gone) + gone.thumbKey.failedName +
            "${live.thumbKey.fileName(ThumbTier.GRID)}.${ThumbFormat.TEMP_EXTENSION}" + "readme.txt"
        assertEquals(expected, stale)
        assertTrue(allNames(live).none { it in stale })
    }

    @Test
    fun priorityPutsCardSheetsThenRecentThenRestWithoutRepeats() {
        val a1 = file("a1.mp4"); val a2 = file("a2.mp4"); val a3 = file("a3.mp4"); val a4 = file("a4.mp4")
        val b1 = file("b1.mp4"); val b2 = file("b2.mp4")
        val hidden = file("hidden.mp4")
        val recent = file("recent-in-b.mp4")
        val order = ThumbnailPlan.priority(
            homeOrder = listOf("A", "B"),
            sheets = mapOf("A" to listOf(a1, a2, a3), "B" to listOf(b1)),
            recent = listOf(recent, a2),
            filesByFolder = mapOf("A" to listOf(a1, a2, a3, a4), "B" to listOf(b1, recent, b2)),
            everything = listOf(hidden, a1, a2, a3, a4, b1, recent, b2),
        )
        assertEquals(listOf(a1, a2, a3, b1, recent, a4, b2, hidden), order)
    }
}
