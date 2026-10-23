package com.phnem.vap.player.subtitles

import com.phnem.vap.player.subtitles.online.MovieHash
import org.junit.Assert.*
import org.junit.Test

class MovieHashTest {
    @Test fun zeroBlocksIncludeFileSize() { assertEquals("0000000000020000", MovieHash.sum(131072, ByteArray(65536), ByteArray(65536))) }
    @Test fun wordsUseLittleEndian() {
        val first = ByteArray(65536); val last = ByteArray(65536)
        first[0] = 1; last[0] = 1
        assertEquals("0000000000020002", MovieHash.sum(131072, first, last))
    }
    @Test fun unsignedSumWrapsAt64Bits() {
        assertEquals("000000000001c000", MovieHash.sum(131072, ByteArray(65536) { -1 }, ByteArray(65536) { -1 }))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTooSmallVideos() { MovieHash.sum(131071, ByteArray(65536), ByteArray(65536)) }
}
