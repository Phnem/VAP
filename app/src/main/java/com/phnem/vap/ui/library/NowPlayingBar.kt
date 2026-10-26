package com.phnem.vap.ui.library

import android.content.Intent
import android.view.LayoutInflater
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.phnem.vap.R
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.PlaybackService
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.theme.MotionTokens
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MiniLandscapeWidth = 220.dp
private val MiniPortraitWidth = 132.dp
private val MiniMargin = 12.dp

/**
 * Мини-плеер: маленькое окно с видео поверх библиотеки, как «картинка в картинке», только внутри
 * приложения. Таскается пальцем и прилипает к левому или правому краю; тап разворачивает плеер, кнопка
 * в центре — пауза, крестик — остановить и убрать. Звук и позиция живут в сервисе, окно лишь показывает
 * его кадр, поэтому «свернуть» в плеере не прерывает воспроизведение.
 */
@Composable
fun NowPlayingBar(modifier: Modifier = Modifier, onVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var hasItems by remember { mutableStateOf(false) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
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
                videoSize = player.videoSize
            }
        }
        future.addListener({
            if (!disposed) runCatching {
                controller = future.get().also {
                    it.addListener(listener)
                    title = it.mediaMetadata.title?.toString().orEmpty()
                    playing = it.isPlaying
                    hasItems = it.mediaItemCount > 0
                    videoSize = it.videoSize
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
    val active = controller ?: return
    MiniWindow(
        controller = active,
        playing = playing,
        videoSize = videoSize,
        onExpand = {
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .setAction(PlaybackService.ACTION_RESUME)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        },
        onTogglePlay = {
            if (active.playWhenReady) active.pause() else {
                if (active.playbackState == Player.STATE_IDLE) active.prepare()
                if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
                active.play()
            }
        },
        onClose = {
            active.stop()
            active.clearMediaItems()
        },
        modifier = modifier,
    )
}

@Composable
private fun MiniWindow(
    controller: MediaController,
    playing: Boolean,
    videoSize: VideoSize,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(18.dp)

    // Окно подстраивается под кадр: вертикальное видео — узкое высокое, остальные — широкое.
    val ratio = if (videoSize.width > 0 && videoSize.height > 0) {
        (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(0.5f, 2.2f)
    } else {
        16f / 9f
    }
    val width = if (ratio >= 1f) MiniLandscapeWidth else MiniPortraitWidth
    val height = width / ratio
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }
    val marginPx = with(density) { MiniMargin.toPx() }
    val topInset = WindowInsets.statusBars.getTop(density)
    val bottomInset = WindowInsets.navigationBars.getBottom(density)

    var area by remember { mutableStateOf(IntSize.Zero) }
    var ready by remember { mutableStateOf(false) }
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    fun minX() = marginPx
    fun maxX() = (area.width - widthPx - marginPx).coerceAtLeast(minX())
    fun minY() = topInset + marginPx
    fun maxY() = (area.height - heightPx - bottomInset - marginPx).coerceAtLeast(minY())

    LaunchedEffect(area, widthPx, heightPx) {
        if (area == IntSize.Zero) return@LaunchedEffect
        if (!ready) {
            // Первое появление: правый нижний угол над доком.
            x.snapTo(maxX())
            y.snapTo((maxY() - with(density) { 96.dp.toPx() }).coerceAtLeast(minY()))
            ready = true
        } else {
            x.snapTo(x.value.coerceIn(minX(), maxX()))
            y.snapTo(y.value.coerceIn(minY(), maxY()))
        }
    }

    // Картинка рисуется на TextureView: он скругляется и сдвигается вместе с окном, в отличие от SurfaceView.
    val playerView = remember(context) {
        LayoutInflater.from(context).inflate(R.layout.vap_mini_player_view, null) as PlayerView
    }
    // Кадр показывается, пока библиотека на экране; на время плеера или ухода в фон поверхность отдаётся ему.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller, playerView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> playerView.player = controller
                Lifecycle.Event.ON_STOP -> playerView.player = null
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playerView.player = controller
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerView.player = null
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { area = it }) {
        if (!ready) return@Box
        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                .size(width, height)
                .shadow(12.dp, shape)
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                .pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) }
                .pointerInput(area, widthPx, heightPx) {
                    detectDragGestures(
                        onDragEnd = { scope.launch { x.animateTo(if (x.value + widthPx / 2 < area.width / 2f) minX() else maxX(), MotionTokens.standard()) } },
                        onDragCancel = { scope.launch { x.animateTo(if (x.value + widthPx / 2 < area.width / 2f) minX() else maxX(), MotionTokens.standard()) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch {
                            x.snapTo((x.value + drag.x).coerceIn(minX(), maxX()))
                            y.snapTo((y.value + drag.y).coerceIn(minY(), maxY()))
                        }
                    }
                },
        ) {
            AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
            MiniButton(
                icon = R.drawable.ic_vap_close,
                description = stringResource(R.string.close),
                size = 26,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                onClick = onClose,
            )
            MiniButton(
                icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                description = stringResource(if (playing) R.string.player_pause else R.string.action_play),
                size = 40,
                modifier = Modifier.align(Alignment.Center),
                onClick = onTogglePlay,
            )
        }
    }
}

@Composable
private fun MiniButton(icon: Int, description: String, size: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, Modifier.size((size * 0.55f).dp), tint = Color.White)
    }
}
