package com.phnem.vap.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimecodeTest {
    @Test fun secondsAndWhitespace() {
        assertEquals(35_000L, parseTimecode(" 35 "))
        assertEquals(0L, parseTimecode("0"))
        assertEquals(120_000L, parseTimecode("120"))
    }
    @Test fun minutesAndHours() {
        assertEquals(83_000L, parseTimecode("1:23"))
        assertEquals(5_025_000L, parseTimecode("01:23:45"))
        assertEquals(5_400_000L, parseTimecode("90:00"))
    }
    @Test fun missingComponentsAreRejected() {
        listOf("", " ", ":", ":12", "12:", "1::2", "1:2:3:4").forEach { assertNull(it, parseTimecode(it)) }
    }
    @Test fun malformedValuesAreRejected() {
        listOf("-1", "+10", "1.5", "1: 20", "00:ab", "1e3").forEach { assertNull(it, parseTimecode(it)) }
    }
    @Test fun minutesAndSecondsCannotOverflowTheirFields() {
        listOf("1:60", "1:60:00", "1:00:60").forEach { assertNull(it, parseTimecode(it)) }
        assertEquals(7_199_000L, parseTimecode("1:59:59"))
    }
    @Test fun arithmeticOverflowDoesNotBecomeANegativeSeek() {
        assertNull(parseTimecode(Long.MAX_VALUE.toString()))
        assertNull(parseTimecode("99999999999999999999999999"))
        assertNull(parseTimecode("2562047788016:00:00"))
    }
}
