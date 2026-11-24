package com.phnem.vap.ui.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot
import kotlin.math.max

/**
 * Щипок двумя пальцами внутри открытой папки: сведи пальцы — папка сворачивается вслед за ними.
 *
 * Жест перехватывается раньше списка, тапов и долгого нажатия (проход Initial): второй палец отменяет
 * то, что успел начать первый, и до отпускания всё принадлежит щипку. Один палец не трогается вовсе.
 *
 * [onBegin] решает, принимать ли щипок. [onRatio] получает отношение текущего расстояния между пальцами к
 * начальному (меньше 1 — пальцы сошлись) и время события. [onEnd] вызывается один раз, когда один из двух
 * пальцев поднят, с последним отношением.
 */
@Composable
internal fun Modifier.folderPinchClose(
    enabled: Boolean,
    onBegin: () -> Boolean,
    onRatio: (ratio: Float, timeMillis: Long) -> Unit,
    onEnd: (ratio: Float) -> Unit,
): Modifier {
    // Обработчик жеста живёт между рекомпозициями, а лямбды вызывающего — нет: читаем свежие.
    val begin by rememberUpdatedState(onBegin)
    val ratioChanged by rememberUpdatedState(onRatio)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var rejected = false
        var startDistance = 1f
        var ratio = 1f
        var a: PointerId? = null
        var b: PointerId? = null
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (pinching) {
                    val first = event.changes.firstOrNull { it.id == a && it.pressed }
                    val second = event.changes.firstOrNull { it.id == b && it.pressed }
                    if (first != null && second != null) {
                        ratio = distance(first, second) / startDistance
                        ratioChanged(ratio, event.changes.maxOf { it.uptimeMillis })
                    } else {
                        pinching = false
                        end(ratio)
                    }
                    // Всё, что осталось от жеста, принадлежит щипку: иначе оставшийся палец нажмёт карточку.
                    event.changes.forEach { it.consume() }
                } else if (!rejected) {
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        if (pressed.none { it.isConsumed } && begin()) {
                            pinching = true
                            a = pressed[0].id
                            b = pressed[1].id
                            startDistance = max(distance(pressed[0], pressed[1]), MIN_START_DISTANCE)
                            ratio = 1f
                            event.changes.forEach { it.consume() }
                        } else {
                            rejected = true
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            // Жест оборвали (экран пересоздан, ключ сменился): папка не должна остаться на полпути.
            if (pinching) end(ratio)
        }
    }
    }
}

private fun distance(a: PointerInputChange, b: PointerInputChange): Float =
    hypot(a.position.x - b.position.x, a.position.y - b.position.y)

/** Пальцы ближе этого, px, считаются одним касанием: от очень малой базы отношение прыгало бы. */
private const val MIN_START_DISTANCE = 24f
