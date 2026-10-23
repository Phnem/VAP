package com.phnem.vap.player.subtitles

import com.phnem.vap.player.subtitles.online.*
import org.junit.Assert.*
import org.junit.Test

class SubtitleSearchTest {
    @Test fun canonicalSearchSortsKeysLanguagesAndNormalizesCase() {
        assertEquals("subtitles?episode_number=2&languages=en%2Cru&query=the+matrix&season_number=1",
            SubtitleSearch("  The Matrix ", "ru,EN,ru", 1, 2).path())
    }
    @Test fun allowsHashWithoutTitle() {
        assertEquals("subtitles?languages=en&moviehash=0123456789abcdef", SubtitleSearch("", "en", hash = "0123456789abcdef").path())
    }
    @Test fun acceptsOnlyOfficialApiHosts() {
        assertEquals("https://vip-api.opensubtitles.com/api/v1", OpenSubtitlesClient.normalizeBase("vip-api.opensubtitles.com"))
        listOf("http://api.opensubtitles.com", "https://api.opensubtitles.com.evil.test", "https://evil.test@api.opensubtitles.com", "https://api.opensubtitles.com:8443", "https://api.opensubtitles.com/api/v1?key=x").forEach {
            assertTrue(it, runCatching { OpenSubtitlesClient.normalizeBase(it) }.isFailure)
        }
    }
}
