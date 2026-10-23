package com.phnem.vap.network

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Selects the protocol per request, including adaptive child segments. Local sources use Media3. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkDataSource(private val context: Context, private val ordinary: DataSource.Factory) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null)
        val next = if (dataSpec.uri.scheme in setOf("smb", "ftp", "sftp")) RemoteDataSource(context) else ordinary.createDataSource()
        delegate = next; listeners.forEach(next::addTransferListener)
        try { return next.open(dataSpec) } catch (e: Exception) { close(); throw IOException("Cannot open media source", e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = requireNotNull(delegate).read(buffer, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { val old = delegate; delegate = null; old?.close() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class RemoteDataSource(private val context: Context) : BaseDataSource(true) {
    private var reader: RemoteReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val address = RemoteAddress.parse(dataSpec.uri.toString())
        val connection = runBlocking { NetworkConnectionStore(context).list() }.filter { it.kind in setOf(NetworkKind.SMB, NetworkKind.FTP, NetworkKind.SFTP) &&
            RemoteAddress.parse(it.url).directory().contains(address) }.maxByOrNull { it.url.length } ?: throw IOException("Save this server connection first")
        reader = RemoteFiles.open(connection, address.url); uri = dataSpec.uri; position = dataSpec.position
        val length = reader!!.size
        if (length != null && position > length) throw IOException("Position beyond remote file")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length?.minus(position) ?: C.LENGTH_UNSET.toLong()
        started = true; transferStarted(dataSpec); return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length
        val read = reader!!.read(position, buffer, offset, count)
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read; if (remaining >= 0) remaining -= read
        bytesTransferred(read); return read
    }
    override fun getUri() = uri
    override fun close() { try { reader?.close() } finally { reader = null; uri = null; if (started) { started = false; transferEnded() } } }
}
