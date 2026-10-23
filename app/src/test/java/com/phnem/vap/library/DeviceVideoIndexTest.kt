package com.phnem.vap.library

import android.net.TestUri
import org.junit.Assert.*
import org.junit.Test

class DeviceVideoIndexTest {
    private fun row(volume: String, path: String, name: String) = IndexedVideo(volume, path, VideoFile(TestUri("$volume:$path/$name"), name, 100, 10))
    private fun folders(vararg rows: IndexedVideo) = DeviceVideoIndex.folders(rows.toList(), "Internal", "SD", ::TestUri)
    @Test fun equalNamesInDifferentPathsAndVolumesStaySeparate() {
        val result = folders(row("external_primary", "A/Movies", "a.mp4"), row("external_primary", "B/Movies", "b.mp4"), row("card", "A/Movies", "c.mp4"))
        assertEquals(3, result.count { it.name == "Movies" && it.files.isNotEmpty() })
        assertEquals(result.size, result.map { it.id }.distinct().size)
    }
    @Test fun ancestorsAggregateCountsAndSizesWithoutDepthLimit() {
        val result = folders(row("external_primary", "A/B/C/D/E/F/G/H", "a.mp4"), row("external_primary", "A", "b.mp4"))
        val root = result.first { it.parentId == null }
        assertEquals(2, root.totalCount); assertEquals(200L, root.totalSize)
        assertEquals(9, result.size)
        assertTrue(result.all { it.childIds.all { id -> result.any { child -> child.id == id && child.parentId == it.id } } })
    }
    @Test fun duplicateProviderRowsDoNotInflateCounts() {
        val row = row("external_primary", "Movies", "a.mp4")
        assertEquals(1, folders(row, row).first { it.name == "Movies" }.totalCount)
    }
    @Test fun deviceRootFilesAreNotLost() {
        val result = folders(row("external_primary", "", "a.mp4"))
        assertEquals(1, result.size); assertEquals("Internal", result.single().name); assertEquals(1, result.single().files.size)
    }
    @Test fun countSortOutranksFavoriteAndKeepsAlphabeticalTies() {
        val rows = listOf(row("external_primary", "A", "a.mp4"), row("external_primary", "Z", "a.mp4"), row("external_primary", "Z", "b.mp4"))
        val result = DeviceVideoIndex.folders(rows, "Internal", "SD", ::TestUri)
        val data = LibraryData(result, favoriteFolders = setOf(DeviceVideoIndex.folderId("external_primary", "A")))
        val ordered = LibraryProjector.homeFolders(data, false, LibraryFilter.ALL, LibrarySettings().folderSort, "")
        assertEquals(listOf("Z", "A"), ordered.map { it.name })
    }
    @Test fun flatFoldersCountOwnVideosWhileTreeCountsDescendants() {
        val nodes = folders(row("external_primary", "A", "a.mp4"), row("external_primary", "A/B", "b.mp4"), row("external_primary", "A/B", "c.mp4"))
        val data = LibraryData(nodes)
        val flat = LibraryProjector.homeFolders(data, false, LibraryFilter.ALL, SortSpec(FolderSortKey.COUNT, true), "")
        assertEquals(listOf("B", "A"), flat.map { it.name }); assertEquals(listOf(2, 1), flat.map { it.totalCount })
        assertEquals(3, LibraryProjector.homeFolders(data, true, LibraryFilter.ALL, SortSpec(FolderSortKey.COUNT, true), "").single().totalCount)
    }
    @Test fun duplicateSafFilesAndEmptyAncestorsArePruned() {
        val nodes = folders(row("card", "A/B", "a.mp4"))
        assertTrue(DeviceVideoIndex.uniqueSaf(nodes) { true }.isEmpty())
    }
    @Test fun nonIndexedSafFilesRemainAndCountsAreRecomputed() {
        val nodes = folders(row("card", "A/B", "a.mp4"), row("card", "A/B", "b.mp4"))
        val result = DeviceVideoIndex.uniqueSaf(nodes) { it.name == "a.mp4" }
        assertEquals(1, result.first { it.parentId == null }.totalCount)
        assertEquals(listOf("b.mp4"), result.flatMap { it.files }.map { it.name })
    }
}
