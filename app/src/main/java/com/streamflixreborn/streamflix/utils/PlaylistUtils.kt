package com.streamflixreborn.streamflix.utils

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistUtils(private val client: OkHttpClient) {

    /**
     * Descarga la playlist maestra y devuelve una pista por calidad.
     *
     * Antes la respuesta no se cerraba (fuga de sockets) y el parseo se rompía con
     * playlists que separan `#EXT-X-STREAM-INF` y la URI con líneas en blanco o
     * comentarios (habitual en los HLS de estos sitios).
     */
    suspend fun extractFromHls(
        playlistUrl: String,
        referer: String? = null,
    ): List<Video> {
        val masterPlaylist = try {
            val request = Request.Builder().url(playlistUrl)
            if (referer != null) {
                request.header("Referer", referer)
            }
            client.await(request.build()).use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            // Si la URL principal ya es un video, devuélvelo directamente
            return listOf(Video(source = playlistUrl, subtitles = emptyList(), type = MimeTypes.APPLICATION_M3U8))
        }

        val videoList = mutableListOf<Pair<Int, Video>>()

        if (!masterPlaylist.contains("#EXT-X-STREAM-INF")) {
            return listOf(
                Video(
                    source = playlistUrl,
                    subtitles = emptyList(),
                    type = MimeTypes.APPLICATION_M3U8
                )
            )
        }

        val masterUrl = playlistUrl.toHttpUrl()
        val masterBaseUrl = masterUrl.toString().substringBeforeLast("/") + "/"

        // Obtener subtítulos de la playlist
        val subtitles = SUBTITLE_REGEX.findAll(masterPlaylist).map {
            val url = getAbsoluteUrl(it.groupValues[2], masterUrl.toString(), masterBaseUrl)
            Video.Subtitle(
                label = it.groupValues[1],
                file = url,
            )
        }.toList()

        masterPlaylist.split("#EXT-X-STREAM-INF:").drop(1).forEach { block ->
            val resolution = RESOLUTION_REGEX.find(block)?.groupValues?.get(1)
            val quality = resolution?.let { res -> "${res}p" } ?: getQualityFromBandwidth(block)
            // Primera línea útil (URI) del bloque, ignorando atributos y comentarios.
            val variant = block.lineSequence()
                .drop(1)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return@forEach
            val url = getAbsoluteUrl(variant, masterUrl.toString(), masterBaseUrl)
            val videoHeaders = referer?.let { mapOf("Referer" to it) }

            val height = resolution?.toIntOrNull() ?: 0
            videoList.add(
                height to Video(
                    source = url,
                    subtitles = subtitles,
                    type = MimeTypes.APPLICATION_M3U8,
                    headers = videoHeaders
                )
            )
        }
        // Orden real por resolución (antes solo miraba si la URL contenía "1080")
        // y sin duplicados por URL: algunos master list repiten la misma variante.
        return videoList
            .sortedByDescending { (height, _) -> height }
            .distinctBy { (_, video) -> video.source }
            .map { (_, video) -> video }
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> playlistUrl.toHttpUrl().scheme + "://" + playlistUrl.toHttpUrl().host + url
            else -> masterBase + url
        }
    }

    private fun getQualityFromBandwidth(text: String): String {
        val bandwidth = BANDWIDTH_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return "Default"
        return when {
            bandwidth >= 2000000 -> "1080p"
            bandwidth >= 1000000 -> "720p"
            bandwidth >= 600000 -> "480p"
            else -> "360p"
        }
    }

    companion object {
        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=\d{3,4}x(\d{3,4})""") }
        private val BANDWIDTH_REGEX by lazy { Regex("""BANDWIDTH=(\d+)""") }
    }
}