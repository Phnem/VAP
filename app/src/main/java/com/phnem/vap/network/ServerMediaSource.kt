package com.phnem.vap.network

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.*
import java.io.IOException

/** Resolves a persisted server item only when playback is prepared, including queued items. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ServerMediaSource(
    private val original: MediaItem,
    private val resolve: suspend () -> MediaItem,
    private val create: (MediaItem) -> MediaSource,
) : CompositeMediaSource<Unit>() {
    private var child: MediaSource? = null
    private var failure: IOException? = null
    private var scope: CoroutineScope? = null
    private var generation = 0
    override fun getMediaItem(): MediaItem = child?.mediaItem ?: original
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        failure = null
        val current = ++generation
        val handler = Handler(Looper.myLooper()!!)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { owner ->
            owner.launch {
                try {
                    val fresh = resolve()
                    ensureActive()
                    handler.post {
                        if (current == generation) {
                            try { val source = create(fresh); child = source; prepareChildSource(Unit, source) }
                            catch (e: Exception) { failure = IOException("Cannot prepare server video", e) }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    handler.post { if (current == generation) failure = IOException("Cannot resolve server video", e) }
                }
            }
        }
    }
    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, newTimeline: Timeline) { refreshSourceInfo(newTimeline) }
    override fun maybeThrowSourceInfoRefreshError() { failure?.let { throw it }; super.maybeThrowSourceInfoRefreshError() }
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = checkNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod) { checkNotNull(child).releasePeriod(mediaPeriod) }
    override fun releaseSourceInternal() {
        generation++; scope?.cancel(); scope = null
        super.releaseSourceInternal()
        child = null; failure = null
    }
}
