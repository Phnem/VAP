package com.phnem.vap.player.audio

import kotlin.math.*

/** Per-video processing. Delays are media milliseconds: positive means audio later. */
data class AudioSettings(
    val delayMs: Int = 0,
    val gainDb: Float = 0f,
    val balance: Float = 0f,
    val mono: Boolean = false,
    val normalize: Boolean = false,
    val equalizer: List<Float> = List(5) { 0f },
    val externalUri: String? = null,
) {
    fun sanitized() = copy(delayMs = delayMs.coerceIn(-2000, 2000),
        gainDb = gainDb.safe(0f, 12f), balance = balance.safe(-1f, 1f),
        equalizer = List(5) { equalizer.getOrElse(it) { 0f }.safe(-12f, 12f) },
        externalUri = externalUri?.takeIf { it.isNotBlank() })
    companion object { val FREQUENCIES = listOf(100, 300, 1000, 3000, 10000) }
}
private fun Float.safe(min: Float, max: Float) = if (isFinite()) coerceIn(min, max) else 0f

/** Stateful PCM DSP; no Android dependencies. EQ is RBJ peaking biquads, one bank per channel. */
class PcmAudioEngine(private val sampleRate: Int, private val channels: Int) {
    private var settings = AudioSettings()
    private var filters = Array(channels) { emptyArray<Biquad>() }
    private var energy = 0.0
    private var normalizationGain = 1.0
    private val energyAlpha = 1 - exp(-1.0 / (sampleRate * 0.2))
    private val attack = 1 - exp(-1.0 / (sampleRate * 0.02))
    private val release = 1 - exp(-1.0 / (sampleRate * 0.5))
    private var fixedGain = 1.0
    private val frame = DoubleArray(channels)
    val left: Double get() = frame[0]
    val right: Double get() = frame[if (channels > 1) 1 else 0]
    fun configure(value: AudioSettings) {
        val next = value.sanitized()
        if (next.equalizer != settings.equalizer) {
            filters = Array(channels) { AudioSettings.FREQUENCIES.mapIndexed { i, hz ->
                Biquad(sampleRate, hz.toDouble().coerceAtMost(sampleRate * 0.45), next.equalizer[i].toDouble())
            }.toTypedArray() }
        }
        settings = next
        fixedGain = 10.0.pow(settings.gainDb / 20.0)
    }
    fun process(input: ShortArray): ShortArray {
        require(input.size % channels == 0)
        val output = ShortArray(input.size)
        for (offset in input.indices step channels) {
            for (c in 0 until channels) frame[c] = input[offset + c] / 32768.0
            processFrame()
            for (c in 0 until channels) output[offset + c] = (frame[c] * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }
    fun processFrame(input: java.nio.ByteBuffer, output: java.nio.ByteBuffer) {
        for (c in 0 until channels) frame[c] = input.short / 32768.0
        processFrame()
        for (c in 0 until channels) output.putShort((frame[c] * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
    }
    private fun processFrame() {
        for (c in 0 until channels) for (filter in filters[c]) frame[c] = filter.apply(frame[c])
        if (settings.mono && channels == 2) { val mean = (frame[0] + frame[1]) / 2; frame[0] = mean; frame[1] = mean }
        if (settings.normalize) {
            val power = frame.sumOf { it * it } / channels
            energy += (power - energy) * energyAlpha
            // Streaming RMS control, not a whole-file LUFS/EBU R128 measurement. No gain on silence.
            val target = if (energy < 0.000001) 1.0 else (0.15 / sqrt(energy)).coerceIn(0.25, 4.0)
            normalizationGain += (target - normalizationGain) * if (target < normalizationGain) attack else release
        } else { energy = 0.0; normalizationGain = 1.0 }
        val gain = fixedGain * normalizationGain
        for (c in 0 until channels) {
            val pan = if (channels == 2) { if (c == 0) 1.0 - settings.balance.coerceAtLeast(0f) else 1.0 + settings.balance.coerceAtMost(0f) } else 1.0
            frame[c] = (frame[c] * gain * pan).coerceIn(-1.0, 32767.0 / 32768)
        }
    }
    private class Biquad(rate: Int, hz: Double, db: Double) {
        private val a = 10.0.pow(db / 40)
        private val w = 2 * PI * hz / rate
        private val alpha = sin(w) / 2 // Q=1
        private val a0 = 1 + alpha / a
        private val b0 = (1 + alpha * a) / a0
        private val b1 = -2 * cos(w) / a0
        private val b2 = (1 - alpha * a) / a0
        private val a1 = b1
        private val a2 = (1 - alpha / a) / a0
        private var z1 = 0.0; private var z2 = 0.0
        fun apply(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
    }
}
