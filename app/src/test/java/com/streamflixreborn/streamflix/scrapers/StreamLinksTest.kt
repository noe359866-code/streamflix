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
        )
        rejected.forEach { assertFalse("no debería aceptar '$it'", StreamLinks.isPlayable(it)) }
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
