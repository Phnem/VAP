package com.phnem.vap.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.phnem.vap.data.VapData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.phnem.vap.player.video.DecoderPolicy
import com.phnem.vap.player.video.DecoderRuntime
import com.phnem.vap.player.video.HdrOutput
import com.phnem.vap.player.video.VideoCommands
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.phnem.vap.player.settings.PlayerPreferences
import org.json.JSONArray
import org.json.JSONObject
import com.phnem.vap.player.audio.*
import com.phnem.vap.player.subtitles.*

/** Движок, медиасессия и история продолжают жить при закрытии UI и выключении экрана. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private val decoderPolicy = DecoderPolicy()
    private lateinit var decoderRuntime: DecoderRuntime
    private lateinit var audioRuntime: AudioRuntime
    private lateinit var networkRecovery: com.phnem.vap.network.NetworkRecovery
    private lateinit var serverProgress: com.phnem.vap.network.ServerProgress
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Очередь одного потока сохраняет порядок снимков при быстрых переходах между видео.
    private val writes = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var knownDuration = 0L
    private val currentUri = MutableStateFlow<String?>(null)
    private var subtitleSettings = SubtitleSettings()

    override fun onCreate() {
        super.onCreate()
        val audioProcessor = PlaybackAudioProcessor()
        val audioSources = AudioMediaSources(com.phnem.vap.network.NetworkPlayback.sources(this))
        player = PlayerFactory.create(this, decoderPolicy, audioProcessor = audioProcessor, sourceFactory = audioSources)
        decoderRuntime = DecoderRuntime(player, decoderPolicy)
        audioRuntime = AudioRuntime(this, player, audioProcessor, audioSources)
        networkRecovery = com.phnem.vap.network.NetworkRecovery(player)
        serverProgress = com.phnem.vap.network.ServerProgress(this, player, enabled = { !com.phnem.vap.cast.RemoteRuntime.casting() })
        restoreSession()
        val openPlayer = PendingIntent.getActivity(this, 0,
            Intent(this, PlayerActivity::class.java).setAction(ACTION_RESUME)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(openPlayer).setCallback(object : MediaSession.Callback {
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                val own = controller.uid == applicationInfo.uid && controller.packageName == packageName
                return Futures.immediateFuture(if (own) mediaItems else mediaItems.map { item ->
                    val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { remove(com.phnem.vap.network.ServerPlaybackRef.EXTRA) }
                    item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                })
            }
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                if (controller.uid == applicationInfo.uid && controller.packageName == packageName) {
                    commands.add(VideoCommands.INFO).add(VideoCommands.RETRY)
                    commands.add(AudioCommands.INFO).add(AudioCommands.ROUTE)
                }
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands.build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.uid != applicationInfo.uid || controller.packageName != packageName)
                    return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED))
                return when (command.customAction) {
                    AudioCommands.INFO.customAction -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, audioRuntime.info()))
                    AudioCommands.ROUTE.customAction -> Futures.immediateFuture(SessionResult(
                        if (audioRuntime.route(args.getInt("id"))) SessionResult.RESULT_SUCCESS else androidx.media3.session.SessionError.ERROR_BAD_VALUE))
                    VideoCommands.INFO.customAction -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, decoderRuntime.info()))
                    VideoCommands.RETRY.customAction -> {
                        decoderRuntime.retry()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    else -> Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                }
            }
        }).build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { player.applySubtitleSelection(subtitleSettings) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentUri.value = mediaItem?.localConfiguration?.uri?.toString()
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                record(old.mediaItem, old.positionMs, knownDuration)
                knownDuration = player.duration.takeIf { it > 0 } ?: 0L
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (player.duration > 0) knownDuration = player.duration
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) saveProgress()
            }
        })
        currentUri.value = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val preferences = PlayerPreferences(this)
        scope.launch {
            currentUri.filterNotNull().collectLatest { uri ->
                preferences.fileSettings(uri).map { it.subtitles }.distinctUntilChanged().collect {
                    if (player.currentMediaItem?.localConfiguration?.uri?.toString() == uri) {
                        subtitleSettings = it
                        player.applySubtitleSelection(it)
                    }
                }
            }
        }
        scope.launch {
            currentUri.filterNotNull().collectLatest { uri ->
                combine(preferences.settings, preferences.fileSettings(uri)) { global, file -> global to file }
                    .distinctUntilChanged().collect { (global, file) ->
                        if (player.currentMediaItem?.localConfiguration?.uri?.toString() == uri)
                            audioRuntime.configure(uri, global, file)
                    }
            }
        }
        scope.launch {
            preferences.settings.collect { player.pauseAtEndOfMediaItems = !it.autoNext }
        }
        scope.launch {
            currentUri.filterNotNull().collectLatest { uri ->
                preferences.fileSettings(uri).map { it.speed }.distinctUntilChanged().collect { speed ->
                    if (player.currentMediaItem?.localConfiguration?.uri?.toString() == uri) {
                        player.setPlaybackSpeed(speed)
                    }
                }
            }
        }
        scope.launch {
            currentUri.filterNotNull().collectLatest { uri ->
                combine(preferences.settings, preferences.fileSettings(uri)) { global, file -> global to file.picture }
                    .distinctUntilChanged().collect { (global, picture) ->
                        if (player.currentMediaItem?.localConfiguration?.uri?.toString() == uri)
                            decoderRuntime.configure(global.decoderMode, global.hdrOutput == HdrOutput.SDR, global.matchFrameRate, picture)
                    }
            }
        }
        scope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) saveProgress()
                serverProgress.sample()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            pauseAllPlayersAndStopSelf()
        }
    }

    private fun record(item: MediaItem?, position: Long, duration: Long) {
        val uri = item?.localConfiguration?.uri?.toString() ?: return
        if (duration <= 0 || position < 0) return
        val title = item.mediaMetadata.title?.toString().orEmpty()
        writes.execute { VapData.history(this).record(uri, title, position, duration) }
    }

    private fun saveProgress() {
        record(player.currentMediaItem, player.currentPosition, player.duration)
        val items = JSONArray()
        repeat(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            items.put(JSONObject().put("uri", item.localConfiguration?.uri.toString())
                .put("mime", item.localConfiguration?.mimeType.orEmpty())
                .put("server", item.mediaMetadata.extras?.getString(com.phnem.vap.network.ServerPlaybackRef.EXTRA).orEmpty())
                .put("title", item.mediaMetadata.title?.toString().orEmpty()))
        }
        val snapshot = JSONObject().put("items", items)
            .put("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .put("position", player.currentPosition.coerceAtLeast(0))
            .put("speed", player.playbackParameters.speed).toString()
        writes.execute { getSharedPreferences("playback", MODE_PRIVATE).edit().putString("session", snapshot).commit() }
    }

    private fun restoreSession() {
        runCatching {
            val raw = getSharedPreferences("playback", MODE_PRIVATE).getString("session", null) ?: return
            val saved = JSONObject(raw)
            val array = saved.getJSONArray("items")
            val items = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                com.phnem.vap.player.model.PlayableItem(item.getString("uri"), item.optString("title"), item.optString("mime").ifBlank { null }, com.phnem.vap.network.ServerPlaybackRef.parse(item.optString("server"), forceRefresh = true)).toMediaItem()
            }
            if (items.isNotEmpty()) {
                player.setMediaItems(items, saved.optInt("index").coerceIn(items.indices), saved.optLong("position").coerceAtLeast(0))
                player.setPlaybackSpeed(saved.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f))
                // Восстановление очереди не включает звук без действия пользователя.
                player.playWhenReady = false
            }
        }.onFailure { android.util.Log.w("VapPlayback", "Cannot restore session", it) }
    }

    override fun onDestroy() {
        saveProgress()
        scope.cancel()
        session?.release()
        session = null
        audioRuntime.release()
        networkRecovery.release()
        serverProgress.release()
        player.release()
        writes.shutdown()
        super.onDestroy()
    }

    companion object { const val ACTION_RESUME = "com.phnem.vap.player.RESUME" }
}
