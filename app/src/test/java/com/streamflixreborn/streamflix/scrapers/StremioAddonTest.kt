package com.streamflixreborn.streamflix.scrapers

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocolo de addons Stremio: normalización de URL y parseo de manifests/metas. */
class StremioAddonTest {

    private val gson = Gson()

    @Test
    fun `normalize acepta manifest json o url base`() {
        val expected = "https://v3-cinemeta.strem.io"
        assertEquals(expected, StremioAddon.normalize("https://v3-cinemeta.strem.io"))
        assertEquals(expected, StremioAddon.normalize("https://v3-cinemeta.strem.io/"))
        assertEquals(expected, StremioAddon.normalize("https://v3-cinemeta.strem.io/manifest.json"))
        assertEquals(
            "https://addon.notorrent2.workers.dev",
            StremioAddon.normalize("https://addon.notorrent2.workers.dev/manifest.json"),
        )
    }

    @Test
    fun `cinemeta declara catalogos de cine y series con search`() {
        val manifest = gson.fromJson(CINEMETA_MANIFEST, StremioAddon.Manifest::class.java)

        assertTrue(manifest.has("catalog"))
        assertTrue(manifest.has("meta"))
        assertTrue(manifest.catalogs.any { it.type == "movie" && it.id == "top" })
        assertTrue(manifest.catalogs.any { it.type == "series" && it.id == "top" })
        assertTrue(manifest.catalogs.first { it.id == "top" }.extra?.any { it.name == "search" } == true)
    }

    @Test
    fun `notorrent publica catalogos de plataformas y recurso stream`() {
        val manifest = gson.fromJson(NOTORRENT_MANIFEST, StremioAddon.Manifest::class.java)

        assertTrue(manifest.has("stream"))
        assertEquals(
            listOf("Netflix", "Disney+", "Prime Video", "Apple TV+", "HBO Max", "Paramount+", "Crunchyroll"),
            manifest.catalogs.map { it.displayName },
        )
        assertTrue(manifest.idPrefixes.orEmpty().contains("tt"))
    }

    @Test
    fun `el manifest de catalogo de plataformas no expone streams`() {
        val manifest = gson.fromJson(STREAMING_CATALOGS_MANIFEST, StremioAddon.Manifest::class.java)

        assertTrue(manifest.has("catalog"))
        assertFalse(manifest.has("stream"))
        assertTrue(manifest.catalogs.any { it.type == "movie" && it.id == "nfx" })
        assertTrue(manifest.catalogs.any { it.type == "series" && it.id == "nfx" })
    }

    @Test
    fun `toServer y toVideo entregan IDs limpios`() {
        val stream = StremioAddon.Stream(
            url = "https://cdn.example.com/live/master.m3u8",
            name = "NoTorrent · 1080p",
            type = "hls",
        )

        val server = StremioAddon.toServer(stream)
        assertEquals("https://cdn.example.com/live/master.m3u8", server.id)
        assertEquals("NoTorrent · 1080p", server.name)

        val video = StremioAddon.toVideo(stream)
        assertEquals("application/x-mpegURL", video.type)
        assertEquals(stream.url, video.source)
    }

    private companion object {
        const val CINEMETA_MANIFEST =
            """{"id":"com.linvo.cinemeta","name":"Cinemeta","resources":["catalog","meta","addon_catalog"],"types":["movie","series"],"idPrefixes":["tt"],"catalogs":[{"type":"movie","id":"top","extra":[{"name":"search"},{"name":"skip"}],"name":"Popular"},{"type":"series","id":"top","extra":[{"name":"search"}],"name":"Popular"}]}"""

        const val NOTORRENT_MANIFEST =
            """{"id":"com.notorrent.addon","name":"NoTorrent","resources":["catalog","stream"],"types":["series","movie"],"idPrefixes":["tmdb_","tt"],"catalogs":[{"type":"series","id":"netflix","name":"Netflix"},{"type":"series","id":"disney","name":"Disney+"},{"type":"series","id":"primevideo","name":"Prime Video"},{"type":"series","id":"apple","name":"Apple TV+"},{"type":"series","id":"max","name":"HBO Max"},{"type":"series","id":"paramount","name":"Paramount+"},{"type":"series","id":"crunchyroll","name":"Crunchyroll"}]}"""

        const val STREAMING_CATALOGS_MANIFEST =
            """{"id":"pw.ers.netflix-catalog","name":"Streaming Catalogs","catalogs":[{"id":"nfx","type":"movie","name":"Netflix"},{"id":"nfx","type":"series","name":"Netflix"},{"id":"hbm","type":"movie","name":"HBO Max"}],"resources":["catalog"],"types":["movie","series"],"idPrefixes":["tt"]}"""
    }
}
