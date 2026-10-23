package com.phnem.vap.network

import org.junit.Assert.*
import org.junit.Test

class ServerScopeTest {
    private val connection = NetworkConnection(id = "test-server", name = "Server", kind = NetworkKind.JELLYFIN,
        url = "https://nas.test/jellyfin/", token = "private-server-token", userId = "user1")
    @Test fun serverKeysRespectReverseProxyRoot() {
        assertEquals("https://nas.test/jellyfin/Videos/movie1/stream.mp4", ServerClient.allowed(connection, "/Videos/movie1/stream.mp4"))
        assertEquals("https://nas.test/jellyfin/Items", ServerClient.allowed(connection, "Items"))
    }
    @Test fun tokenRemovedFromUrls() {
        val value = ServerClient.allowed(connection, "Videos/movie1/stream?api_key=private-server-token&Static=true")
        assertFalse(value.contains("private-server-token")); assertTrue(value.contains("Static=true"))
        assertTrue(runCatching { ServerClient.allowed(connection, "Videos/movie1/stream?unusual=private-server-token") }.isFailure)
    }
    @Test fun noCrossOriginOrTraversalCredentials() {
        listOf("https://evil.test/jellyfin/Items", "http://nas.test/jellyfin/Items", "https://nas.test:9443/jellyfin/Items", "../outside", "//evil.test/Items").forEach {
            assertTrue(it, runCatching { ServerClient.allowed(connection, it) }.isFailure)
        }
    }
    @Test fun folderKeysBoundToConnection() {
        val key = "library/sections/1/all?sort=titleSort:asc"
        assertEquals(key, ServerClient.folderId(connection, ServerClient.folder(connection.id, key)))
        assertTrue(runCatching { ServerClient.folderId(connection, ServerClient.folder("other-server", key)) }.isFailure)
    }
    @Test fun secretsAndUnsafeIdentifiersRejected() {
        assertFalse(connection.toString().contains("private-server-token"))
        assertTrue(runCatching { connection.copy(token = "bad\r\nheader") }.isFailure)
        assertTrue(runCatching { ServerPlaybackRef(connection.id, "../admin") }.isFailure)
    }
}
