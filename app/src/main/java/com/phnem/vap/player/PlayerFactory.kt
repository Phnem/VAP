package com.phnem.vap.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import com.phnem.vap.player.video.DecoderPolicy

/** Shared engine construction for the service and real-codec device tests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerFactory {
    fun create(context: Context, policy: DecoderPolicy = DecoderPolicy(), codecFactory: MediaCodecAdapter.Factory? = null,
        audioProcessor: com.phnem.vap.player.audio.PlaybackAudioProcessor = com.phnem.vap.player.audio.PlaybackAudioProcessor(),
        sourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory? = null): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(false)
                    .setAudioProcessors(arrayOf(audioProcessor)).build()
                // Encoded passthrough/offload bypasses PCM processing. Decode all audio to PCM.
                return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
                    override fun supportsFormat(format: androidx.media3.common.Format) =
                        format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW && super.supportsFormat(format)
                    override fun getFormatSupport(format: androidx.media3.common.Format) =
                        if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                        else androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED
                }
            }
            override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean, eventHandler: Handler,
                eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
                val builder = MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(policy).setEnableDecoderFallback(true)
                    .setCodecAdapterFactory(codecFactory ?: getCodecAdapterFactory())
                    .setEventHandler(eventHandler).setEventListener(eventListener)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs).setMaxDroppedFramesToNotify(50)
                out.add(VapVideoRenderer(builder, policy))
            }
        }
        return ExoPlayer.Builder(context.applicationContext, renderers)
            .setLoadControl(com.phnem.vap.network.NetworkLoadControl(context.applicationContext))
            .setMediaSourceFactory(sourceFactory ?: DefaultMediaSourceFactory(context.applicationContext,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
    }

    // Media3 1.11.1 exposes no public playback tone-map control. Keep this version-pinned bridge
    // isolated; its protected callback returns this library-internal type. Revalidate on upgrades.
    @android.annotation.SuppressLint("RestrictedApi")
    private class VapVideoRenderer(builder: MediaCodecVideoRenderer.Builder, private val policy: DecoderPolicy) : MediaCodecVideoRenderer(builder) {
        private var graph: PlaybackVideoGraphWrapper? = null
        private val sinkField = MediaCodecVideoRenderer::class.java.getDeclaredField("videoSink").apply { isAccessible = true }
        // stop/prepare must re-evaluate the raw/GL path and output color space, not reuse its old sink.
        override fun onDisabled() { try { super.onDisabled() } finally { reset() } }
        override fun onReset() {
            try { super.onReset() } finally {
                if (graph != null) {
                    graph?.release(); graph = null
                    // 1.11.1 keeps a released sink across reset. Clear our owned sink so onEnabled
                    // creates a graph with the new output color space. Kept explicitly for R8.
                    sinkField.set(this, null)
                }
            }
        }
        override fun createPlaybackVideoGraphWrapper(context: Context, videoFrameReleaseControl: VideoFrameReleaseControl): PlaybackVideoGraphWrapper =
            PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl).setEnablePlaylistMode(true)
                .setVideoGraphFactory(androidx.media3.effect.SingleInputVideoGraph.Factory(
                    androidx.media3.effect.DefaultVideoFrameProcessor.Factory.Builder()
                        .setSdrWorkingColorSpace(androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_LINEAR)
                        .setEnableReplayableCache(true).build()))
                .setEnableReplayableCache(true).setClock(getClock()).build().also {
                    it.setRequestOpenGlToneMapping(policy.toneMap); graph = it
                }
    }
}
