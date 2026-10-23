package com.phnem.vap.cast
import com.phnem.vap.cast.proxy.CastRange
import org.junit.Assert.*
import org.junit.Test
class CastRangeTest {
    @Test fun `ranges preserve length suffix and EOF boundaries`() {
        assertEquals(CastRange(0, 100), CastRange.parse(null, 100))
        assertEquals(CastRange(10, 10), CastRange.parse("bytes=10-19", 100))
        assertEquals(CastRange(90, 10), CastRange.parse("bytes=90-", 100))
        assertEquals(CastRange(90, 10), CastRange.parse("bytes=-10", 100))
        assertEquals(CastRange(0, 100), CastRange.parse("bytes=-200", 100))
        assertEquals(CastRange(90, 10), CastRange.parse("bytes=90-500", 100))
        assertEquals(CastRange(0, null), CastRange.parse(null, null))
    }
    @Test fun `malformed oversized multiple and unknown length ranges are refused`() {
        for (header in listOf("bytes=100-", "bytes=-0", "bytes=10-9", "bytes=0-1,3-5", "bytes=", "bytes=999999999999999999999-", "bad")) assertNull(header, CastRange.parse(header, 100))
        assertNull(CastRange.parse("bytes=1-2", null))
        assertNull(CastRange.parse("bytes=0-", 0))
    }
}
