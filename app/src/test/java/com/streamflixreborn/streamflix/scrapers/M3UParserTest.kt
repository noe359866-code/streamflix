package com.streamflixreborn.streamflix.scrapers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3UParserTest {

    @Test
    fun `parses basic list with logo group and user agent`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-id="la1.es" tvg-logo="https://cdn.test/la1.png" group-title="General",La 1
            https://stream.test/la1.m3u8
        """.trimIndent()

        val channels = M3UParser.parse(raw)

        assertEquals(1, channels.size)
        val channel = channels.first()
        assertEquals("La 1", channel.name)
        assertEquals("https://stream.test/la1.m3u8", channel.url)
        assertEquals("https://cdn.test/la1.png", channel.logo)
        assertEquals("General", channel.group)
    }

    @Test
    fun `keeps commas inside channel name`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="l.png",Cine, el bueno
            https://stream.test/cine.m3u8
        """.trimIndent()

        // Antes el nombre se truncaba después de la primera coma.
        assertEquals("Cine, el bueno", M3UParser.parse(raw).single().name)
    }

    @Test
    fun `reads extvlcopt user agent and referrer`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 group-title="Sports",Canal Premium
            #EXTVLCOPT:http-user-agent=CustomAgent/1.0
            #EXTVLCOPT:http-referrer=https://referrer.test/
            https://stream.test/premium.m3u8
        """.trimIndent()

        val channel = M3UParser.parse(raw).single()

        assertEquals("CustomAgent/1.0", channel.userAgent)
        assertEquals("https://referrer.test/", channel.referrer)
        assertEquals("Sports", channel.group)
    }

    @Test
    fun `supports extgrp and ignores entries without url`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1,Sin URL
            #EXTINF:-1 tvg-logo="x.png",Con URL
            #EXTGRP:Movies
            https://stream.test/ok.m3u8
        """.trimIndent()

        val channels = M3UParser.parse(raw)

        assertEquals(1, channels.size)
        assertEquals("Con URL", channels.single().name)
        assertEquals("Movies", channels.single().group)
    }

    @Test
    fun `removes duplicate urls and handles bom`() {
        val raw = "\uFEFF#EXTM3U\n" +
            "#EXTINF:-1,Dup\nhttps://stream.test/a.m3u8\n" +
            "#EXTINF:-1,Dup2\nhttps://stream.test/a.m3u8\n"

        assertEquals(1, M3UParser.parse(raw).size)
    }

    @Test
    fun `returns empty list for blank input`() {
        assertTrue(M3UParser.parse("   ").isEmpty())
    }

    @Test
    fun `falls back to empty logo when attribute missing`() {
        val raw = "#EXTM3U\n#EXTINF:-1,Canal\nhttps://stream.test/c.m3u8"
        assertNull(M3UParser.parse(raw).single().logo)
    }
}
