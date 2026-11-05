package com.streamflixreborn.streamflix.scrapers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScraperParsingTest {

    @Test
    fun `cleanTitle removes quality tags and language suffixes`() {
        assertEquals("El Padrino", ScraperParsing.cleanTitle("El Padrino [HD]"))
        assertEquals("Dune", ScraperParsing.cleanTitle("Dune - Ver online"))
        assertEquals("Matrix", ScraperParsing.cleanTitle("  Matrix   "))
        assertNull(ScraperParsing.cleanTitle("   "))
    }

    @Test
    fun `parseYear works with different position of the year`() {
        assertEquals("2023", ScraperParsing.parseYear("2023"))
        assertEquals("1999", ScraperParsing.parseYear("Estreno 1999 HD"))
        assertNull(ScraperParsing.parseYear("sin año"))
    }

    @Test
    fun `parseRating accepts comma and scales`() {
        assertEquals(7.8, ScraperParsing.parseRating("7.8")!!, 0.001)
        assertEquals(6.5, ScraperParsing.parseRating("6,5 / 10")!!, 0.001)
        assertEquals(8.0, ScraperParsing.parseRating("IMDb 8.0")!!, 0.001)
        assertNull(ScraperParsing.parseRating("no rating"))
        // Fuera de rango => descartado en vez de guardar basura
        assertNull(ScraperParsing.parseRating("1080"))
    }

    @Test
    fun `parseRuntimeMinutes handles h m and colon formats`() {
        assertEquals(92, ScraperParsing.parseRuntimeMinutes("1h 32m"))
        assertEquals(120, ScraperParsing.parseRuntimeMinutes("120 min"))
        assertEquals(90, ScraperParsing.parseRuntimeMinutes("1:30"))
        assertNull(ScraperParsing.parseRuntimeMinutes(""))
    }

    @Test
    fun `parseEpisodeNumber handles several languages`() {
        assertEquals(12, ScraperParsing.parseEpisodeNumber("Capítulo 12"))
        assertEquals(5, ScraperParsing.parseEpisodeNumber("Episodio 5"))
        assertEquals(7, ScraperParsing.parseEpisodeNumber("S01E07"))
    }

    @Test
    fun `parseSeasonNumber handles temporada and season`() {
        assertEquals(3, ScraperParsing.parseSeasonNumber("Temporada 3"))
        assertEquals(2, ScraperParsing.parseSeasonNumber("Season 2"))
        assertEquals(4, ScraperParsing.parseSeasonNumber("T4"))
    }

    @Test
    fun `absoluteUrl resolves relative paths`() {
        assertEquals(
            "https://site.test/path/img.jpg",
            HttpScraper.absoluteUrl("https://site.test/path/page.html", "/path/img.jpg"),
        )
        assertEquals(
            "https://cdn.test/a.jpg",
            HttpScraper.absoluteUrl("https://site.test/", "//cdn.test/a.jpg"),
        )
        assertEquals(
            "https://site.test/dir/b.jpg",
            HttpScraper.absoluteUrl("https://site.test/dir/page.html", "b.jpg"),
        )
    }

    @Test
    fun `looksLikeChallenge detects cloudflare pages`() {
        val cf = "<html><head><title>Just a moment...</title></head></html>"
        val ok = "<html><body><h1>Películas</h1></body></html>"
        assertEquals(true, HttpScraper.looksLikeChallenge(cf))
        assertEquals(false, HttpScraper.looksLikeChallenge(ok))
        assertEquals(false, HttpScraper.looksLikeChallenge(null))
    }

    @Test
    fun `normalizedKey ignores case accents and punctuation`() {
        assertEquals("el nino", ScraperParsing.normalizedKey("El Niño!"))
        assertEquals("el-padrino", ScraperParsing.slugify("El Padrino"))
    }
}
