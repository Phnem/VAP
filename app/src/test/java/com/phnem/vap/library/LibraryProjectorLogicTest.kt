package com.phnem.vap.library

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты проекции списков: фильтры, избранное, скрытие, режим дерева, «Продолжить», порядок серий.
 * `Uri` в JVM-тестах подменяет `android.net.TestUri` (лежит в тестовых исходниках).
 */
class LibraryProjectorLogicTest {

    private fun file(name: String, modified: Long = 0L, size: Long = 1L) =
        VideoFile(TestUri("doc:$name"), name, size, modified)

    private fun folder(
        id: String,
        files: List<VideoFile> = emptyList(),
        parent: String? = null,
        children: List<String> = emptyList(),
        childCount: Int = 0,
    ) = VideoFolder(
        id = id,
        name = id,
        uri = TestUri(id),
        kind = FolderKind.GENERIC,
        posterUri = null,
        files = files,
        parentId = parent,
        childIds = children,
        totalCount = files.size + childCount,
    )

    private val name = SortSpec(FolderSortKey.NAME)

    private fun home(data: LibraryData, filter: LibraryFilter = LibraryFilter.ALL, tree: Boolean = false, query: String = "") =
        LibraryProjector.homeFolders(data, tree, filter, name, query).map { it.id }

    @Test
    fun unwatchedAndWatchedFilterFoldersByTheirFiles() {
        val a = folder("a", listOf(file("a1.mkv"), file("a2.mkv")))
        val b = folder("b", listOf(file("b1.mkv")))
        val history = mapOf(
            "doc:a1.mkv" to WatchInfo(100, 100, true, 1),
            "doc:b1.mkv" to WatchInfo(100, 100, true, 2),
        )
        val data = LibraryData(listOf(a, b), history = history)
        assertEquals(listOf("a"), home(data, LibraryFilter.UNWATCHED))
        assertEquals(listOf("b"), home(data, LibraryFilter.WATCHED))
    }

    @Test
    fun favoritesAreFirstWhateverTheSort() {
        val data = LibraryData(
            listOf(folder("a", listOf(file("1.mkv"))), folder("b", listOf(file("2.mkv"))), folder("c", listOf(file("3.mkv")))),
            favoriteFolders = setOf("c"),
        )
        assertEquals(listOf("c", "a", "b"), home(data))
        assertEquals(listOf("c"), home(data, LibraryFilter.FAVORITES))
        val desc = LibraryProjector.homeFolders(data, false, LibraryFilter.ALL, SortSpec(FolderSortKey.NAME, descending = true), "")
        assertEquals(listOf("c", "b", "a"), desc.map { it.id })
    }

    @Test
    fun hiddenAncestorHidesTheWholeBranch() {
        val parent = folder("p", children = listOf("p/c"), childCount = 1)
        val child = folder("p/c", listOf(file("x.mkv")), parent = "p")
        val data = LibraryData(listOf(parent, child), hidden = setOf("p"))
        assertTrue(home(data).isEmpty())
        assertEquals(listOf("p"), LibraryProjector.hiddenFolders(data, name).map { it.id })
    }

    @Test
    fun treeModeShowsOnlyRootsAndFlatModeShowsFoldersWithFiles() {
        val root = folder("root", children = listOf("root/s1"), childCount = 1)
        val s1 = folder("root/s1", listOf(file("e1.mkv")), parent = "root")
        val data = LibraryData(listOf(root, s1))
        assertEquals(listOf("root"), home(data, tree = true))
        assertEquals(listOf("root/s1"), home(data, tree = false))
    }

    @Test
    fun searchMatchesFolderNameOrAnyFileName() {
        val data = LibraryData(listOf(folder("shows", listOf(file("Frieren 01.mkv"))), folder("other", listOf(file("x.mkv")))))
        assertEquals(listOf("shows"), home(data, query = "frieren"))
        assertEquals(listOf("other"), home(data, query = "OTH"))
    }

    @Test
    fun continueWatchingKeepsStartedUnfinishedFreshFirst() {
        val f = folder("f", listOf(file("a.mkv"), file("b.mkv"), file("c.mkv"), file("d.mkv")))
        val history = mapOf(
            "doc:a.mkv" to WatchInfo(60_000, 600_000, false, lastPlayedAt = 10),
            "doc:b.mkv" to WatchInfo(60_000, 600_000, false, lastPlayedAt = 30),
            "doc:c.mkv" to WatchInfo(590_000, 600_000, true, lastPlayedAt = 40),
            "doc:d.mkv" to WatchInfo(1_000, 600_000, false, lastPlayedAt = 50),
            "doc:gone.mkv" to WatchInfo(60_000, 600_000, false, lastPlayedAt = 60),
        )
        val items = LibraryProjector.continueWatching(LibraryData(listOf(f), history = history))
        assertEquals(listOf("b.mkv", "a.mkv"), items.map { it.file.name })
    }

    @Test
    fun filesAreOrderedByEpisodeNumberNotAlphabetically() {
        val f = folder("show", listOf(file("Show S01E10.mkv"), file("Show S01E2.mkv"), file("Show S01E1.mkv")))
        val data = LibraryData(listOf(f))
        val sorted = LibraryProjector.files(f, data, LibraryFilter.ALL, SortSpec(FileSortKey.NAME), "")
        assertEquals(listOf("Show S01E1.mkv", "Show S01E2.mkv", "Show S01E10.mkv"), sorted.map { it.name })
    }
}
