package com.phnem.vap.library

import java.util.Locale

enum class LibraryFilter { ALL, FAVORITES, UNWATCHED, WATCHED }

enum class FolderSortKey { NAME, DATE, SIZE, COUNT }

enum class FileSortKey { NAME, DATE, SIZE, DURATION }

data class SortSpec<K : Enum<K>>(val key: K, val descending: Boolean = false)

data class WatchInfo(val positionMs: Long, val durationMs: Long, val watched: Boolean, val lastPlayedAt: Long)

data class FileMeta(val durationMs: Long, val width: Int, val height: Int)

/** Метка «нового» на папке: ничего, индикатор (новая лишь часть видео внутри) или полный чип. */
enum class NewMark { NONE, DOT, CHIP }

/**
 * Единое правило «просмотрено» (как в Vetro): остаток не больше большего из 30 с и 5% длительности.
 * Кнопка «Отметить просмотренным» и автоматическая отметка по концу ходят через него же.
 */
object WatchRules {
    const val MIN_RESUME_MS = 5_000L
    private const val END_FLOOR_MS = 30_000L
    private const val END_FRACTION = 0.05

    fun isWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val tail = maxOf(END_FLOOR_MS, (durationMs * END_FRACTION).toLong())
        return durationMs - positionMs <= tail
    }
}

/** Всё, что нужно для проекции списков: результат сканирования плюс данные пользователя. */
class LibraryData(
    val nodes: List<VideoFolder>,
    val history: Map<String, WatchInfo> = emptyMap(),
    val favoriteFolders: Set<String> = emptySet(),
    val favoriteFiles: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val meta: Map<String, FileMeta> = emptyMap(),
    /** Что пользователь ещё не видел; считается при сканировании ([NewTracker]), здесь только читается. */
    val unseen: NewState = NewState.EMPTY,
    /** Файлы, которые уже удалены или перенесены, но обход хранилища ещё не убрал их из списка: на экране их нет. */
    val hiding: Set<String> = emptySet(),
) {
    val byId: Map<String, VideoFolder> = nodes.associateBy { it.id }

    /** URI видео → (файл, папка, в которой он лежит). */
    val fileIndex: Map<String, Pair<VideoFile, VideoFolder>> by lazy {
        buildMap { nodes.forEach { n -> n.files.forEach { put(it.uri.toString(), it to n) } } }
    }

    fun isWatched(file: VideoFile): Boolean = history[file.uri.toString()]?.watched == true

    /** Видео новое, пока его не открывали (оно появилось после первого сканирования) и истории просмотра у него нет. */
    fun isNew(file: VideoFile): Boolean = file.key in unseen.videos && file.key !in history

    private val ownNew: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (unseen.videos.isEmpty()) emptyMap() else nodes.associate { node -> node.id to node.files.count { isNew(it) } }
    }

    private val deepNew: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (unseen.videos.isEmpty()) {
            emptyMap()
        } else {
            val memo = HashMap<String, Int>()
            fun count(node: VideoFolder): Int = memo.getOrPut(node.id) {
                (ownNew[node.id] ?: 0) + node.childIds.sumOf { id -> byId[id]?.let { count(it) } ?: 0 }
            }
            nodes.forEach { count(it) }
            memo
        }
    }

    /** Сколько в папке новых видео: своих или (при [deep]) вместе со вложенными. */
    fun newCount(folder: VideoFolder, deep: Boolean): Int = (if (deep) deepNew else ownNew)[folder.id] ?: 0

    /**
     * Метка папки. Полный чип — папка новая сама (её ещё не открывали) или новым оказалось всё её
     * содержимое; индикатор — новы лишь некоторые видео внутри.
     */
    fun newMark(folder: VideoFolder, deep: Boolean): NewMark {
        val total = if (deep) folder.totalCount else folder.files.size
        val fresh = newCount(folder, deep)
        return when {
            total > 0 && fresh == total -> NewMark.CHIP
            folder.id in unseen.folders -> NewMark.CHIP
            fresh > 0 -> NewMark.DOT
            else -> NewMark.NONE
        }
    }

    fun isHidden(folder: VideoFolder): Boolean {
        var cur: VideoFolder? = folder
        while (cur != null) {
            if (cur.id in hidden) return true
            cur = cur.parentId?.let(byId::get)
        }
        return false
    }

    /** Все файлы папки и вложенных папок. */
    fun filesUnder(folder: VideoFolder): List<VideoFile> {
        val out = ArrayList<VideoFile>(folder.totalCount)
        fun walk(n: VideoFolder) {
            out += n.files
            n.childIds.forEach { id -> byId[id]?.let(::walk) }
        }
        walk(folder)
        return out
    }
}

data class ContinueItem(
    val file: VideoFile,
    val folderId: String,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Чистая проекция списков: фильтр, поиск, сортировка. Без Android и без состояния, поэтому
 * проверяется обычными JVM-тестами.
 */
object LibraryProjector {

    /** Папки главного экрана: плоский режим (папки с видео) или дерево (корни). */
    fun homeFolders(
        data: LibraryData,
        treeMode: Boolean,
        filter: LibraryFilter,
        sort: SortSpec<FolderSortKey>,
        query: String,
    ): List<VideoFolder> {
        val base = if (treeMode) data.nodes.filter { it.parentId == null } else data.nodes.filter { it.files.isNotEmpty() }
        return base
            .filter { !data.isHidden(it) }
            .filter { matchesFolder(it, data, filter, query, deep = treeMode) }
            .map { if (treeMode) it else it.copy(totalCount = it.files.size, totalSize = it.files.sumOf { file -> file.sizeBytes },
                newestModified = it.files.maxOfOrNull { file -> file.lastModified } ?: 0) }
            .sortedWith(folderComparator(sort, data, deep = treeMode))
    }

    fun hiddenFolders(data: LibraryData, sort: SortSpec<FolderSortKey>): List<VideoFolder> =
        data.nodes.filter { it.id in data.hidden }.sortedWith(folderComparator(sort, data, deep = false, newFirst = false))

    /** Вложенные папки открытой папки (режим дерева). */
    fun childFolders(
        parent: VideoFolder,
        data: LibraryData,
        filter: LibraryFilter,
        sort: SortSpec<FolderSortKey>,
        query: String,
    ): List<VideoFolder> =
        parent.childIds.mapNotNull(data.byId::get)
            .filter { !data.isHidden(it) }
            .filter { matchesFolder(it, data, filter, query, deep = true) }
            .sortedWith(folderComparator(sort, data, deep = true))

    /**
     * Файлы открытой папки. Новинки идут выше остальных ([newFirst]); очереди воспроизведения нужен
     * обычный порядок без этого подъёма, чтобы серии шли подряд.
     */
    fun files(
        folder: VideoFolder,
        data: LibraryData,
        filter: LibraryFilter,
        sort: SortSpec<FileSortKey>,
        query: String,
        newFirst: Boolean = true,
    ): List<VideoFile> {
        val terms = SearchTerms.parse(query)
        val folderHit = !terms.isEmpty && terms.matches(SearchTerms.compact(folder.name))
        return folder.files
            .filter { it.key !in data.hiding && matchesFile(it, data, filter, terms, folderHit) }
            .sortedWith(fileComparator(sort, data, newFirst))
    }

    /**
     * Первые [limit] файлов папки в порядке [sort] — «листы» на карточке. Свои файлы, а у папки без
     * собственных (режим дерева) — вложенные. Результат тот же, что у `files(...).take(limit)` без
     * фильтра и поиска, но без сортировки всего списка: карточек много, файлов в папке сотни.
     */
    fun firstFiles(folder: VideoFolder, data: LibraryData, sort: SortSpec<FileSortKey>, limit: Int): List<VideoFile> {
        val all = folder.files.ifEmpty { data.filesUnder(folder) }
        val source = if (data.hiding.isEmpty()) all else all.filter { it.key !in data.hiding }
        return smallest(source, limit, fileComparator(sort, data))
    }

    /** [n] наименьших элементов по [order]; при равенстве раньше идёт тот, кто раньше в списке (как у `sortedWith`). */
    internal fun <T> smallest(items: List<T>, n: Int, order: Comparator<T>): List<T> {
        if (n <= 0) return emptyList()
        if (items.size <= n) return items.sortedWith(order)
        val best = ArrayList<T>(n + 1)
        for (item in items) {
            if (best.size == n && order.compare(item, best[n - 1]) >= 0) continue
            var at = best.size
            while (at > 0 && order.compare(item, best[at - 1]) < 0) at--
            best.add(at, item)
            if (best.size > n) best.removeAt(n)
        }
        return best
    }

    /** «Продолжить просмотр»: начатые и не досмотренные, свежие первыми; пропавшие файлы отбрасываются. */
    fun continueWatching(data: LibraryData, limit: Int = 10): List<ContinueItem> =
        data.history.entries
            .asSequence()
            .filter { !it.value.watched && it.value.positionMs >= WatchRules.MIN_RESUME_MS }
            .sortedByDescending { it.value.lastPlayedAt }
            .mapNotNull { (uri, w) ->
                val (file, folder) = data.fileIndex[uri] ?: return@mapNotNull null
                if (data.isHidden(folder)) return@mapNotNull null
                ContinueItem(file, folder.id, w.positionMs, w.durationMs)
            }
            .take(limit)
            .toList()

    // ——— Фильтры ———

    private fun matchesFolder(
        folder: VideoFolder,
        data: LibraryData,
        filter: LibraryFilter,
        query: String,
        deep: Boolean,
    ): Boolean {
        val files = if (deep) data.filesUnder(folder) else folder.files
        val okFilter = when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.FAVORITES -> folder.id in data.favoriteFolders
            LibraryFilter.UNWATCHED -> files.any { !data.isWatched(it) }
            LibraryFilter.WATCHED -> files.isNotEmpty() && files.all { data.isWatched(it) }
        }
        if (!okFilter) return false
        val terms = SearchTerms.parse(query)
        if (terms.isEmpty) return true
        return terms.matches(SearchTerms.compact(folder.name)) || files.any { terms.matches(it.compactName) }
    }

    private fun matchesFile(file: VideoFile, data: LibraryData, filter: LibraryFilter, terms: SearchTerms, folderHit: Boolean): Boolean {
        val okFilter = when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.FAVORITES -> file.uri.toString() in data.favoriteFiles
            LibraryFilter.UNWATCHED -> !data.isWatched(file)
            LibraryFilter.WATCHED -> data.isWatched(file)
        }
        if (!okFilter) return false
        // Нашлась сама папка («9-1-1») — в ней показываются все серии, а не только те, чьё имя содержит запрос.
        return terms.isEmpty || folderHit || terms.matches(file.compactName)
    }

    // ——— Сортировка ———

    /** Новинки и папки с новинками выше остальных; внутри каждой группы действует выбранная сортировка. */
    private fun folderComparator(
        sort: SortSpec<FolderSortKey>,
        data: LibraryData,
        deep: Boolean,
        newFirst: Boolean = true,
    ): Comparator<VideoFolder> {
        val base = baseFolderComparator(sort, data)
        if (!newFirst) return base
        return compareByDescending<VideoFolder> { data.newMark(it, deep) != NewMark.NONE }.then(base)
    }

    private fun baseFolderComparator(sort: SortSpec<FolderSortKey>, data: LibraryData): Comparator<VideoFolder> {
        val byName = compareBy<VideoFolder> { it.name.lowercase(Locale.getDefault()) }
        if (sort.key == FolderSortKey.COUNT) {
            val count = compareBy<VideoFolder> { it.totalCount }
            return (if (sort.descending) count.reversed() else count).then(byName).thenBy { it.id }
        }
        val base: Comparator<VideoFolder> = when (sort.key) {
            FolderSortKey.NAME -> byName
            FolderSortKey.DATE -> compareBy<VideoFolder> { it.newestModified }.then(byName)
            FolderSortKey.SIZE -> compareBy<VideoFolder> { it.totalSize }.then(byName)
            FolderSortKey.COUNT -> compareBy<VideoFolder> { it.totalCount }.then(byName)
        }
        // Избранные всегда выше остальных: иначе «избранное» теряет смысл при любой сортировке.
        val favoriteFirst = compareByDescending<VideoFolder> { it.id in data.favoriteFolders }
        return favoriteFirst.then(if (sort.descending) base.reversed() else base)
    }

    private fun fileComparator(sort: SortSpec<FileSortKey>, data: LibraryData, newFirst: Boolean = true): Comparator<VideoFile> {
        val byName = compareBy<VideoFile>(
            { it.episode?.season ?: Int.MAX_VALUE },
            { it.episode?.episode ?: Int.MAX_VALUE },
            { it.nameKey },
        )
        val base: Comparator<VideoFile> = when (sort.key) {
            FileSortKey.NAME -> byName
            FileSortKey.DATE -> compareBy<VideoFile> { it.lastModified }.then(byName)
            FileSortKey.SIZE -> compareBy<VideoFile> { it.sizeBytes }.then(byName)
            FileSortKey.DURATION -> compareBy<VideoFile> { data.meta[it.uri.toString()]?.durationMs ?: 0L }.then(byName)
        }
        val ordered = if (sort.descending) base.reversed() else base
        return if (newFirst) compareByDescending<VideoFile> { data.isNew(it) }.then(ordered) else ordered
    }
}

/**
 * Запрос поиска. Сравнение идёт по «сжатому» тексту: только буквы и цифры в нижнем регистре, «ё» как «е».
 * Поэтому `911`, `9.1.1` и `9 1 1` находят `9-1-1.S01E02.mkv`, а `game thrones` — `Game.of.Thrones`:
 * слова запроса (длиной от двух знаков) могут стоять в любом порядке и с пропусками.
 */
class SearchTerms private constructor(private val whole: String, private val words: List<String>) {
    val isEmpty: Boolean get() = whole.isEmpty()

    fun matches(compactText: String): Boolean =
        compactText.contains(whole) ||
            (words.size > 1 && words.all { it.length >= 2 && compactText.contains(it) })

    companion object {
        fun compact(text: String): String = buildString(text.length) {
            for (ch in text) if (ch.isLetterOrDigit()) append(ch.lowercaseChar().let { if (it == 'ё') 'е' else it })
        }

        fun parse(query: String): SearchTerms {
            val words = query.split(' ', '\t', '\n').map(::compact).filter { it.isNotEmpty() }
            return SearchTerms(words.joinToString(""), words)
        }
    }
}

/** Секция списка: «Сезон N» ([season] != null) или общий список без сезона. */
data class FileSection<T>(val season: Int?, val files: List<T>, val fresh: Boolean = false)

object SeriesGrouping {
    /**
     * То же деление на сезоны, но когда есть новинки и остальное разделено на сезоны, новинки идут первой
     * секцией «Новое»: иначе новая серия 3-го сезона лежала бы внизу, в своём сезоне. Без сезонов список
     * остаётся одним (в нём новинки и так стоят первыми).
     */
    fun <T> groupWithNew(items: List<T>, isNew: (T) -> Boolean, nameOf: (T) -> String): List<FileSection<T>> {
        val fresh = items.filter(isNew)
        if (fresh.isEmpty() || fresh.size == items.size) return group(items, nameOf)
        val rest = group(items.filterNot(isNew), nameOf)
        val titled = rest.size > 1 || rest.first().season != null
        return if (titled) listOf(FileSection(null, fresh, fresh = true)) + rest else listOf(FileSection(null, items))
    }

    /**
     * Делит элементы на сезоны, если большая часть имён распознана как серии (4.2.9). Порядок внутри
     * секции остаётся тем, что пришёл (его задаёт сортировка). Обобщён по типу, чтобы проверяться
     * тестами на обычных строках, без Android-типов.
     */
    fun <T> group(items: List<T>, nameOf: (T) -> String): List<FileSection<T>> {
        if (items.size < 2) return listOf(FileSection(null, items))
        val parsed = items.map { it to EpisodeParser.parse(nameOf(it)) }
        val recognized = parsed.count { it.second != null }
        if (recognized * 2 < items.size) return listOf(FileSection(null, items))
        val sections = LinkedHashMap<Int?, MutableList<T>>()
        parsed.forEach { (item, info) -> sections.getOrPut(info?.season) { ArrayList() }.add(item) }
        // Если сезона нет ни у кого, секция одна и заголовок не нужен.
        if (sections.size == 1 && sections.keys.first() == null) return listOf(FileSection(null, items))
        return sections.entries
            .sortedWith(compareBy({ it.key == null }, { it.key ?: 0 }))
            .map { FileSection(it.key, it.value) }
    }
}
