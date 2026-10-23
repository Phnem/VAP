package com.phnem.vap.player.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AudioProcessingTest {
    @Test fun neutralIsBitExact() {
        val input = shortArrayOf(-32768, 32767, -1, 0, 1234, -1234)
        assertArrayEquals(input, PcmAudioEngine(48000, 2).process(input))
    }
    @Test fun balanceMutesOnlyTheRequestedChannel() {
        val engine = PcmAudioEngine(48000, 2)
        engine.configure(AudioSettings(balance = 1f))
        assertArrayEquals(shortArrayOf(0, 2000, 0, -2000), engine.process(shortArrayOf(1000, 2000, -1000, -2000)))
        engine.configure(AudioSettings(balance = -1f))
        assertArrayEquals(shortArrayOf(1000, 0), engine.process(shortArrayOf(1000, 2000)))
    }
    @Test fun monoAveragesStereoWithoutOverflow() {
        val engine = PcmAudioEngine(48000, 2)
        engine.configure(AudioSettings(mono = true))
        assertArrayEquals(shortArrayOf(0, 0, 32767, 32767), engine.process(shortArrayOf(30000, -30000, 32767, 32767)))
    }
    @Test fun boostIsBoundedAndNeverWraps() {
        val engine = PcmAudioEngine(48000, 1)
        engine.configure(AudioSettings(gainDb = 12f))
        val result = engine.process(shortArrayOf(1000, 30000, -30000))
        assertTrue(result[0] in 3980..3982); assertEquals(32767, result[1].toInt()); assertEquals(-32768, result[2].toInt())
    }
    @Test fun eqBoostsItsCenterFrequency() {
        val signal = tone(1000, .05)
        val engine = PcmAudioEngine(48000, 1)
        engine.configure(AudioSettings(equalizer = listOf(0f, 0f, 6f, 0f, 0f)))
        val ratio = rms(engine.process(signal).drop(4800)) / rms(signal.drop(4800))
        assertEquals(10.0.pow(6.0 / 20), ratio, .02)
    }
    @Test fun normalizationBringsQuietAndLoudCloser() {
        fun level(amplitude: Double): Double {
            val engine = PcmAudioEngine(48000, 1); engine.configure(AudioSettings(normalize = true))
            return rms(engine.process(tone(440, amplitude, 3)).takeLast(48000))
        }
        val quiet = level(.05); val loud = level(.8)
        assertTrue(loud / quiet < 1.5); assertTrue(quiet > .10)
    }
    @Test fun normalizationDoesNotRaiseDigitalSilence() {
        val engine = PcmAudioEngine(48000, 2); engine.configure(AudioSettings(normalize = true, gainDb = 12f))
        assertTrue(engine.process(ShortArray(96000)).all { it == 0.toShort() })
    }
    @Test fun multichannelBalanceDoesNotGuessSpeakerLayout() {
        val engine = PcmAudioEngine(48000, 6); engine.configure(AudioSettings(balance = 1f, mono = true))
        val data = shortArrayOf(1, 2, 3, 4, 5, 6); assertArrayEquals(data, engine.process(data))
    }
    @Test fun malformedSettingsAreFiniteAndBounded() {
        val value = AudioSettings(Int.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN, equalizer = listOf(-100f, Float.NaN)).sanitized()
        assertEquals(2000, value.delayMs); assertEquals(0f, value.gainDb, 0f); assertEquals(0f, value.balance, 0f)
        assertEquals(listOf(-12f, 0f, 0f, 0f, 0f), value.equalizer)
    }
    @Test fun eqIsStableNearNyquistAtLowSampleRate() {
        val engine = PcmAudioEngine(8000, 1); engine.configure(AudioSettings(equalizer = List(5) { 12f }))
        assertTrue(engine.process(tone(1000, .05)).isNotEmpty())
    }
    private fun tone(hz: Int, amplitude: Double, seconds: Int = 1) = ShortArray(48000 * seconds) { (sin(2 * PI * hz * it / 48000) * amplitude * 32767).roundToInt().toShort() }
    private fun rms(samples: List<Short>) = sqrt(samples.sumOf { (it / 32768.0).pow(2) } / samples.size)
}
