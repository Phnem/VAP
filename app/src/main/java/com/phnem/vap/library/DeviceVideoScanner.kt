package com.phnem.vap.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.phnem.vap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** Queries only MediaStore.Video on every mounted shared volume. No all-files permission. */
class DeviceVideoScanner(private val context: Context) {
    suspend fun scan(): List<IndexedVideo> = withContext(Dispatchers.IO) {
        if (VideoPermissions.access(context) == VideoAccess.NONE) return@withContext emptyList()
        val out = ArrayList<IndexedVideo>()
        val primaryRoot = if (Build.VERSION.SDK_INT < 29) android.os.Environment.getExternalStorageDirectory().absolutePath else ""
        val legacyVolumePattern = Regex("^/storage/([^/]+)/")
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).sorted() else listOf("external")
        for (volume in volumes) {
            coroutineContext.ensureActive()
            val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
            val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED, pathColumn)
            val selection = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.Video.Media.IS_PENDING}=0" else null
            context.contentResolver.query(collection, columns, selection, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val name = cursor.getString(1) ?: continue
                    val path = cursor.getString(4).orEmpty()
                    val legacyVolume = if (Build.VERSION.SDK_INT < 29) legacyVolumePattern.find(path)?.groupValues?.get(1) else null
                    val recordVolume = if (Build.VERSION.SDK_INT >= 29) volume else if (path.startsWith("$primaryRoot/")) "external_primary" else legacyVolume ?: volume
                    val directory = if (Build.VERSION.SDK_INT >= 29) path else File(path).parent.orEmpty()
                        .removePrefix(if (recordVolume == "external_primary") primaryRoot else "/storage/$recordVolume").trimStart('/')
                    out += IndexedVideo(recordVolume, DeviceVideoIndex.directory(directory), VideoFile(
                        ContentUris.withAppendedId(collection, cursor.getLong(0)), name,
                        cursor.getLong(2).coerceAtLeast(0), cursor.getLong(3).coerceAtLeast(0) * 1000))
                }
            }
        }
        out
    }
    /** `path|size|mtime` of files already handed to the media scanner: an unindexable file is tried once, not on every rescan. */
    private val handedOver = HashSet<String>()
    private var lastProbe = 0L

    /**
     * A download that bypassed MediaStore stays invisible until the system's own idle scan, which can be hours
     * away. Looks for video files next to the indexed ones (same folders, and their new subfolders) that
     * MediaStore does not know and asks the scanner to index them. Returns true when something was submitted,
     * i.e. the caller should query MediaStore again.
     */
    suspend fun indexStray(records: List<IndexedVideo>): Boolean {
        val stray = withContext(Dispatchers.IO) { findStray(records) }
        if (stray.isEmpty()) return false
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val left = AtomicInteger(stray.size)
                MediaScannerConnection.scanFile(context, stray.toTypedArray(), null) { _, _ ->
                    if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
        return true
    }

    private fun findStray(records: List<IndexedVideo>): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (VideoPermissions.access(context) == VideoAccess.NONE || now - lastProbe < PROBE_GAP_MS) return emptyList()
        lastProbe = now
        val known = records.groupBy({ it.volume to DeviceVideoIndex.directory(it.directory) }, { it.file.name }).mapValues { it.value.toHashSet() }
        val found = ArrayList<String>()
        fun consider(file: File, indexedNames: Set<String>) {
            if (found.size >= MAX_STRAY || !file.isFile || file.name.startsWith(".") || file.name in indexedNames) return
            if (!FolderScanner.isVideo(file.name, null) || System.currentTimeMillis() - file.lastModified() < SETTLE_MS) return
            if (handedOver.add("${file.path}|${file.length()}|${file.lastModified()}")) found += file.path
        }
        val newDirs = ArrayList<File>()
        for ((place, names) in known) {
            val (volume, directory) = place
            val root = volumeRoot(volume) ?: continue
            for (child in (if (directory.isEmpty()) root else File(root, directory)).listFiles().orEmpty()) {
                if (child.isDirectory) {
                    val childPath = if (directory.isEmpty()) child.name else "$directory/${child.name}"
                    if (!child.name.startsWith(".") && (volume to childPath) !in known) newDirs += child
                } else consider(child, names)
            }
        }
        for (dir in newDirs) dir.listFiles().orEmpty().forEach { consider(it, emptySet()) }
        return found
    }

    private fun volumeRoot(volume: String): File? =
        if (volume == "external_primary" || volume == "external") android.os.Environment.getExternalStorageDirectory()
        else File("/storage").listFiles()?.firstOrNull { it.name.equals(volume, ignoreCase = true) }

    private companion object {
        const val PROBE_GAP_MS = 3_000L
        const val SCAN_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 3_000L
        const val MAX_STRAY = 200
    }

    fun folders(records: List<IndexedVideo>) = DeviceVideoIndex.folders(records,
        context.getString(R.string.storage_internal), context.getString(R.string.storage_removable))

    fun uniqueSaf(saf: List<VideoFolder>, records: List<IndexedVideo>): List<VideoFolder> {
        val paths = records.mapTo(hashSetOf()) { it.volume to "${DeviceVideoIndex.directory(it.directory)}/${it.file.name}".trimStart('/') }
        val uniqueIds = records.groupBy { it.file.uri.lastPathSegment }.filterValues { it.size == 1 }.keys
        val knownUris = records.mapTo(hashSetOf()) { it.file.key }
        return DeviceVideoIndex.uniqueSaf(saf) { file ->
            runCatching {
                val id = DocumentsContract.getDocumentId(file.uri)
                when (file.uri.authority) {
                    "com.android.externalstorage.documents" -> {
                        val volume = id.substringBefore(':').let { if (it == "primary") "external_primary" else it.lowercase(java.util.Locale.ROOT) }
                        (volume to id.substringAfter(':', "").trimStart('/')) in paths
                    }
                    "com.android.providers.media.documents" -> id.startsWith("video:") && id.substringAfter(':') in uniqueIds
                    else -> file.key in knownUris
                }
            }.getOrDefault(false)
        }
    }
}
