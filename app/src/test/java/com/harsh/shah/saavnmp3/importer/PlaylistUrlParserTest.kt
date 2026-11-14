package com.harsh.shah.saavnmp3.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUrlParserTest {

    @Test
    fun `parses standard youtube playlist url`() {
        val result = PlaylistUrlParser.parse("https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf")
        assertTrue(result is PlaylistUrlParseResult.Success)
        val success = result as PlaylistUrlParseResult.Success
        assertEquals("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", success.playlistId)
        assertFalse(success.isYouTubeMusic)
    }

    @Test
    fun `parses youtube music playlist url`() {
        val result = PlaylistUrlParser.parse("https://music.youtube.com/playlist?list=OLAK5uy_n9f8e7d6c5b4a3210fedcba98")
        assertTrue(result is PlaylistUrlParseResult.Success)
        val success = result as PlaylistUrlParseResult.Success
        assertEquals("OLAK5uy_n9f8e7d6c5b4a3210fedcba98", success.playlistId)
        assertTrue(success.isYouTubeMusic)
    }

    @Test
    fun `parses watch url with list parameter`() {
        val result = PlaylistUrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890ABCDEF&index=3")
        assertTrue(result is PlaylistUrlParseResult.Success)
        assertEquals("PL1234567890ABCDEF", (result as PlaylistUrlParseResult.Success).playlistId)
    }

    @Test
    fun `parses youtu be short url with list parameter`() {
        val result = PlaylistUrlParser.parse("https://youtu.be/dQw4w9WgXcQ?list=PL1234567890ABCDEF&si=xyz")
        assertTrue(result is PlaylistUrlParseResult.Success)
        assertEquals("PL1234567890ABCDEF", (result as PlaylistUrlParseResult.Success).playlistId)
    }

    @Test
    fun `rejects non-youtube domain`() {
        val result = PlaylistUrlParser.parse("https://example.com/playlist?list=PL1234567890ABCDEF")
        assertTrue(result is PlaylistUrlParseResult.Invalid)
    }

    @Test
    fun `rejects missing list parameter`() {
        val result = PlaylistUrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(result is PlaylistUrlParseResult.Invalid)
    }

    @Test
    fun `rejects watch later and liked videos system lists`() {
        assertTrue(PlaylistUrlParser.parse("https://www.youtube.com/playlist?list=WL") is PlaylistUrlParseResult.Invalid)
        assertTrue(PlaylistUrlParser.parse("https://www.youtube.com/playlist?list=LL") is PlaylistUrlParseResult.Invalid)
    }

    @Test
    fun `rejects short or malformed playlist id`() {
        assertTrue(PlaylistUrlParser.parse("https://www.youtube.com/playlist?list=abc") is PlaylistUrlParseResult.Invalid)
        assertTrue(PlaylistUrlParser.parse("   ") is PlaylistUrlParseResult.Invalid)
    }
}
