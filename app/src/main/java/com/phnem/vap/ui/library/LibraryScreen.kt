package com.phnem.vap.ui.library

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.phnem.vap.ui.loading.BubbleClusterLoader
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.Backdrop
import com.phnem.vap.ui.glass.freezableBackdrop
import com.phnem.vap.ui.glass.rememberFreezableBackdrop
import com.phnem.vap.R
import com.phnem.vap.BuildConfig
import com.phnem.vap.library.ContinueItem
import com.phnem.vap.library.FileSortKey
import com.phnem.vap.library.FolderSortKey
import com.phnem.vap.library.LibraryFilter
import com.phnem.vap.library.LibraryScope
import com.phnem.vap.library.LibraryUiState
import com.phnem.vap.library.LibraryViewModel
import com.phnem.vap.library.SortSpec
import com.phnem.vap.library.ThumbTier
import com.phnem.vap.library.ThumbnailCache
import com.phnem.vap.library.VideoFile
import com.phnem.vap.library.VideoFolder
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.ui.brand.VapWordmark
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val ScreenInset = 16.dp
private val GridGap = 16.dp

/**
 * Нижний отступ прокручиваемого содержимого: место под док и мини-плеер. Раньше при активном плеере
 * весь слой содержимого укорачивался на 128 dp, и под доком оставалась пустая чёрная полоса, которую
 * стеклу нечем было размывать. Теперь слой всегда на весь экран, а список листается дальше.
 */
internal val LocalLibraryBottomInset = androidx.compose.runtime.compositionLocalOf { 128.dp }
internal const val NO_PLAYLIST = -2L
internal const val QUEUE_ID = -1L

/**
 * Корень библиотеки: главный экран, экран открытой папки, плейлисты, общий нижний док и меню.
 *
 * Открытие папки — морф [FolderMorph]: листы летят с карточки в первые карточки файлов, пластина
 * наклоняется. Вложенные папки (режим дерева) переключаются кросс-фейдом внутри экрана папки.
 */
@Composable
fun LibraryRoot(
    onPlay: (List<PlayableItem>, Int) -> Unit,
    onRequestVideoAccess: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addTree(uri)
    }
    val backdrop = rememberFreezableBackdrop()
    val morph = remember { FolderMorph() }
    // Пока папка открывается или закрывается, подложка стекла дока не пишется: размытие над неизменным
    // слоем не пересчитывается (около 20 проходов рендера на кадр), по окончании оно плавно переезжает.
    LaunchedEffect(morph.flying) { if (morph.flying) backdrop.freeze() else backdrop.thaw() }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    var hasPlayback by remember { mutableStateOf(false) }

    // Путь по папкам хранится строкой (идентификаторы — URI без переводов строк), чтобы пережить
    // пересоздание Activity.
    var stackKey by rememberSaveable { mutableStateOf("") }
    var playlistId by rememberSaveable { mutableLongStateOf(NO_PLAYLIST) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val stack = remember(stackKey) { stackKey.split('\n').filter { it.isNotEmpty() } }
    val rootFolder = stack.firstOrNull()?.let { state.data.byId[it] }

    val needsRestore = remember { stackKey.isNotEmpty() }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(rootFolder?.id) {
        if (needsRestore && !restored && rootFolder != null) {
            restored = true
            if (morph.open == null) morph.restore(rootFolder)
        }
    }
    // Папка пропала при пересканировании: возвращаемся на главный экран.
    LaunchedEffect(state.data, stackKey) {
        if (stack.isNotEmpty() && state.data.nodes.isNotEmpty() && stack.any { it !in state.data.byId }) {
            stackKey = ""
            morph.reset()
        }
    }

    fun play(items: List<PlayableItem>, index: Int) {
        if (items.isEmpty()) return
        viewModel.rememberSession(items)
        onPlay(items, index)
    }

    val opening = remember { booleanArrayOf(false) }
    fun openFolder(folder: VideoFolder) {
        if (morph.open != null || opening[0]) return
        opening[0] = true
        scope.launch {
            try {
                // Список и подписи первых карточек готовятся вне главного потока, до начала морфа.
                val sheets = if (folder.files.isNotEmpty()) viewModel.prepareOpen(folder) else emptyList()
                stackKey = folder.id
                morph.expand(folder, sheets, reduced)
            } finally {
                opening[0] = false
            }
        }
    }

    fun back() {
        when {
            morph.progress.isRunning -> Unit
            stack.size > 1 -> stackKey = stack.dropLast(1).joinToString("\n")
            stack.size == 1 -> scope.launch {
                morph.collapse(reduced)
                stackKey = ""
            }
        }
    }

    BackHandler(
        enabled = state.searchOpen || stack.isNotEmpty() || playlistId != NO_PLAYLIST || state.scope != LibraryScope.FOLDERS,
    ) {
        when {
            state.searchOpen -> viewModel.closeSearch()
            stack.isNotEmpty() -> back()
            playlistId != NO_PLAYLIST -> playlistId = NO_PLAYLIST
            else -> viewModel.setScope(LibraryScope.FOLDERS)
        }
    }

    // Клавиатура поднимает строку поиска и прячет док с мини-плеером; флаг меняется дважды за показ,
    // а не на каждом кадре анимации клавиатуры.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember { androidx.compose.runtime.derivedStateOf { imeInsets.getBottom(density) > 0 } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalLibraryBottomInset provides if (hasPlayback) 200.dp else 128.dp,
        ) {
        // Всё, что под доком и меню, собирается в слой для стекла.
        Box(
            Modifier
                .fillMaxSize()
                .freezableBackdrop(backdrop, MaterialTheme.colorScheme.background)
                .onGloballyPositioned { morph.root = it },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - (morph.progress.value * 1.6f).coerceIn(0f, 1f)
                        // Без этого прозрачность < 1 заводит буфер на весь экран 1080x2400 на каждый кадр морфа.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                if (state.scope == LibraryScope.PLAYLISTS) {
                    PlaylistsScreen(
                        state = state,
                        openId = playlistId,
                        viewModel = viewModel,
                        onOpen = { playlistId = it },
                        onBack = { playlistId = NO_PLAYLIST },
                        onTitleClick = { overlay = Overlay.ScopeMenu },
                        onMenu = { overlay = it },
                        onPlay = ::play,
                    )
                } else {
                    val hiddenScope = state.scope == LibraryScope.HIDDEN
                    LibraryHome(
                        state = state,
                        folders = if (hiddenScope) state.hiddenFolders else state.folders,
                        title = stringResource(if (hiddenScope) R.string.scope_hidden else R.string.library_title),
                        showContinue = !hiddenScope && state.query.isBlank() && state.settings.filter == LibraryFilter.ALL,
                        showAddTile = !hiddenScope,
                        morph = morph,
                        cardSheets = viewModel::cardSheets,
                        onOpen = ::openFolder,
                        onFolderMenu = { overlay = Overlay.FolderMenu(it) },
                        onAddFolder = { picker.launch(null) },
                        onRequestVideoAccess = onRequestVideoAccess,
                        onRescan = viewModel::rescan,
                        onTitleClick = { overlay = Overlay.ScopeMenu },
                        onFilter = viewModel::setFilter,
                        onSortClick = { overlay = Overlay.SortMenu(forFiles = false) },
                        onQuery = viewModel::setQuery,
                        onCloseSearch = viewModel::closeSearch,
                        onContinue = { item -> viewModel.playbackFor(item.file.key)?.let { (items, i) -> play(items, i) } },
                    )
                }
            }

            if (morph.open != null && stack.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (morph.progress.value * 1.4f - 0.2f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    AnimatedContent(
                        targetState = stack.last(),
                        transitionSpec = { fadeIn(MotionTokens.standard()) togetherWith fadeOut(MotionTokens.dialogExit()) },
                        label = "folderLevel",
                    ) { id ->
                        val folder = state.data.byId[id]
                        if (folder != null) {
                            FolderScreen(
                                folder = folder,
                                state = state,
                                isRoot = stack.size == 1,
                                morph = morph,
                                cardSheets = viewModel::cardSheets,
                                onBack = ::back,
                                onOpenChild = { child -> stackKey = (stack + child.id).joinToString("\n") },
                                onFolderMenu = { overlay = Overlay.FolderMenu(it) },
                                onFileMenu = { overlay = Overlay.FileMenu(it) },
                                onPlay = ::play,
                                onFilter = viewModel::setFilter,
                                onSortClick = { overlay = Overlay.SortMenu(forFiles = true) },
                                onQuery = viewModel::setQuery,
                                onCloseSearch = viewModel::closeSearch,
                                ensureMeta = viewModel::ensureMeta,
                            )
                        }
                    }
                }
            }

            FlyingPapers(morph)
        }
        }

        // Док и мини-плеер остаются в композиции (мини-плеер держит соединение с сервисом и решение
        // «продолжить»), под клавиатурой они только становятся невидимыми.
        val underIme = Modifier.graphicsLayer { alpha = if (imeVisible) 0f else 1f }
        NowPlayingBar(
            backdrop = backdrop,
            onVisibilityChanged = { hasPlayback = it },
            modifier = underIme.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
        )

        LibraryDock(
            backdrop = backdrop,
            grid = state.settings.grid,
            onGrid = viewModel::setGrid,
            searchActive = state.searchOpen,
            onSearch = viewModel::toggleSearch,
            modifier = underIme,
        )

        LibrarySearchBar(
            visible = state.searchOpen,
            query = state.query,
            onQuery = viewModel::setQuery,
            onClose = viewModel::closeSearch,
            backdrop = backdrop,
            hasPlayback = hasPlayback,
        )

        overlay?.let { current ->
            LibraryOverlays(
                overlay = current,
                state = state,
                viewModel = viewModel,
                backdrop = backdrop,
                onChange = { overlay = it },
                onOpenFolder = { folder -> if (stack.isEmpty()) openFolder(folder) else stackKey = (stack + folder.id).joinToString("\n") },
                onPlay = ::play,
                onOpenPlaylist = { playlistId = it },
            )
        }
    }
}

// ——— Меню и диалоги ———

@Composable
private fun LibraryOverlays(
    overlay: Overlay,
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    backdrop: Backdrop,
    onChange: (Overlay?) -> Unit,
    onOpenFolder: (VideoFolder) -> Unit,
    onPlay: (List<PlayableItem>, Int) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val data = state.data
    val scope = rememberCoroutineScope()
    val close = { onChange(null) }

    when (overlay) {
        Overlay.NetworkSources -> com.phnem.vap.network.NetworkSourcesDialog(onPlay = { items -> close(); onPlay(items, 0) }, onDismiss = close)
        is Overlay.FolderMenu -> {
            val folder = overlay.folder
            val favorite = folder.id in data.favoriteFolders
            val hidden = folder.id in data.hidden
            val all = data.filesUnder(folder)
            GlassSheet(backdrop, onDismiss = close) { done ->
                SheetTitle(folder.name)
                SheetItem(stringResource(R.string.action_open), icon = R.drawable.ic_vap_folder, onClick = { done(); onOpenFolder(folder) })
                SheetItem(
                    stringResource(if (favorite) R.string.action_unfavorite else R.string.action_favorite),
                    icon = if (favorite) R.drawable.ic_vap_star else R.drawable.ic_vap_star_outline,
                    onClick = { viewModel.setFolderFavorite(folder.id, !favorite); done() },
                )
                SheetItem(stringResource(R.string.action_enqueue), icon = R.drawable.ic_vap_queue, onClick = { viewModel.enqueue(all); done() })
                SheetItem(
                    stringResource(R.string.action_add_playlist),
                    icon = R.drawable.ic_vap_playlist,
                    onClick = { onChange(Overlay.PlaylistPicker(all)) },
                )
                SheetItem(
                    stringResource(if (hidden) R.string.action_unhide else R.string.action_hide),
                    icon = R.drawable.ic_vap_eye_off,
                    onClick = { viewModel.setHidden(folder.id, !hidden); done() },
                )
            }
        }

        is Overlay.FileMenu -> {
            val file = overlay.file
            val info = data.history[file.key]
            val favorite = file.key in data.favoriteFiles
            GlassSheet(backdrop, onDismiss = close) { done ->
                SheetTitle(file.title)
                SheetItem(
                    stringResource(R.string.action_play),
                    icon = R.drawable.ic_vap_play,
                    onClick = { done(); viewModel.playbackFor(file.key)?.let { (items, i) -> onPlay(items, i) } },
                )
                SheetItem(stringResource(R.string.action_enqueue), icon = R.drawable.ic_vap_queue, onClick = { viewModel.enqueue(listOf(file)); done() })
                SheetItem(
                    stringResource(R.string.action_add_playlist),
                    icon = R.drawable.ic_vap_playlist,
                    onClick = { onChange(Overlay.PlaylistPicker(listOf(file))) },
                )
                SheetItem(
                    stringResource(if (favorite) R.string.action_unfavorite else R.string.action_favorite),
                    icon = if (favorite) R.drawable.ic_vap_star else R.drawable.ic_vap_star_outline,
                    onClick = { viewModel.setFileFavorite(file, !favorite); done() },
                )
                if (info?.watched != true) {
                    SheetItem(stringResource(R.string.action_mark_watched), icon = R.drawable.ic_vap_check, onClick = { viewModel.markWatched(file); done() })
                }
                if (info != null) {
                    SheetItem(stringResource(R.string.action_reset_progress), icon = R.drawable.ic_vap_close, onClick = { viewModel.resetProgress(file); done() })
                }
            }
        }

        Overlay.ScopeMenu -> GlassSheet(backdrop, onDismiss = close) { done ->
            SheetTitle("VAP ${BuildConfig.VERSION_NAME}")
            SheetItem(stringResource(R.string.network_sources), icon = R.drawable.ic_vap_play,
                onClick = { onChange(Overlay.NetworkSources) })
            SheetItem(
                stringResource(R.string.library_title),
                icon = R.drawable.ic_vap_folder,
                checked = state.scope == LibraryScope.FOLDERS,
                onClick = { viewModel.setScope(LibraryScope.FOLDERS); done() },
            )
            SheetItem(
                stringResource(R.string.scope_hidden),
                icon = R.drawable.ic_vap_eye_off,
                checked = state.scope == LibraryScope.HIDDEN,
                onClick = { viewModel.setScope(LibraryScope.HIDDEN); done() },
            )
            SheetItem(
                stringResource(R.string.scope_playlists),
                icon = R.drawable.ic_vap_playlist,
                checked = state.scope == LibraryScope.PLAYLISTS,
                onClick = { viewModel.setScope(LibraryScope.PLAYLISTS); done() },
            )
            SheetItem(
                stringResource(R.string.tree_mode),
                icon = R.drawable.ic_vap_folder_plus,
                checked = state.settings.treeMode,
                onClick = { viewModel.setTreeMode(!state.settings.treeMode); done() },
            )
        }

        is Overlay.SortMenu -> GlassSheet(backdrop, onDismiss = close) { done ->
            SheetTitle(stringResource(R.string.sort))
            if (overlay.forFiles) {
                val current = state.settings.fileSort
                FileSortKey.entries.forEach { key ->
                    val selected = current.key == key
                    SheetItem(
                        label = stringResource(fileSortLabel(key)),
                        checked = selected,
                        trailing = if (selected) arrow(current.descending) else null,
                        onClick = {
                            viewModel.setFileSort(if (selected) current.copy(descending = !current.descending) else SortSpec(key, descending = key != FileSortKey.NAME))
                            done()
                        },
                    )
                }
            } else {
                val current = state.settings.folderSort
                FolderSortKey.entries.forEach { key ->
                    val selected = current.key == key
                    SheetItem(
                        label = stringResource(folderSortLabel(key)),
                        checked = selected,
                        trailing = if (selected) arrow(current.descending) else null,
                        onClick = {
                            viewModel.setFolderSort(if (selected) current.copy(descending = !current.descending) else SortSpec(key, descending = key != FolderSortKey.NAME))
                            done()
                        },
                    )
                }
            }
        }

        is Overlay.PlaylistPicker -> GlassSheet(backdrop, onDismiss = close) { done ->
            SheetTitle(stringResource(R.string.action_add_playlist))
            state.playlists.forEach { p ->
                SheetItem(
                    label = p.name,
                    icon = R.drawable.ic_vap_playlist,
                    trailing = p.itemCount.toString(),
                    onClick = { viewModel.addToPlaylist(p.id, overlay.files); done() },
                )
            }
            SheetItem(
                stringResource(R.string.playlist_new),
                icon = R.drawable.ic_vap_plus,
                onClick = { onChange(Overlay.NewPlaylist(overlay.files)) },
            )
        }

        is Overlay.NewPlaylist -> NameSheet(
            backdrop = backdrop,
            title = stringResource(R.string.playlist_new),
            initial = "",
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { name -> viewModel.createPlaylist(name, overlay.files); close() },
            onDismiss = close,
        )

        is Overlay.RenamePlaylist -> NameSheet(
            backdrop = backdrop,
            title = stringResource(R.string.playlist_rename),
            initial = overlay.current,
            confirmLabel = stringResource(R.string.playlist_save),
            onConfirm = { name -> viewModel.renamePlaylist(overlay.id, name); close() },
            onDismiss = close,
        )

        is Overlay.PlaylistMenu -> {
            val p = overlay.playlist
            GlassSheet(backdrop, onDismiss = close) { done ->
                SheetTitle(p.name)
                SheetItem(
                    stringResource(R.string.action_play),
                    icon = R.drawable.ic_vap_play,
                    onClick = {
                        done()
                        scope.launch {
                            val entries = viewModel.playlistItems(p.id).first()
                            if (entries.isNotEmpty()) onPlay(viewModel.toPlayable(entries), 0)
                        }
                    },
                )
                SheetItem(stringResource(R.string.playlist_rename), icon = R.drawable.ic_vap_edit, onClick = { onChange(Overlay.RenamePlaylist(p.id, p.name)) })
                SheetItem(
                    stringResource(R.string.playlist_delete),
                    icon = R.drawable.ic_vap_trash,
                    destructive = true,
                    onClick = { viewModel.deletePlaylist(p.id); done() },
                )
            }
        }

        Overlay.QueueMenu -> GlassSheet(backdrop, onDismiss = close) { done ->
            SheetTitle(stringResource(R.string.queue_title))
            SheetItem(
                stringResource(R.string.queue_clear),
                icon = R.drawable.ic_vap_trash,
                destructive = true,
                onClick = { viewModel.clearQueue(); done() },
            )
        }
    }
}

private fun arrow(descending: Boolean) = if (descending) "↓" else "↑"

private fun folderSortLabel(key: FolderSortKey): Int = when (key) {
    FolderSortKey.NAME -> R.string.sort_name
    FolderSortKey.DATE -> R.string.sort_date
    FolderSortKey.SIZE -> R.string.sort_size
    FolderSortKey.COUNT -> R.string.sort_count
}

private fun fileSortLabel(key: FileSortKey): Int = when (key) {
    FileSortKey.NAME -> R.string.sort_name
    FileSortKey.DATE -> R.string.sort_date
    FileSortKey.SIZE -> R.string.sort_size
    FileSortKey.DURATION -> R.string.sort_duration
}

// ——— Главный экран ———

@Composable
private fun LibraryHome(
    state: LibraryUiState,
    folders: List<VideoFolder>,
    title: String,
    showContinue: Boolean,
    showAddTile: Boolean,
    morph: FolderMorph,
    cardSheets: (VideoFolder) -> List<VideoFile>,
    onOpen: (VideoFolder) -> Unit,
    onFolderMenu: (VideoFolder) -> Unit,
    onAddFolder: () -> Unit,
    onRequestVideoAccess: () -> Unit,
    onRescan: () -> Unit,
    onTitleClick: () -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onSortClick: () -> Unit,
    onQuery: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onContinue: (ContinueItem) -> Unit,
) {
    if (!state.hasSources && !state.scanning) {
        EmptyLibrary(onAddFolder, onRequestVideoAccess)
        return
    }
    val settings = state.settings
    val continueItems = if (showContinue) state.continueItems else emptyList()

    @Composable
    fun Header() {
        Column {
            ScreenHeader(title = title, scanning = state.scanning, onRescan = onRescan, onTitleClick = onTitleClick)
            if (state.videoAccess != com.phnem.vap.library.VideoAccess.FULL) {
                Text(stringResource(if (state.videoAccess == com.phnem.vap.library.VideoAccess.PARTIAL) R.string.video_access_partial else R.string.video_access_denied),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                AccessCapsule(
                    stringResource(if (state.videoAccess == com.phnem.vap.library.VideoAccess.PARTIAL) R.string.video_access_change else R.string.video_access_allow),
                    onRequestVideoAccess, primary = false, modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.scanFailed) {
                Text(stringResource(R.string.video_scan_failed), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Число колонок по ширине окна: телефон — 2, планшеты и складные — больше (design-spec §3.2).
        val columns = maxOf(2, ((maxWidth - ScreenInset) / (160.dp + GridGap)).toInt())
        val contentPadding = PaddingValues(start = ScreenInset, end = ScreenInset, bottom = LocalLibraryBottomInset.current)
        val cardWidth = gridCellWidth(maxWidth, ScreenInset, GridGap, columns)

        if (settings.grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGap),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header() }
                item(span = { GridItemSpan(maxLineSpan) }) { FilterChips(settings.filter, onFilter, onSortClick) }
                if (continueItems.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { ContinueRow(continueItems, onContinue) }
                if (folders.isEmpty() && !state.scanning) item(span = { GridItemSpan(maxLineSpan) }) { NothingFound() }
                items(folders, key = { it.id }) { folder ->
                    val sheets = state.cardSheets[folder.id] ?: cardSheets(folder)
                    FolderCard(
                        folder = folder,
                        sheets = sheets,
                        morph = morph,
                        favorite = folder.id in state.data.favoriteFolders,
                        cardWidth = cardWidth,
                        onClick = { onOpen(folder) },
                        onLongClick = { onFolderMenu(folder) },
                    )
                }
                if (showAddTile) item { AddFolderTile(onAddFolder) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Header() }
                item { FilterChips(settings.filter, onFilter, onSortClick) }
                if (continueItems.isNotEmpty()) item { ContinueRow(continueItems, onContinue) }
                if (folders.isEmpty() && !state.scanning) item { NothingFound() }
                items(folders, key = { it.id }) { folder ->
                    FolderRow(folder, onClick = { onOpen(folder) }, onLongClick = { onFolderMenu(folder) })
                }
                if (showAddTile) item { AddFolderTile(onAddFolder, Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

/**
 * Заголовок экрана: крупный жирный гротеск (референс 1). С [onTitleClick] рядом стрелка: заголовок —
 * переключатель раздела. Справа значок «сканирование хранилища» (решение владельца №8), пока идёт
 * обход — с индикатором.
 */
@Composable
fun ScreenHeader(
    title: String,
    scanning: Boolean,
    onRescan: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .then(
                    if (onTitleClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTitleClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = ink,
                style = TextStyle(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp,
                    letterSpacing = (-0.4).sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onTitleClick != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    painterResource(R.drawable.ic_vap_chevron_down),
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (onRescan != null) {
            val description = stringResource(R.string.scan_storage)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRescan,
                    )
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (scanning) {
                    BubbleClusterLoader(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(painterResource(R.drawable.ic_vap_scan), contentDescription = null, tint = ink)
                }
            }
        }
    }
}

/** Чипы фильтра (design-spec §3.1): активный — контрастная заливка. В конце — чип сортировки. */
@Composable
fun FilterChips(current: LibraryFilter, onSelect: (LibraryFilter) -> Unit, onSortClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(stringResource(R.string.filter_all), current == LibraryFilter.ALL) { onSelect(LibraryFilter.ALL) }
        Chip(stringResource(R.string.filter_favorites), current == LibraryFilter.FAVORITES) { onSelect(LibraryFilter.FAVORITES) }
        Chip(stringResource(R.string.filter_unwatched), current == LibraryFilter.UNWATCHED) { onSelect(LibraryFilter.UNWATCHED) }
        Chip(stringResource(R.string.filter_watched), current == LibraryFilter.WATCHED) { onSelect(LibraryFilter.WATCHED) }
        Chip(stringResource(R.string.sort), selected = false, icon = R.drawable.ic_vap_sort, onClick = onSortClick)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, icon: Int? = null, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    val bg = if (selected) ink else ink.copy(alpha = 0.08f)
    val fg = if (selected) MaterialTheme.colorScheme.background else ink
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, fontFamily = SnProFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
    }
}

/** «Продолжить просмотр»: начатые и не досмотренные видео, свежие первыми. */
@Composable
private fun ContinueRow(items: List<ContinueItem>, onClick: (ContinueItem) -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.continue_watching),
            color = ink.copy(alpha = 0.6f),
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.file.key }) { item -> ContinueCard(item) { onClick(item) } }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    val context = LocalContext.current
    val frame by produceState(ThumbnailCache.peek(item.file, ThumbTier.LARGE), item.file.thumbKey.id) { value = ThumbnailCache.frame(context, item.file, ThumbTier.LARGE) }
    val fraction = if (item.durationMs > 0) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    val left = ((item.durationMs - item.positionMs) / 60_000L).toInt().coerceAtLeast(1)
    Column(
        Modifier
            .width(220.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(ink.copy(alpha = 0.08f)),
        ) {
            frame?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.25f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.file.title,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.minutes_left, left),
            color = MaterialTheme.colorScheme.primary,
            fontFamily = SnProFamily,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun NothingFound() {
    Text(
        text = stringResource(R.string.nothing_found),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        fontFamily = SnProFamily,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

@Composable
private fun AddFolderTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.04f)
            .clip(shape)
            .border(1.5.dp, ink.copy(alpha = 0.18f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_vap_folder_plus),
            contentDescription = null,
            tint = ink.copy(alpha = 0.7f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.add_folder),
            color = ink.copy(alpha = 0.7f),
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderRow(folder: VideoFolder, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ink.copy(alpha = 0.05f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_vap_folder), contentDescription = null, tint = ink.copy(alpha = 0.7f))
        Spacer(Modifier.width(12.dp))
        Text(
            folder.name,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CountCapsule(folder.totalCount)
    }
}

/** Капсула действия на экране доступа: главная — оранжевая, вторичная — мягкая (как кнопки листов Collection). */
@Composable
private fun AccessCapsule(label: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    Box(
        modifier
            .clip(CircleShape)
            .background(if (primary) MaterialTheme.colorScheme.primary else ink.copy(alpha = 0.08f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { this.role = androidx.compose.ui.semantics.Role.Button }
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun EmptyLibrary(onAddFolder: () -> Unit, onRequestVideoAccess: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VapWordmark(color = MaterialTheme.colorScheme.primary, modifier = Modifier.height(56.dp))
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.empty_title),
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_body),
            color = ink.copy(alpha = 0.6f),
            fontFamily = SnProFamily,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        AccessCapsule(stringResource(R.string.video_access_allow), onRequestVideoAccess, primary = true)
        Spacer(Modifier.height(12.dp))
        AccessCapsule(stringResource(R.string.add_folder), onAddFolder, primary = false)
    }
}
