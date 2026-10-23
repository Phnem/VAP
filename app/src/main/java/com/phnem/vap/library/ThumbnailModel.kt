package com.phnem.vap.library

import java.security.MessageDigest

/**
 * Три готовых размера превью одного видео. Декодирование видео (сотни миллисекунд) делается один раз
 * на файл, из того же кадра получаются все три размера, и они лежат на диске.
 *
 * Ширины подобраны по реальным ячейкам на экране 1080 px при плотности 440 (2,75 px на dp):
 * - [CARD]: лист на карточке папки главного экрана — 0,56 ширины карточки, около 265×166 px;
 * - [GRID]: карточка файла в открытой папке — 474×267 px в сетке из двух колонок, 352 px в списке;
 * - [LARGE]: «Продолжить просмотр» (220 dp = 605 px) и верх плеера.
 */
enum class ThumbTier(val widthPx: Int, val quality: Int, val tag: String) {
    CARD(320, 80, "c"),
    GRID(480, 85, "g"),
    LARGE(720, 85, "l"),
}

object ThumbFormat {
    /**
     * Версия формата: входит в ключ и в имя каталога хранилища. Смена размеров, качества или способа
     * выбора кадра — повод поднять число: старый каталог удаляется целиком, превью делаются заново.
     * 1 — прежний кэш `cacheDir/thumbs` (JPEG 480 без размера и даты в ключе), его хранилище не читает.
     */
    const val VERSION = 2

    const val EXTENSION = "webp"
    const val FAILED_EXTENSION = "none"
    const val TEMP_EXTENSION = "tmp"
}

/**
 * Ключ превью одного файла: адрес, размер и время изменения плюс версия формата. Заменённый файл с
 * тем же адресом получает другой [id], поэтому старая картинка не показывается.
 */
data class ThumbKey(val uri: String, val sizeBytes: Long, val modified: Long) {
    val id: String = sha1("${ThumbFormat.VERSION}|$uri|$sizeBytes|$modified")

    fun fileName(tier: ThumbTier): String = "$id.${tier.tag}.${ThumbFormat.EXTENSION}"

    /** Пометка «кадр получить не удалось»: без неё файл без видеодорожки перебирался бы при каждом запуске. */
    val failedName: String get() = "$id.${ThumbFormat.FAILED_EXTENSION}"

    companion object {
        fun of(file: VideoFile): ThumbKey = ThumbKey(file.key, file.sizeBytes, file.lastModified)

        /** Идентификатор из имени файла хранилища; null — имя не наше. */
        fun idOf(fileName: String): String? = fileName.substringBefore('.', "").takeIf { it.length == ID_LENGTH }

        private const val ID_LENGTH = 40

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Что уже лежит в хранилище и что нужно сделать: чистые функции над списками имён, без файловой системы. */
object ThumbnailPlan {
    /** Все три размера готовы либо файл помечен как неудавшийся. */
    fun isReady(key: ThumbKey, names: Set<String>): Boolean =
        key.failedName in names || ThumbTier.entries.all { key.fileName(it) in names }

    /** Файлы, которым чего-то не хватает, в исходном порядке и без повторов одного и того же ключа. */
    fun missing(files: List<VideoFile>, names: Set<String>): List<VideoFile> {
        val seen = HashSet<String>()
        return files.filter { file ->
            val key = file.thumbKey
            seen.add(key.id) && !isReady(key, names)
        }
    }

    /**
     * Имена, которые надо удалить: превью исчезнувших или изменённых файлов (их [ThumbKey.id] нет среди
     * [liveIds]), недописанные временные файлы и всё, что не похоже на файл хранилища.
     */
    fun stale(names: Set<String>, liveIds: Set<String>): List<String> = names.filter { name ->
        val id = ThumbKey.idOf(name)
        id == null || name.endsWith(".${ThumbFormat.TEMP_EXTENSION}") || id !in liveIds
    }

    /**
     * Порядок работы «сначала то, что видно»: листы карточек главного экрана в порядке папок, затем
     * «Продолжить», затем остальные файлы папок в порядке главного экрана, затем всё прочее.
     */
    fun priority(
        homeOrder: List<String>,
        sheets: Map<String, List<VideoFile>>,
        recent: List<VideoFile>,
        filesByFolder: Map<String, List<VideoFile>>,
        everything: List<VideoFile>,
    ): List<VideoFile> {
        val seen = HashSet<String>()
        val out = ArrayList<VideoFile>(everything.size)
        fun add(file: VideoFile) { if (seen.add(file.thumbKey.id)) out.add(file) }
        homeOrder.forEach { id -> sheets[id]?.forEach(::add) }
        recent.forEach(::add)
        homeOrder.forEach { id -> filesByFolder[id]?.forEach(::add) }
        everything.forEach(::add)
        return out
    }
}
