package com.phnem.vap.player.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

@Stable
class PlayerViewportState {
    var geometry by mutableStateOf(VideoGeometry(1f, 1f, 1f, 1f))
        private set
    var transform by mutableStateOf(VideoTransform())
        private set
    val zoomed: Boolean get() = transform.zoom > 1.01f
    val transformed: Boolean get() = zoomed || kotlin.math.abs(transform.x) > 1f || kotlin.math.abs(transform.y) > 1f
    val canPan: Boolean get() = zoomed || geometry.limitX(1f) > 1f || geometry.limitY(1f) > 1f

    fun updateGeometry(value: VideoGeometry) {
        geometry = value
        transform = transform.copy(x = transform.x.coerceIn(-value.limitX(transform.zoom), value.limitX(transform.zoom)),
            y = transform.y.coerceIn(-value.limitY(transform.zoom), value.limitY(transform.zoom)))
    }

    fun apply(zoom: Float, pan: Offset, focus: Offset) {
        transform = transformVideo(transform, geometry, zoom, pan.x, pan.y, focus.x, focus.y)
    }

    fun reset() { transform = VideoTransform() }
}
