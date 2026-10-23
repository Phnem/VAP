package com.phnem.vap.player.subtitles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

object SubtitleSettingsJson {
    fun encode(s: SubtitleSettings): String = JSONObject().apply {
        put("selection", s.selection); put("secondary", s.secondary); put("language", s.language)
        put("discovered", s.discovered)
        put("assNative", s.assNative)
        put("delay", s.delayMs); put("scale", s.scale); put("size", s.sizeSp.toDouble()); put("font", s.font)
        put("color", s.color); put("opacity", s.opacity.toDouble()); put("background", s.background); put("edge", s.edge); put("bottom", s.bottom.toDouble())
        put("sources", JSONArray().apply { s.sources.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("language", it.language).put("format", it.format).put("embeddedTrackId", it.embeddedTrackId).put("originStamp", it.originStamp)) } })
    }.toString()
    fun decode(raw: String?): SubtitleSettings = runCatching {
        if (raw == null) return SubtitleSettings()
        val o = JSONObject(raw); val sources = o.optJSONArray("sources") ?: JSONArray()
        SubtitleSettings(selection = o.optString("selection", "auto"), secondary = o.optString("secondary").takeIf { it.isNotBlank() && it != "null" },
            language = o.optString("language"), sources = List(sources.length()) { i -> sources.getJSONObject(i).let { SubtitleSource(it.getString("id"), it.getString("title"), it.optString("language"), it.optString("format", "text"), it.optString("embeddedTrackId"), it.optString("originStamp")) } },
            discovered = o.optBoolean("discovered"),
            assNative = o.optBoolean("assNative", true),
            delayMs = o.optLong("delay"), scale = o.optDouble("scale", 1.0), sizeSp = o.optDouble("size", 22.0).toFloat(), font = o.optString("font", "sans"),
            color = o.optInt("color", -1), opacity = o.optDouble("opacity", 1.0).toFloat(), background = o.optBoolean("background", true), edge = o.optString("edge", "outline"),
            bottom = o.optDouble("bottom", .08).toFloat()).sanitized()
    }.getOrDefault(SubtitleSettings())
}

/** Imports copy text to private storage; no broad document or network permission is required. */
class SubtitleStorage(private val context: Context) {
    private val directory get() = File(context.filesDir, "subtitles").apply { mkdirs() }
    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Invalid subtitle id" }
        return File(directory, "$id.srt")
    }
    suspend fun import(uri: Uri): SubtitleSource = withContext(Dispatchers.IO) {
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= SubtitleDocument.MAX_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, SubtitleDocument.MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Cannot read subtitles")
        require(bytes.size <= SubtitleDocument.MAX_BYTES) { "Subtitle file exceeds 5 MB" }
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, Charsets.UTF_16BE)
            else -> runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes, charset("windows-1251")) }
        }
        val styled = text.contains("[Events]", true)
        val lines = SubtitleDocument.parse(text, allowEmpty = styled)
        val id = UUID.randomUUID().toString()
        write(id, lines)
        if (styled) File(directory, "$id.ass").writeText(text)
        val language = name.substringBeforeLast('.').substringAfterLast('.').takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2})?")) }.orEmpty()
        SubtitleSource(id, name.take(250).ifBlank { "Subtitles" }, language, if (styled) "ass" else "text")
    }
    suspend fun read(id: String): List<SubtitleLine> = withContext(Dispatchers.IO) {
        file(id).readText().takeIf { it.isNotBlank() }?.let { SubtitleDocument.parse(it) }.orEmpty()
    }
    suspend fun readAss(id: String): ByteArray = withContext(Dispatchers.IO) {
        file(id) // validates the identifier
        File(directory, "$id.ass").readBytes().also { require(it.size <= SubtitleDocument.MAX_BYTES) }
    }
    suspend fun create(title: String, language: String, lines: List<SubtitleLine>, assDocument: String? = null, fonts: List<EmbeddedFont> = emptyList(), embeddedTrackId: String = "", originStamp: String = ""): SubtitleSource {
        val source = SubtitleSource(UUID.randomUUID().toString(), title.take(250), language, if (assDocument == null) "text" else "ass", embeddedTrackId, originStamp)
        write(source.id, lines)
        if (assDocument != null) withContext(Dispatchers.IO) { File(directory, "${source.id}.ass").writeText(assDocument) }
        if (assDocument != null && fonts.isNotEmpty()) withContext(Dispatchers.IO) {
            val folder = File(directory, "${source.id}-fonts").apply { mkdirs() }
            fonts.forEachIndexed { index, font -> File(folder, "$index.ttf").writeBytes(font.bytes) }
        }
        return source
    }
    suspend fun fonts(id: String): Array<String> = withContext(Dispatchers.IO) {
        file(id)
        File(directory, "$id-fonts").listFiles().orEmpty().filter { it.isFile && it.extension == "ttf" }.map { it.absolutePath }.toTypedArray()
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        file(id).delete()
        File(directory, "$id.ass").delete()
        val fonts = File(directory, "$id-fonts")
        fonts.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        fonts.delete()
    }
    suspend fun write(id: String, lines: List<SubtitleLine>) = withContext(Dispatchers.IO) {
        val target = file(id); val pending = File(target.parentFile, "${target.name}.tmp")
        pending.writeText(SubtitleDocument.export(lines)); check(pending.renameTo(target)) { "Cannot save subtitles" }
        File(directory, "$id.ass").delete() // an edited normalized text copy no longer has the original ASS layout
    }
    /** Only siblings covered by an existing SAF tree grant are readable. MediaStore VIDEO grants do not cover text files. */
    suspend fun discover(video: Uri): List<Uri> = withContext(Dispatchers.IO) {
        if (!DocumentsContract.isDocumentUri(context, video) || !DocumentsContract.isTreeUri(video)) return@withContext emptyList()
        val id = DocumentsContract.getDocumentId(video)
        if ('/' !in id) return@withContext emptyList()
        val parent = id.substringBeforeLast('/')
        val base = id.substringAfterLast('/').substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(video, parent)
        val result = mutableListOf<Uri>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 32) {
                val name = cursor.getString(1)?.lowercase(java.util.Locale.ROOT) ?: continue
                val stem = name.substringBeforeLast('.')
                if (name.substringAfterLast('.') in setOf("srt", "vtt", "ass", "ssa") && (stem == base || stem.startsWith("$base.")))
                    result += DocumentsContract.buildDocumentUriUsingTree(video, cursor.getString(0) ?: continue)
            }
        }
        result
    }

    suspend fun discoverInTree(tree: Uri, videoName: String): List<Uri> = withContext(Dispatchers.IO) {
        val base = videoName.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        require(base.isNotBlank()) { "Missing video name" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = mutableListOf<Uri>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 32) {
                val name = cursor.getString(1)?.lowercase(java.util.Locale.ROOT) ?: continue
                val stem = name.substringBeforeLast('.')
                if (name.substringAfterLast('.') in setOf("srt", "vtt", "ass", "ssa") && (stem == base || stem.startsWith("$base.")))
                    result += DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0) ?: continue)
            }
        }
        result
    }
}
