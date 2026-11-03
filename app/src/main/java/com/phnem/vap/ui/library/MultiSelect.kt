package com.phnem.vap.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.toSize
import com.phnem.vap.library.VideoFile
import com.phnem.vap.ui.theme.MotionTokens
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Сколько миниатюр показывает стопка в углу; сколько выбрано всего, видно на её значке. */
internal const val MAX_STACK = 4

/** Как карточка видео выглядит при множественном выборе. */
enum class SelectVisual { NONE, SELECTABLE, SELECTED, PENDING }

/**
 * Множественный выбор видео по удержанию.
 *
 * Фазы: [Phase.SELECTING] — отмечаем карточки, сверху справа стеклянный док с действиями; [Phase.MOVING] —
 * выбранные «улетели» стопкой в левый верхний угол и ждут папку назначения (док превратился в два пузырька:
 * принять и отменить), пользователь ходит по библиотеке, стопка остаётся поверх.
 *
 * Полёты (как у [FolderMorph]): один общий прогресс на фазу и прямоугольники в координатах корня [root],
 * считанные из размещённых узлов только в момент перехода, поэтому прокрутка не пишет в состояние.
 */
@Stable
class MultiSelect {
    enum class Phase { IDLE, SELECTING, MOVING }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** Выбранные видео в порядке выбора. */
    var selection by mutableStateOf<List<VideoFile>>(emptyList())
        private set
    private var keySet by mutableStateOf<Set<String>>(emptySet())

    /** Папка, где начался выбор: в неё переносить нельзя. */
    var sourceFolderId: String? = null
        private set

    /** 0 — миниатюры на своих карточках, 1 — стопка в углу. */
    val gather = Animatable(0f)

    /** 0 — стопка в углу, 1 — миниатюры сели в сетку папки назначения. */
    val drop = Animatable(0f)

    /** 0 — на месте, 1 — стопка и пузырьки улетели вверх за экран. */
    val eject = Animatable(0f)

    var dropping by mutableStateOf(false)
        private set
    var ejecting by mutableStateOf(false)
        private set
    var gatherFrom by mutableStateOf<List<Rect?>>(emptyList())
        private set
    var dropTo by mutableStateOf<List<Rect?>>(emptyList())
        private set

    internal val cardCoords = HashMap<String, LayoutCoordinates>()
    internal val dropCoords = HashMap<Int, LayoutCoordinates>()
    internal var root: LayoutCoordinates? = null

    fun visualOf(file: VideoFile): SelectVisual = when {
        file.key !in keySet -> if (phase == Phase.SELECTING) SelectVisual.SELECTABLE else SelectVisual.NONE
        phase == Phase.MOVING -> SelectVisual.PENDING
        else -> SelectVisual.SELECTED
    }

    /** Отметить или снять отметку. После начала переноса выбор не меняется. */
    fun toggle(file: VideoFile, folderId: String): Boolean {
        if (phase == Phase.MOVING || ejecting) return false
        val next = if (file.key in keySet) selection.filterNot { it.key == file.key } else selection + file
        selection = next
        keySet = next.mapTo(HashSet()) { it.key }
        if (next.isEmpty()) {
            phase = Phase.IDLE
            sourceFolderId = null
        } else {
            if (phase == Phase.IDLE) sourceFolderId = folderId
            phase = Phase.SELECTING
        }
        return true
    }

    /** Снять выбор без полётов: отмена до переноса, действие выполнено. */
    fun clearSelection() {
        selection = emptyList()
        keySet = emptySet()
        phase = Phase.IDLE
        sourceFolderId = null
        cardCoords.clear()
    }

    /** «Переместить»: миниатюры улетают с карточек в левый верхний угол. */
    suspend fun gatherToCorner(reducedMotion: Boolean) {
        if (phase != Phase.SELECTING) return
        gatherFrom = selection.take(MAX_STACK).map { rectOf(cardCoords[it.key]) }
        phase = Phase.MOVING
        gather.snapTo(0f)
        if (reducedMotion) gather.snapTo(1f) else gather.animateTo(1f, MotionTokens.largeSurfaceEnter())
    }

    /** Принять перенос: стопка летит в сетку открытой папки и растворяется там. */
    suspend fun dropIntoFolder(reducedMotion: Boolean) {
        if (phase != Phase.MOVING) return
        dropTo = selection.take(MAX_STACK).indices.map { rectOf(dropCoords[it]) }
        dropping = true
        try {
            drop.snapTo(0f)
            if (!reducedMotion) drop.animateTo(1f, MotionTokens.largeSurfaceEnter())
        } finally {
            withContext(NonCancellable) { finish() }
        }
    }

    /** Отменить: стопка и пузырьки «сбрасываются» вверх за экран, ничего не переносится. */
    suspend fun eject(reducedMotion: Boolean) {
        if (phase == Phase.IDLE || ejecting) return
        ejecting = true
        try {
            eject.snapTo(0f)
            if (!reducedMotion) eject.animateTo(1f, tween(380, easing = FastOutLinearInEasing))
        } finally {
            withContext(NonCancellable) { finish() }
        }
    }

    private suspend fun finish() {
        clearSelection()
        dropping = false
        ejecting = false
        gatherFrom = emptyList()
        dropTo = emptyList()
        dropCoords.clear()
        gather.snapTo(0f)
        drop.snapTo(0f)
        eject.snapTo(0f)
    }

    internal fun rectOf(coords: LayoutCoordinates?): Rect? {
        val r = root ?: return null
        if (coords == null || !coords.isAttached || !r.isAttached) return null
        return Rect(r.localPositionOf(coords, Offset.Zero), coords.size.toSize())
    }
}
