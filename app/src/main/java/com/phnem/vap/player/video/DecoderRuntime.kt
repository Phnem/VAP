package com.phnem.vap.player.video

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DecoderRuntime(private val player: ExoPlayer, private val policy: DecoderPolicy) {
    private val budget = DecoderRetryBudget()
    var decoder: String = "—"; private set
    var recoveries: Int = 0; private set
    var lastError: String = ""; private set
    private var picture = PictureSettings()
    private var effectPipelineUsed = false
    private var lastTransfer: Int? = null
    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (decoder == decoderName) decoder = "—"
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                val transfer = format.colorInfo?.colorTransfer ?: C.COLOR_TRANSFER_SDR
                val changed = lastTransfer != null && lastTransfer != transfer
                lastTransfer = transfer
                if (changed && effectPipelineUsed && !policy.toneMap) {
                    val item = player.currentMediaItem
                    android.os.Handler(player.applicationLooper).post {
                        if (player.currentMediaItem == item && player.playerError == null && lastTransfer == transfer) restart()
                    }
                }
            }
        })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                budget.reset(); policy.exclude(emptySet()); lastError = ""; recoveries = 0
            }
            override fun onPlayerError(error: PlaybackException) {
                lastError = error.errorCodeName
                val videoError = error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_RENDERER &&
                    error.rendererFormat?.sampleMimeType?.startsWith("video/") == true && error.errorCode in setOf(
                        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
                // Codec release analytics can arrive before the failure callback. The exception
                // still identifies the failed codec even when the active decoder is already empty.
                val failedCodec = when (val cause = error.cause) {
                    is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException -> cause.codecInfo?.name
                    is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name
                    else -> null
                } ?: decoder.takeUnless { it == "—" }
                if (!videoError || !budget.exclude(failedCodec)) return
                policy.exclude(budget.excluded())
                val mime = (error as ExoPlaybackException).rendererFormat?.sampleMimeType ?: return
                if (runCatching { policy.getDecoderInfos(mime, false, false).isEmpty() }.getOrDefault(true)) return
                recoveries++
                // Run after listeners have received the failure; position, playlist and intent survive.
                android.os.Handler(player.applicationLooper).post { if (player.playerError === error) restart() }
            }
        })
    }
    fun configure(mode: DecoderMode, forceSdr: Boolean, matchFrameRate: Boolean, value: PictureSettings) {
        val next = value.sanitized()
        val toneMap = android.os.Build.VERSION.SDK_INT >= 29 && (forceSdr || !next.neutral)
        val toneChanged = policy.toneMap != toneMap
        val rebuild = policy.mode != mode || toneChanged
        policy.mode = mode; policy.toneMap = toneMap
        player.setVideoChangeFrameRateStrategy(if (matchFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        if (picture != next || toneMap || effectPipelineUsed) {
            if (picture != next || !effectPipelineUsed || toneChanged) {
                picture = next
                // A new instance belongs to this playback pipeline only (never shared with export).
                player.setVideoEffects(if (!next.neutral || toneMap) listOf(PictureEffect(next)) else emptyList())
                effectPipelineUsed = true
                if (!rebuild && !player.isPlaying && player.playbackState == Player.STATE_READY)
                    player.setVideoEffects(androidx.media3.common.VideoFrameProcessor.REDRAW)
            }
        }
        if (rebuild) {
            budget.reset(); policy.exclude(emptySet()); lastError = ""; recoveries = 0
            if (player.playbackState != Player.STATE_IDLE || player.playerError != null) restart()
        }
    }
    fun retry() { budget.reset(); policy.exclude(emptySet()); lastError = ""; restart() }
    private fun restart() {
        val position = player.currentPosition.coerceAtLeast(0)
        val intent = player.playWhenReady
        decoder = "—"
        player.stop(); player.seekTo(player.currentMediaItemIndex.coerceAtLeast(0), position); player.prepare()
        player.playWhenReady = intent
    }
    fun info() = Bundle().apply {
        putString("decoder", decoder)
        putString("decoderMode", policy.mode.name)
        putInt("recoveries", recoveries)
        putString("error", lastError)
        putBoolean("toneMap", policy.toneMap)
        putBoolean("matchFrameRate", player.videoChangeFrameRateStrategy != C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        val videoGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        putBoolean("unsupportedVideo", videoGroups.isNotEmpty() && videoGroups.none { group -> (0 until group.length).any { group.isTrackSupported(it) } })
        (player.videoFormat ?: videoGroups.firstOrNull()?.getTrackFormat(0))?.let { f ->
            putString("mime", f.sampleMimeType); putString("codecs", f.codecs); putString("container", f.containerMimeType)
            putInt("width", f.width); putInt("height", f.height); putFloat("fps", f.frameRate)
            putInt("bitrate", f.bitrate)
            putBoolean("hdr", androidx.media3.common.ColorInfo.isTransferHdr(f.colorInfo))
            putInt("transfer", f.colorInfo?.colorTransfer ?: -1)
        }
        putInt("dropped", player.videoDecoderCounters?.droppedBufferCount ?: 0)
    }
}
