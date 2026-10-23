package com.phnem.vap.network

import android.os.Bundle
import org.json.JSONObject

/** No credentials here: only private store ID and bounded server media/session identifiers. */
data class ServerPlaybackRef(val connectionId: String, val itemId: String, val sourceId: String = "", val sessionId: String = "", val method: String = "DirectPlay", val refresh: Boolean = false) {
    init {
        require(connectionId.matches(Regex("[a-zA-Z0-9-]{1,80}")) && itemId.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        require(sourceId.length <= 128 && sessionId.length <= 128 && method in setOf("DirectPlay", "DirectStream", "Transcode"))
        require((sourceId + sessionId).all { it.isLetterOrDigit() || it == '-' })
    }
    fun json() = JSONObject().put("connection", connectionId).put("item", itemId).put("source", sourceId).put("session", sessionId).put("method", method).put("refresh", refresh).toString()
    fun bundle() = Bundle().apply { putString(EXTRA, json()) }
    companion object {
        const val EXTRA = "vap.server.playback"
        fun parse(raw: String?, forceRefresh: Boolean = false): ServerPlaybackRef? = runCatching {
            if (raw.isNullOrBlank()) return null
            require(raw.length <= 2048)
            val json = JSONObject(raw)
            ServerPlaybackRef(json.getString("connection"), json.getString("item"), json.optString("source"), json.optString("session"), json.optString("method", "DirectPlay"), forceRefresh || json.optBoolean("refresh"))
        }.getOrNull()
    }
}
