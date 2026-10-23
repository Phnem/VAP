package com.phnem.vap.network

import java.net.URI

class RemoteAddress private constructor(private val uri: URI) {
    val scheme: String get() = uri.scheme
    val host: String get() = uri.host
    val port: Int get() = uri.port.takeIf { it > 0 } ?: when(scheme) { "smb" -> 445; "sftp" -> 22; else -> 21 }
    val path: String get() = uri.path.ifEmpty { "/" }
    val segments: List<String> get() = path.split('/').filter(String::isNotEmpty)
    val url: String get() = uri.toASCIIString()
    fun directory(): RemoteAddress = if (path.endsWith('/')) this else withPath("$path/")
    fun child(name: String, folder: Boolean): RemoteAddress {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it in "/\\\u0000\r\n" })
        return withPath(directory().path + name + if (folder) "/" else "")
    }
    fun parent() = withPath(path.trimEnd('/').substringBeforeLast('/', "") + "/")
    fun contains(other: RemoteAddress) = scheme == other.scheme && host.equals(other.host, true) && port == other.port &&
        (path == "/" || other.path.trimEnd('/') == path.trimEnd('/') || other.path.startsWith(directory().path))
    private fun withPath(value: String) = RemoteAddress(URI(scheme, null, host, uri.port, value, null, null))
    companion object {
        fun parse(value: String): RemoteAddress {
            require(value.length in 1..8192)
            val uri = URI(value.trim())
            require(uri.scheme in setOf("smb", "ftp", "sftp") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            require(uri.path.orEmpty().startsWith('/') || uri.path.isNullOrEmpty())
            require(uri.path.orEmpty().none { it in "\\\u0000\r\n" } && uri.path.orEmpty().split('/').none { it == "." || it == ".." })
            require(!Regex("(?i)%2f|%5c").containsMatchIn(uri.rawPath.orEmpty()))
            return RemoteAddress(uri)
        }
    }
}
