package com.phnem.vap.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.R
import com.phnem.vap.library.FileMeta
import com.phnem.vap.library.LibraryFilter
import com.phnem.vap.library.LibraryProjector
import com.phnem.vap.library.ByteSizeText
import com.phnem.vap.library.traced
import com.phnem.vap.library.LibraryUiState
import com.phnem.vap.library.SeriesGrouping
import com.phnem.vap.library.ThumbTier
import com.phnem.vap.library.ThumbnailCache
import com.phnem.vap.library.VideoFile
import com.phnem.vap.library.VideoFolder
import com.phnem.vap.library.WatchInfo
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.ui.theme.SnProFamily
import java.util.Locale

private val ScreenInset = 16.dp
private val GridGap = 16.dp
private val WatchedGreen = Color(0xFF34C759)

/** Строка экрана папки: заголовок сезона, вложенная папка (режим дерева) или файл. */
private sealed interface Entry {
    val key: String

    data class Season(val season: Int?) : Entry {
        override val key get() = "s:$season"
    }

    data class Child(val folder: VideoFolder) : Entry {
        override val key get() = "c:${folder.id}"
    }

    data class FileItem(val file: VideoFile, val displayIndex: Int) : Entry {
        override val key get() = "f:${file.key}"
    }
}

/**
 * Экран открытой папки: вложенные папки (режим дерева), файлы по сезонам, фильтры, поиск, полоса и
 * отметка просмотра. Первые три карточки файлов — цели морфа: на них садятся листы с карточки папки.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    folder: VideoFolder,
    state: LibraryUiState,
    isRoot: Boolean,
    morph: FolderMorph,
    cardSheets: (VideoFolder) -> List<VideoFile>,
    onBack: () -> Unit,
    onOpenChild: (VideoFolder) -> Unit,
    onFolderMenu: (VideoFolder) -> Unit,
    onFileMenu: (VideoFile) -> Unit,
    onPlay: (List<PlayableItem>, Int) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onSortClick: () -> Unit,
    onQuery: (String) -> Unit,
    onCloseSearch: () -> Unit,
    ensureMeta: (VideoFile) -> Unit,
) {
    val data = state.data
    val settings = state.settings
    val ink = MaterialTheme.colorScheme.onBackground

    val files = remember(folder, data, settings.filter, settings.fileSort, state.query) {
        traced("VAP.fs.files") { LibraryProjector.files(folder, data, settings.filter, settings.fileSort, state.query) }
    }
    // Очередь плеера — вся папка, а не отфильтрованный список: после поиска «дальше» не должно
    // перепрыгивать через файлы.
    val ordered = remember(folder, data, settings.fileSort, files) {
        traced("VAP.fs.ordered") {
            if (settings.filter == LibraryFilter.ALL && state.query.isBlank()) {
                files
            } else {
                LibraryProjector.files(folder, data, LibraryFilter.ALL, settings.fileSort, "")
            }
        }
    }
    val queue = remember(ordered) { traced("VAP.fs.queue") { ordered.map { PlayableItem(it.key, it.title) } } }
    val queueIndex = remember(ordered) { traced("VAP.fs.queueIndex") { ordered.withIndex().associate { it.value.key to it.index } } }
    // Вложенные папки показываются только в режиме дерева; в плоском режиме они и так лежат
    // отдельными карточками на главном экране.
    val children = remember(folder, data, settings.treeMode, settings.filter, settings.folderSort, state.query) {
        if (settings.treeMode) {
            LibraryProjector.childFolders(folder, data, settings.filter, settings.folderSort, state.query)
        } else {
            emptyList()
        }
    }
    val sections = remember(files) { traced("VAP.fs.sections") { SeriesGrouping.group(files) { it.name } } }
    val entries = remember(children, sections) {
        traced("VAP.fs.entries") { buildList {
            children.forEach { add(Entry.Child(it)) }
            var displayIndex = 0
            val titled = sections.size > 1 || sections.firstOrNull()?.season != null
            sections.forEach { section ->
                if (titled) add(Entry.Season(section.season))
                section.files.forEach { add(Entry.FileItem(it, displayIndex++)) }
            }
        } }
    }
    val backDescription = stringResource(R.string.back)

    @Composable
    fun Header() {
        ScreenHeader(
            title = folder.name,
            scanning = false,
            onRescan = null,
            leading = {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ink.copy(alpha = 0.08f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack,
                        )
                        .semantics { contentDescription = backDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_player_back), contentDescription = null, tint = ink)
                }
            },
        )
    }

    @Composable
    fun Item(entry: Entry, cardWidth: Dp) {
        when (entry) {
            is Entry.Season -> SeasonHeader(entry.season)
            is Entry.Child -> {
                val c = entry.folder
                if (settings.grid) {
                    FolderCard(
                        folder = c,
                        sheets = state.cardSheets[c.id] ?: cardSheets(c),
                        morph = morph,
                        favorite = c.id in data.favoriteFolders,
                        cardWidth = cardWidth,
                        onClick = { onOpenChild(c) },
                        onLongClick = { onFolderMenu(c) },
                    )
                } else {
                    FolderRow(c, onClick = { onOpenChild(c) }, onLongClick = { onFolderMenu(c) })
                }
            }
            is Entry.FileItem -> {
                val file = entry.file
                LaunchedEffect(file.key) { ensureMeta(file) }
                val morphIndex = entry.displayIndex.takeIf { isRoot && it < MAX_SHEETS }
                val open = { onPlay(queue, queueIndex[file.key] ?: 0) }
                if (settings.grid) {
                    FileCard(
                        file = file,
                        meta = data.meta[file.key],
                        watch = data.history[file.key],
                        favorite = file.key in data.favoriteFiles,
                        morph = morph,
                        morphIndex = morphIndex,
                        onClick = open,
                        onLongClick = { onFileMenu(file) },
                    )
                } else {
                    FileRow(
                        file = file,
                        meta = data.meta[file.key],
                        watch = data.history[file.key],
                        favorite = file.key in data.favoriteFiles,
                        morph = morph,
                        morphIndex = morphIndex,
                        onClick = open,
                        onLongClick = { onFileMenu(file) },
                    )
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = maxOf(2, ((maxWidth - ScreenInset) / (160.dp + GridGap)).toInt())
        val padding = PaddingValues(start = ScreenInset, end = ScreenInset, bottom = LocalLibraryBottomInset.current)
        val cardWidth = gridCellWidth(maxWidth, ScreenInset, GridGap, columns)
        if (settings.grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGap),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header() }
                item(span = { GridItemSpan(maxLineSpan) }) { FilterChips(settings.filter, onFilter, onSortClick) }
                if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { NothingFound() }
                items(
                    entries,
                    key = { it.key },
                    span = { if (it is Entry.Season) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                ) { Item(it, cardWidth) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Header() }
                item { FilterChips(settings.filter, onFilter, onSortClick) }
                if (entries.isEmpty()) item { NothingFound() }
                items(entries, key = { it.key }) { Item(it, cardWidth) }
            }
        }
    }
}

@Composable
private fun SeasonHeader(season: Int?) {
    Text(
        text = if (season != null) stringResource(R.string.season_title, season) else stringResource(R.string.season_other),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun rememberFrame(file: VideoFile): Bitmap? {
    val context = LocalContext.current
    val frame by produceState(ThumbnailCache.peek(file, ThumbTier.GRID), file.thumbKey.id) { value = ThumbnailCache.frame(context, file, ThumbTier.GRID) }
    return frame
}

/**
 * Превью файла 16:9. [morphIndex] != null — это цель морфа: координаты пишутся для полёта, а сама
 * карточка прозрачна, пока вместо неё летит лист.
 */
@Composable
private fun Thumbnail(
    file: VideoFile,
    watch: WatchInfo?,
    favorite: Boolean,
    morph: FolderMorph,
    morphIndex: Int?,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val frame = rememberFrame(file)
    val fraction = watch?.takeIf { !it.watched && it.durationMs > 0 }
        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) } ?: 0f
    Box(
        modifier
            .then(
                if (morphIndex != null) {
                    Modifier
                        .onPlaced { morph.targetCoords[morphIndex] = it }
                        .graphicsLayer { alpha = if (morph.targetHidden(morphIndex)) 0f else 1f }
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .background(ink.copy(alpha = 0.08f)),
    ) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (fraction > 0.01f) {
            // Полоса просмотра: оранжевая, по нижней кромке.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.25f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary))
        }
        if (watch?.watched == true) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(WatchedGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_vap_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (favorite) {
            Icon(
                painter = painterResource(R.drawable.ic_vap_star),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: VideoFile,
    meta: FileMeta?,
    watch: WatchInfo?,
    favorite: Boolean,
    morph: FolderMorph,
    morphIndex: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Thumbnail(file, watch, favorite, morph, morphIndex, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(8.dp))
        Text(
            file.title,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MetaLine(file, meta, watch)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: VideoFile,
    meta: FileMeta?,
    watch: WatchInfo?,
    favorite: Boolean,
    morph: FolderMorph,
    morphIndex: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(file, watch, favorite, morph, morphIndex, Modifier.width(128.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                file.title,
                color = ink,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(file, meta, watch)
        }
    }
}

/** «42:10 · 1080p · 1,4 ГБ»: длительность и разрешение появляются, когда считаны. */
@Composable
private fun MetaLine(file: VideoFile, meta: FileMeta?, watch: WatchInfo?) {
    val context = LocalContext.current
    val text = remember(file.sizeBytes, meta) {
        buildList {
            meta?.durationMs?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
            meta?.let { resolutionLabel(it) }?.let(::add)
            add(ByteSizeText.format(context, file.sizeBytes))
        }.joinToString(" · ")
    }
    val left = watch?.takeIf { !it.watched && it.durationMs > 0 && it.positionMs > 0 }
        ?.let { ((it.durationMs - it.positionMs) / 60_000L).toInt().coerceAtLeast(1) }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        fontFamily = SnProFamily,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (left != null) {
        Text(
            text = stringResource(R.string.minutes_left, left),
            color = MaterialTheme.colorScheme.primary,
            fontFamily = SnProFamily,
            fontSize = 12.sp,
        )
    }
}

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

internal fun resolutionLabel(meta: FileMeta): String? {
    val side = minOf(meta.width, meta.height)
    return when {
        meta.width <= 0 || meta.height <= 0 -> null
        side >= 2000 -> "4K"
        side >= 1400 -> "1440p"
        side >= 1000 -> "1080p"
        side >= 700 -> "720p"
        side >= 460 -> "480p"
        else -> "${meta.width}×${meta.height}"
    }
}
