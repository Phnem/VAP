package com.phnem.vap.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кэш результата сканирования на диске: при запуске список папок виден сразу, а свежий обход
 * идёт в фоне (stale-while-revalidate, как в Vetro). Формат — JSON; повреждённый файл = нет кэша.
 */
class ScanCache(context: Context) {
    private val file = File(context.filesDir, "scan_cache.json")

    fun load(): List<VideoFolder>? = runCatching {
        if (!file.exists()) return null
        val arr = JSONArray(file.readText())
        List(arr.length()) { decode(arr.getJSONObject(it)) }
    }.getOrNull()

    fun save(folders: List<VideoFolder>) {
        runCatching {
            val arr = JSONArray()
            // Permission-limited MediaStore results must never survive on disk as stale access.
            folders.filterNot { DeviceVideoIndex.isDeviceFolder(it.id) }.forEach { arr.put(encode(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }

    fun clear() {
        file.delete()
    }

    private fun encode(f: VideoFolder): JSONObject = JSONObject().apply {
        put("id", f.id)
        put("name", f.name)
        put("uri", f.uri.toString())
        put("kind", f.kind.name)
        f.posterUri?.let { put("poster", it.toString()) }
        f.parentId?.let { put("parent", it) }
        put("children", JSONArray(f.childIds))
        put("count", f.totalCount)
        put("size", f.totalSize)
        put("newest", f.newestModified)
        put(
            "files",
            JSONArray().also { arr ->
                f.files.forEach { v ->
                    arr.put(
                        JSONObject()
                            .put("u", v.uri.toString())
                            .put("n", v.name)
                            .put("s", v.sizeBytes)
                            .put("m", v.lastModified),
                    )
                }
            },
        )
    }

    private fun decode(o: JSONObject): VideoFolder {
        val files = o.getJSONArray("files")
        val children = o.getJSONArray("children")
        return VideoFolder(
            id = o.getString("id"),
            name = o.getString("name"),
            uri = Uri.parse(o.getString("uri")),
            kind = runCatching { FolderKind.valueOf(o.getString("kind")) }.getOrDefault(FolderKind.GENERIC),
            posterUri = o.optString("poster").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            files = List(files.length()) {
                val v = files.getJSONObject(it)
                VideoFile(Uri.parse(v.getString("u")), v.getString("n"), v.getLong("s"), v.getLong("m"))
            },
            parentId = o.optString("parent").takeIf { it.isNotEmpty() },
            childIds = List(children.length()) { children.getString(it) },
            totalCount = o.getInt("count"),
            totalSize = o.getLong("size"),
            newestModified = o.getLong("newest"),
        )
    }
}
