package com.phnem.vap.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeParserTest {

    @Test
    fun seasonEpisodeWithDots() {
        val i = EpisodeParser.parse("Breaking.Bad.S02E05.1080p.BluRay.x264.mkv")!!
        assertEquals(2, i.season)
        assertEquals(5, i.episode)
        assertEquals("Breaking Bad", i.show)
    }

    @Test
    fun seasonEpisodeLowercaseWithSpaces() {
        val i = EpisodeParser.parse("the wire s1 e12.mp4")!!
        assertEquals(1, i.season)
        assertEquals(12, i.episode)
    }

    @Test
    fun crossForm() {
        val i = EpisodeParser.parse("Show Name 3x07.avi")!!
        assertEquals(3, i.season)
        assertEquals(7, i.episode)
        assertEquals("Show Name", i.show)
    }

    @Test
    fun resolutionIsNotAnEpisode() {
        assertNull(EpisodeParser.parse("Movie 1920x1080.mp4"))
        assertNull(EpisodeParser.parse("Movie.2019.1080p.x264.mkv"))
    }

    @Test
    fun episodeWord() {
        val i = EpisodeParser.parse("Cowboy Bebop - Episode 7.mkv")!!
        assertNull(i.season)
        assertEquals(7, i.episode)
        assertEquals("Cowboy Bebop", i.show)
    }

    @Test
    fun animeFansubStyle() {
        val i = EpisodeParser.parse("[SubsPlease] Frieren - 05 (1080p) [ABCDEF12].mkv")!!
        assertNull(i.season)
        assertEquals(5, i.episode)
        assertEquals("Frieren", i.show)
    }

    @Test
    fun plainMovieHasNoEpisode() {
        assertNull(EpisodeParser.parse("Interstellar.mkv"))
        assertNull(EpisodeParser.parse("IMG_20240101_120000.mp4"))
    }
}
