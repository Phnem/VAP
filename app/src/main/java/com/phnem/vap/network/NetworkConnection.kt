package com.phnem.vap.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

enum class NetworkKind { URL, WEBDAV, SMB, FTP, SFTP, DLNA, JELLYFIN, EMBY, PLEX }

/** Secret-bearing object: never use its generated toString in logs or saved-instance state. */
data class NetworkConnection(val id: String = UUID.randomUUID().toString(), val name: String, val kind: NetworkKind,
    val url: String, val username: String = "", val password: String = "", val hostKey: String = "", val streamType: String = "auto",
    val token: String = "", val userId: String = "") {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.isNotBlank() && name.length <= 150)
        require(streamType in setOf("auto", "hls", "dash"))
        val parsed = if (kind !in setOf(NetworkKind.SMB, NetworkKind.FTP, NetworkKind.SFTP)) NetworkUrls.parse(url) else null
        if (parsed == null) require(RemoteAddress.parse(url).scheme == kind.name.lowercase())
        require(username.length <= 512 && ':' !in username && password.length <= 2048)
        if (kind == NetworkKind.WEBDAV) require(parsed?.query == null)
        if (kind == NetworkKind.SMB) require(RemoteAddress.parse(url).segments.isNotEmpty())
        if (kind == NetworkKind.SFTP) require(hostKey.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?")))
        require(token.length <= 8192 && token.all { it.code in 33..126 } && userId.length <= 128 && userId.all { it.isLetterOrDigit() || it == '-' })
        if (isServer) require(parsed?.query == null)
    }
    val isServer: Boolean get() = kind in setOf(NetworkKind.JELLYFIN, NetworkKind.EMBY, NetworkKind.PLEX)
    val root: HttpUrl get() = if (kind == NetworkKind.WEBDAV || isServer) NetworkUrls.directory(url) else NetworkUrls.parse(url).resolve("./")!!
    fun contains(candidate: HttpUrl) = NetworkUrls.contains(root, candidate)
    val rootUrl: String get() = when(kind) {
        NetworkKind.URL, NetworkKind.WEBDAV -> root.toString()
        NetworkKind.DLNA -> url
        NetworkKind.JELLYFIN, NetworkKind.EMBY, NetworkKind.PLEX -> root.toString()
        else -> RemoteAddress.parse(url).directory().url
    }
    val mimeType: String? get() = when(streamType) { "hls" -> "application/x-mpegURL"; "dash" -> "application/dash+xml"; else -> null }
    override fun toString() = "NetworkConnection($id, $kind)"
}

object NetworkUrls {
    fun parse(value: String): HttpUrl {
        require(value.length in 1..8192)
        val url = value.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        require(safeSegments(url))
        return url
    }
    fun directory(value: String): HttpUrl = parse(value).newBuilder().query(null).apply {
        if (!parse(value).encodedPath.endsWith('/')) addPathSegment("")
    }.build()
    fun contains(root: HttpUrl, candidate: HttpUrl): Boolean {
        if (root.scheme != candidate.scheme || root.host != candidate.host || root.port != candidate.port ||
            candidate.username.isNotEmpty() || candidate.password.isNotEmpty() || candidate.fragment != null || !safeSegments(candidate)) return false
        val prefix = root.encodedPath.trimEnd('/')
        return prefix.isEmpty() || candidate.encodedPath == prefix || candidate.encodedPath.startsWith("$prefix/")
    }
    private fun safeSegments(url: HttpUrl) = url.pathSegments.none { it == "." || it == ".." || '/' in it || '\\' in it || '%' in it }
    fun resolve(root: HttpUrl, href: String): HttpUrl? = runCatching {
        root.resolve(href.trim())?.takeIf { contains(root, it) && it.query == null }
    }.getOrNull()
    fun isNetwork(value: String?) = value != null && (value.toHttpUrlOrNull() != null || runCatching { RemoteAddress.parse(value) }.isSuccess)
}
