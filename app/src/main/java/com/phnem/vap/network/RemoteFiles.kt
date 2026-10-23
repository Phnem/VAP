package com.phnem.vap.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.Closeable
import java.io.IOException
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.TimeUnit

interface RemoteReader : Closeable {
    val size: Long?
    fun read(position: Long, target: ByteArray, offset: Int, count: Int): Int
}

/** Read-only SMB2/3, SFTP and FTP access. File handles are random-access or reopened with REST. */
object RemoteFiles {
    suspend fun list(connection: NetworkConnection, directory: String): List<NetworkEntry> = withContext(Dispatchers.IO) {
        val address = checked(connection, directory).directory()
        val entries = when(connection.kind) {
            NetworkKind.SMB -> resources { scope ->
                val share = smb(connection, address, scope)
                share.list(address.segments.drop(1).joinToString("\\")).map { item ->
                    val folder = item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    Triple(item.fileName, folder, item.endOfFile)
                }
            }
            NetworkKind.SFTP -> resources { scope ->
                val ssh = ssh(connection, address, scope); val sftp = ssh.newSFTPClient().also { scope.add { it.close() } }
                sftp.ls(address.path).filter { it.isDirectory || it.isRegularFile }.map { Triple(it.name, it.isDirectory, it.attributes.size) }
            }
            NetworkKind.FTP -> resources { scope ->
                val ftp = ftp(connection, address, scope)
                val listed = ftp.mlistDir(address.path)
                val items = if (FTPReply.isPositiveCompletion(ftp.replyCode)) listed else ftp.listFiles(address.path)
                check(FTPReply.isPositiveCompletion(ftp.replyCode)) { "FTP listing failed" }
                items.filter { it.isDirectory || it.isFile }.map { Triple(it.name, it.isDirectory, it.size) }
            }
            else -> error("Unsupported file protocol")
        }
        require(entries.size <= 10000)
        entries.mapNotNull { (name, folder, size) ->
            if (name in setOf(".", "..") || name.any { it in "/\\\u0000\r\n" } || (!folder && !WebDavBrowser.isVideo(name))) null
            else NetworkEntry(address.child(name, folder).url, name.take(250), folder, size.takeIf { it >= 0 })
        }.sortedWith(compareByDescending<NetworkEntry> { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    fun open(connection: NetworkConnection, url: String): RemoteReader {
        val address = checked(connection, url)
        val scope = Resources()
        try {
            val reader = when(connection.kind) {
                NetworkKind.SMB -> {
                    val share = smb(connection, address, scope)
                    val file = share.openFile(address.segments.drop(1).joinToString("\\"), EnumSet.of(AccessMask.GENERIC_READ), null,
                        EnumSet.allOf(SMB2ShareAccess::class.java), SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
                    scope.add { file.close() }
                    val length = file.getFileInformation(FileStandardInformation::class.java).endOfFile
                    object : RemoteReader {
                        override val size = length
                        override fun read(position: Long, target: ByteArray, offset: Int, count: Int) = file.read(target, position, offset, minOf(count, 65536))
                        override fun close() = scope.close()
                    }
                }
                NetworkKind.SFTP -> {
                    val ssh = ssh(connection, address, scope); val sftp = ssh.newSFTPClient().also { scope.add { it.close() } }
                    val file = sftp.open(address.path, EnumSet.of(OpenMode.READ)).also { scope.add { it.close() } }
                    val length = file.length()
                    object : RemoteReader {
                        override val size = length
                        override fun read(position: Long, target: ByteArray, offset: Int, count: Int) = file.read(position, target, offset, minOf(count, 32768))
                        override fun close() = scope.close()
                    }
                }
                NetworkKind.FTP -> {
                    val ftp = ftp(connection, address, scope)
                    val length = ftp.mlistFile(address.path)?.size?.takeIf { it >= 0 }
                    var stream: java.io.InputStream? = null; var next = -1L
                    scope.add { stream?.close(); stream = null }
                    object : RemoteReader {
                        override val size = length
                        override fun read(position: Long, target: ByteArray, offset: Int, count: Int): Int {
                            if (next != position || stream == null) {
                                if (stream != null) { stream?.close(); stream = null; check(ftp.completePendingCommand()) }
                                ftp.restartOffset = position
                                stream = ftp.retrieveFileStream(address.path) ?: throw IOException("FTP retrieve rejected")
                                next = position
                            }
                            val read = stream!!.read(target, offset, count)
                            if (read > 0) next += read
                            return read
                        }
                        override fun close() = scope.close()
                    }
                }
                else -> error("Unsupported file protocol")
            }
            return reader
        } catch (e: Exception) { scope.close(); throw IOException("Cannot open network file", e) }
    }
    private fun checked(connection: NetworkConnection, value: String): RemoteAddress {
        val address = RemoteAddress.parse(value)
        require(RemoteAddress.parse(connection.url).directory().contains(address))
        return address
    }
    private fun smb(connection: NetworkConnection, address: RemoteAddress, scope: Resources): DiskShare {
        val client = SMBClient(SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).withSoTimeout(15000)
            .withSigningRequired(true).withDfsEnabled(false).withAuthenticators(listOf(com.hierynomus.smbj.auth.NtlmAuthenticator.Factory())).build()).also { scope.add { it.close() } }
        val transport = client.connect(address.host, address.port).also { scope.add { it.close() } }
        val domain = connection.username.substringBefore('\\', "")
        val user = connection.username.substringAfter('\\')
        val session = try { transport.authenticate(AuthenticationContext(user, connection.password.toCharArray(), domain)) }
            catch (e: com.hierynomus.mssmb2.SMBApiException) { throw NetworkAuthFailure(e) }
        scope.add { session.close() }
        val share = session.connectShare(address.segments.first()) as? DiskShare ?: error("Not a disk share")
        scope.add { share.close() }; return share
    }
    private fun ssh(connection: NetworkConnection, address: RemoteAddress, scope: Resources): SSHClient {
        require(connection.hostKey.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?")))
        SshCrypto.prepare()
        val ssh = SSHClient().also { scope.add { it.close() } }
        ssh.connectTimeout = 12000; ssh.timeout = 15000
        ssh.addHostKeyVerifier(connection.hostKey)
        ssh.connect(address.host, address.port); ssh.authPassword(connection.username, connection.password)
        return ssh
    }
    private fun ftp(connection: NetworkConnection, address: RemoteAddress, scope: Resources): FTPClient {
        val ftp = FTPClient().also { client -> scope.add { if (client.isConnected) client.disconnect() } }
        ftp.controlEncoding = "UTF-8"; ftp.connectTimeout = 12000; ftp.defaultTimeout = 15000; ftp.setDataTimeout(Duration.ofSeconds(15))
        ftp.setIpAddressFromPasvResponse(false); ftp.setUseEPSVwithIPv4(true)
        ftp.connect(address.host, address.port); check(FTPReply.isPositiveCompletion(ftp.replyCode))
        if (!ftp.login(connection.username.ifBlank { "anonymous" }, connection.password.ifBlank { if (connection.username.isBlank()) "vap@localhost" else "" })) throw NetworkAuthFailure()
        ftp.enterLocalPassiveMode(); check(ftp.setFileType(FTP.BINARY_FILE_TYPE))
        return ftp
    }
    private inline fun <T> resources(action: (Resources) -> T): T = Resources().use(action)
    private class Resources : Closeable {
        private val cleanup = ArrayDeque<() -> Unit>()
        fun add(close: () -> Unit) { cleanup.addFirst(close) }
        override fun close() { while (cleanup.isNotEmpty()) runCatching { cleanup.removeFirst().invoke() } }
    }
}
