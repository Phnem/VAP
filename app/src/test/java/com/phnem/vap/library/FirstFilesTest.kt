package com.phnem.vap.library

import android.net.TestUri
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** `firstFiles` обязан давать ровно то же, что полная сортировка и `take`: меняется стоимость, а не порядок. */
class FirstFilesTest {

    private fun file(name: String, modified: Long, size: Long) = VideoFile(TestUri("doc:$name"), name, size, modified)

    private fun folder(files: List<VideoFile>) = VideoFolder(
        id = "f", name = "f", uri = TestUri("f"), kind = FolderKind.GENERIC, posterUri = null, files = files,
    )

    private val names = listOf(
        "Show.S01E02.mkv", "Show.S01E01.mkv", "Show.S02E01.mkv", "[Grp] Show - 03 [1080p].mkv",
        "movie.mkv", "Movie.mkv", "b.mp4", "a.mp4", "Show S01E10.mkv", "episode 5.mkv",
    )

    @Test
    fun matchesFullSortForEveryKeyAndDirection() {
        val random = Random(7)
        repeat(50) {
            val files = List(random.nextInt(1, 40)) { i ->
                file(names[random.nextInt(names.size)] + (if (random.nextBoolean()) "" else ".$i"), random.nextLong(0, 5), random.nextLong(0, 5))
            }
            val folder = folder(files)
            val data = LibraryData(listOf(folder))
            for (key in listOf(FileSortKey.NAME, FileSortKey.DATE, FileSortKey.SIZE, FileSortKey.DURATION)) {
                for (desc in listOf(false, true)) {
                    val sort = SortSpec(key, desc)
                    val expected = LibraryProjector.files(folder, data, LibraryFilter.ALL, sort, "").take(3)
                    assertEquals("$key desc=$desc", expected, LibraryProjector.firstFiles(folder, data, sort, 3))
                }
            }
        }
    }

    @Test
    fun usesNestedFilesWhenFolderHasNone() {
        val child = folder(listOf(file("b.mkv", 0, 1), file("a.mkv", 0, 1)))
            .copy(id = "child", parentId = "root")
        val root = folder(emptyList()).copy(id = "root", childIds = listOf("child"))
        val data = LibraryData(listOf(root, child))
        assertEquals(listOf("a.mkv", "b.mkv"), LibraryProjector.firstFiles(root, data, SortSpec(FileSortKey.NAME), 3).map { it.name })
    }

    @Test
    fun episodeAndNameKeyAreCachedPerFile() {
        val f = file("Show.S02E05.mkv", 0, 1)
        assertEquals(EpisodeInfo(2, 5, "Show"), f.episode)
        assertEquals("show.s02e05.mkv", f.nameKey)
    }
}
