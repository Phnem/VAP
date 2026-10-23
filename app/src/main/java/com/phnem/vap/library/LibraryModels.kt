package com.phnem.vap.library

import android.net.Uri

/** Тип папки устройства — от него зависит значок-стикер на карточке (design-spec §3.3). */
enum class FolderKind { DOWNLOAD, CAMERA, MOVIES, SCREENSHOTS, DOCUMENTS, GENERIC }

data class VideoFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val key: String get() = uri.toString()

    /**
     * Серия и имя в нижнем регистре считаются один раз на файл. Сортировка звала разбор имени (четыре
     * регулярки) и `lowercase` в компараторе по нескольку раз на каждое сравнение.
     */
    val episode: EpisodeInfo? by lazy(LazyThreadSafetyMode.PUBLICATION) { EpisodeParser.parse(name) }
    val nameKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { name.lowercase(java.util.Locale.getDefault()) }

    /** Ключ превью (адрес, размер, время изменения, версия формата): считается один раз, а не на каждую карточку. */
    val thumbKey: ThumbKey by lazy(LazyThreadSafetyMode.PUBLICATION) { ThumbKey.of(this) }

    /** Название без расширения и без точек-разделителей релиза: `Show.S01E02.mkv` → `Show S01E02`. */
    val title: String
        get() = name.substringBeforeLast('.', name).replace('.', ' ').replace('_', ' ').trim()
}

/**
 * Узел дерева папок. В списке сканирования лежат ВСЕ узлы, в которых есть видео на любой глубине:
 * плоский режим показывает узлы с собственными файлами, режим дерева — корни и вложенность.
 */
data class VideoFolder(
    val id: String,
    val name: String,
    val uri: Uri,
    val kind: FolderKind,
    /** Постер из файла рядом с видео (`folder.jpg`, `poster.jpg`…). null — постера нет. */
    val posterUri: Uri?,
    /** Видео прямо в этой папке. */
    val files: List<VideoFile>,
    val parentId: String? = null,
    val childIds: List<String> = emptyList(),
    /** Число видео в папке и во всех вложенных. */
    val totalCount: Int = files.size,
    val totalSize: Long = files.sumOf { it.sizeBytes },
    val newestModified: Long = files.maxOfOrNull { it.lastModified } ?: 0L,
)
