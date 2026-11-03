package com.phnem.vap.library

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Новое»: база при первом сканировании, метки папок и подъём новинок наверх. Android-типы не создаются. */
class NewTrackingTest {

    private fun file(name: String) = VideoFile(TestUri("v:$name"), name, 100, 10)

    private fun safFolder(tree: String, name: String, vararg files: VideoFile, parent: String? = null, children: List<String> = emptyList()) =
        VideoFolder(
            id = "content://x/tree/$tree/document/$tree%3A$name", name = name, uri = TestUri("f:$tree:$name"), kind = FolderKind.GENERIC,
            posterUri = null, files = files.toList(), parentId = parent, childIds = children,
        )

    private fun deviceFolder(name: String, vararg files: VideoFile) =
        VideoFolder(
            id = DeviceVideoIndex.folderId("external_primary", name), name = name, uri = TestUri("d:$name"), kind = FolderKind.GENERIC,
            posterUri = null, files = files.toList(),
        )

    private fun baseline(
        nodes: List<VideoFolder>,
        sources: Set<String> = emptySet(),
        level: Int = 0,
        access: VideoAccess = VideoAccess.FULL,
        scanned: Boolean = true,
    ) = NewRules.baseline(nodes, sources, level, access, scanned)

    private fun unseen(nodes: List<VideoFolder>, base: Baseline, seenVideos: Set<String> = base.videos.toSet(), seenFolders: Set<String> = base.folders.toSet()) =
        NewRules.unseen(nodes, base.sources, base.deviceLevel, seenVideos, seenFolders)

    // ——— База и определение нового ———

    @Test
    fun firstFullScanMakesNothingNew() {
        val nodes = listOf(deviceFolder("Movies", file("a"), file("b")), safFolder("t1", "Series", file("c")))
        val base = baseline(nodes)
        assertEquals(setOf("v:a", "v:b", "v:c"), base.videos.toSet())
        assertEquals(NewState.EMPTY, unseen(nodes, base))
    }

    @Test
    fun fileAddedAfterBaselineIsNewAndOldOnesAreNot() {
        val before = listOf(deviceFolder("Movies", file("a")))
        val base = baseline(before)
        val after = listOf(deviceFolder("Movies", file("a"), file("b")))
        val state = unseen(after, baseline(after, base.sources, base.deviceLevel), base.videos.toSet(), base.folders.toSet())
        assertEquals(setOf("v:b"), state.videos)
    }

    @Test
    fun folderAddedAfterBaselineIsNew() {
        val first = listOf(deviceFolder("Movies", file("a")))
        val base = baseline(first)
        val next = first + deviceFolder("New", file("z"))
        val state = unseen(next, baseline(next, base.sources, base.deviceLevel), base.videos.toSet(), base.folders.toSet())
        assertEquals(setOf(DeviceVideoIndex.folderId("external_primary", "New")), state.folders)
        assertEquals(setOf("v:z"), state.videos)
    }

    @Test
    fun deviceScanThatFailedDoesNotTakeABaseline() {
        val nodes = listOf(safFolder("t1", "A", file("a")))
        val base = baseline(nodes, scanned = false)
        assertEquals(0, base.deviceLevel)
        // Позже устройство отсканировалось успешно: тогда и берётся его база.
        val withDevice = nodes + deviceFolder("Movies", file("m"))
        val second = baseline(withDevice, base.sources, base.deviceLevel)
        assertEquals(2, second.deviceLevel)
        assertTrue("v:m" in second.videos)
        assertFalse("v:a" in second.videos)
    }

    @Test
    fun widerAccessRebaselinesDeviceButSameAccessDoesNot() {
        val partial = listOf(deviceFolder("Movies", file("a")))
        val one = baseline(partial, access = VideoAccess.PARTIAL)
        assertEquals(1, one.deviceLevel)
        val full = listOf(deviceFolder("Movies", file("a"), file("b")), deviceFolder("Camera", file("c")))
        val two = baseline(full, one.sources, one.deviceLevel, VideoAccess.FULL)
        assertEquals(2, two.deviceLevel)
        assertEquals(setOf("v:a", "v:b", "v:c"), two.videos.toSet())
        // Тот же уровень доступа: новые файлы остаются новыми.
        val again = baseline(full + deviceFolder("Fresh", file("d")), two.sources, two.deviceLevel, VideoAccess.FULL)
        assertTrue(again.videos.isEmpty())
    }

    @Test
    fun noAccessTakesNoDeviceBaseline() {
        val base = baseline(listOf(deviceFolder("Movies", file("a"))), access = VideoAccess.NONE)
        assertEquals(0, base.deviceLevel)
        assertTrue(base.videos.isEmpty())
    }

    @Test
    fun eachSafTreeGetsItsOwnBaselineWhenFirstScanned() {
        val first = listOf(safFolder("t1", "A", file("a")))
        val one = baseline(first)
        val both = first + safFolder("t2", "B", file("b"))
        val two = baseline(both, one.sources, one.deviceLevel)
        assertEquals(setOf("v:b"), two.videos.toSet())
        // Дерево t1 уже было: добавленный в него файл новый.
        val grown = listOf(safFolder("t1", "A", file("a"), file("a2")), safFolder("t2", "B", file("b")))
        val state = NewRules.unseen(grown, two.sources, two.deviceLevel, setOf("v:a", "v:b"), setOf(first[0].id, both[1].id))
        assertEquals(setOf("v:a2"), state.videos)
    }

    @Test
    fun sourceNotYetBaselinedShowsNothingNew() {
        val nodes = listOf(deviceFolder("Movies", file("a")))
        val state = NewRules.unseen(nodes, emptySet(), 0, emptySet(), emptySet())
        assertEquals(NewState.EMPTY, state)
    }

    @Test
    fun newStateOnlyShrinks() {
        val state = NewState(setOf("a", "b"), setOf("f"))
        assertEquals(setOf("b"), state.withoutVideos(listOf("a", "zzz")).videos)
        assertTrue(state.withoutVideos(listOf("zzz")) === state)
        assertEquals(emptySet<String>(), state.withoutFolder("f").folders)
        assertTrue(state.withoutFolder("zzz") === state)
    }

    // ——— Метки папок ———

    private fun data(nodes: List<VideoFolder>, videos: Set<String> = emptySet(), folders: Set<String> = emptySet(), history: Map<String, WatchInfo> = emptyMap()) =
        LibraryData(nodes, history = history, unseen = NewState(videos, folders))

    @Test
    fun folderWithSomeNewVideosGetsOnlyAnIndicator() {
        val folder = deviceFolder("Series", file("a"), file("b"), file("c"))
        val data = data(listOf(folder), videos = setOf("v:c"))
        assertEquals(NewMark.DOT, data.newMark(folder, deep = false))
        assertEquals(1, data.newCount(folder, deep = false))
    }

    @Test
    fun folderWhoseContentIsAllNewGetsTheFullChip() {
        val folder = deviceFolder("Series", file("a"), file("b"))
        assertEquals(NewMark.CHIP, data(listOf(folder), videos = setOf("v:a", "v:b")).newMark(folder, deep = false))
    }

    @Test
    fun unopenedFolderGetsTheChipEvenIfItsVideosAreOld() {
        val folder = deviceFolder("Moved", file("a"))
        assertEquals(NewMark.CHIP, data(listOf(folder), folders = setOf(folder.id)).newMark(folder, deep = false))
    }

    @Test
    fun oldFolderWithNothingNewHasNoMark() {
        val folder = deviceFolder("Old", file("a"))
        assertEquals(NewMark.NONE, data(listOf(folder)).newMark(folder, deep = false))
    }

    @Test
    fun playedVideoIsNotNewAnymore() {
        val folder = deviceFolder("Series", file("a"), file("b"))
        val watch = mapOf("v:a" to WatchInfo(10_000, 100_000, watched = false, lastPlayedAt = 1))
        val data = data(listOf(folder), videos = setOf("v:a", "v:b"), history = watch)
        assertFalse(data.isNew(folder.files[0]))
        assertTrue(data.isNew(folder.files[1]))
        assertEquals(NewMark.DOT, data.newMark(folder, deep = false))
    }

    @Test
    fun treeCountsNewVideosOfDescendantsAndRootStaysOldWhenOnlyAChildIsNew() {
        val child = safFolder("t", "child", file("c1"), file("c2"), parent = "root")
        val root = safFolder("t", "root", file("r"), children = listOf(child.id)).copy(id = "root", totalCount = 3)
        val data = data(listOf(root, child), videos = setOf("v:c1"), folders = setOf(child.id))
        assertEquals(1, data.newCount(root, deep = true))
        assertEquals(0, data.newCount(root, deep = false))
        assertEquals(NewMark.DOT, data.newMark(root, deep = true))
        assertEquals(NewMark.CHIP, data.newMark(child, deep = true))
    }

    // ——— Порядок ———

    @Test
    fun foldersWithNewVideosRiseAboveEverythingEvenUnderCountSort() {
        val big = deviceFolder("Big", file("1"), file("2"), file("3"))
        val small = deviceFolder("Small", file("4"))
        val other = deviceFolder("Other", file("5"), file("6"))
        val data = data(listOf(big, small, other), videos = setOf("v:4"))
        val ordered = LibraryProjector.homeFolders(data, false, LibraryFilter.ALL, SortSpec(FolderSortKey.COUNT, true), "")
        assertEquals(listOf("Small", "Big", "Other"), ordered.map { it.name })
    }

    @Test
    fun newFoldersKeepTheChosenSortAmongThemselves() {
        val a = deviceFolder("A", file("1"))
        val b = deviceFolder("B", file("2"))
        val c = deviceFolder("C", file("3"))
        val data = data(listOf(a, b, c), videos = setOf("v:3", "v:2"))
        val ordered = LibraryProjector.homeFolders(data, false, LibraryFilter.ALL, SortSpec(FolderSortKey.NAME, false), "")
        assertEquals(listOf("B", "C", "A"), ordered.map { it.name })
    }

    @Test
    fun newFilesComeFirstInTheListButNotInThePlaybackOrder() {
        val files = listOf(file("S01E01.mkv"), file("S01E02.mkv"), file("S01E03.mkv"))
        val folder = deviceFolder("Show", *files.toTypedArray())
        val data = data(listOf(folder), videos = setOf("v:S01E03.mkv"))
        val sort = SortSpec(FileSortKey.NAME)
        assertEquals(
            listOf("S01E03.mkv", "S01E01.mkv", "S01E02.mkv"),
            LibraryProjector.files(folder, data, LibraryFilter.ALL, sort, "").map { it.name },
        )
        assertEquals(
            listOf("S01E01.mkv", "S01E02.mkv", "S01E03.mkv"),
            LibraryProjector.files(folder, data, LibraryFilter.ALL, sort, "", newFirst = false).map { it.name },
        )
        // «Листы» на карточке папки показывают то, что на экране первым.
        assertEquals("S01E03.mkv", LibraryProjector.firstFiles(folder, data, sort, 3).first().name)
    }

    @Test
    fun newEpisodesGetTheirOwnSectionAboveTheSeasons() {
        val names = listOf("Show.S01E01.mkv", "Show.S01E02.mkv", "Show.S03E01.mkv")
        val sections = SeriesGrouping.groupWithNew(names, { it == "Show.S03E01.mkv" }) { it }
        assertEquals(listOf(null, 1), sections.map { it.season })
        assertTrue(sections[0].fresh)
        assertEquals(listOf("Show.S03E01.mkv"), sections[0].files)
        assertEquals(listOf("Show.S01E01.mkv", "Show.S01E02.mkv"), sections[1].files)
    }

    @Test
    fun plainFolderKeepsOneSectionAndNoNewHeader() {
        val names = listOf("b.mp4", "a.mp4", "c.mp4")
        val sections = SeriesGrouping.groupWithNew(names, { it == "b.mp4" }) { it }
        assertEquals(1, sections.size)
        assertFalse(sections[0].fresh)
        assertEquals(names, sections[0].files)
    }

    @Test
    fun hiddenListIgnoresNew() {
        val a = deviceFolder("A", file("1"))
        val b = deviceFolder("B", file("2"))
        val data = LibraryData(listOf(a, b), hidden = setOf(a.id, b.id), unseen = NewState(setOf("v:2"), emptySet()))
        assertEquals(listOf("A", "B"), LibraryProjector.hiddenFolders(data, SortSpec(FolderSortKey.NAME)).map { it.name })
    }
}
