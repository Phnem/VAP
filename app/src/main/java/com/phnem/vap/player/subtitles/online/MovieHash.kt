package com.phnem.vap.player.subtitles.online

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MovieHash {
    fun sum(size: Long, first: ByteArray, last: ByteArray): String {
        require(size >= 131072 && first.size == 65536 && last.size == 65536)
        var hash = size
        for (bytes in listOf(first, last)) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) hash += buffer.long
        }
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }
    suspend fun read(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("file", "content"))
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val channel = stream.channel; val size = channel.size()
            if (size < 131072) return@withContext null
            val blocks = listOf(0L, size - 65536).map { offset ->
                channel.position(offset); val buffer = ByteBuffer.allocate(65536)
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) error("Truncated video")
                buffer.array()
            }
            sum(size, blocks[0], blocks[1])
        }
    }
}
