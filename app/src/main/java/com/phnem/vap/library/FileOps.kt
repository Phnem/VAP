package com.phnem.vap.library

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/** Итог операции над файлами: сколько изменено и какие не удалось (их ключи возвращаются в список). */
class OpResult(val done: Int, val failedKeys: List<String>) {
    val failed: Int get() = failedKeys.size
}

/** Чистые правила: можно ли перенести файл в папку. Без Android, поэтому проверяются JVM-тестами. */
object FileRules {
    /** Видео из MediaStore (папки устройства); остальные — документы выбранных деревьев SAF. */
    fun isMedia(file: VideoFile): Boolean = file.key.startsWith("content://media/")

    /** (том, путь внутри тома) из идентификатора папки устройства; null — это не папка устройства. */
    fun deviceFolder(id: String): Pair<String, String>? {
        if (!DeviceVideoIndex.isDeviceFolder(id)) return null
        val rest = id.removePrefix(DeviceVideoIndex.PREFIX)
        return rest.substringBefore(':') to URLDecoder.decode(rest.substringAfter(':', ""), "UTF-8")
    }

    private fun authority(uri: String): String = uri.substringAfter("://").substringBefore('/')

    /**
     * Файл [file] из папки [from] можно перенести в [dest], если это тот же том устройства (MediaStore меняет
     * только путь внутри тома) или тот же поставщик документов SAF. Корень тома не годится: видео не
     * кладут вне стандартных каталогов.
     */
    fun canMove(file: VideoFile, from: VideoFolder?, dest: VideoFolder): Boolean {
        if (from == null || from.id == dest.id) return false
        val target = deviceFolder(dest.id)
        val source = deviceFolder(from.id)
        return if (isMedia(file)) {
            target != null && source != null && target.first == source.first && target.second.isNotEmpty()
        } else {
            target == null && source == null && authority(file.key) == authority(dest.id)
        }
    }
}

/**
 * Удаление и перенос видео. Без прав на запись файлы чужих приложений менять нельзя: с Android 11 систему
 * просят о согласии отдельным окном ([deleteConsent], [moveConsent]), пользователь подтверждает его, и только
 * тогда выполняется операция. Документы SAF меняются по праву на дерево, выданному при выборе папки.
 */
class FileOps(private val context: Context) {
    private val resolver get() = context.contentResolver

    private fun mediaUris(files: List<VideoFile>) = files.filter(FileRules::isMedia).map { it.uri }

    /** Окно согласия на удаление медиафайлов; null — не нужно (нет медиафайлов или Android до 11). */
    fun deleteConsent(files: List<VideoFile>): IntentSender? {
        val uris = mediaUris(files)
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }

    /** Окно согласия на запись (перенос) медиафайлов; null — не нужно. */
    fun moveConsent(files: List<VideoFile>): IntentSender? {
        val uris = mediaUris(files)
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    /** С Android 11 медиафайлы уже удалила система, когда пользователь согласился ([consented]). */
    suspend fun delete(files: List<VideoFile>, consented: Boolean): OpResult = withContext(Dispatchers.IO) {
        val failed = ArrayList<String>()
        var done = 0
        for (file in files) {
            val ok = runCatching {
                if (FileRules.isMedia(file)) {
                    (consented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) || resolver.delete(file.uri, null, null) > 0
                } else {
                    DocumentsContract.deleteDocument(resolver, file.uri)
                }
            }.getOrDefault(false)
            if (ok) done++ else failed += file.key
        }
        OpResult(done, failed)
    }

    suspend fun move(files: List<VideoFile>, sourceOf: (VideoFile) -> VideoFolder?, dest: VideoFolder): OpResult =
        withContext(Dispatchers.IO) {
            val failed = ArrayList<String>()
            var done = 0
            for (file in files) {
                val from = sourceOf(file)
                val ok = FileRules.canMove(file, from, dest) && runCatching {
                    if (FileRules.isMedia(file)) {
                        val dir = FileRules.deviceFolder(dest.id)!!.second
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/") }
                        resolver.update(file.uri, values, null, null) > 0
                    } else {
                        DocumentsContract.moveDocument(resolver, file.uri, from!!.uri, dest.uri) != null
                    }
                }.getOrDefault(false)
                if (ok) done++ else failed += file.key
            }
            OpResult(done, failed)
        }
}
