package com.phnem.vap.player.audio

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.concurrent.ConcurrentHashMap

/** External audio replaces embedded audio; removing it restores all embedded tracks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioMediaSources(private val delegate: MediaSource.Factory) : MediaSource.Factory {
    val external = ConcurrentHashMap<String, String>()
    override fun getSupportedTypes() = delegate.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply { delegate.setDrmSessionManagerProvider(provider) }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply { delegate.setLoadErrorHandlingPolicy(policy) }
    override fun createMediaSource(item: MediaItem): MediaSource {
        val original = delegate.createMediaSource(item)
        val uri = item.localConfiguration?.uri?.toString()?.let { external[it] } ?: return original
        require(android.net.Uri.parse(uri).scheme in setOf("content", "file")) { "External audio must be a local file" }
        return MergingMediaSource(false, false,
            FilteringMediaSource(original, setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA)),
            FilteringMediaSource(delegate.createMediaSource(MediaItem.fromUri(uri)), C.TRACK_TYPE_AUDIO))
    }
}
