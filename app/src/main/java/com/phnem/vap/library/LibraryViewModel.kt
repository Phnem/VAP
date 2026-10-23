package com.phnem.vap.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phnem.vap.data.Favorites
import com.phnem.vap.data.PlaylistSummary
import com.phnem.vap.data.QueueEntry
import com.phnem.vap.data.VapData
import com.phnem.vap.player.model.PlayableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LibraryUiState(
    val hasSources: Boolean = false,
    val videoAccess: VideoAccess = VideoAccess.NONE,
    val scanFailed: Boolean = false,
    val scanning: Boolean = false,
    val settings: LibrarySettings = LibrarySettings(),
    val scope: LibraryScope = LibraryScope.FOLDERS,
    val query: String = "",
    val searchOpen: Boolean = false,
    /** Сырые данные: нужны экрану папки, чтобы проецировать её файлы тем же проектором. */
    val data: LibraryData = LibraryData(emptyList()),
    val folders: List<VideoFolder> = emptyList(),
    val hiddenFolders: List<VideoFolder> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val queue: List<QueueEntry> = emptyList(),
    /** Первые три файла каждой папки в порядке сортировки (id папки → «листы» карточки), считаются вне UI-потока. */
    val cardSheets: Map<String, List<VideoFile>> = emptyMap(),
)

private data class ViewState(
    val hasSources: Boolean,
    val videoAccess: VideoAccess,
    val scanFailed: Boolean,
    val scanning: Boolean,
    val settings: LibrarySettings,
    val scope: LibraryScope,
    val query: String,
    val searchOpen: Boolean,
)

@OptIn(FlowPreview::class)
/** Сколько карточек файлов помещается на первый экран папки с запасом (подписи для них готовятся заранее). */
private const val VISIBLE_CARDS = 24

class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {
    private val trees = TreeStore(app)
    private val scanner = FolderScanner(app)
    private val deviceScanner = DeviceVideoScanner(app)
    private val cache = ScanCache(app)
    private val history = VapData.history(app)
    private val user = VapData.user(app)

    // Список папок из кэша виден сразу, свежий обход идёт в фоне.
    private val scan = MutableStateFlow(cache.load().orEmpty().filterNot { DeviceVideoIndex.isDeviceFolder(it.id) })
    private val scanning = MutableStateFlow(false)
    private val settings = MutableStateFlow(trees.settings())
    private val scope = MutableStateFlow(LibraryScope.FOLDERS)
    private val query = MutableStateFlow("")
    private val searchOpen = MutableStateFlow(false)
    private val videoAccess = MutableStateFlow(VideoPermissions.access(app))
    private val scanFailed = MutableStateFlow(false)
    private val hasSources = MutableStateFlow(trees.trees().isNotEmpty() || videoAccess.value != VideoAccess.NONE)
    private var refreshJob: Job? = null
    private var observing = false
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshJob?.cancel()
            refreshJob = viewModelScope.launch { delay(500); rescan() }
        }
    }

    private var scanJob: Job? = null
    private var scanGeneration = 0
    private val metaRequested = HashSet<String>()

    private val dataFlow: Flow<LibraryData> = combine(
        scan,
        history.observe(),
        user.favorites(),
        user.hidden(),
        // Метаданные приходят пачкой по одному файлу; без паузы каждая запись пересобирала бы весь экран.
        user.meta().debounce(400),
    ) { nodes, hist, fav: Favorites, hidden, meta ->
        LibraryData(nodes, hist, fav.folders, fav.files, hidden, meta)
    }

    private val viewFlow: Flow<ViewState> = combine(hasSources, scanning, settings, scope, query, searchOpen, videoAccess, scanFailed) { values ->
        ViewState(
            hasSources = values[0] as Boolean,
            videoAccess = values[6] as VideoAccess,
            scanFailed = values[7] as Boolean,
            scanning = values[1] as Boolean,
            settings = values[2] as LibrarySettings,
            scope = values[3] as LibraryScope,
            query = values[4] as String,
            searchOpen = values[5] as Boolean,
        )
    }

    val state: StateFlow<LibraryUiState> = combine(
        dataFlow,
        viewFlow,
        user.playlists(),
        user.queue(),
    ) { data, view, playlists, queue ->
        val s = view.settings
        LibraryUiState(
            hasSources = view.hasSources,
            videoAccess = view.videoAccess,
            scanFailed = view.scanFailed,
            scanning = view.scanning,
            settings = s,
            scope = view.scope,
            query = view.query,
            searchOpen = view.searchOpen,
            data = data,
            folders = LibraryProjector.homeFolders(data, s.treeMode, s.filter, s.folderSort, view.query),
            hiddenFolders = LibraryProjector.hiddenFolders(data, s.folderSort),
            continueItems = LibraryProjector.continueWatching(data),
            playlists = playlists,
            queue = queue,
            cardSheets = traced("VAP.cardSheetsMap") {
                data.nodes.associate { it.id to LibraryProjector.firstFiles(it, data, s.fileSort, 3) }
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LibraryUiState(hasSources = hasSources.value, videoAccess = videoAccess.value, settings = settings.value),
    )

    init {
        rescan()
        // Первый вызов форматтера размера (~9 мс) оплачивается здесь, а не в первом кадре открытия папки.
        viewModelScope.launch(Dispatchers.Default) { ByteSizeText.format(app, 1_500_000L) }
    }

    // ——— Сканирование ———

    fun addTree(uri: Uri) {
        trees.add(uri)
        hasSources.value = true
        rescan()
    }

    /** Повторный обход всех выбранных папок (значок «сканирование хранилища»). */
    fun rescan() {
        val access = VideoPermissions.access(app)
        if (access != VideoAccess.FULL || access != videoAccess.value)
            scan.value = scan.value.filterNot { DeviceVideoIndex.isDeviceFolder(it.id) }
        videoAccess.value = access
        hasSources.value = trees.trees().isNotEmpty() || access != VideoAccess.NONE
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = viewModelScope.launch {
            scanning.value = true
            scanFailed.value = false
            try {
                val saf = scanner.scan(trees.trees())
                var indexed = emptyList<IndexedVideo>()
                try { indexed = deviceScanner.scan() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    android.util.Log.w("VapLibrary", "Cannot query device videos", error)
                    scanFailed.value = true
                }
                val found = withContext(Dispatchers.Default) { deviceScanner.folders(indexed) + deviceScanner.uniqueSaf(saf, indexed) }
                if (generation != scanGeneration) return@launch
                // Recheck runtime permission: grants can change while a provider query is running.
                val latestAccess = VideoPermissions.access(app)
                videoAccess.value = latestAccess
                scan.value = if (latestAccess != access) found.filterNot { DeviceVideoIndex.isDeviceFolder(it.id) } else found
                hasSources.value = trees.trees().isNotEmpty() || latestAccess != VideoAccess.NONE
                withContext(Dispatchers.IO) { cache.save(saf) }
                if (latestAccess != access) rescan() else submitThumbnails(scan.value, sweep = !scanFailed.value)
            } finally {
                if (generation == scanGeneration) scanning.value = false
            }
        }
    }

    /**
     * Превью всей библиотеки готовятся в фоне в порядке «сначала то, что видно»: листы карточек главного
     * экрана, «Продолжить», остальные файлы папок по порядку экрана. Лишние превью (файл удалён или
     * изменён) убираются только после полного свежего обхода ([sweep]).
     */
    private fun submitThumbnails(nodes: List<VideoFolder>, sweep: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val ui = withTimeoutOrNull(2_000) { state.first { it.data.nodes === nodes } } ?: state.value
            val order = traced("VAP.thumbPriority") {
                ThumbnailPlan.priority(
                    homeOrder = ui.folders.map { it.id },
                    sheets = ui.cardSheets,
                    recent = ui.continueItems.map { it.file },
                    filesByFolder = nodes.associate { node ->
                        node.id to LibraryProjector.files(node, ui.data, LibraryFilter.ALL, ui.settings.fileSort, "")
                    },
                    everything = nodes.flatMap { it.files },
                )
            }
            ThumbnailPipeline.submit(app, order, sweep)
        }
    }

    fun startObserving() {
        if (VideoPermissions.access(app) == VideoAccess.NONE) { stopObserving(); return }
        if (observing) return
        app.contentResolver.registerContentObserver(android.net.Uri.parse("content://media"), true, mediaObserver)
        observing = true
    }
    fun stopObserving() {
        if (observing) app.contentResolver.unregisterContentObserver(mediaObserver)
        observing = false; refreshJob?.cancel()
    }
    override fun onCleared() { stopObserving(); super.onCleared() }

    // ——— Вид ———

    private fun update(change: (LibrarySettings) -> LibrarySettings) {
        val next = change(settings.value)
        settings.value = next
        trees.save(next)
    }

    fun setFilter(filter: LibraryFilter) = update { it.copy(filter = filter) }
    fun setGrid(grid: Boolean) = update { it.copy(grid = grid) }
    fun setTreeMode(tree: Boolean) = update { it.copy(treeMode = tree) }
    fun setFolderSort(sort: SortSpec<FolderSortKey>) = update { it.copy(folderSort = sort) }
    fun setFileSort(sort: SortSpec<FileSortKey>) = update { it.copy(fileSort = sort) }

    fun setScope(value: LibraryScope) {
        scope.value = value
        closeSearch()
    }

    fun toggleSearch() = if (searchOpen.value) closeSearch() else searchOpen.value = true

    fun closeSearch() {
        searchOpen.value = false
        query.value = ""
    }

    fun setQuery(text: String) {
        query.value = text
    }

    // ——— Пользовательские данные ———

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    fun setFolderFavorite(id: String, on: Boolean) = io { user.setFavorite(true, id, on) }
    fun setFileFavorite(file: VideoFile, on: Boolean) = io { user.setFavorite(false, file.key, on) }
    fun setHidden(id: String, hidden: Boolean) = io { user.setHidden(id, hidden) }

    fun markWatched(file: VideoFile) = io {
        val known = state.value.data.meta[file.key]?.durationMs ?: 0L
        history.markWatched(file.key, file.title, known)
    }

    fun resetProgress(file: VideoFile) = io { history.reset(file.key) }

    fun enqueue(files: List<VideoFile>) {
        io { user.addToQueue(files.map { it.key to it.title }) }
        com.phnem.vap.player.PlaybackConnection.appendIfActive(app, files.map { PlayableItem(it.key, it.title) })
    }
    fun removeFromQueue(position: Long) = io { user.removeFromQueue(position) }
    fun clearQueue() = io { user.clearQueue() }

    fun createPlaylist(name: String, files: List<VideoFile>) = io {
        val id = user.createPlaylist(name.trim())
        if (files.isNotEmpty()) user.addToPlaylist(id, files.map { it.key to it.title })
    }

    fun addToPlaylist(id: Long, files: List<VideoFile>) = io { user.addToPlaylist(id, files.map { it.key to it.title }) }
    fun renamePlaylist(id: Long, name: String) = io { user.renamePlaylist(id, name.trim()) }
    fun deletePlaylist(id: Long) = io { user.deletePlaylist(id) }
    fun removeFromPlaylist(id: Long, position: Long) = io { user.removeFromPlaylist(id, position) }
    fun playlistItems(id: Long): Flow<List<QueueEntry>> = user.playlistItems(id)

    /** Файлы папки в порядке текущей сортировки: очередь плеера и «листы» на карточке берут их отсюда. */
    fun orderedFiles(folder: VideoFolder): List<VideoFile> {
        val s = state.value
        return LibraryProjector.files(folder, s.data, LibraryFilter.ALL, s.settings.fileSort, "")
    }

    /**
     * Файлы для «листов» на карточке папки: свои в порядке сортировки, а у папки без собственных
     * файлов (режим дерева) — первые из вложенных.
     */
    fun cardSheets(folder: VideoFolder): List<VideoFile> {
        val s = state.value
        return s.cardSheets[folder.id] ?: traced("VAP.cardSheets") { LibraryProjector.firstFiles(folder, s.data, s.settings.fileSort, 3) }
    }

    /**
     * Файлы, чьи кадры полетят при открытии папки: первые три из тех, что экран папки покажет
     * (с учётом фильтра, поиска, сортировки и деления на сезоны), иначе цель полёта и картинка
     * в нём разошлись бы.
     */
    suspend fun prepareOpen(folder: VideoFolder): List<VideoFile> = withContext(Dispatchers.Default) {
        traced("VAP.prepareOpen") {
            val s = state.value
            val shown = LibraryProjector.files(folder, s.data, s.settings.filter, s.settings.fileSort, s.query)
            val ordered = SeriesGrouping.group(shown) { it.name }.flatMap { it.files }
            // Подписи размера первых карточек считаются здесь, а не при их первой отрисовке на главном потоке.
            ByteSizeText.warm(app, ordered.take(VISIBLE_CARDS))
            ordered.take(3)
        }
    }

    // ——— Метаданные и воспроизведение ———

    /** Длительность и разрешение файла считываются лениво, когда карточка попала на экран. */
    fun ensureMeta(file: VideoFile) {
        val key = file.key
        if (key in state.value.data.meta || !metaRequested.add(key)) return
        viewModelScope.launch {
            val meta = ThumbnailCache.readMeta(app, file.uri) ?: return@launch
            withContext(Dispatchers.IO) { user.saveMeta(key, meta) }
        }
    }

    /**
     * Очередь и позиция в ней для файла: сначала последняя сессия, если файл в ней был
     * (4.2.7), иначе вся папка, где он лежит.
     */
    fun playbackFor(uri: String): Pair<List<PlayableItem>, Int>? {
        val session = trees.session()
        val inSession = session.indexOfFirst { it.uri == uri }
        if (inSession >= 0) return session to inSession
        val data = state.value.data
        val (_, folder) = data.fileIndex[uri] ?: return null
        val items = orderedFiles(folder).map { PlayableItem(it.key, it.title) }
        return items to items.indexOfFirst { it.uri == uri }.coerceAtLeast(0)
    }

    fun rememberSession(items: List<PlayableItem>) = io { trees.saveSession(items) }

    /** Элементы плейлиста или очереди в форме для плеера. */
    fun toPlayable(entries: List<QueueEntry>): List<PlayableItem> = entries.map { PlayableItem(it.uri, it.title) }
}
