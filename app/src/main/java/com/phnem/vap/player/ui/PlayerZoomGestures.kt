package com.phnem.vap.player.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

@Stable
class PlayerPinchState {
    var multiTouchActive by mutableStateOf(false)
        internal set
    private var pinchEndedAt by mutableLongStateOf(0L)
    fun swipesBlocked(): Boolean = multiTouchActive || SystemClock.uptimeMillis() - pinchEndedAt < POST_PINCH_LOCK_MS
    internal fun notePinchEnded() { pinchEndedAt = SystemClock.uptimeMillis() }
}

/** Reserve transforms before tap/seek/level recognizers. A remaining pinch finger never seeks. */
fun Modifier.playerViewportGestures(state: PlayerPinchState, viewport: PlayerViewportState,
    enabled: Boolean, gestureKey: Any?): Modifier = pointerInput(state, viewport, enabled, gestureKey) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var transforming = false
        var moved = 0f
        val panAtStart = enabled && viewport.canPan
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.isConsumed }) break
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    transforming = true
                    state.multiTouchActive = true
                    if (enabled) viewport.apply(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = false))
                } else if (transforming) {
                    // Wait for the last pointer without moving or seeking.
                } else if (panAtStart && pressed > 0) {
                    val pan = event.calculatePan()
                    moved += pan.getDistance()
                    if (moved > viewConfiguration.touchSlop) {
                        state.multiTouchActive = true
                        viewport.apply(1f, pan, event.calculateCentroid())
                    }
                } else continue
                if (transforming || moved > viewConfiguration.touchSlop) {
                    // Consume the second DOWN too: two stationary fingers must cancel a 2x hold.
                    event.changes.forEach { if (transforming || it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            state.multiTouchActive = false
            if (transforming || moved > viewConfiguration.touchSlop) state.notePinchEnded()
        }
    }
}
