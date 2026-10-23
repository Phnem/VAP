package com.phnem.vap.network

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class NetworkConnectionStore(context: Context, namespace: String = "network_connections") {
    private val vault = CredentialVault(context, namespace)
    suspend fun list(): List<NetworkConnection> = decode(vault.get("connections"))
    suspend fun save(connection: NetworkConnection) = lock.withLock {
        val old = list().filter { it.id != connection.id }
        require(old.size < 64)
        persist(old + connection)
    }
    suspend fun remove(id: String) = lock.withLock { persist(list().filter { it.id != id }) }
    private suspend fun persist(connections: List<NetworkConnection>) {
        val array = JSONArray()
        connections.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)
            .put("url", it.url).put("username", it.username).put("password", it.password).put("hostKey", it.hostKey).put("streamType", it.streamType).put("token", it.token).put("userId", it.userId)) }
        vault.save(mapOf("connections" to array.toString()))
    }
    private fun decode(raw: String?): List<NetworkConnection> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw); require(array.length() <= 64)
        return (0 until array.length()).map { index -> val item = array.getJSONObject(index)
            NetworkConnection(item.getString("id"), item.getString("name"), NetworkKind.valueOf(item.getString("kind")),
                item.getString("url"), item.optString("username"), item.optString("password"), item.optString("hostKey"), item.optString("streamType", "auto"), item.optString("token"), item.optString("userId")) }
    }
    private companion object { val lock = Mutex() }
}
