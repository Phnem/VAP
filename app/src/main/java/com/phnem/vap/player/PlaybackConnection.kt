package com.phnem.vap.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.phnem.vap.player.model.PlayableItem

fun PlayableItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri)
    .setUri(uri)
    .setMimeType(mimeType)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setExtras(server?.bundle()).build())
    .build()

object PlaybackConnection {
    fun connect(context: Context) = MediaController.Builder(
        context.applicationContext,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    /** Вызывается с видимой библиотеки. Не запускает пустое воспроизведение. */
    fun appendIfActive(context: Context, items: List<PlayableItem>) {
        val future = connect(context)
        future.addListener({
            try {
                val controller = future.get()
                if (controller.mediaItemCount > 0) controller.addMediaItems(items.map { it.toMediaItem() })
            } catch (error: Exception) {
                android.util.Log.w("VapPlayback", "Cannot append to active queue", error)
            } finally {
                MediaController.releaseFuture(future)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
