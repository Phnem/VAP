package com.phnem.vap.player.subtitles

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class EmbeddedFont(val name: String, val bytes: ByteArray)

/** Reads only Matroska attachment metadata/data, seeking over clusters without decoding video. */
object MatroskaFonts {
    suspend fun read(context: Context, uri: Uri): List<EmbeddedFont> = withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("file", "content"))
        val job = currentCoroutineContext()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val channel = stream.channel
            val length = channel.size()
            val reader = Reader(channel) { job.ensureActive() }
            val fonts = mutableListOf<EmbeddedFont>()
            var bytes = 0
            fun attachments(end: Long) {
                while (channel.position() < end && fonts.size < 16) {
                    val file = reader.element(end) ?: break
                    if (file.id == 0x61A7L) {
                        var name = ""; var mime = ""; var dataPosition = -1L; var dataSize = 0L
                        while (channel.position() < file.end) {
                            val field = reader.element(file.end) ?: break
                            val size = field.end - channel.position()
                            when (field.id) {
                                0x466EL -> if (size <= 512) name = reader.bytes(size.toInt()).toString(Charsets.UTF_8)
                                0x4660L -> if (size <= 128) mime = reader.bytes(size.toInt()).toString(Charsets.UTF_8)
                                0x465CL -> { dataPosition = channel.position(); dataSize = size }
                            }
                            reader.seek(field.end)
                        }
                        val isFont = name.substringAfterLast('.').lowercase() in setOf("ttf", "otf", "ttc") ||
                            mime in setOf("application/x-truetype-font", "application/x-font-ttf", "application/vnd.ms-opentype", "font/ttf", "font/otf", "font/sfnt")
                        if (isFont && dataPosition >= 0 && dataSize in 4..8_388_608 && bytes + dataSize <= 33_554_432) {
                            reader.seek(dataPosition)
                            val data = reader.bytes(dataSize.toInt())
                            val signature = ByteBuffer.wrap(data, 0, 4).int
                            if (signature in setOf(0x00010000, 0x4f54544f, 0x74746366, 0x74727565)) {
                                fonts += EmbeddedFont(name, data); bytes += data.size
                            }
                        }
                    }
                    reader.seek(file.end)
                }
            }
            while (channel.position() < length) {
                val root = reader.element(length) ?: break
                if (root.id == 0x18538067L) {
                    while (channel.position() < root.end) {
                        val child = reader.element(root.end) ?: break
                        if (child.id == 0x1941A469L) attachments(child.end)
                        reader.seek(child.end)
                    }
                    break
                }
                reader.seek(root.end)
            }
            fonts
        }
    }
    private data class Element(val id: Long, val end: Long)
    private class Reader(private val channel: FileChannel, private val active: () -> Unit) {
        private val single = ByteBuffer.allocate(1)
        fun seek(position: Long) { active(); channel.position(position) }
        private fun byte(): Int { active(); single.clear(); if (channel.read(single) != 1) throw EOFException(); return single.array()[0].toInt() and 255 }
        private fun vint(id: Boolean): Long? {
            val first = byte(); var marker = 128; var length = 1
            while (marker > 0 && first and marker == 0) { marker = marker ushr 1; length++ }
            require(marker > 0 && length <= if (id) 4 else 8) { "Invalid EBML element" }
            var value = if (id) first.toLong() else (first and (marker - 1)).toLong()
            var unknown = !id && value == (marker - 1).toLong()
            repeat(length - 1) { val next = byte(); value = (value shl 8) or next.toLong(); unknown = unknown && next == 255 }
            return if (unknown) null else value
        }
        fun element(parentEnd: Long): Element? {
            if (channel.position() >= parentEnd) return null
            val id = vint(true) ?: return null
            val size = vint(false)
            val start = channel.position()
            val end = if (size == null) parentEnd else start + size
            require(end >= start && end <= parentEnd) { "Invalid EBML size" }
            return Element(id, end)
        }
        fun bytes(count: Int): ByteArray {
            val result = ByteArray(count); val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) { active(); if (channel.read(buffer) < 0) throw EOFException() }
            return result
        }
    }
}
