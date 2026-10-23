package com.phnem.vap.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.phnem.vap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

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
