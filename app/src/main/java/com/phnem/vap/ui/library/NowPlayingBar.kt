package com.phnem.vap.ui.library

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxWidth
import com.phnem.vap.ui.components.PanelBody
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.kyant.backdrop.Backdrop
import com.phnem.vap.R
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.PlaybackService
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.ui.glass.FrostedMaterials
import com.phnem.vap.ui.glass.frostedGlass

/** Возврат к активной очереди без повторного открытия файла и сброса позиции. */
@Composable
fun NowPlayingBar(backdrop: Backdrop, modifier: Modifier = Modifier, onVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var hasItems by remember { mutableStateOf(false) }
    var resumeChecked by rememberSaveable { mutableStateOf(false) }
    var offerResume by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(context) {
        var disposed = false
        val future = PlaybackConnection.connect(context)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                title = player.mediaMetadata.title?.toString().orEmpty()
                playing = player.isPlaying
                hasItems = player.mediaItemCount > 0
            }
        }
        future.addListener({
            if (!disposed) runCatching {
                controller = future.get().also {
                    it.addListener(listener)
                    title = it.mediaMetadata.title?.toString().orEmpty()
                    playing = it.isPlaying
                    hasItems = it.mediaItemCount > 0
                    if (!resumeChecked) {
                        resumeChecked = true
                        offerResume = hasItems && !it.playWhenReady && it.currentPosition >= 5_000 &&
                            it.playbackState != Player.STATE_ENDED &&
                            (it.duration <= 0 || it.duration - it.currentPosition > maxOf(30_000L, it.duration / 20))
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            controller?.removeListener(listener)
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    LaunchedEffect(hasItems) { onVisibilityChanged(hasItems) }
    if (!hasItems) return
    // Решение «позже» фиксируется сразу (offerResume переживает пересоздание), а шторка дорисовывает уход.
    var offerSheet by remember { mutableStateOf(offerResume) }
    if (offerResume) offerSheet = true
    if (offerSheet) com.phnem.vap.ui.components.VapSheet({ offerSheet = false }, isDark = com.phnem.vap.ui.theme.isAppInDarkTheme(), immersive = false,
        onClosing = { offerResume = false }) { close ->
        com.phnem.vap.ui.components.PanelHeader(stringResource(R.string.resume_session_title))
        PanelBody { com.phnem.vap.ui.components.PanelText(title) }
        com.phnem.vap.ui.components.PanelFooter {
            com.phnem.vap.ui.components.PanelButton(stringResource(R.string.resume_session_action), {
                controller?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
                context.startActivity(Intent(context, PlayerActivity::class.java).setAction(PlaybackService.ACTION_RESUME))
                close()
            }, primary = true, modifier = Modifier.fillMaxWidth())
            com.phnem.vap.ui.components.PanelButton(stringResource(R.string.resume_session_later), close, modifier = Modifier.fillMaxWidth())
        }
    }
    Row(
        modifier.frostedGlass(backdrop, RoundedCornerShape(24.dp), FrostedMaterials.dock())
            .clickable {
                context.startActivity(Intent(context, PlayerActivity::class.java)
                    .setAction(PlaybackService.ACTION_RESUME)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }.padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Вне Surface цвет содержимого Material по умолчанию чёрный: на тёмной теме текст и значок
        // были чёрными по чёрному.
        val ink = MaterialTheme.colorScheme.onBackground
        Text(title.ifBlank { stringResource(R.string.app_name) }, Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = ink)
        IconButton(onClick = {
            controller?.let {
                if (it.playWhenReady) it.pause() else {
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                    it.play()
                }
            }
        }) {
            Icon(painterResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play),
                stringResource(if (playing) R.string.player_pause else R.string.action_play), Modifier.size(24.dp), tint = ink)
        }
    }
}
