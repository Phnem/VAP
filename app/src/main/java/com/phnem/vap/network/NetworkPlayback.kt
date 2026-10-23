package com.phnem.vap.network

import android.content.Context
import android.os.Handler
import com.phnem.vap.player.toMediaItem
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory

enum class NetworkBuffer(val minMs: Int, val maxMs: Int, val startMs: Int, val resumeMs: Int) {
    LOW(3_000, 10_000, 500, 1_000), NORMAL(15_000, 30_000, 1_000, 2_000), LARGE(60_000, 90_000, 2_000, 6_000);
    companion object {
        fun read(context: Context) = entries.firstOrNull { it.name == context.getSharedPreferences("network_options", 0).getString("buffer", null) } ?: NORMAL
        fun save(context: Context, value: NetworkBuffer) { context.getSharedPreferences("network_options", 0).edit().putString("buffer", value.name).apply() }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NetworkPlayback {
    fun sources(context: Context): androidx.media3.exoplayer.source.MediaSource.Factory = ScopedSources(context)
    private fun factory(context: Context, connectionId: String? = null) = DefaultMediaSourceFactory(context, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
        .setLoadErrorHandlingPolicy(NetworkLoadErrors()).setDataSourceFactory(DataSourceFactory(context, connectionId))
    private class DataSourceFactory(private val context: Context, connectionId: String?) : androidx.media3.datasource.DataSource.Factory {
        private val ordinary = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(NetworkHttp.playback(context, connectionId)).setUserAgent("VAP"))
        override fun createDataSource() = NetworkDataSource(context, ordinary)
    }
    private class ScopedSources(private val context: Context) : androidx.media3.exoplayer.source.MediaSource.Factory {
        private val ordinary = factory(context)
        private var drm: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null
        private var errors: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy = NetworkLoadErrors()
        override fun getSupportedTypes() = ordinary.supportedTypes
        override fun setDrmSessionManagerProvider(provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): androidx.media3.exoplayer.source.MediaSource.Factory = apply { drm = provider; ordinary.setDrmSessionManagerProvider(provider) }
        override fun setLoadErrorHandlingPolicy(policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy): androidx.media3.exoplayer.source.MediaSource.Factory = apply { errors = policy; ordinary.setLoadErrorHandlingPolicy(policy) }
        override fun createMediaSource(item: MediaItem): androidx.media3.exoplayer.source.MediaSource {
            val ref = ServerPlaybackRef.parse(item.mediaMetadata.extras?.getString(ServerPlaybackRef.EXTRA)) ?: return ordinary.createMediaSource(item)
            val selected = factory(context, ref.connectionId).apply { setLoadErrorHandlingPolicy(errors); drm?.let(::setDrmSessionManagerProvider) }
            if (!ref.refresh) return selected.createMediaSource(item)
            return ServerMediaSource(item, {
                val connection = NetworkConnectionStore(context).list().firstOrNull { it.id == ref.connectionId && it.isServer }
                    ?: throw java.io.IOException("Server connection removed")
                ServerClient().play(connection, NetworkEntry(ServerClient.folder(connection.id, ref.itemId), item.mediaMetadata.title?.toString().orEmpty(), false, null, serverItemId = ref.itemId)).toMediaItem()
            }, selected::createMediaSource)
        }
    }
}

/** Vetro's large-buffer tuning, with a hard byte budget and live settings changes. Local defaults stay delegated to Media3. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkLoadControl(private val context: Context) : DefaultLoadControl() {
    private var loading = true
    private val byteLimit = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(4L * 1024 * 1024)
    private fun remote(p: LoadControl.Parameters): Boolean {
        if (p.timeline.isEmpty) return false
        if (p.timeline.getIndexOfPeriod(p.mediaPeriodId.periodUid) == C.INDEX_UNSET) return false
        val period = p.timeline.getPeriodByUid(p.mediaPeriodId.periodUid, androidx.media3.common.Timeline.Period())
        return NetworkUrls.isNetwork(p.timeline.getWindow(period.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem.localConfiguration?.uri?.toString())
    }
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val ordinary = super.shouldContinueLoading(parameters)
        if (!remote(parameters)) return ordinary
        val profile = NetworkBuffer.read(context)
        val minUs = (profile.minMs * 1000L * parameters.playbackSpeed.coerceAtLeast(1f)).toLong().coerceAtMost(profile.maxMs * 1000L)
        if (super.getAllocator(parameters.playerId).totalBytesAllocated >= byteLimit) loading = false
        else if (parameters.bufferedDurationUs < minUs) loading = true
        else if (parameters.bufferedDurationUs >= profile.maxMs * 1000L) loading = false
        return loading
    }
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!remote(parameters)) return super.shouldStartPlayback(parameters)
        val profile = NetworkBuffer.read(context)
        var required = (if (parameters.rebuffering) profile.resumeMs else profile.startMs) * 1000L
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) required = minOf(required, parameters.targetLiveOffsetUs / 2)
        return parameters.bufferedDurationUs / parameters.playbackSpeed >= required || super.getAllocator(parameters.playerId).totalBytesAllocated >= byteLimit
    }
}

/** Bounded retry adapted from Vetro's recovery policy; permanent HTTP errors require user action. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkRecovery(private val player: ExoPlayer) : Player.Listener {
    private val handler = Handler(player.applicationLooper)
    private var attempts = 0
    private var generation = 0
    init { player.addListener(this) }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { attempts = 0; generation++ }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) { val current = generation; handler.postDelayed({ if (current == generation && player.isPlaying) attempts = 0 }, 20_000) }
    }
    override fun onPlayerError(error: PlaybackException) {
        if (!NetworkUrls.isNetwork(player.currentMediaItem?.localConfiguration?.uri?.toString()) || attempts >= 3 || NetworkFailures.permanent(error)) return
        val status = generateSequence(error as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (status in setOf(400, 401, 403, 404, 405, 410, 416) || error.errorCode !in setOf(PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)) return
        val current = generation; val position = player.currentPosition.coerceAtLeast(0); val intent = player.playWhenReady
        val delayMs = 1000L shl attempts++
        handler.postDelayed({
            if (current == generation && player.playerError === error && player.playWhenReady == intent) {
                player.seekTo(player.currentMediaItemIndex, position); player.prepare(); player.playWhenReady = intent
            }
        }, delayMs)
    }
    fun release() { generation++; handler.removeCallbacksAndMessages(null); player.removeListener(this) }
}

