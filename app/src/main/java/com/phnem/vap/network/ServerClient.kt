package com.phnem.vap.network

import com.phnem.vap.player.model.PlayableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Wire subset adapted from Vetro PersonalMediaServerPlaybackSource, extended with browsing/progress. */
class ServerClient(private val transport: BoundedHttp? = null) {
    suspend fun login(connection: NetworkConnection, username: String, password: String): NetworkConnection {
        require(connection.kind in setOf(NetworkKind.JELLYFIN, NetworkKind.EMBY) && username.isNotBlank() && username.length <= 512 && password.length <= 2048)
        val response = json(connection.copy(token = ""), "Users/AuthenticateByName", "POST", JSONObject().put("Username", username).put("Pw", password))
        val user = response.getJSONObject("User").getString("Id").also(::requireId)
        val token = response.getString("AccessToken").also { require(it.isNotBlank()) }
        return connection.copy(username = username, password = "", token = token, userId = user)
    }
    suspend fun validate(connection: NetworkConnection): NetworkConnection {
        require(connection.isServer && connection.token.isNotBlank())
        return if (connection.kind == NetworkKind.PLEX) { json(connection, "library/sections"); connection.copy(password = "") }
        else {
            val user = if (connection.userId.isBlank()) json(connection, "Users/Me").getString("Id") else connection.userId
            requireId(user); json(connection, "Users/$user/Views")
            connection.copy(userId = user, password = "")
        }
    }
    suspend fun browse(connection: NetworkConnection, folder: String): List<NetworkEntry> {
        require(connection.isServer && connection.token.isNotEmpty())
        return if (connection.kind == NetworkKind.PLEX) browsePlex(connection, folder) else browseEmby(connection, folder)
    }
    private suspend fun browseEmby(connection: NetworkConnection, folder: String): List<NetworkEntry> {
        requireId(connection.userId)
        val parent = folderId(connection, folder)
        if (parent.isNotEmpty()) requireId(parent)
        val path = if (parent.isEmpty()) "Users/${connection.userId}/Views" else "Users/${connection.userId}/Items"
        var offset = 0; val entries = mutableListOf<NetworkEntry>()
        while (offset < 5000) {
            val parameters = if (parent.isEmpty()) emptyMap() else mapOf("ParentId" to parent, "StartIndex" to offset.toString(), "Limit" to "200", "Fields" to "UserData,MediaSources", "EnableImages" to "false", "Recursive" to "false")
            val reply = json(connection, path, query = parameters)
            val items = reply.optJSONArray("Items") ?: JSONArray()
            for (index in 0 until minOf(items.length(), 1000)) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("Id").takeIf(::validId) ?: continue
                val type = item.optString("Type")
                if (parent.isEmpty() && item.optString("CollectionType") in setOf("music", "books", "photos", "playlists")) continue
                val isFolder = item.optBoolean("IsFolder") || type in setOf("CollectionFolder", "Folder", "Series", "Season", "UserView", "BoxSet")
                if (!isFolder && type !in setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")) continue
                entries += NetworkEntry(ServerClient.folder(connection.id, id), item.optString("Name").take(250).ifBlank { id }, isFolder, null,
                    serverItemId = if (isFolder) null else id, resumeMs = item.optJSONObject("UserData")?.optLong("PlaybackPositionTicks")?.div(10000)?.coerceAtLeast(0) ?: 0)
            }
            val count = items.length(); offset += count
            if (parent.isEmpty() || count == 0 || offset >= reply.optInt("TotalRecordCount", offset)) break
        }
        return entries.take(5000)
    }
    private suspend fun browsePlex(connection: NetworkConnection, folder: String): List<NetworkEntry> {
        val relative = folderId(connection, folder).ifBlank { "library/sections" }
        val url = allowed(connection, relative)
        val entries = mutableListOf<NetworkEntry>(); var offset = 0
        while (offset < 5000) {
            val container = json(connection, url, query = mapOf("X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to "200")).getJSONObject("MediaContainer")
            val items = (container.optJSONArray("Directory")?.objects().orEmpty().map { it to true } + container.optJSONArray("Metadata")?.objects().orEmpty().map { it to false } + container.optJSONArray("Video")?.objects().orEmpty().map { it to false })
            for ((item, forcedFolder) in items.take(1000)) {
                val type = item.optString("type")
                if (relative == "library/sections" && type !in setOf("movie", "show", "video")) continue
                val rating = item.optString("ratingKey")
                val video = !forcedFolder && (type in setOf("movie", "episode", "clip") || (item.has("Media") && validId(rating)))
                val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
                val path = if (relative == "library/sections" && key.matches(Regex("[0-9]+"))) "library/sections/$key" else resolveKey(connection, url, key)
                val id = if (video) rating.takeIf(::validId) ?: continue else path
                entries += NetworkEntry(ServerClient.folder(connection.id, id), item.optString("title").ifBlank { item.optString("name") }.ifBlank { id }.take(250), !video, null,
                    serverItemId = if (video) id else null, resumeMs = item.optLong("viewOffset").coerceAtLeast(0))
            }
            offset += items.size
            if (items.isEmpty() || offset >= container.optInt("totalSize", container.optInt("size", offset))) break
        }
        return entries.distinctBy { it.url }.take(5000)
    }
    suspend fun play(connection: NetworkConnection, entry: NetworkEntry): PlayableItem {
        val id = entry.serverItemId ?: error("Not a video")
        requireId(id)
        return if (connection.kind == NetworkKind.PLEX) {
            val item = json(connection, "library/metadata/$id").getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0)
            val media = item.getJSONArray("Media").getJSONObject(0)
            val part = media.getJSONArray("Part").getJSONObject(0)
            val url = allowed(connection, part.getString("key"))
            PlayableItem(url, entry.name, mime(media.optString("container")), ServerPlaybackRef(connection.id, id, part.optString("id").takeIf(::validId).orEmpty()), entry.resumeMs)
        } else {
            requireId(connection.userId)
            val info = json(connection, "Items/$id/PlaybackInfo", query = mapOf("UserId" to connection.userId))
            val sources = info.getJSONArray("MediaSources").objects()
            val direct = sources.firstOrNull { it.optBoolean("SupportsDirectPlay") || it.optBoolean("SupportsDirectStream") }
            val source = direct ?: sources.firstOrNull { it.optBoolean("SupportsTranscoding") && it.optString("TranscodingUrl").isNotBlank() } ?: error("No supported source")
            val sourceId = source.getString("Id").also(::requireId)
            val session = info.optString("PlaySessionId").takeIf(::validId).orEmpty()
            val container = source.optString("Container").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,15}")) }.orEmpty()
            val candidate = if (direct == null) source.getString("TranscodingUrl") else source.optString("DirectStreamUrl").takeIf { it.isNotBlank() && it != "null" }
                ?: "Videos/$id/stream${container.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()}?Static=true&MediaSourceId=${encode(sourceId)}"
            val url = allowed(connection, candidate)
            val adaptive = direct == null || "m3u8" in url.substringBefore('?')
            PlayableItem(url, entry.name, if (adaptive) "application/x-mpegURL" else mime(container),
                ServerPlaybackRef(connection.id, id, sourceId, session, if (direct == null) "Transcode" else "DirectStream"), entry.resumeMs)
        }
    }
    suspend fun progress(connection: NetworkConnection, ref: ServerPlaybackRef, position: Long, duration: Long, paused: Boolean, stopped: Boolean, started: Boolean) {
        require(connection.id == ref.connectionId && connection.isServer)
        val pos = position.coerceIn(0, if (duration > 0) minOf(duration, Long.MAX_VALUE / 10000) else Long.MAX_VALUE / 10000)
        if (connection.kind == NetworkKind.PLEX) {
            json(connection, ":/timeline", query = mapOf("ratingKey" to ref.itemId, "key" to "/library/metadata/${ref.itemId}", "state" to if (stopped) "stopped" else if (paused) "paused" else "playing", "time" to pos.toString(), "duration" to duration.coerceAtLeast(0).toString()), expectJson = false)
        } else {
            val body = JSONObject().put("ItemId", ref.itemId).put("MediaSourceId", ref.sourceId).put("PlaySessionId", ref.sessionId).put("PositionTicks", pos * 10000)
                .put("IsPaused", paused).put("CanSeek", true).put("PlayMethod", ref.method)
            val path = if (stopped) "Sessions/Playing/Stopped" else if (started) "Sessions/Playing" else "Sessions/Playing/Progress"
            json(connection, path, "POST", body, expectJson = false)
        }
    }
    private suspend fun json(connection: NetworkConnection, path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap(), expectJson: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val url = NetworkUrls.parse(allowed(connection, path)).newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val headers = buildMap {
            put("Accept", "application/json"); put("Content-Type", "application/json")
            if (connection.kind == NetworkKind.PLEX) { put("X-Plex-Token", connection.token); put("X-Plex-Client-Identifier", connection.id) }
            else { if (connection.token.isNotEmpty()) put("X-Emby-Token", connection.token)
                put("X-Emby-Authorization", "${if (connection.kind == NetworkKind.EMBY) "Emby" else "MediaBrowser"} Client=\"VAP\", Device=\"Android\", DeviceId=\"${connection.id}\", Version=\"${com.phnem.vap.BuildConfig.VERSION_NAME}\"") }
        }
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val reply = if (transport != null) transport.request(url.toString(), method, headers, bytes, LIMIT) else {
            val request = Request.Builder().url(url).apply { headers.forEach(::header); method(method, if (method == "GET") null else (bytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())) }.build()
            NetworkHttp.connection(connection).newCall(request).execute().use { response ->
                val out = ByteArrayOutputStream()
                response.body?.byteStream()?.use { input -> val buffer = ByteArray(8192); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= LIMIT); out.write(buffer, 0, n) } }
                HttpReply(response.code, out.toByteArray())
            }
        }
        if (reply.code in setOf(401, 403)) throw NetworkAuthFailure()
        require(reply.bytes.size <= LIMIT)
        check(reply.code in 200..299) { "Server HTTP ${reply.code}" }
        if (!expectJson || reply.bytes.isEmpty()) JSONObject() else JSONObject(String(reply.bytes, Charsets.UTF_8))
    }
    companion object {
        private const val LIMIT = 2 * 1024 * 1024
        fun validId(value: String) = value.matches(Regex("[a-zA-Z0-9-]{1,128}"))
        private fun requireId(value: String) { require(validId(value)) }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        fun folder(connectionId: String, id: String) = "vapserver://$connectionId/${encode(id)}"
        fun folderId(connection: NetworkConnection, value: String): String {
            if (!value.startsWith("vapserver://")) { require(value == connection.rootUrl); return "" }
            val uri = URI(value); require(uri.host == connection.id && uri.rawQuery == null && uri.rawFragment == null)
            return URLDecoder.decode(uri.rawPath.removePrefix("/"), "UTF-8").also { require(it.length <= 2048) }
        }
        fun allowed(connection: NetworkConnection, path: String): String {
            val relative = if (path.startsWith('/') && !path.startsWith("//") && !path.startsWith(connection.root.encodedPath)) path.removePrefix("/") else path
            val url = connection.root.resolve(relative) ?: error("Invalid server URL")
            require(connection.contains(url))
            val clean = url.newBuilder()
            for (name in url.queryParameterNames) if (name.lowercase() in setOf("api_key", "apikey", "x-plex-token", "access_token", "token")) clean.removeAllQueryParameters(name)
            val result = clean.build()
            require(connection.token.isBlank() || (!result.toString().contains(connection.token) && result.queryParameterNames.none { name -> result.queryParameterValues(name).any { it?.contains(connection.token) == true } }))
            return result.toString()
        }
        private fun resolveKey(connection: NetworkConnection, current: String, key: String): String {
            if (key.startsWith('/')) return allowed(connection, key)
            val base = NetworkUrls.parse(current).newBuilder().query(null).apply { if (!NetworkUrls.parse(current).encodedPath.endsWith('/')) addPathSegment("") }.build()
            return allowed(connection, base.resolve(key)?.toString() ?: error("Invalid key"))
        }
        private fun mime(container: String): String? = when(container.lowercase()) { "mp4", "m4v" -> "video/mp4"; "mkv", "matroska" -> "video/x-matroska"; "webm" -> "video/webm"; "ts", "mpegts" -> "video/mp2t"; else -> null }
        private fun JSONArray.objects() = (0 until minOf(length(), 5000)).mapNotNull { optJSONObject(it) }
    }
}
