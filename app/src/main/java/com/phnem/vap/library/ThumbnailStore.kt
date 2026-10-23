package com.phnem.vap.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Хранилище готовых превью на диске: `filesDir/thumbs/v<версия>/<id>.<c|g|l>.webp`.
 *
 * Лежит в `filesDir`, а не в `cacheDir`: систему «очистки кэша» (её запускают и сама Android при нехватке
 * места, и приложения-чистильщики) превью не касаются, поэтому они не «возвращаются» заново. Записи
 * точные, по ключу [ThumbKey], без вытеснения по давности: размер хранилища ограничен самой
 * библиотекой, а превью исчезнувших и изменённых файлов убирает [ThumbnailPlan.stale].
 *
 * Список имён читается с диска один раз и дальше ведётся в памяти: «готово ли превью» — это поиск в
 * множестве, а не обращение к файловой системе на каждую карточку.
 */
internal class ThumbnailStore(private val context: Context) {
    private val root = File(context.filesDir, "thumbs")
    private val dir = File(root, "v${ThumbFormat.VERSION}")
    private val names: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            dir.mkdirs()
            purgeOldVersions()
            dir.list()?.let(names::addAll)
            loaded = true
        }
    }

    /** Каталоги других версий формата и прежний `cacheDir/thumbs` не читаются и удаляются целиком. */
    private fun purgeOldVersions() {
        root.listFiles()?.filter { it.name != dir.name }?.forEach { it.deleteRecursively() }
        File(context.cacheDir, "thumbs").takeIf { it.exists() }?.deleteRecursively()
    }

    fun snapshot(): Set<String> {
        ensureLoaded()
        return HashSet(names)
    }

    fun isReady(key: ThumbKey): Boolean {
        ensureLoaded()
        return ThumbnailPlan.isReady(key, names)
    }

    fun isFailed(key: ThumbKey): Boolean {
        ensureLoaded()
        return key.failedName in names
    }

    fun has(key: ThumbKey, tier: ThumbTier): Boolean {
        ensureLoaded()
        return key.fileName(tier) in names
    }

    /** Готовая картинка из хранилища сразу в видеопамяти; null — нет файла или он повреждён (тогда удаляется). */
    fun read(key: ThumbKey, tier: ThumbTier): Bitmap? {
        ensureLoaded()
        val name = key.fileName(tier)
        if (name !in names) return null
        val file = File(dir, name)
        val bitmap = runCatching {
            // Аппаратный битмап декодируется сразу в видеопамять: без промежуточной копии в куче.
            val gpu = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
            BitmapFactory.decodeFile(file.path, gpu) ?: BitmapFactory.decodeFile(file.path)
        }.getOrNull()
        if (bitmap == null) {
            names.remove(name)
            file.delete()
        }
        return bitmap
    }

    /** Все размеры одного файла: сначала временные файлы, затем переименование, чтобы недописанный не считался готовым. */
    fun write(key: ThumbKey, encoded: Map<ThumbTier, ByteArray>): Boolean {
        ensureLoaded()
        dir.mkdirs()
        return runCatching {
            for ((tier, bytes) in encoded) {
                val name = key.fileName(tier)
                val temp = File(dir, "$name.${ThumbFormat.TEMP_EXTENSION}")
                temp.writeBytes(bytes)
                val target = File(dir, name)
                if (!temp.renameTo(target)) {
                    target.delete()
                    check(temp.renameTo(target))
                }
                names.add(name)
            }
            true
        }.getOrDefault(false)
    }

    fun markFailed(key: ThumbKey) {
        ensureLoaded()
        runCatching {
            File(dir, key.failedName).createNewFile()
            names.add(key.failedName)
        }
    }

    fun delete(stale: Collection<String>) {
        ensureLoaded()
        stale.forEach { name ->
            File(dir, name).delete()
            names.remove(name)
        }
    }

    /** Занятое место, байт: для проверок и отчёта. */
    fun bytesUsed(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}
