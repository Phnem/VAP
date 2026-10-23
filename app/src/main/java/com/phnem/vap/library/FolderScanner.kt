package com.phnem.vap.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Обход выбранных деревьев SAF одним запросом на папку (`DocumentsContract`), а не по запросу на
 * каждый элемент, как у `DocumentFile.listFiles()`: на больших библиотеках это на порядок быстрее.
 *
 * Возвращает все узлы, в поддереве которых есть видео. Узлы без видео отбрасываются целиком.
 */
class FolderScanner(private val context: Context) {

    private class Child(
        val docId: String,
        val name: String,
        val mime: String?,
        val size: Long,
        val modified: Long,
    ) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    suspend fun scan(trees: List<Uri>): List<VideoFolder> = withContext(Dispatchers.IO) {
        val out = ArrayList<VideoFolder>()
        for (tree in trees) {
            coroutineContext.ensureActive()
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val rootName = displayName(tree, rootId) ?: continue
            walk(tree, rootId, rootName, parentId = null, depth = 0, out = out)
        }
        out
    }

    private suspend fun walk(
        tree: Uri,
        docId: String,
        name: String,
        parentId: String?,
        depth: Int,
        out: MutableList<VideoFolder>,
    ): VideoFolder? {
        coroutineContext.ensureActive()
        val id = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
        val children = children(tree, docId)

        val videos = ArrayList<VideoFile>()
        var poster: Uri? = null
        for (c in children) {
            if (c.isDir) continue
            when {
                isVideo(c.name, c.mime) -> videos += VideoFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.docId),
                    name = c.name,
                    sizeBytes = c.size,
                    lastModified = c.modified,
                )
                poster == null && isPoster(c.name) -> poster = DocumentsContract.buildDocumentUriUsingTree(tree, c.docId)
            }
        }

        val childNodes = ArrayList<VideoFolder>()
        if (depth < MAX_DEPTH) {
            for (c in children) {
                if (!c.isDir) continue
                walk(tree, c.docId, c.name, parentId = id, depth = depth + 1, out = out)?.let(childNodes::add)
            }
        }
        if (videos.isEmpty() && childNodes.isEmpty()) return null

        val sorted = videos.sortedBy { it.name.lowercase(Locale.getDefault()) }
        val node = VideoFolder(
            id = id,
            name = name,
            uri = Uri.parse(id),
            kind = kindOf(name),
            posterUri = poster,
            files = sorted,
            parentId = parentId,
            childIds = childNodes.map { it.id },
            totalCount = sorted.size + childNodes.sumOf { it.totalCount },
            totalSize = sorted.sumOf { it.sizeBytes } + childNodes.sumOf { it.totalSize },
            newestModified = maxOf(
                sorted.maxOfOrNull { it.lastModified } ?: 0L,
                childNodes.maxOfOrNull { it.newestModified } ?: 0L,
            ),
        )
        out += node
        return node
    }

    private fun children(tree: Uri, docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val list = ArrayList<Child>()
        runCatching {
            context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    list += Child(
                        docId = c.getString(0) ?: continue,
                        name = name,
                        mime = c.getString(2),
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        modified = if (c.isNull(4)) 0L else c.getLong(4),
                    )
                }
            }
        }
        return list
    }

    private fun displayName(tree: Uri, docId: String): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    internal companion object {
        const val MAX_DEPTH = 6
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        private val VIDEO_EXTENSIONS =
            setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "3gp", "mpg", "mpeg")
        private val POSTER_NAMES = setOf("folder", "poster", "cover", "thumb", "thumbnail", "fanart")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun isVideo(name: String, mime: String?): Boolean =
            mime?.startsWith("video/") == true ||
                name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS

        fun isPoster(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in IMAGE_EXTENSIONS) return false
            return name.substringBeforeLast('.').lowercase(Locale.ROOT) in POSTER_NAMES
        }

        fun kindOf(folderName: String): FolderKind = when (folderName.lowercase(Locale.ROOT)) {
            "download", "downloads" -> FolderKind.DOWNLOAD
            "camera", "dcim" -> FolderKind.CAMERA
            "movies", "video", "videos" -> FolderKind.MOVIES
            "screenshots", "screen recordings", "screenrecorder" -> FolderKind.SCREENSHOTS
            "documents" -> FolderKind.DOCUMENTS
            else -> FolderKind.GENERIC
        }
    }
}
