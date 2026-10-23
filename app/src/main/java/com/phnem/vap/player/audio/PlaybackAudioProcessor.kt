package com.phnem.vap.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Always active PCM16 path: settings can change without silently bypassing processing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackAudioProcessor : BaseAudioProcessor() {
    @Volatile var settings = AudioSettings()
    @Volatile var processedFrames = 0L; private set
    @Volatile var activeChannels = 0; private set
    @Volatile var leftRms = 0f; private set
    @Volatile var rightRms = 0f; private set
    private var engine: PcmAudioEngine? = null
    private var delayFrames = 0L
    private val delayRevision = java.util.concurrent.atomic.AtomicInteger()
    private var appliedRevision = -1
    fun resetDelay() { delayRevision.incrementAndGet() }
    private fun applyDelayReset() {
        val revision = delayRevision.get()
        if (revision != appliedRevision) {
            delayFrames = inputAudioFormat.sampleRate.toLong() * settings.sanitized().delayMs / 1000
            appliedRevision = revision
        }
    }
    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount !in 1..8)
            throw AudioProcessor.UnhandledAudioFormatException(format)
        return format
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (inputAudioFormat.sampleRate <= 0) return
        activeChannels = inputAudioFormat.channelCount
        engine = PcmAudioEngine(inputAudioFormat.sampleRate, activeChannels)
        // Sonic also flushes the chain when changing speed. Do not accumulate another offset.
        applyDelayReset()
        processedFrames = 0
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        applyDelayReset()
        val frameBytes = inputAudioFormat.bytesPerFrame
        if (delayFrames > 0) {
            val count = minOf(delayFrames, 1024).toInt()
            val output = replaceOutputBuffer(count * frameBytes)
            repeat(count * frameBytes) { output.put(0.toByte()) }; output.flip()
            delayFrames -= count
            return
        }
        if (delayFrames < 0) {
            val drop = minOf(-delayFrames, (inputBuffer.remaining() / frameBytes).toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + drop * frameBytes); delayFrames += drop
        }
        val count = inputBuffer.remaining() / frameBytes
        val output = replaceOutputBuffer(count * frameBytes)
        engine?.configure(settings)
        var leftPower = 0.0; var rightPower = 0.0
        repeat(count) {
            engine!!.processFrame(inputBuffer, output)
            leftPower += engine!!.left * engine!!.left; rightPower += engine!!.right * engine!!.right
        }
        if (count > 0) { leftRms = kotlin.math.sqrt(leftPower / count).toFloat(); rightRms = kotlin.math.sqrt(rightPower / count).toFloat() }
        processedFrames += count
        output.flip()
    }
    override fun onReset() { engine = null; activeChannels = 0; processedFrames = 0; appliedRevision = -1; delayFrames = 0 }
}
