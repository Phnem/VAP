package com.phnem.vap.player.subtitles.online

import android.content.Context
import com.phnem.vap.BuildConfig
import com.phnem.vap.network.*
import com.phnem.vap.player.subtitles.SubtitleDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

data class OnlineSubtitle(val fileId: Long, val language: String, val release: String, val fileName: String,
    val hashMatch: Boolean, val trusted: Boolean, val translated: Boolean, val hearingImpaired: Boolean, val downloads: Int, val fps: Double?)
data class SubtitleSearch(val query: String, val languages: String, val season: Int? = null, val episode: Int? = null, val hash: String? = null) {
    fun path(): String {
        val parameters = sortedMapOf<String, String>()
        query.trim().lowercase(Locale.ROOT).take(250).takeIf { it.isNotBlank() }?.let { parameters["query"] = it }
        languages.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.matches(Regex("[a-z]{2,3}(-[a-z]{2})?")) }.distinct().sorted()
            .takeIf { it.isNotEmpty() }?.let { parameters["languages"] = it.joinToString(",") }
        season?.also { require(it in 0..999) }?.let { parameters["season_number"] = it.toString() }
        episode?.also { require(it in 0..9999) }?.let { parameters["episode_number"] = it.toString() }
        hash?.also { require(it.matches(Regex("[0-9a-f]{16}"))) }?.let { parameters["moviehash"] = it }
        require(parameters.containsKey("query") || hash != null) { "Search text or movie hash required" }
        return "subtitles?" + parameters.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }
}
class OpenSubtitlesFailure(val reason: String) : Exception(reason)
data class OnlineDownload(val file: File, val remaining: Int?)

/** Adapted from Vetro's OpenSubtitlesClient/ExternalSubtitleService, without its Ktor/DI graph. */
class OpenSubtitlesClient(context: Context, private val vault: CredentialVault = CredentialVault(context),
    private val http: BoundedHttp = PlatformHttp(), private val now: () -> Long = System::currentTimeMillis,
    private val directory: File = File(context.cacheDir, "opensubtitles")) {
    private val requestLock = Mutex()
    private var lastRequest = 0L
    suspend fun configured() = !vault.get(KEY).isNullOrBlank()
    suspend fun signedIn() = !vault.get(TOKEN).isNullOrBlank()
    suspend fun saveKey(key: String) {
        require(key.trim().length in 8..512 && key.trim().all { it.code in 33..126 })
        vault.save(mapOf(KEY to key.trim(), TOKEN to null, BASE_URL to null))
    }
    suspend fun logout() { vault.save(mapOf(TOKEN to null, BASE_URL to null)) }
    suspend fun clear() { vault.save(mapOf(KEY to null, TOKEN to null, BASE_URL to null)) }
    suspend fun login(username: String, password: String) {
        require(username.isNotBlank() && username.length <= 128 && password.isNotBlank() && password.length <= 512)
        val bytes = JSONObject().put("username", username.trim()).put("password", password).toString().toByteArray()
        val response = api("login", "POST", bytes, authenticated = false)
        val json = JSONObject(String(response, Charsets.UTF_8))
        val token = json.getString("token").takeIf { it.isNotBlank() && it != "null" && it.length <= 7000 } ?: throw OpenSubtitlesFailure("response")
        val base = try { normalizeBase(json.optString("base_url").takeUnless { it.isBlank() || it == "null" } ?: BASE) }
            catch (_: IllegalArgumentException) { throw OpenSubtitlesFailure("response") }
        vault.save(mapOf(TOKEN to token, BASE_URL to base))
    }
    suspend fun search(search: SubtitleSearch): List<OnlineSubtitle> {
        val path = search.path()
        val cache = File(directory, "search-${digest(path)}.json")
        val cached = withContext(Dispatchers.IO) { cache.takeIf { it.isFile && it.length() <= JSON_LIMIT && now() - it.lastModified() in 0..86_400_000 }?.readBytes() }
        if (cached != null) withContext(Dispatchers.Default) { runCatching { parseCandidates(cached) }.getOrNull() }?.let { return it }
        val bytes = api(path, "GET")
        val result = withContext(Dispatchers.Default) { parseCandidates(bytes) }
        write(cache, bytes); trimCache("search-", 24)
        return result
    }
    private fun parseCandidates(bytes: ByteArray): List<OnlineSubtitle> {
        val data = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("data")
        return buildList {
            for (index in 0 until minOf(data.length(), 100)) {
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
                val files = attributes.optJSONArray("files") ?: continue
                val language = attributes.optString("language").takeUnless { it == "null" }.orEmpty().take(35)
                for (fileIndex in 0 until minOf(files.length(), 8)) {
                    val file = files.optJSONObject(fileIndex) ?: continue
                    val id = file.optLong("file_id").takeIf { it > 0 } ?: continue
                    add(OnlineSubtitle(id, language, attributes.optString("release").takeUnless { it == "null" }.orEmpty().take(500), file.optString("file_name").takeUnless { it == "null" }.orEmpty().take(250),
                        attributes.optBoolean("moviehash_match"), attributes.optBoolean("from_trusted"),
                        attributes.optBoolean("machine_translated") || attributes.optBoolean("ai_translated"), attributes.optBoolean("hearing_impaired"),
                        attributes.optInt("download_count"), attributes.optDouble("fps").takeIf { it.isFinite() && it > 0 }))
                }
            }
        }.distinctBy { it.fileId }.sortedWith(compareByDescending<OnlineSubtitle> { it.hashMatch }.thenBy { it.translated }
            .thenByDescending { it.trusted }.thenByDescending { it.downloads }).take(50)
    }
    suspend fun download(offer: OnlineSubtitle): OnlineDownload {
        require(offer.fileId > 0)
        val cached = File(directory, "file-${offer.fileId}.srt")
        if (withContext(Dispatchers.IO) { cached.isFile && cached.length() in 1..SubtitleDocument.MAX_BYTES.toLong() }) return OnlineDownload(cached, null)
        val body = JSONObject().put("file_id", offer.fileId).put("sub_format", "srt").toString().toByteArray()
        val json = JSONObject(String(api("download", "POST", body), Charsets.UTF_8))
        var url = json.getString("link")
        var response: HttpReply? = null
        repeat(4) {
            if (response == null) {
                checkHttps(url)
                val next = http.request(url, "GET", mapOf("User-Agent" to userAgent), null, SubtitleDocument.MAX_BYTES)
                if (next.code in setOf(301, 302, 307, 308)) url = URI(url).resolve(next.location ?: throw OpenSubtitlesFailure("response")).toString()
                else response = next
            }
        }
        val result = response ?: throw OpenSubtitlesFailure("response")
        if (result.code !in 200..299) throw OpenSubtitlesFailure("download")
        val bytes = withContext(Dispatchers.IO) {
            val compressed = result.bytes
            if (compressed.size > 2 && compressed[0] == 0x1f.toByte() && compressed[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                    require(out.size().toLong() + count <= SubtitleDocument.MAX_BYTES); out.write(buffer, 0, count) }
                out.toByteArray()
            } else compressed
        }
        try { withContext(Dispatchers.Default) { SubtitleDocument.parse(String(bytes, Charsets.UTF_8)) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { throw OpenSubtitlesFailure("download") }
        write(cached, bytes); trimCache("file-", 12)
        return OnlineDownload(cached, if (json.has("remaining")) json.optInt("remaining").coerceAtLeast(0) else null)
    }
    private suspend fun api(path: String, method: String, body: ByteArray? = null, authenticated: Boolean = true): ByteArray {
        val key = vault.get(KEY)?.takeIf { it.isNotBlank() } ?: throw OpenSubtitlesFailure("key")
        val token = if (authenticated) vault.get(TOKEN) else null
        val base = if (token == null) BASE else normalizeBase(vault.get(BASE_URL).orEmpty().ifBlank { BASE })
        val headers = mutableMapOf("Api-Key" to key, "User-Agent" to userAgent, "Accept" to "application/json", "Content-Type" to "application/json")
        if (token != null) headers["Authorization"] = "Bearer $token"
        var url = "$base/$path"
        var reply: HttpReply? = null
        repeat(4) { attempt ->
            if (reply == null) {
                val current = requestLock.withLock {
                    val elapsed = System.nanoTime() / 1_000_000 - lastRequest
                    if (elapsed < 300) delay(300 - elapsed)
                    lastRequest = System.nanoTime() / 1_000_000
                    http.request(url, method, headers, body, JSON_LIMIT)
                }
                if (method == "GET" && current.code in setOf(301, 302, 307, 308)) {
                    val next = URI(url).resolve(current.location ?: throw OpenSubtitlesFailure("response"))
                    require(next.scheme == "https" && next.host == URI(base).host && next.userInfo == null && next.path.startsWith("/api/v1/"))
                    url = next.toString()
                } else if (current.code == 429 && method == "GET" && attempt == 0) delay((current.retryAfterSeconds.coerceIn(1, 10) * 1000).toLong())
                else reply = current
            }
        }
        val result = reply ?: throw OpenSubtitlesFailure("response")
        if (result.code !in 200..299) throw OpenSubtitlesFailure(when (result.code) { 401 -> "session"; 403 -> "access"; 406, 429 -> "quota"; else -> "server" })
        return result.bytes
    }
    private suspend fun write(file: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); directory.mkdirs()
        val pending = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try { pending.writeBytes(bytes); check(pending.renameTo(file)) } finally { pending.delete() }
    }
    private suspend fun trimCache(prefix: String, keep: Int) = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.isFile }.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }
    companion object {
        const val KEY = "opensubtitles.key"; const val TOKEN = "opensubtitles.token"; const val BASE_URL = "opensubtitles.base"
        const val BASE = "https://api.opensubtitles.com/api/v1"
        private const val JSON_LIMIT = 1024 * 1024
        private val userAgent get() = "VAP ${BuildConfig.VERSION_NAME}"
        fun normalizeBase(raw: String): String {
            val uri = URI(if (raw.startsWith("https://")) raw else "https://$raw")
            require(uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in setOf("api.opensubtitles.com", "vip-api.opensubtitles.com") && uri.userInfo == null && uri.port in setOf(-1, 443))
            require(uri.path.orEmpty().trimEnd('/') in setOf("", "/api/v1") && uri.query == null && uri.fragment == null)
            return "https://${uri.host.lowercase(Locale.ROOT)}/api/v1"
        }
        private fun checkHttps(value: String) { val uri = URI(value); require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
