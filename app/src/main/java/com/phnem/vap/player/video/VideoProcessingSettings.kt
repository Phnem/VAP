package com.phnem.vap.player.video

import kotlin.math.pow

enum class DecoderMode { AUTO, HARDWARE, SOFTWARE }
enum class HdrOutput { AUTO, SDR }

data class PictureSettings(val brightness: Float = 0f, val contrast: Float = 1f,
    val saturation: Float = 1f, val gamma: Float = 1f) {
    val neutral: Boolean get() = this == PictureSettings()
    fun sanitized() = PictureSettings(brightness.safe(-0.5f, 0.5f, 0f), contrast.safe(0.5f, 1.5f, 1f),
        saturation.safe(0f, 2f, 1f), gamma.safe(0.5f, 2.5f, 1f))
}

private fun Float.safe(min: Float, max: Float, default: Float) = if (isFinite()) coerceIn(min, max) else default

/** Electrical RGB, shared by GIF generation and the GPU shader's corresponding operation. */
fun adjustRgb(r: Float, g: Float, b: Float, settings: PictureSettings): FloatArray {
    val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
    fun channel(value: Float) = (((luma + (value - luma) * settings.saturation - 0.5f) * settings.contrast
        + 0.5f + settings.brightness).coerceIn(0f, 1f)).pow(1f / settings.gamma)
    return floatArrayOf(channel(r), channel(g), channel(b))
}

data class ExportRange(val startMs: Long, val endMs: Long) {
    fun valid(durationMs: Long, maxDurationMs: Long): Boolean = startMs >= 0 && endMs > startMs &&
        endMs <= durationMs && endMs - startMs <= maxDurationMs
}

/** A bounded retry budget; a failed decoder cannot be selected again for the same item. */
class DecoderRetryBudget(private val maxRetries: Int = 3) {
    private val failed = linkedSetOf<String>()
    fun exclude(name: String?): Boolean = !name.isNullOrBlank() && failed.size < maxRetries && failed.add(name)
    fun excluded(): Set<String> = failed.toSet()
    fun reset() = failed.clear()
}
