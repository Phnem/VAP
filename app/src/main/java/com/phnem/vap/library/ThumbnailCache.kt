package com.phnem.vap.library

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.Trace
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Миниатюры: кадр видео в трёх размерах ([ThumbTier]), обложка внутри файла и постер-файл.
 * Ошибки чтения наружу не бросаются: нет миниатюры — карточка просто без неё.
 *
 * Кадры идут по цепочке «память → хранилище на диске ([ThumbnailStore]) → декодирование видео».
 * Декодирование делается один раз на файл и даёт сразу три размера; повторный показ и возврат по
 * списку берут готовую картинку из хранилища (около 4–5 мс на потоке ввода-вывода по замеру на Xiaomi 13,
 * из них почти всё — создание аппаратного битмапа), а не декодируют видео (200–300 мс). Ключ — адрес,
 * размер и время изменения файла плюс версия формата ([ThumbKey]).
 */
object ThumbnailCache {
    // Память по размерам отдельно: прокрутка длинной папки (много кадров сетки) не должна вытеснять
    // листы карточек главного экрана. Кадры аппаратные, живут в видеопамяти, а не в куче.
    private val frames: Map<ThumbTier, LruCache<String, Bitmap>> = ThumbTier.entries.associateWith { tier ->
        object : LruCache<String, Bitmap>(budgetKb(tier)) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private fun budgetKb(tier: ThumbTier): Int {
        val heapKb = Runtime.getRuntime().maxMemory() / 1024
        val share = when (tier) {
            ThumbTier.CARD -> heapKb / 12
            ThumbTier.GRID -> heapKb / 6
            ThumbTier.LARGE -> heapKb / 16
        }
        return share.toInt().coerceAtLeast(8 * 1024)
    }

    /** Постеры и обложки: небольшие, живут только в памяти. */
    private val posters = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    @Volatile
    private var storeRef: ThumbnailStore? = null

    internal fun store(context: Context): ThumbnailStore = storeRef ?: synchronized(this) {
        storeRef ?: ThumbnailStore(context.applicationContext).also {
            storeRef = it
            context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        }
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> frames.values.forEach { it.evictAll() }
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    frames[ThumbTier.GRID]?.let { it.trimToSize(it.size() / 2) }
                    frames[ThumbTier.LARGE]?.let { it.trimToSize(it.size() / 2) }
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> frames.values.forEach { it.trimToSize(it.size() / 2) }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = frames.values.forEach { it.evictAll() }
    }

    /** Кадр, уже лежащий в памяти: можно читать прямо из композиции, без пустого первого кадра. */
    fun peek(file: VideoFile, tier: ThumbTier): Bitmap? = frames.getValue(tier).get(file.thumbKey.id)

    /**
     * Загрузки живут в своей области, а не в корутине первого заказчика: один и тот же кадр нужен
     * карточке папки, карточке файла и летящему листу одновременно, и без общего запроса каждый из
     * них читал бы его сам. Уход заказчика с экрана загрузку не отменяет, поэтому кадр всё равно
     * попадает в память.
     */
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loading = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val generating = ConcurrentHashMap<String, Deferred<Boolean>>()

    /** Не больше трёх одновременных декодирований видео по требованию экрана; фоновой очереди — свои места. */
    private val screenGate = Semaphore(3)
    private val screenBusy = AtomicInteger()

    /** Идёт ли сейчас работа по требованию экрана: фоновая очередь в это время уступает. */
    internal val screenIsBusy: Boolean get() = screenBusy.get() > 0

    /** Кадр нужного размера для карточки; null — кадра нет (нет видеодорожки или файл не читается). */
    suspend fun frame(context: Context, file: VideoFile, tier: ThumbTier): Bitmap? {
        val id = file.thumbKey.id
        frames.getValue(tier).get(id)?.let {
            Trace.beginSection("VAP.thumb.mem")
            Trace.endSection()
            return it
        }
        val app = context.applicationContext
        val flight = "$id.${tier.tag}"
        val job = loading.computeIfAbsent(flight) {
            loaderScope.async(start = CoroutineStart.LAZY) { load(app, file, tier) }
                .also { created -> created.invokeOnCompletion { loading.remove(flight, created) } }
        }
        job.start()
        return job.await()
    }

    private suspend fun load(context: Context, file: VideoFile, tier: ThumbTier): Bitmap? {
        val key = file.thumbKey
        val cache = frames.getValue(tier)
        cache.get(key.id)?.let { return it }
        val store = store(context)
        if (store.isFailed(key) || key.id in failedThisSession) return null
        var bitmap = traced("VAP.thumb.disk") { store.read(key, tier) }
        if (bitmap == null) {
            screenBusy.incrementAndGet()
            val ready = try {
                screenGate.withPermit { ensure(context, file, background = false) }
            } finally {
                screenBusy.decrementAndGet()
            }
            if (!ready) return null
            bitmap = traced("VAP.thumb.disk") { store.read(key, tier) } ?: return null
        }
        cache.put(key.id, bitmap)
        return bitmap
    }

    /**
     * Все три размера файла лежат в хранилище (или файл помечен как не дающий кадра). Одновременные
     * вызовы для одного файла делят один запуск. Возвращает false, если кадра получить не удалось.
     */
    internal suspend fun ensure(context: Context, file: VideoFile, background: Boolean): Boolean {
        val key = file.thumbKey
        val store = store(context)
        if (store.isReady(key)) return !store.isFailed(key)
        if (key.id in failedThisSession) return false
        val app = context.applicationContext
        val job = generating.computeIfAbsent(key.id) {
            loaderScope.async(start = CoroutineStart.LAZY) { generate(app, file, background) }
                .also { created -> created.invokeOnCompletion { generating.remove(key.id, created) } }
        }
        job.start()
        return job.await()
    }

    /** Файлы, с которых в этом запуске кадр получить не удалось: повторять в том же запуске бессмысленно. */
    private val failedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private sealed interface Extracted {
        class Frame(val bitmap: Bitmap) : Extracted
        /** Источник открылся, но видеодорожки нет: пометка в хранилище, повторять не нужно. */
        data object NoVideo : Extracted
        /** Источник не открылся или декодер не справился: может быть временным, на диск не пишется. */
        data object Failed : Extracted
    }

    private fun generate(context: Context, file: VideoFile, background: Boolean): Boolean {
        val key = file.thumbKey
        val store = store(context)
        val previous = Process.getThreadPriority(0)
        if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return traced("VAP.thumb.gen") {
                when (val result = extractFrame(context, file.uri, ThumbTier.LARGE.widthPx)) {
                    is Extracted.Frame -> {
                        val encoded = encodeAll(result.bitmap)
                        store.write(key, encoded).also { ok -> if (!ok) failedThisSession.add(key.id) }
                    }
                    Extracted.NoVideo -> {
                        store.markFailed(key)
                        false
                    }
                    Extracted.Failed -> {
                        failedThisSession.add(key.id)
                        false
                    }
                }
            }
        } finally {
            if (background) Process.setThreadPriority(previous)
        }
    }

    /**
     * Три размера из одного кадра. Каждый следующий уменьшается от предыдущего (720 → 480 → 320), а не
     * от исходного: так уменьшение в 1,5 раза за шаг даёт ровнее картинку, чем в 2,25 раза сразу.
     */
    private fun encodeAll(frame: Bitmap): Map<ThumbTier, ByteArray> {
        val out = EnumMap<ThumbTier, ByteArray>(ThumbTier::class.java)
        var current = frame
        for (tier in listOf(ThumbTier.LARGE, ThumbTier.GRID, ThumbTier.CARD)) {
            val scaled = current.scaledToWidth(tier.widthPx)
            out[tier] = compress(scaled, tier.quality)
            if (current !== frame && current !== scaled) current.recycle()
            current = scaled
        }
        if (current !== frame) current.recycle()
        frame.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        return ByteArrayOutputStream(32 * 1024).also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    private fun extractFrame(context: Context, uri: Uri, widthPx: Int): Extracted {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (_: Exception) {
                return Extracted.Failed
            }
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return Extracted.NoVideo
            fun num(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val durationMs = num(MediaMetadataRetriever.METADATA_KEY_DURATION)
            // Не первый кадр: он часто чёрный. Берём ~10% длительности, но не раньше секунды.
            val atUs = (durationMs * 100L).coerceAtLeast(1_000_000L)
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    // Кадр сразу нужного размера: полноразмерный 4K в ARGB_8888 весит ~32 МБ, а несколько таких
                    // за раз — почти сотня мегабайт временных bitmap.
                    var w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                    var h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                    val rotation = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
                    if (rotation == 90 || rotation == 270) w = h.also { h = w }
                    val scaled = if (w > 0 && h > 0) {
                        val dstW = minOf(widthPx, w)
                        val dstH = (h * dstW.toFloat() / w).toInt().coerceAtLeast(1)
                        retriever.getScaledFrameAtTime(atUs, option, dstW, dstH)
                    } else {
                        null
                    }
                    scaled ?: retriever.getFrameAtTime(atUs, option)?.scaledToWidth(widthPx)
                } else {
                    retriever.getFrameAtTime(atUs, option)?.scaledToWidth(widthPx)
                }
            }.getOrNull()
            return if (bitmap != null) Extracted.Frame(bitmap) else Extracted.Failed
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Обложка, вшитая в файл (MKV/MP4 attachments). null, если её нет. */
    suspend fun embeddedCover(context: Context, uri: Uri, widthPx: Int = 320): Bitmap? =
        loadPoster("e:$uri:$widthPx") {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    ?.scaledToWidth(widthPx)
            } finally {
                runCatching { retriever.release() }
            }
        }

    /** Постер-файл рядом с видео. */
    suspend fun image(context: Context, uri: Uri, widthPx: Int = 320): Bitmap? =
        loadPoster("i:$uri:$widthPx") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.scaledToWidth(widthPx)
        }

    private val posterGate = Semaphore(2)

    /** Метаданные идут своей очередью: иначе пачка `ensureMeta` занимает все места, и кадры ждут. */
    private val metaGate = Semaphore(2)

    /** Длительность и разрешение одного файла; считывается один раз и сохраняется в БД. */
    suspend fun readMeta(context: Context, uri: Uri): FileMeta? = metaGate.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    fun num(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
                    FileMeta(
                        durationMs = num(MediaMetadataRetriever.METADATA_KEY_DURATION),
                        width = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(),
                        height = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt(),
                    )
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
        }
    }

    private suspend fun loadPoster(key: String, block: () -> Bitmap?): Bitmap? {
        posters.get(key)?.let { return it }
        val bitmap = posterGate.withPermit { withContext(Dispatchers.IO) { runCatching(block).getOrNull() } }?.toGpu() ?: return null
        posters.put(key, bitmap)
        return bitmap
    }

    /** Аппаратная копия грузится в GPU один раз вне рендер-потока, а не заново на каждом кадре (см. `FolderPlateBlur`). */
    private fun Bitmap.toGpu(): Bitmap {
        if (config == Bitmap.Config.HARDWARE) return this
        val gpu = runCatching { copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return this
        recycle()
        return gpu
    }

    private fun Bitmap.scaledToWidth(target: Int): Bitmap =
        if (width <= target) {
            this
        } else {
            Bitmap.createScaledBitmap(this, target, (height * target.toFloat() / width).toInt().coerceAtLeast(1), true)
        }
}
