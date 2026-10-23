package com.phnem.vap.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream

data class NetworkEntry(val url: String, val name: String, val folder: Boolean, val size: Long?, val mimeType: String? = null, val serverItemId: String? = null, val resumeMs: Long = 0)

/** Vetro's bounded DAV parser extended from title matching to a single-level directory browser. */
object WebDavBrowser {
    suspend fun list(connection: NetworkConnection, directory: String): List<NetworkEntry> = withContext(Dispatchers.IO) {
        val root = connection.root
        val current = NetworkUrls.directory(directory)
        require(connection.kind == NetworkKind.WEBDAV && NetworkUrls.contains(root, current))
        val request = Request.Builder().url(current).method("PROPFIND", BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1").build()
        val call = NetworkHttp.connection(connection).newCall(request)
        val cancel = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try { call.execute().use { response ->
            check(response.code == 207) { "WebDAV HTTP ${response.code}" }
            val out = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input -> val buffer = ByteArray(8192)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 2 * 1024 * 1024); out.write(buffer, 0, n) } } ?: error("Empty WebDAV response")
            parse(out.toString("UTF-8"), root.toString(), current.toString())
        } } finally { cancel?.dispose() }
    }
    fun parse(xml: String, rootUrl: String, currentUrl: String): List<NetworkEntry> {
        val root = NetworkUrls.directory(rootUrl); val current = NetworkUrls.directory(currentUrl)
        val document = SafeXml.parse(xml)
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        require(responses.length <= 5000)
        fun Element.first(name: String) = getElementsByTagNameNS("DAV:", name).item(0)?.textContent?.trim()
        return (0 until responses.length).mapNotNull { index ->
            val response = responses.item(index) as? Element ?: return@mapNotNull null
            val href = current.resolve(response.first("href") ?: return@mapNotNull null) ?: return@mapNotNull null
            val url = NetworkUrls.resolve(root, href.toString()) ?: return@mapNotNull null
            val folder = response.getElementsByTagNameNS("DAV:", "collection").length > 0
            val segments = url.pathSegments.filter(String::isNotEmpty)
            val parent = current.pathSegments.filter(String::isNotEmpty)
            if (segments.size != parent.size + 1 || segments.take(parent.size) != parent) return@mapNotNull null
            val name = response.first("displayname")?.takeIf(String::isNotBlank)?.take(250) ?: segments.last()
            if (!folder && !isVideo(name)) return@mapNotNull null
            NetworkEntry(url.toString(), name, folder, response.first("getcontentlength")?.toLongOrNull()?.takeIf { it >= 0 })
        }.distinctBy { it.url.trimEnd('/') }.sortedWith(compareByDescending<NetworkEntry> { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "mts", "m2ts", "mpg", "mpeg", "3gp", "m3u8", "mpd")
    private const val BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
}
