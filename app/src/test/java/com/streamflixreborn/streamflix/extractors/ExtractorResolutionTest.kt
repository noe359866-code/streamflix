package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Garantiza que **cada reproductor** que devuelven los providers en español
 * tiene un resolver asociado en el registro de [Extractor], o cae en el
 * fallback genérico / enlace directo.
 */
class ExtractorResolutionTest {

    private fun describe(url: String, serverName: String = "") =
        Extractor.describe(url, Video.Server(id = url, name = serverName, src = url))

    @Test
    fun `enlaces directos se reconocen por extension`() {
        assertEquals("direct", describe("https://cdn.example.com/live/channel.m3u8?token=abc"))
        assertEquals("direct", describe("https://raw.githubusercontent.com/x/y/main/VIDEO/SIN-SE%C3%91AL.mp4"))
        assertEquals("direct", describe("https://cdn.example.com/movie.mkv"))
    }

    @Test
    fun `players comunes en español tienen extractor propio`() {
        val players = mapOf(
            "https://voe.sx/e/abc123" to "VOE",
            "https://streamwish.to/e/abc123" to "Streamwish",
            "https://filemoon.site/e/abc123" to "Filemoon",
            "https://mixdrop.co/e/abc123" to "MixDrop",
            "https://streamtape.com/e/abc123" to "Streamtape",
            "https://mp4upload.com/embed-abc123.html" to "Mp4Upload",
            "https://uqload.cx/embed-abc123.html" to "Uqload",
            "https://dhtpre.com/e/abc123" to "VidHide",
            "https://vidoza.net/abc123.html" to "Vidoza",
            "https://dood.la/e/abc123" to "DoodStream",
            "https://yourupload.com/embed/abc123" to "YourUpload",
            "https://firestream.to/watch/abc123" to "Firestream",
            "https://jkanime.net/jkplayer/abc123/" to "JKPlayer",
            "https://api.moviemaster.lat/video?tmdb=1&type=movie&embed=abc123" to "Moviemaster",
            "https://esprinahy.com/embed/abc123" to "Esprinahy",
        )
        players.forEach { (url, expected) ->
            assertEquals("Resolver incorrecto para $url", expected, describe(url))
        }
    }

    @Test
    fun `el fallback por nombre de servidor funciona sin host conocido`() {
        val url = "https://mirror-rotativo.example/e/abc123"
        assertEquals("Streamwish", describe(url, serverName = "Streamwish · Latino"))
        assertEquals("Moviemaster", describe(url, serverName = "Moviemaster · Opción 1 · Castellano"))
    }

    @Test
    fun `un host totalmente desconocido no se registra como extractor`() {
        // Sin extractor: `extract` usará el fallback genérico, pero `describe` lo reporta como null.
        assertNull(describe("https://player-desconocido.example/embed/xyz"))
    }
}
