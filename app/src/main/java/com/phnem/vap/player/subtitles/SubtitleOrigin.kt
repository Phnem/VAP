package com.phnem.vap.player.subtitles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Invalidates automatically extracted ASS when a video is replaced at the same URI. */
object SubtitleOrigin {
    suspend fun stamp(context: Context, uri: String): String = withContext(Dispatchers.IO) {
        val value = Uri.parse(uri)
        if (value.scheme == "file") return@withContext File(checkNotNull(value.path)).let { "${it.length()}:${it.lastModified()}" }
        val modified = if (DocumentsContract.isDocumentUri(context, value)) DocumentsContract.Document.COLUMN_LAST_MODIFIED
            else if (value.authority == "media") MediaStore.MediaColumns.DATE_MODIFIED else null
        val projection = listOfNotNull(OpenableColumns.SIZE, modified).toTypedArray()
        runCatching { context.contentResolver.query(value, projection, null, null, null)?.use {
            if (it.moveToFirst()) "${it.getLong(0)}:${if (modified == null) 0 else it.getLong(1)}" else "unknown"
        } }.getOrNull() ?: "unknown"
    }
}
