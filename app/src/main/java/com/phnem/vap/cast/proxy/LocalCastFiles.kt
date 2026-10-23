package com.phnem.vap.cast.proxy

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.phnem.vap.network.NetworkDataSource
import com.phnem.vap.network.RemoteReader

/** Reads only the URI registered by the casting session; supports seekable SAF/MediaStore files. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalCastFiles(private val context: Context) {
    fun open(url: String): RemoteReader {
        require(Uri.parse(url).scheme in setOf("content", "file", "smb", "sftp", "ftp"))
        val data = NetworkDataSource(context, DefaultDataSource.Factory(context))
        val initial = data.open(DataSpec(Uri.parse(url)))
        return object : RemoteReader {
            override val size: Long? = initial.takeIf { it != C.LENGTH_UNSET.toLong() }
            private var position = 0L
            override fun read(position: Long, target: ByteArray, offset: Int, count: Int): Int {
                if (this.position != position) {
                    data.close(); data.open(DataSpec.Builder().setUri(url).setPosition(position).build()); this.position = position
                }
                return data.read(target, offset, count).also { if (it > 0) this.position += it }
            }
            override fun close() = data.close()
        }
    }
}
