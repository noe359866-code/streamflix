package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.HttpScraper
import org.json.JSONObject

/**
 * MovieMaster (`api.moviemaster.lat`): backend de reproductores usado por
 * CineHax y otras webs en español.
 *
 * El embed expone `window.PM_UI`, y con ese `ui` + el id de TMDb se piden los
 * servidores en `GET /servers?...`, agrupados por idioma (LAT/ESP/SUB). Cada
 * servidor se reproduce con `GET /video?embed=...&noiframe=1`, que devuelve la
 * URL firmada del HLS.
 *
 * Mejoras:
 * - todo pasa por [HttpScraper] (IO, reintentos, cierre de respuesta);
 * - el `User-Agent` de la petición y del `Video` es el mismo (antes provocaba 403);
 * - los servidores se deduplican y se ordenan latino → castellano → subtitulado.
 */
class MovieMasterExtractor : Extractor() {

    override val name = "Moviemaster"
    override val mainUrl = "https://api.moviemaster.lat"

    /** Servidores disponibles para una película o episodio (no requiere `extract`). */
    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        val embedUrl = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/e/movie/${videoType.id}"
            is Video.Type.Episode ->
                "$mainUrl/e/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
        }
        val mediaParams = when (videoType) {
            is Video.Type.Movie -> "tmdb=${videoType.id}&type=movie"
            is Video.Type.Episode ->
                "tmdb=${videoType.tvShow.id}&type=tv&season=${videoType.season.number}&episode=${videoType.number}"
        }

        val embedHtml = HttpScraper.getText(
            url = embedUrl,
            referer = "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT, "Sec-Fetch-Dest" to "iframe"),
            attempts = 2,
        ) ?: return emptyList()

        val pmUi = Regex("""window\.PM_UI\s*=\s*["']([^"']+)["']""")
            .find(embedHtml)?.groupValues?.get(1) ?: return emptyList()

        val serversJson = HttpScraper.getText(
            url = "$mainUrl/servers?$mediaParams&ui=$pmUi",
            referer = embedUrl,
            headers = mapOf("User-Agent" to USER_AGENT),
            attempts = 2,
        ) ?: return emptyList()

        val serversArray = runCatching { JSONObject(serversJson).optJSONArray("servers") }.getOrNull()
            ?: return emptyList()

        val languageGroups = linkedMapOf<String, MutableList<String>>()
        for (index in 0 until serversArray.length()) {
            val serverObj = serversArray.optJSONObject(index) ?: continue
            val serverId = serverObj.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val lang = serverObj.optString("lang").uppercase()
            val langKey = when (lang) {
                "LAT" -> "latino"
                "ESP" -> "castellano"
                "SUB" -> "subtitulado"
                else -> lang.lowercase()
            }
            languageGroups.getOrPut(langKey) { mutableListOf() }.add(serverId)
        }

        val allLangs = languageGroups.keys.toList()
        val orderedLangs = LANGUAGE_ORDER.filter { it in allLangs } + (allLangs - LANGUAGE_ORDER.toSet())

        return orderedLangs.flatMap { langKey ->
            val serverIds = languageGroups[langKey].orEmpty()
            val langLabel = LANGUAGE_LABELS[langKey] ?: langKey.replaceFirstChar { it.uppercase() }
            serverIds.mapIndexed { index, serverId ->
                Video.Server(
                    id = serverId,
                    name = "Moviemaster · Opción ${index + 1} · $langLabel",
                    src = "$mainUrl/video?$mediaParams&embed=$serverId&noiframe=1&r=1",
                )
            }
        }.distinctBy { it.src }
    }

    override suspend fun extract(link: String): Video {
        val jsonStr = HttpScraper.getText(
            url = link,
            referer = "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT),
            attempts = 3,
        ) ?: throw Exception("MovieMaster: respuesta vacía")

        val videoUrl = runCatching { JSONObject(jsonStr).optString("url") }.getOrNull().orEmpty()
        if (videoUrl.isEmpty()) throw Exception("MovieMaster: no se encontró la URL del stream")
        Log.d(TAG, "MovieMaster: stream resuelto")

        return Video(
            source = videoUrl,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
            ),
        )
    }

    private companion object {
        const val TAG = "MovieMaster"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val LANGUAGE_ORDER = listOf("latino", "castellano", "subtitulado")
        val LANGUAGE_LABELS = mapOf(
            "latino" to "Latino",
            "castellano" to "Castellano",
            "subtitulado" to "Subtitulado",
        )
    }
}
