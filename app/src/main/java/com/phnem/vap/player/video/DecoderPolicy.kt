package com.phnem.vap.player.video

import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.concurrent.CopyOnWriteArraySet

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DecoderPolicy : MediaCodecSelector {
    @Volatile var mode = DecoderMode.AUTO
    @Volatile var toneMap = false
    private val excluded = CopyOnWriteArraySet<String>()
    fun exclude(names: Set<String>) { excluded.clear(); excluded.addAll(names) }
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean) =
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .filter { info -> !mimeType.startsWith("video/") || (info.name !in excluded && when (mode) {
                DecoderMode.AUTO -> true
                DecoderMode.HARDWARE -> info.hardwareAccelerated
                DecoderMode.SOFTWARE -> info.softwareOnly
            }) }.sortedBy { if (it.softwareOnly) 1 else 0 }
}
