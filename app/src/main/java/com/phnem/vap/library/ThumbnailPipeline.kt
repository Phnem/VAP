package com.phnem.vap.library

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Фоновая подготовка превью всей библиотеки: после каждого полного обхода берёт файлы, у которых в
 * хранилище нет готовых размеров ([ThumbnailPlan.missing]), и делает их в порядке «сначала то, что
 * видно». Новые и изменённые файлы подхватываются тем же способом: у них другой [ThumbKey].
 *
 * Живёт в области приложения, а не экрана: уход с экрана (например, пока владелец выбирает язык и тему
 * на первом запуске) работу не отменяет. Состояния на диске достаточно, чтобы продолжить после
 * гибели процесса: следующий обход заново посчитает, чего не хватает, и доделает остальное.
 *
 * Бережёт телефон: два рабочих потока с фоновым приоритетом, уступает экрану (пока карточки сами
 * просят кадры), останавливается в режиме энергосбережения и при заметном нагреве.
 */
object ThumbnailPipeline {
    /** Сколько файлов нужно подготовить в текущем проходе и сколько уже готово. */
    data class Progress(val total: Int = 0, val done: Int = 0) {
        val running: Boolean get() = done < total
    }

    private const val WORKERS = 2

    private val mutableProgress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = mutableProgress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * [ordered] — файлы библиотеки в порядке важности ([ThumbnailPlan.priority]). [sweep] — это полный
     * свежий обход: только тогда превью, которым не нашлось файла, удаляются (по неполному списку,
     * например из кэша до обхода, удалять нельзя).
     */
    @Synchronized
    fun submit(context: Context, ordered: List<VideoFile>, sweep: Boolean) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch { run(app, ordered, sweep) }
    }

    private suspend fun run(context: Context, ordered: List<VideoFile>, sweep: Boolean) {
        val store = ThumbnailCache.store(context)
        val names = withContext(Dispatchers.IO) { store.snapshot() }
        if (sweep && ordered.isNotEmpty()) {
            val live = ordered.mapTo(HashSet()) { it.thumbKey.id }
            val stale = ThumbnailPlan.stale(names, live)
            if (stale.isNotEmpty()) withContext(Dispatchers.IO) { store.delete(stale) }
        }
        val todo = ThumbnailPlan.missing(ordered, names)
        mutableProgress.value = Progress(todo.size, 0)
        if (todo.isEmpty()) return
        val next = AtomicInteger()
        val done = AtomicInteger()
        coroutineScope {
            repeat(WORKERS) {
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val index = next.getAndIncrement()
                        if (index >= todo.size) break
                        yieldToScreenAndBattery(context)
                        ThumbnailCache.ensure(context, todo[index], background = true)
                        mutableProgress.value = Progress(todo.size, done.incrementAndGet())
                    }
                }
            }
        }
    }

    private suspend fun yieldToScreenAndBattery(context: Context) {
        while (true) {
            when {
                ThumbnailCache.screenIsBusy -> delay(150)
                shouldPause(context) -> delay(5_000)
                else -> return
            }
        }
    }

    private fun shouldPause(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (power.isPowerSaveMode) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }
}
