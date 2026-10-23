package com.phnem.vap.player.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaExports {
    fun requireLocal(uri: Uri) { require(uri.scheme in setOf("content", "file")) { "Local video required" } }

    suspend fun frame(surface: SurfaceView, width: Int, height: Int): Bitmap = withContext(Dispatchers.Main) {
        require(width > 0 && height > 0 && width.toLong() * height <= 33_177_600) { "Video frame is not ready or exceeds 8K" }
        suspendCancellableCoroutine { cont ->
            val scale = minOf(1f, 4096f / maxOf(width, height))
            val bitmap = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(surface, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && cont.isActive) cont.resume(bitmap, onCancellation = { _, value, _ -> value.recycle() })
                    else {
                        bitmap.recycle()
                        if (cont.isActive) cont.resumeWithException(java.io.IOException("Frame capture failed ($result)"))
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (error: Exception) { bitmap.recycle(); if (cont.isActive) cont.resumeWithException(error) }
        }
    }

    suspend fun clip(context: Context, uri: Uri, range: ExportRange, picture: PictureSettings, output: File, progress: (Int?) -> Unit) = withContext(Dispatchers.Main) {
        requireLocal(uri)
        require(range.startMs >= 0 && range.endMs > range.startMs && range.endMs - range.startMs <= 120_000) { "MP4 range must be at most two minutes" }
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(range.startMs).setEndPositionMs(range.endMs).build()).build()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(),
            listOf(Presentation.createForHeight(720), PictureEffect(picture.sanitized())))).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(if (android.os.Build.VERSION.SDK_INT >= 29) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                else Composition.HDR_MODE_KEEP_HDR).build()
        coroutineScope {
            var transformer: Transformer? = null
            val polling = launch {
                val holder = ProgressHolder()
                while (true) {
                    val t = transformer
                    if (t != null) progress(if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                    delay(300)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val t = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setVideoFrameProcessorFactory(androidx.media3.effect.DefaultVideoFrameProcessor.Factory.Builder()
                            .setSdrWorkingColorSpace(androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_LINEAR).build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        }).build()
                    transformer = t
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
                    try { t.start(composition, output.absolutePath) }
                    catch (error: Exception) { if (cont.isActive) cont.resumeWithException(error) }
                }
            } finally { polling.cancel(); transformer?.cancel() }
        }
    }

    suspend fun gif(context: Context, uri: Uri, range: ExportRange, picture: PictureSettings, output: File, progress: (Int?) -> Unit) = withContext(Dispatchers.IO) {
        requireLocal(uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (android.os.Build.VERSION.SDK_INT < 27) {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                require(width * height <= 8_294_400) { "GIF sources above 4K require Android 8.1+" }
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(range.valid(duration, 15_000)) { "GIF range must be within the video and at most 15 seconds" }
            val count = ((range.endMs - range.startMs + 124) / 125).toInt()
            output.outputStream().buffered().use { stream ->
                var writer: GifWriter? = null
                var targetW = 0; var targetH = 0
                try {
                    repeat(count) { index ->
                        coroutineContext.ensureActive()
                        val timeUs = (range.startMs + index * 125L) * 1_000
                        val raw = if (android.os.Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 480)
                        else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        checkNotNull(raw) { "Cannot decode GIF frame" }
                        val scale = minOf(1f, 480f / maxOf(raw.width, raw.height))
                        if (writer == null) {
                            targetW = (raw.width * scale).roundToInt().coerceAtLeast(1)
                            targetH = (raw.height * scale).roundToInt().coerceAtLeast(1)
                            writer = GifWriter(stream, targetW, targetH)
                        }
                        val frame = if (raw.width == targetW && raw.height == targetH) raw else Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                        try {
                            val pixels = IntArray(targetW * targetH)
                            frame.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                            val settings = picture.sanitized()
                            if (!settings.neutral) pixels.indices.forEach { p -> pixels[p] = adjustPixel(pixels[p], settings) }
                            // Alternate 12/13 hundredths: exactly 8 frames per second.
                            val remaining = (range.endMs - (range.startMs + index * 125L)).coerceAtMost(125)
                            writer!!.frame(pixels, if (remaining < 125) (remaining / 10).toInt().coerceAtLeast(1) else if (index % 2 == 0) 12 else 13)
                        } finally { if (frame !== raw) frame.recycle(); raw.recycle() }
                        progress((index + 1) * 100 / count)
                    }
                } finally { writer?.close() }
            }
        } finally { retriever.release() }
    }

    private fun adjustPixel(pixel: Int, value: PictureSettings): Int {
        val r = (pixel ushr 16 and 255) / 255f; val g = (pixel ushr 8 and 255) / 255f; val b = (pixel and 255) / 255f
        val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
        fun channel(v: Float) = (((luma + (v - luma) * value.saturation - 0.5f) * value.contrast + 0.5f + value.brightness)
            .coerceIn(0f, 1f).pow(1f / value.gamma) * 255).roundToInt()
        return (0xff shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
