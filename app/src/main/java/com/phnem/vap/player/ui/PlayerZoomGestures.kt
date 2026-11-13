package com.phnem.vap.player.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

@Stable
class PlayerPinchState {
    var multiTouchActive by mutableStateOf(false)
        internal set
    private var pinchEndedAt by mutableLongStateOf(0L)
    fun swipesBlocked(): Boolean = multiTouchActive || SystemClock.uptimeMillis() - pinchEndedAt < POST_PINCH_LOCK_MS
    internal fun notePinchEnded() { pinchEndedAt = SystemClock.uptimeMillis() }
}

/**
 * Reserve pinches before tap/seek/level recognizers. A pinch only scales the picture about the screen
 * centre: it is never dragged, so one finger always belongs to seek/brightness/volume, zoomed or not.
 */
fun Modifier.playerViewportGestures(state: PlayerPinchState, viewport: PlayerViewportState,
    enabled: Boolean, gestureKey: Any?): Modifier = pointerInput(state, viewport, enabled, gestureKey) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var transforming = false
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.isConsumed }) break
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    transforming = true
                    state.multiTouchActive = true
                    if (enabled) viewport.zoomBy(event.calculateZoom())
                } else if (!transforming) continue
                // Consume the second DOWN too: two stationary fingers must cancel a 2x hold.
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        } finally {
            state.multiTouchActive = false
            if (transforming) state.notePinchEnded()
        }
    }
}
