package com.streamflixreborn.streamflix.utils

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/fixtures/iptv-tv.m3u"))
        .bufferedReader().use { it.readText() }

    @Test fun parsesOfflineFixtureWithoutLeakingMetadata() {
        val channels = M3uParser.parse(fixture())
        assertEquals(listOf("Canal Uno, HD", "Canal Dos", "Canal Tres"), channels.map { it.name })
        assertEquals("Streamflix/1.0", channels[0].userAgent)
        assertEquals("https://example.invalid/", channels[0].referrer)
        assertEquals("Noticias", channels[0].group)
        assertEquals("InlineAgent", channels[1].userAgent)
        assertNull(channels[2].userAgent)
        assertNull(channels[2].referrer)
        assertEquals("", channels[2].logo)
    }
    @Test fun acceptsWindowsLineEndingsAndWhitespace() {
        val input = "  #EXTINF:-1,Canal\r\n  https://example.invalid/a  \r\n"
        assertEquals(M3uParser.Channel("Canal", "https://example.invalid/a"), M3uParser.parse(input).single())
    }
    @Test fun emptyAndMalformedPlaylistsAreSafe() {
        assertTrue(M3uParser.parse("").isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U\nnot a url\n#EXTINF:-1,Missing URL").isEmpty())
    }
    @Test fun preservesCommasInChannelNames() {
        val channel = M3uParser.parse("#EXTINF:-1 tvg-name=\"News, HD\",Noticias, Madrid\nhttps://example.invalid/a").single()
        assertEquals("Noticias, Madrid", channel.name)
    }
}
