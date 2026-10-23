package com.phnem.vap.player.ui

import kotlin.math.max
import kotlin.math.min

/** Crop adds a 25% close-up to Fill; both preserve the picture's proportions. */
enum class VideoFit { FIT, FILL, CROP, STRETCH, ORIGINAL }

const val MAX_VIDEO_ZOOM = 8f

/** All dimensions and translations are physical display pixels, including Original (1:1). */
data class VideoGeometry(val viewportWidth: Float, val viewportHeight: Float, val width: Float, val height: Float) {
    fun limitX(zoom: Float) = max(0f, (width * zoom - viewportWidth) / 2f)
    fun limitY(zoom: Float) = max(0f, (height * zoom - viewportHeight) / 2f)
}

fun videoGeometry(viewportWidth: Int, viewportHeight: Int, videoWidth: Int, videoHeight: Int,
    pixelRatio: Float, fit: VideoFit): VideoGeometry {
    val vw = viewportWidth.coerceAtLeast(1).toFloat()
    val vh = viewportHeight.coerceAtLeast(1).toFloat()
    val ratio = pixelRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val w = videoWidth.coerceAtLeast(1) * ratio
    val h = videoHeight.coerceAtLeast(1).toFloat()
    val scale = when (fit) {
        VideoFit.FIT -> min(vw / w, vh / h)
        VideoFit.FILL -> max(vw / w, vh / h)
        VideoFit.CROP -> max(vw / w, vh / h) * 1.25f
        VideoFit.ORIGINAL -> 1f
        VideoFit.STRETCH -> return VideoGeometry(vw, vh, vw, vh)
    }
    return VideoGeometry(vw, vh, w * scale, h * scale)
}

data class VideoTransform(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f)

/** Zoom about the fingers, then clamp the picture so panning never exposes a new black edge. */
fun transformVideo(old: VideoTransform, geometry: VideoGeometry, zoomChange: Float,
    panX: Float, panY: Float, focusX: Float, focusY: Float): VideoTransform {
    if (!zoomChange.isFinite() || !panX.isFinite() || !panY.isFinite() ||
        !focusX.isFinite() || !focusY.isFinite() || zoomChange <= 0f) return old
    val zoom = (old.zoom * zoomChange).coerceIn(1f, MAX_VIDEO_ZOOM)
    val factor = zoom / old.zoom
    val cx = focusX - geometry.viewportWidth / 2f
    val cy = focusY - geometry.viewportHeight / 2f
    fun clamp(value: Float, limit: Float) = if (limit == 0f) 0f else value.coerceIn(-limit, limit)
    return VideoTransform(zoom,
        clamp(cx + (old.x - cx) * factor + panX, geometry.limitX(zoom)),
        clamp(cy + (old.y - cy) * factor + panY, geometry.limitY(zoom)))
}
