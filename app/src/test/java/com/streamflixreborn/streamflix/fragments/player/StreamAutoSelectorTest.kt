package com.streamflixreborn.streamflix.fragments.player

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El usuario nunca elige servidor: el primero de la lista es el que se reproduce,
 * así que este orden es el comportamiento visible del reproductor.
 */
class StreamAutoSelectorTest {

    private fun server(id: String, name: String, src: String = id) = Video.Server(id, name, src)

    @Test
    fun `un HLS directo gana a un enlace de player`() {
        val embed = server("1", "Filemoon", "https://filemoon.sx/e/abc123")
        val hls = server("2", "Stream", "https://cdn.example.com/master.m3u8")
        assertEquals(listOf(hls, embed), StreamAutoSelector.order(listOf(embed, hls)))
    }

    @Test
    fun `1080p gana a 480p con el mismo tipo`() {
        val sd = server("1", "Player 480p", "https://a.example.com/e/1")
        val hd = server("2", "Player 1080p", "https://b.example.com/e/2")
        assertEquals(listOf(hd, sd), StreamAutoSelector.order(listOf(sd, hd)))
    }

    @Test
    fun `latino gana a castellano y a subtitulado`() {
        val sub = server("1", "Player (sub)", "https://a.example.com/e/1")
        val cas = server("2", "Player castellano", "https://b.example.com/e/2")
        val lat = server("3", "Player latino", "https://c.example.com/e/3")
        assertEquals(listOf(lat, cas, sub), StreamAutoSelector.order(listOf(sub, cas, lat)))
    }

    @Test
    fun `https gana a http plano`() {
        val http = server("1", "Player", "http://a.example.com/e/1")
        val https = server("2", "Player", "https://b.example.com/e/2")
        assertEquals(listOf(https, http), StreamAutoSelector.order(listOf(http, https)))
    }

    @Test
    fun `el ultimo host que funciono va primero`() {
        val a = server("1", "Player 1080p", "https://a.example.com/e/1")
        val b = server("2", "Player 1080p", "https://b.example.com/e/2")
        val ordered = StreamAutoSelector.order(listOf(a, b), lastGoodHost = "b.example.com")
        assertEquals(b, ordered.first())
    }

    @Test
    fun `un host que fallo cae al final aunque sea mejor`() {
        val good = server("1", "Player 1080p", "https://dead.example.com/e/1")
        val meh = server("2", "Player 480p", "https://ok.example.com/e/2")
        StreamAutoSelector.markFailed(good)
        try {
            assertEquals(listOf(meh, good), StreamAutoSelector.order(listOf(good, meh)))
        } finally {
            StreamAutoSelector.markWorked(good)
        }
    }

    @Test
    fun `el orden es estable entre servidores equivalentes`() {
        val a = server("1", "A", "https://a.example.com/e/1")
        val b = server("2", "B", "https://b.example.com/e/2")
        val c = server("3", "C", "https://c.example.com/e/3")
        assertTrue(StreamAutoSelector.order(listOf(a, b, c)).map { it.id } == listOf("1", "2", "3"))
    }

    @Test
    fun `hostKey ignora esquema, ruta y query`() {
        assertEquals(
            "cdn.example.com",
            StreamAutoSelector.hostKey(server("x", "x", "https://cdn.example.com/live/play.m3u8?t=1"))
        )
    }
}
