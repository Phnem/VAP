package com.phnem.vap

import com.phnem.vap.ai.whisper.WhisperChunks
import org.junit.Assert.*
import org.junit.Test

class WhisperChunksTest {
    @Test fun startsAtCurrentChunkThenCompletesBeginning() {
        val plan = WhisperChunks.plan(95_000, 65_000, emptySet())
        assertEquals(listOf(60_000L, 90_000L, 0L, 30_000L), plan.map { it.first })
        assertEquals(94_999L, plan[1].last)
        assertEquals(95_000L, plan.sumOf { it.last - it.first + 1 })
    }
    @Test fun resumeOmitsOnlyCompletedChunks() {
        assertEquals(listOf(90_000L, 30_000L), WhisperChunks.plan(95_000, 65_000, setOf(0, 60_000)).map { it.first })
    }
    @Test fun tinyAndExactDurationDoNotCreateEmptyChunks() {
        assertEquals(listOf(0L..0L), WhisperChunks.plan(1, Long.MAX_VALUE, emptySet()))
        assertEquals(listOf(0L..29_999L), WhisperChunks.plan(30_000, -1, emptySet()))
    }
    @Test(expected = IllegalArgumentException::class) fun liveDurationIsRejected() { WhisperChunks.plan(-1, 0, emptySet()) }
    @Test(expected = IllegalArgumentException::class) fun unboundedVideoIsRejected() { WhisperChunks.plan(Long.MAX_VALUE, 0, emptySet()) }
}

