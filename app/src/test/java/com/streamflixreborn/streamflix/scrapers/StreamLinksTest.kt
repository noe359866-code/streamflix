package com.streamflixreborn.streamflix.scrapers

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El provider sólo debe entregar streams reproducibles. */
class StreamLinksTest {

    @Test
    fun `acepta reproductores reales`() {
        val playable = listOf(
            "https://voe.sx/e/8n1abc",
            "https://streamwish.to/e/abc123",
            "https://filemoon.site/e/abc123",
            "https://dood.la/e/xyz",
            "https://player.example.com/embed/xyz",
            "https://cdn.example.com/live/channel.m3u8",
            "https://cdn.example.com/movie.mp4",
            "https://esprinahy.com/v/abc",
            "https://jkanime.net/jkplayer/123/?x=1",
        )
        playable.forEach { assertTrue("debería aceptar $it", StreamLinks.isPlayable(it)) }
    }

    @Test
    fun `rechaza soporte, donaciones y redes sociales`() {
        val rejected = listOf(
            "",
            "#",
            "https://t.me/mi_canal",
            "https://whatsapp.com/channel/xyz",
            "https://www.paypal.com/donate?hosted_button_id=1",
            "https://linktr.ee/mi_perfil",
            "https://www.facebook.com/mi_grupo",
            "https://www.youtube.com/watch?v=trailer",
            "https://sitio.com/genero/accion",
            "https://sitio.com/donacion",
            "https://sitio.com/soporte",
            "javascript:void(0)",
            "about:blank",
            "https://cdn.example.com/manifest.mpd",
            "https://cdn.example.com/movie.mkv",
            "https://cdn.example.com/movie.webm",
            "https://cdn.example.com/movie.flv",
        )
        rejected.forEach { assertFalse("no debería aceptar '$it'", StreamLinks.isPlayable(it)) }
    }

    @Test
    fun `solo m3u8 y mp4 cuentan como media directa`() {
        assertTrue(StreamLinks.isDirectMedia("https://cdn.example.com/a.m3u8?t=1"))
        assertTrue(StreamLinks.isDirectMedia("https://cdn.example.com/a.mp4"))
        assertFalse(StreamLinks.isDirectMedia("https://cdn.example.com/a.mpd"))
        assertFalse(StreamLinks.isDirectMedia("https://cdn.example.com/a.mkv"))
        assertFalse(StreamLinks.isDirectMedia("https://cdn.example.com/e/abc"))
    }

    @Test
    fun `sanitize deduplica, filtra y rellena nombres vacios`() {
        val servers = listOf(
            Video.Server(id = "https://voe.sx/e/a", name = "VOE", src = "https://voe.sx/e/a"),
            Video.Server(id = "https://voe.sx/e/a", name = "VOE repetido", src = "https://voe.sx/e/a"),
            Video.Server(id = "https://t.me/canal", name = "Soporte", src = "https://t.me/canal"),
            Video.Server(id = "https://streamwish.to/e/b", name = "", src = "https://streamwish.to/e/b"),
        )
        val result = StreamLinks.sanitize(servers)

        assertEquals(2, result.size)
        assertEquals("VOE", result[0].name)
        assertEquals("Streamwish", result[1].name)
    }
}
