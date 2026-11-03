package com.phnem.vap.library

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила переноса видео между папками и скрытие уже удалённых файлов. Android-типы не создаются. */
class FileRulesTest {

    private fun media(id: Int) = VideoFile(TestUri("content://media/external/video/media/$id"), "v$id.mp4", 1, 1)

    private fun doc(tree: String, name: String) =
        VideoFile(TestUri("content://com.android.externalstorage.documents/tree/$tree/document/$tree%3A$name"), name, 1, 1)

    private fun device(volume: String, dir: String, vararg files: VideoFile) =
        VideoFolder(DeviceVideoIndex.folderId(volume, dir), dir, TestUri("d:$dir"), FolderKind.GENERIC, null, files.toList())

    private fun saf(authority: String, tree: String, name: String) = VideoFolder(
        "content://$authority/tree/$tree/document/$tree%3A$name", name, TestUri("s:$name"), FolderKind.GENERIC, null, emptyList(),
    )

    @Test
    fun deviceFolderIdIsParsedBackIntoVolumeAndPath() {
        assertEquals("external_primary" to "Movies/My Show", FileRules.deviceFolder(DeviceVideoIndex.folderId("external_primary", "Movies/My Show")))
        assertEquals("1A2B-3C4D" to "", FileRules.deviceFolder(DeviceVideoIndex.folderId("1A2B-3C4D", "")))
        assertNull(FileRules.deviceFolder("content://x/tree/t/document/t%3Aa"))
    }

    @Test
    fun mediaFileMovesWithinTheSameVolume() {
        val from = device("external_primary", "Movies/A")
        assertTrue(FileRules.canMove(media(1), from, device("external_primary", "Movies/B")))
    }

    @Test
    fun mediaFileCannotLeaveItsVolumeOrGoToTheVolumeRootOrTheSameFolder() {
        val from = device("external_primary", "Movies/A")
        assertFalse(FileRules.canMove(media(1), from, device("1A2B-3C4D", "Movies/B")))
        assertFalse(FileRules.canMove(media(1), from, device("external_primary", "")))
        assertFalse(FileRules.canMove(media(1), from, from))
        assertFalse(FileRules.canMove(media(1), null, device("external_primary", "Movies/B")))
    }

    @Test
    fun mediaFileCannotGoIntoASafFolder() {
        assertFalse(FileRules.canMove(media(1), device("external_primary", "Movies/A"), saf("com.android.externalstorage.documents", "t", "B")))
    }

    @Test
    fun safDocumentMovesOnlyWithinTheSameProvider() {
        val provider = "com.android.externalstorage.documents"
        val from = saf(provider, "t1", "A")
        assertTrue(FileRules.canMove(doc("t1", "a.mp4"), from, saf(provider, "t1", "B")))
        assertFalse(FileRules.canMove(doc("t1", "a.mp4"), from, saf("com.other.provider", "t9", "B")))
        assertFalse(FileRules.canMove(doc("t1", "a.mp4"), from, device("external_primary", "Movies/B")))
    }

    @Test
    fun hiddenFilesAreDroppedFromListsAndSheets() {
        val a = media(1)
        val b = media(2)
        val folder = device("external_primary", "Movies/A", a, b)
        val data = LibraryData(listOf(folder), hiding = setOf(a.key))
        val sort = SortSpec(FileSortKey.NAME)
        assertEquals(listOf(b), LibraryProjector.files(folder, data, LibraryFilter.ALL, sort, ""))
        assertEquals(listOf(b), LibraryProjector.firstFiles(folder, data, sort, 3))
        assertEquals(listOf(a, b), LibraryProjector.files(folder, LibraryData(listOf(folder)), LibraryFilter.ALL, sort, ""))
    }
}
