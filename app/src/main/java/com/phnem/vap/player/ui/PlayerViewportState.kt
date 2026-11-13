package com.phnem.vap.player.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class PlayerViewportState {
    var geometry by mutableStateOf(VideoGeometry(1f, 1f, 1f, 1f))
        private set
    var transform by mutableStateOf(VideoTransform())
        private set
    val zoomed: Boolean get() = transform.zoom > 1.01f
    val transformed: Boolean get() = zoomed

    fun updateGeometry(value: VideoGeometry) {
        geometry = value
        transform = transform.copy(x = transform.x.coerceIn(-value.limitX(transform.zoom), value.limitX(transform.zoom)),
            y = transform.y.coerceIn(-value.limitY(transform.zoom), value.limitY(transform.zoom)))
    }

    /** Scale about the viewport centre; the picture never shifts, so there is nothing to pan. */
    fun zoomBy(zoom: Float) {
        transform = transformVideo(transform, geometry, zoom, 0f, 0f, geometry.viewportWidth / 2f, geometry.viewportHeight / 2f)
    }

    fun reset() { transform = VideoTransform() }
}
