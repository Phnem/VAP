package com.phnem.vap.library

import android.content.Context
import android.text.format.Formatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * «163 kB», «1,4 GB»: размер файла в подписи карточки.
 *
 * `Formatter.formatShortFileSize` стоит ~0,4 мс на вызов, а самый первый — около 9 мс (трасса, первый
 * кадр открытия папки: восемь подписей карточек дали 11,9 мс из 13 мс всей подкомпозиции ячеек).
 * Строка по размеру не меняется, поэтому результат запоминается, а первые файлы папки и холодный
 * вызов прогреваются вне главного потока до начала морфа.
 */
internal object ByteSizeText {
    private const val LIMIT = 4096
    private val cache = ConcurrentHashMap<Long, String>()
    @Volatile private var localeTag = ""

    fun format(context: Context, bytes: Long): String {
        val tag = Locale.getDefault().toLanguageTag()
        if (tag != localeTag || cache.size > LIMIT) {
            cache.clear()
            localeTag = tag
        }
        return cache.getOrPut(bytes) { Formatter.formatShortFileSize(context.applicationContext, bytes) }
    }

    fun warm(context: Context, files: List<VideoFile>) {
        for (file in files) format(context, file.sizeBytes)
    }
}
