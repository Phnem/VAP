package com.phnem.vap.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.R
import com.phnem.vap.data.PlaylistSummary
import com.phnem.vap.data.QueueEntry
import com.phnem.vap.library.LibraryUiState
import com.phnem.vap.library.LibraryViewModel
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.ui.theme.SnProFamily
import kotlinx.coroutines.flow.flowOf

/**
 * Раздел «Плейлисты» (4.2.4, 4.2.5): очередь воспроизведения, плейлисты и содержимое выбранного.
 * [openId]: [NO_PLAYLIST] — список, [QUEUE_ID] — очередь, иначе идентификатор плейлиста.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    state: LibraryUiState,
    openId: Long,
    viewModel: LibraryViewModel,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onSettings: () -> Unit,
    onMenu: (Overlay) -> Unit,
    onPlay: (List<PlayableItem>, Int) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val padding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalLibraryBottomInset.current)

    if (openId == NO_PLAYLIST) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.scope_playlists),
                    onSettings = onSettings,
                    onTitleClick = onTitleClick,
                )
            }
            item {
                ListRow(
                    icon = R.drawable.ic_vap_queue,
                    title = stringResource(R.string.queue_title),
                    count = state.queue.size,
                    onClick = { onOpen(QUEUE_ID) },
                    onLongClick = { onMenu(Overlay.QueueMenu) },
                )
            }
            items(state.playlists.size) { index ->
                val p = state.playlists[index]
                ListRow(
                    icon = R.drawable.ic_vap_playlist,
                    title = p.name,
                    count = p.itemCount,
                    onClick = { onOpen(p.id) },
                    onLongClick = { onMenu(Overlay.PlaylistMenu(p)) },
                )
            }
            if (state.playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.playlists_empty),
                        color = ink.copy(alpha = 0.5f),
                        fontFamily = SnProFamily,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onMenu(Overlay.NewPlaylist(emptyList())) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_vap_plus), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.playlist_new),
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
        return
    }

    // Содержимое выбранного плейлиста или очереди.
    val isQueue = openId == QUEUE_ID
    val playlist: PlaylistSummary? = state.playlists.firstOrNull { it.id == openId }
    val entriesFlow = remember(openId, isQueue) {
        if (isQueue) flowOf(null) else viewModel.playlistItems(openId)
    }
    val playlistEntries by entriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val entries: List<QueueEntry> = if (isQueue) state.queue else playlistEntries.orEmpty()
    val title = if (isQueue) stringResource(R.string.queue_title) else playlist?.name.orEmpty()
    val backDescription = stringResource(R.string.back)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenHeader(
                title = title,
                onSettings = null,
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
        if (entries.isNotEmpty()) {
            item {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPlay(viewModel.toPlayable(entries), 0) }
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_vap_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.action_play),
                            color = Color.White,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }
                    if (isQueue) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.queue_clear),
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { viewModel.clearQueue() },
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    stringResource(if (isQueue) R.string.queue_empty else R.string.playlist_empty),
                    color = ink.copy(alpha = 0.5f),
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
            }
        }
        itemsIndexed(entries, key = { _, e -> "${e.position}:${e.uri}" }) { index, entry ->
            val remove = stringResource(R.string.remove)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ink.copy(alpha = 0.05f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPlay(viewModel.toPlayable(entries), index) }
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.title.ifBlank { entry.uri.substringAfterLast('/') },
                    color = ink,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (isQueue) viewModel.removeFromQueue(entry.position) else viewModel.removeFromPlaylist(openId, entry.position)
                        }
                        .semantics { contentDescription = remove },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_vap_close), contentDescription = null, tint = ink.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(icon: Int, title: String, count: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ink.copy(alpha = 0.7f))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CountCapsule(count)
    }
}
