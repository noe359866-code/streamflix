package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.HttpScraper
import com.streamflixreborn.streamflix.utils.NetworkClient

/**
 * JKPlayer: reproductor de JKAnime (español latino).
 *
 * El player sirve la URL del HLS codificada en Base64 dentro de un `atob(...)`
 * (o directamente en `loadSource('...')`). Necesita `Referer`/`Origin` de
 * jkanime.net para no devolver 403, así que se devuelven como headers del Video.
 */
class JKPlayerExtractor : Extractor() {

    override val name = "JKPlayer"
    override val mainUrl = "https://jkanime.net/jkplayer/"

    override suspend fun extract(link: String): Video {
        val html = HttpScraper.getText(
            url = link,
            referer = "$SITE_URL/",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
            attempts = 2,
        ) ?: throw IllegalStateException("JKPlayer: no se pudo cargar $link")

        if (HttpScraper.looksLikeChallenge(html)) {
            throw IllegalStateException("JKPlayer: challenge anti-bot detectado")
        }

        val source = ATOB_URL_REGEX.find(html)?.groupValues?.getOrNull(1)?.let(::decodeBase64Url)
            ?: RAW_HLS_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("JKPlayer: no se encontró el HLS")

        Log.d(TAG, "JKPlayer: fuente obtenida")
        return Video(
            source = source.replace("\\/", "/"),
            headers = mapOf(
                "Origin" to SITE_URL,
                "Referer" to "$SITE_URL/",
                "User-Agent" to NetworkClient.USER_AGENT,
            ),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun decodeBase64Url(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
    }.getOrNull()?.takeIf { it.startsWith("http") }

    private companion object {
        const val TAG = "JKPlayer"
        const val SITE_URL = "https://jkanime.net"

        val ATOB_URL_REGEX = Regex(
            """url\s*:\s*atob\(\s*['"]([^'"]+)['"]\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        val RAW_HLS_REGEX = Regex(
            """(?:url\s*:\s*|loadSource\(\s*)['"](https?://[^'"]+\.m3u8[^'"]*)['"]""",
            RegexOption.IGNORE_CASE,
        )
    }
}
