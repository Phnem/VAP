package com.phnem.vap.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phnem.vap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Только локальные URI; один retriever за раз, ограниченный кэш, ключевые кадры около таймкода. */
object SeekFrameLoader {
    private val gate = Mutex()
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, uri: String, timeMs: Long): Bitmap? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content" && parsed.scheme != "file") return null
        val bucket = timeMs.coerceAtLeast(0) / 1_000
        val key = "$uri@$bucket"
        return gate.withLock {
            cache.get(key)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context.applicationContext, parsed)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val atMs = (bucket * 1_000).coerceAtMost((duration ?: Long.MAX_VALUE).coerceAtLeast(1) - 1)
                    val atUs = atMs.coerceIn(0, Long.MAX_VALUE / 1_000) * 1_000
                    val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    } else {
                        retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
                            val scale = minOf(320f / raw.width, 180f / raw.height, 1f)
                            if (scale == 1f) raw else Bitmap.createScaledBitmap(raw,
                                (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
                                .also { raw.recycle() }
                        }
                    }
                    bitmap?.also { cache.put(key, it) }
                } catch (_: RuntimeException) { null }
                catch (_: java.io.IOException) { null }
                finally { runCatching { retriever.release() } }
            }
        }
    }
}

@Composable
fun SeekFramePreview(uri: String, timeMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bucket = timeMs.coerceAtLeast(0) / 1_000
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, bucket) {
        delay(90)
        bitmap = SeekFrameLoader.load(context, uri, bucket * 1_000)
    }
    Box(modifier.width(144.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
        .background(Color.Black.copy(alpha = 0.7f))) {
        bitmap?.let {
            Image(it.asImageBitmap(), stringResource(R.string.player_preview_keyframe),
                modifier = Modifier.matchParentSize().testTag("player_seek_frame"), contentScale = ContentScale.Fit)
        }
    }
}
