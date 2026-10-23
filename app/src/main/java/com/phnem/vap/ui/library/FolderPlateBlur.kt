package com.phnem.vap.ui.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * То, что просвечивает сквозь переднюю матовую пластину папки: задняя пластина и «листы» (кадры
 * видео), размытые один раз.
 *
 * Раньше пластина размывала живой слой (`layerBackdrop` на каждую карточку). Это стоило на устройстве
 * около 35 выделений видеопамяти и 800 заливок на кадр при прокрутке сетки, и сетка шла на 20 fps при
 * 120 Гц. Содержимое под пластиной при прокрутке не меняется, поэтому оно считается один раз в
 * маленькую картинку (1/[SCALE] размера), размывается на процессоре и дальше только растягивается.
 */
internal object FolderPlateBlur {
    private const val SCALE = 6

    private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }

    /**
     * Ключ кэша: папка, тема, размер и файлы листов (загруженные отмечены). По идентичности Bitmap
     * ключ делать нельзя: кадр, вытесненный из `ThumbnailCache` и прочитанный заново, получал бы новый
     * ключ, и размытие пересчитывалось бы во время анимации возврата на главный экран.
     */
    fun key(folderId: String, dark: Boolean, widthPx: Int, heightPx: Int, fileKeys: List<String>, frames: List<Bitmap?>): String =
        buildString {
            append(folderId).append('|').append(dark).append('|').append(widthPx).append('x').append(heightPx)
            fileKeys.forEachIndexed { i, key -> append('|').append(if (frames.getOrNull(i) != null) key else "-") }
        }

    /** Готовая картинка из кэша без вычисления: нужна, чтобы первый кадр карточки не был пустым. */
    fun peek(key: String): Bitmap? = cache.get(key)

    /**
     * Картинка для нижней части карточки: [plateHeightPx] из [heightPx], ширина [widthPx].
     * Геометрия листов повторяет `Paper` в `FolderCard`: ширина 0,56 ширины карточки, 16:10, позы из
     * [PaperPoses]. Вызывать не на главном потоке.
     */
    fun render(
        key: String,
        frames: List<Bitmap?>,
        widthPx: Int,
        heightPx: Int,
        plateHeightPx: Int,
        density: Float,
        dark: Boolean,
        blurDp: Float,
    ): Bitmap {
        cache.get(key)?.let { return it }
        val mw = max(1, ceil(widthPx / SCALE.toFloat()).toInt())
        val mh = max(1, ceil(plateHeightPx / SCALE.toFloat()).toInt())
        val bitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(mw / widthPx.toFloat(), mh / plateHeightPx.toFloat())
        canvas.translate(0f, -(heightPx - plateHeightPx).toFloat())
        canvas.drawColor(if (dark) 0xFF2B2B2B.toInt() else 0xFFE4DDC9.toInt())

        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val paperW = w * 0.56f
        val paperH = paperW * 10f / 16f
        val corner = 9f * density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.White.copy(alpha = if (dark) 0.25f else 0.9f).toArgb()
        }
        for (i in intArrayOf(1, 2, 0)) {
            val pose = PaperPoses[i]
            val left = (w - paperW) / 2f + w * pose.dx
            val top = h * (0.04f + pose.dy)
            val rect = RectF(left, top, left + paperW, top + paperH)
            canvas.save()
            canvas.rotate(pose.rotation, rect.centerX(), rect.centerY())
            fill.color = paperColor(i, dark).toArgb()
            canvas.drawRoundRect(rect, corner, corner, fill)
            readable(frames.getOrNull(i))?.let { frame ->
                canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) })
                canvas.drawBitmap(frame, centerCrop(frame, paperW / paperH), rect, image)
                canvas.restore()
            }
            canvas.drawRoundRect(rect, corner, corner, rim)
            canvas.restore()
        }

        val radius = (blurDp * density / SCALE * 0.9f).roundToInt().coerceIn(1, max(1, min(mw, mh) / 2))
        val pixels = IntArray(mw * mh)
        bitmap.getPixels(pixels, 0, mw, 0, 0, mw, mh)
        boxBlur(pixels, mw, mh, radius)
        bitmap.setPixels(pixels, 0, mw, 0, 0, mw, mh)
        // Подложка только рисуется, пиксели из неё не читаются: аппаратная копия грузится в GPU один раз.
        val result = runCatching { bitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()?.also { bitmap.recycle() } ?: bitmap
        cache.put(key, result)
        return result
    }

    /** Аппаратные и освобождённые картинки на программный холст не рисуются. */
    private fun readable(bitmap: Bitmap?): Bitmap? = when {
        bitmap == null || bitmap.isRecycled -> null
        bitmap.config == Bitmap.Config.HARDWARE -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else -> bitmap
    }

    private fun centerCrop(bitmap: Bitmap, aspect: Float): Rect {
        val bw = bitmap.width
        val bh = bitmap.height
        return if (bw.toFloat() / bh > aspect) {
            val cropW = (bh * aspect).roundToInt().coerceAtMost(bw)
            Rect((bw - cropW) / 2, 0, (bw - cropW) / 2 + cropW, bh)
        } else {
            val cropH = (bw / aspect).roundToInt().coerceAtMost(bh)
            Rect(0, (bh - cropH) / 2, bw, (bh - cropH) / 2 + cropH)
        }
    }

    /** Три прохода коробочного размытия по каждой оси дают близкое к гауссову размытие. */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        repeat(3) {
            pass(px, tmp, w, h, r, horizontal = true)
            pass(tmp, px, w, h, r, horizontal = false)
        }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var a = 0
            var red = 0
            var green = 0
            var blue = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, length - 1) * step]
                a += p ushr 24
                red += (p shr 16) and 255
                green += (p shr 8) and 255
                blue += p and 255
            }
            for (i in 0 until length) {
                dst[base + i * step] = ((a / div) shl 24) or ((red / div) shl 16) or ((green / div) shl 8) or (blue / div)
                val add = src[base + min(i + r + 1, length - 1) * step]
                val sub = src[base + max(i - r, 0) * step]
                a += (add ushr 24) - (sub ushr 24)
                red += ((add shr 16) and 255) - ((sub shr 16) and 255)
                green += ((add shr 8) and 255) - ((sub shr 8) and 255)
                blue += (add and 255) - (sub and 255)
            }
        }
    }
}
