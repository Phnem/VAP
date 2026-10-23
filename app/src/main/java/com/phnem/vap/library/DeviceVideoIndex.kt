package com.phnem.vap.library

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class IndexedVideo(val volume: String, val directory: String, val file: VideoFile)

/** Pure grouping: physical path + volume, never bucket name alone. Empty ancestors form a tree. */
object DeviceVideoIndex {
    const val PREFIX = "vap-media-folder:"
    fun isDeviceFolder(id: String) = id.startsWith(PREFIX)
    fun directory(path: String) = path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
    fun folderId(volume: String, directory: String) = PREFIX + volume + ":" + URLEncoder.encode(directory, StandardCharsets.UTF_8.name())

    fun folders(records: List<IndexedVideo>, primaryLabel: String, removableLabel: String, uri: (String) -> Uri = Uri::parse): List<VideoFolder> {
        data class Key(val volume: String, val path: String)
        val files = records.distinctBy { it.file.key }.groupBy { Key(it.volume, directory(it.directory)) }
        val keys = linkedSetOf<Key>()
        files.keys.forEach { key ->
            var path = key.path
            while (true) {
                keys += Key(key.volume, path)
                if (path.isEmpty()) break
                path = path.substringBeforeLast('/', "")
            }
        }
        val children = keys.filter { it.path.isNotEmpty() }.groupBy { Key(it.volume, it.path.substringBeforeLast('/', "")) }
        val built = HashMap<Key, VideoFolder>()
        keys.sortedByDescending { it.path.length }.forEach { key ->
            val own = files[key].orEmpty().map { it.file }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
            val nested = children[key].orEmpty().mapNotNull(built::get)
            val id = folderId(key.volume, key.path)
            val name = key.path.substringAfterLast('/').ifEmpty { if (key.volume == "external_primary" || key.volume == "external") primaryLabel else removableLabel }
            built[key] = VideoFolder(id, name, uri(id), FolderScanner.kindOf(name), null, own,
                parentId = key.path.takeIf { it.isNotEmpty() }?.let { folderId(key.volume, it.substringBeforeLast('/', "")) },
                childIds = nested.map { it.id }, totalCount = own.size + nested.sumOf { it.totalCount },
                totalSize = own.sumOf { it.sizeBytes } + nested.sumOf { it.totalSize },
                newestModified = maxOf(own.maxOfOrNull { it.lastModified } ?: 0, nested.maxOfOrNull { it.newestModified } ?: 0))
        }
        return keys.mapNotNull(built::get)
    }

    /** Prune duplicate SAF files while retaining non-indexed files and valid ancestors. */
    fun uniqueSaf(nodes: List<VideoFolder>, duplicate: (VideoFile) -> Boolean): List<VideoFolder> {
        val byId = nodes.associateBy { it.id }; val built = HashMap<String, VideoFolder>()
        fun visit(node: VideoFolder): VideoFolder? {
            built[node.id]?.let { return it }
            val files = node.files.filterNot(duplicate).distinctBy { it.key }
            val children = node.childIds.mapNotNull { byId[it]?.let(::visit) }
            if (files.isEmpty() && children.isEmpty()) return null
            return node.copy(files = files, childIds = children.map { it.id }, totalCount = files.size + children.sumOf { it.totalCount },
                totalSize = files.sumOf { it.sizeBytes } + children.sumOf { it.totalSize },
                newestModified = maxOf(files.maxOfOrNull { it.lastModified } ?: 0, children.maxOfOrNull { it.newestModified } ?: 0)).also { built[node.id] = it }
        }
        nodes.filter { it.parentId == null || it.parentId !in byId }.forEach(::visit)
        return nodes.mapNotNull { built[it.id] }.distinctBy { it.id }
    }
}
