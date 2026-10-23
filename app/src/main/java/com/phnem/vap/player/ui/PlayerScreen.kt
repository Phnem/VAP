package com.phnem.vap.player.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import com.phnem.vap.ui.components.PanelButton
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.phnem.vap.player.pip.PipHostActivity
import com.phnem.vap.player.pip.PipPlaybackCommands
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phnem.vap.player.settings.PlaybackSettingsViewModel
import com.phnem.vap.player.subtitles.subtitleChoices

/** Один вариант аудиодорожки для выбора (например «Русский», «English», «Japanese»). */
data class AudioTrackOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
    val renditionUrl: String? = null,
)

/**
 * Пункт меню субтитров: выключить, встроенная дорожка ([groupIndex]/[trackIndex]) или внешняя, которую
 * ещё надо скачать ([externalKey]).
 */
data class SubtitleOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val externalKey: String? = null,
    val isOff: Boolean = false,
    /** Переход в раздел или настройка — меню остаётся открытым. */
    val keepsMenuOpen: Boolean = false,
    /** Действие для вызывающего (раздел меню, Whisper); null — обычная дорожка. */
    val action: String? = null,
)

/**
 * Полноэкранный проигрыватель на androidx.media3 (ExoPlayer) с полностью кастомным скином:
 * плоские доки + белая полоса с оранжевым бегунком (см. [PlayerControlsOverlay]).
 * Стандартный контроллер [PlayerView] отключён (`useController = false`) — все кнопки наши,
 * никаких дефолтных +5/−5.
 *
 * Показывает общий Player сервиса; жизненный цикл экрана не освобождает движок.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun PlayerScreen(
    exoPlayer: Player,
    isInPip: Boolean,
    onEnterPip: () -> Unit,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: PlaybackSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val remote = remember(context) { com.phnem.vap.cast.RemoteRuntime.get(context) }
    val remoteConnection by remote.manager.connection.collectAsStateWithLifecycle()
    val remoteMedia by remote.manager.media.collectAsStateWithLifecycle()
    var remoteOpen by remember { mutableStateOf(false) }
    val casting = remoteMedia != null

    // ——— Наблюдаемое состояние плеера ———
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var isBuffering by remember { mutableStateOf(exoPlayer.playbackState == Player.STATE_BUFFERING) }
    // Позицию хост не читает: см. PlaybackClock.
    val clock = rememberPlaybackClock(exoPlayer, isPlaying)
    var currentIndex by remember { mutableIntStateOf(exoPlayer.currentMediaItemIndex.coerceAtLeast(0)) }

    val pinchState = remember { PlayerPinchState() }
    var unsupportedVideo by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf(exoPlayer.currentTracks.extractAudioOptions()) }
    // Выбор для файла хранится в DataStore; метка сеанса — запасной выбор для следующего видео.
    var preferredAudioLabel by remember { mutableStateOf<String?>(null) }
    var currentUri by remember { mutableStateOf(exoPlayer.currentMediaItem?.mediaId.orEmpty()) }
    var subtitlePreview by remember(currentUri) { mutableStateOf<com.phnem.vap.player.subtitles.SubtitleSettings?>(null) }
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val storedFile by settingsViewModel.file.collectAsStateWithLifecycle()
    val fileSettings = storedFile?.takeIf { it.uri == currentUri }?.value
    val speed = fileSettings?.speed ?: exoPlayer.playbackParameters.speed
    LaunchedEffect(currentUri) {
        settingsViewModel.selectFile(currentUri)
        if (currentUri.isNotBlank()) settingsViewModel.discoverSubtitles(currentUri)
        exoPlayer.currentTracks.subtitleChoices().firstOrNull { it.selected && it.ass }?.let {
            settingsViewModel.prepareStyledEmbedded(currentUri, it.trackId)
        }
    }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.saveFailures.collect {
            android.widget.Toast.makeText(context, com.phnem.vap.R.string.player_settings_save_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // Режим кадра восстанавливается по URI, независимо от порядка видео в очереди.
    val fit = fileSettings?.fit ?: VideoFit.FIT
    var settingsOpen by remember { mutableStateOf(false) }
    var timecodeOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var subtitleRevision by remember { mutableIntStateOf(0) }
    var nativePlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var fitOpen by remember { mutableStateOf(false) }
    var orientationOpen by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var videoSize by remember { mutableStateOf(exoPlayer.videoSize) }
    val viewport = remember(currentUri, fit) { PlayerViewportState() }
    val geometry = videoGeometry(viewportSize.width, viewportSize.height,
        videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio, fit)
    val nativeFrame = videoGeometry(viewportSize.width, viewportSize.height,
        videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio, VideoFit.FIT)
    val density = LocalDensity.current
    SideEffect { viewport.updateGeometry(geometry) }
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation
        onDispose { if (originalOrientation != null) activity.requestedOrientation = originalOrientation }
    }
    LaunchedEffect(activity, settings.orientation, isInPip) {
        if (!isInPip) activity?.requestedOrientation = settings.orientation.requested
    }
    var playbackFailed by remember { mutableStateOf(exoPlayer.playerError != null) }
    var itemCount by remember { mutableIntStateOf(exoPlayer.mediaItemCount) }
    var title by remember { mutableStateOf(exoPlayer.mediaMetadata.title?.toString().orEmpty()) }


    DisposableEffect(exoPlayer) {
        fun syncItem() {
            currentUri = exoPlayer.currentMediaItem?.mediaId.orEmpty()
            currentIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
            itemCount = exoPlayer.mediaItemCount
            title = (exoPlayer.mediaMetadata.title ?: exoPlayer.currentMediaItem?.mediaMetadata?.title)?.toString().orEmpty()
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) syncItem()
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackFailed = false
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("LocalPlayer", "Playback failed: ${error.errorCodeName}", error)
                isBuffering = false
                playbackFailed = true
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                itemCount = exoPlayer.mediaItemCount
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentUri = mediaItem?.mediaId.orEmpty()
                exoPlayer.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { videoSize = it }
                title = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                currentIndex = exoPlayer.currentMediaItemIndex
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracks.subtitleChoices().firstOrNull { it.selected && it.ass }?.let {
                    settingsViewModel.prepareStyledEmbedded(currentUri, it.trackId)
                }
                audioTracks = tracks.extractAudioOptions()
                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                unsupportedVideo = videoGroups.isNotEmpty() && videoGroups.none { group -> (0 until group.length).any { group.isTrackSupported(it) } }
                videoGroups.firstOrNull { it.isSelected }?.let { group ->
                    val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
                    val format = group.getTrackFormat(index)
                    if (format.width > 0 && format.height > 0) {
                        val rotate = format.rotationDegrees == 90 || format.rotationDegrees == 270
                        videoSize = VideoSize(if (rotate) format.height else format.width,
                            if (rotate) format.width else format.height,
                            if (rotate) 1f / format.pixelWidthHeightRatio else format.pixelWidthHeightRatio)
                    }
                }
                // Новая серия ExoPlayer подбирает дорожку сама — если пользователь уже выбирал
                // дорожку раньше в этом сеансе, переносим её выбор на серию, если такая есть.
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                // Decoder replacement briefly reports UNKNOWN. Keep the native surface aspect stable.
                if (size.width > 0 && size.height > 0) videoSize = size
            }
        }
        exoPlayer.addListener(listener)
        // A controller may deliver its first timeline between composition and listener registration.
        syncItem()
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // PiP: в окне «картинка в картинке» наш оверлей скрыт, поэтому перемотку по сериям и паузу
    // рисует система — по этому описанию состояния.
    val pipHost = remember(context) {
        var candidate: android.content.Context? = context
        var found: PipHostActivity? = null
        while (candidate is android.content.ContextWrapper) {
            if (candidate is PipHostActivity) { found = candidate; break }
            candidate = candidate.baseContext
        }
        found
    }
    androidx.compose.runtime.LaunchedEffect(
        pipHost,
        exoPlayer,
        isPlaying,
        currentIndex,
        itemCount,
        videoSize.width,
        videoSize.height,
    ) {
        pipHost?.updatePipCommands(
            PipPlaybackCommands(
                isPlaying = isPlaying,
                hasPrevious = currentIndex > 0,
                hasNext = currentIndex < (itemCount - 1),
                // Кнопка «следующая» в PiP значит «включи следующую»: при выключенном автопереходе
                // плеер остановлен на последнем кадре, и одна перемотка оставила бы его на паузе.
                onPrevious = { exoPlayer.seekToPreviousMediaItem(); exoPlayer.play() },
                onPlayPause = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onNext = { exoPlayer.seekToNextMediaItem(); exoPlayer.play() },
                videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt(),
                videoHeight = videoSize.height,
            )
        )
    }
    // Уход с экрана плеера снимает кнопки: Activity живёт дольше, и осиротевшие команды дёргали бы
    // отпущенный ExoPlayer.
    DisposableEffect(pipHost) {
        onDispose { pipHost?.updatePipCommands(null) }
    }

    // Полный экран: статус-бар и навигация уезжают, пока виден кадр (в PiP окно и так без баров).
    ImmersivePlayerWindow(enabled = !isInPip)

    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize().background(Color.Black).clipToBounds().onSizeChanged { viewportSize = it },
    ) {
        AndroidView(
            modifier = Modifier
                .requiredSize(with(density) { nativeFrame.width.toDp() }, with(density) { nativeFrame.height.toDp() })
                .align(Alignment.Center)
                .graphicsLayer {
                    val transform = if (isInPip) VideoTransform() else viewport.transform
                    val frame = if (isInPip) videoGeometry(viewportSize.width, viewportSize.height,
                        videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio, VideoFit.FIT) else geometry
                    scaleX = frame.width / nativeFrame.width * transform.zoom
                    scaleY = frame.height / nativeFrame.height * transform.zoom
                    translationX = transform.x
                    translationY = transform.y
                },
            factory = { ctx ->
                // surface_type по умолчанию = SurfaceView: дешевле по батарее и композитингу, чем
                // TextureView.
                PlayerView(ctx).apply {
                    subtitleView?.visibility = android.view.View.GONE
                    nativePlayerView = this
                    player = exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                // Geometry applies aspect ratio once, outside the native fill surface.
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL

                // Пока идёт воспроизведение — не даём экрану гаснуть/затемняться.
                view.keepScreenOn = isPlaying
            },
            onRelease = { view -> nativePlayerView = null; view.player = null },
        )

        val subtitleSettings = subtitlePreview ?: fileSettings?.subtitles ?: com.phnem.vap.player.subtitles.SubtitleSettings()
        val assSource = subtitleSettings.sources.firstOrNull { it.id == subtitleSettings.selection && it.format == "ass" }
        var assFailed by remember(currentUri, assSource?.id) { mutableStateOf(false) }
        val styledPrimary = assSource != null && subtitleSettings.assNative && !assFailed && com.phnem.vap.player.subtitles.AssNative.available
        if (styledPrimary) AssStyledOverlay(exoPlayer, assSource!!, subtitleSettings,
            modifier = Modifier.requiredSize(with(density) { nativeFrame.width.toDp() }, with(density) { nativeFrame.height.toDp() })
                .align(Alignment.Center).graphicsLayer {
                    val transform = if (isInPip) VideoTransform() else viewport.transform
                    val frame = if (isInPip) videoGeometry(viewportSize.width, viewportSize.height,
                        videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio, VideoFit.FIT) else geometry
                    scaleX = frame.width / nativeFrame.width * transform.zoom
                    scaleY = frame.height / nativeFrame.height * transform.zoom
                    translationX = transform.x; translationY = transform.y
                }, onFailure = {
                    assFailed = true
                    android.widget.Toast.makeText(context, com.phnem.vap.R.string.sub_ass_failed, android.widget.Toast.LENGTH_LONG).show()
                })
        SubtitleOverlay(exoPlayer, currentUri, subtitleSettings, subtitleRevision, styledPrimary)

        if (playbackFailed || unsupportedVideo) {
            // Выше центра: по центру стоят кнопки управления, блок ошибки не должен их перекрывать.
            androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.BiasAlignment(0f, -0.5f)),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                com.phnem.vap.ui.components.PanelText(
                    androidx.compose.ui.res.stringResource(if (unsupportedVideo) com.phnem.vap.R.string.video_unsupported_track else com.phnem.vap.R.string.player_error_unsupported))
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PanelButton(androidx.compose.ui.res.stringResource(com.phnem.vap.R.string.video_retry), {
                        (exoPlayer as? androidx.media3.session.MediaController)?.sendCustomCommand(
                            com.phnem.vap.player.video.VideoCommands.RETRY, android.os.Bundle.EMPTY)
                    }, primary = true)
                    PanelButton(androidx.compose.ui.res.stringResource(com.phnem.vap.R.string.video_info), { toolsOpen = true })
                }
            }
        }

        // В режиме «картинка в картинке» все контролы прячем — остаётся только видео.
        if (!isInPip && !casting) {
            PlayerControlsOverlay(
                player = exoPlayer,
                remoteButton = if (remoteConnection is com.phnem.vap.cast.RemoteConnection.Failed) RemoteButtonState.ERROR else RemoteButtonState.IDLE,
                onRemote = { remoteOpen = true },
                title = title,
                onRotate = { orientationOpen = true },
                viewport = viewport,
                onOpenFit = { fitOpen = true },
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                position = clock.position,
                buffered = clock.buffered,
                duration = clock.durationMs,
                hasPrev = currentIndex > 0,
                hasNext = currentIndex < (itemCount - 1),
                pinchState = pinchState,
                audioTracks = audioTracks,
                speed = speed,
                onBack = onBack,
                onLibrary = onLibrary,
                settings = settings,
                previewUri = currentUri,
                onOpenSettings = { settingsOpen = true },
                onOpenTimecode = { timecodeOpen = true },
                modalOpen = remoteOpen || settingsOpen || timecodeOpen || fitOpen || orientationOpen || toolsOpen || audioOpen || subtitlesOpen,
                onOpenSubtitles = { subtitlePreview = null; subtitlesOpen = true },
                onOpenAudio = { subtitlePreview = null; audioOpen = true },
                onEnterPip = onEnterPip,
                onSelectSpeed = { s -> settingsViewModel.setSpeed(currentUri, s) },
                onSelectAudio = { opt ->
                    preferredAudioLabel = opt.label
                    settingsViewModel.setAudio(currentUri, opt.label)
                    exoPlayer.applyAudioOverride(opt)
                },
            )
        }
        if (!isInPip && !casting) subtitlePreview?.let { preview ->
            SyncPreviewBar(preview, onApply = {
                val current = fileSettings?.subtitles ?: com.phnem.vap.player.subtitles.SubtitleSettings()
                settingsViewModel.setSubtitles(currentUri, current.copy(selection = preview.selection,
                    secondary = current.secondary?.takeUnless { it == preview.selection }, delayMs = preview.delayMs, scale = preview.scale))
                subtitlePreview = null
            }, onCancel = { subtitlePreview = null }, modifier = Modifier.align(Alignment.TopCenter))
        }
        if (!isInPip && casting) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.Black), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(remoteMedia?.title.orEmpty(), color = Color.White)
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                PanelButton(androidx.compose.ui.res.stringResource(com.phnem.vap.R.string.cast_controls), { remoteOpen = true }, primary = true)
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                PanelButton(androidx.compose.ui.res.stringResource(com.phnem.vap.R.string.cast_leave), onBack)
            }
        }
        if (!isInPip && remoteOpen) com.phnem.vap.cast.ui.RemoteDevicesDialog(remote, onDismiss = { remoteOpen = false })
        if (!isInPip && subtitlesOpen) {
            androidx.compose.runtime.key(currentUri) {
                val subtitleUri = currentUri
                SubtitleToolsDialog(exoPlayer, subtitleUri, fileSettings?.subtitles ?: com.phnem.vap.player.subtitles.SubtitleSettings(),
                    onSettings = { settingsViewModel.setSubtitles(subtitleUri, it) },
                    onRemove = { settingsViewModel.removeSubtitle(subtitleUri, it) },
                    onEdited = { subtitleRevision++ }, onDismiss = { subtitlePreview = null; subtitlesOpen = false },
                    onPreview = { subtitlePreview = it; subtitlesOpen = false }, canPreview = !casting)
            }
        }
        if (!isInPip && fitOpen) {
            VideoFitDialog(fit, onSelect = { viewport.reset(); settingsViewModel.setFit(currentUri, it) }, onDismiss = { fitOpen = false })
        }
        if (!isInPip && audioOpen) {
            AudioToolsDialog(exoPlayer, currentUri, settings, fileSettings ?: com.phnem.vap.player.settings.FilePlaybackSettings(),
                settingsViewModel::updateSettings, { settingsViewModel.setAudioSettings(currentUri, it) },
                { settingsViewModel.setAudioKey(currentUri, it) }, settingsViewModel::setExternalAudio,
                onDismiss = { audioOpen = false })
        }
        if (!isInPip && orientationOpen) {
            OrientationDialog(settings.orientation, onSelect = { orientation ->
                settingsViewModel.updateSettings { it.copy(orientation = orientation) }
            }, onDismiss = { orientationOpen = false })
        }
        if (!isInPip && toolsOpen) {
            VideoToolsDialog(exoPlayer, currentUri, clock.durationMs, settings,
                fileSettings?.picture ?: com.phnem.vap.player.video.PictureSettings(), settingsViewModel::updateSettings,
                onPicture = { settingsViewModel.setPicture(currentUri, it) },
                onCapture = { model ->
                    (nativePlayerView?.videoSurfaceView as? android.view.SurfaceView)?.let { surface ->
                        model.capture(surface, videoSize.width, videoSize.height)
                    }
                }, canCapture = videoSize.width > 0 && videoSize.height > 0 && !isBuffering && !playbackFailed && !unsupportedVideo,
                onDismiss = { toolsOpen = false })
        }
        if (!isInPip && settingsOpen) {
            PlaybackOptionsDialog(settings, settingsViewModel::updateSettings, onDismiss = { settingsOpen = false },
                onVideoTools = { settingsOpen = false; toolsOpen = true })
        }
        if (!isInPip && timecodeOpen) {
            TimecodeDialog(durationMs = clock.durationMs, onSeek = { exoPlayer.seekTo(it) },
                onDismiss = { timecodeOpen = false })
        }
    }
}

/**
 * Дорожка новой серии, соответствующая ранее выбранной пользователем (по [label][AudioTrackOption.label]
 * — единственный устойчивый признак дорожки между сериями, `groupIndex`/`trackIndex` per-episode).
 * `null`, если выбора не было, подходящей дорожки нет, либо она и так уже выбрана.
 */
internal fun matchPreferredAudioTrack(
    preferredLabel: String?,
    options: List<AudioTrackOption>,
): AudioTrackOption? {
    if (preferredLabel == null) return null
    return options.firstOrNull { it.label == preferredLabel && !it.isSelected }
}

/** Достаёт список аудиодорожек из [Tracks] для меню выбора. */
private fun Tracks.extractAudioOptions(): List<AudioTrackOption> {
    val out = ArrayList<AudioTrackOption>()
    groups.forEachIndexed { gIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (t in 0 until group.length) {
            if (!group.isTrackSupported(t)) continue
            val format = group.getTrackFormat(t)
            val label = format.label
                ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
                ?: "Track ${out.size + 1}"
            out += AudioTrackOption(
                id = "$gIndex:$t",
                label = label.replaceFirstChar { it.uppercase() },
                isSelected = group.isTrackSelected(t),
                groupIndex = gIndex,
                trackIndex = t,
            )
        }
    }
    return out
}

/** Применяет выбор аудиодорожки через override параметров трек-селектора. */
private fun Player.applyAudioOverride(option: AudioTrackOption) {
    val group = currentTracks.groups.getOrNull(option.groupIndex) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
        )
        .build()
}
