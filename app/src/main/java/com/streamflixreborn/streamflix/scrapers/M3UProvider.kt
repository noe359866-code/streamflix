package com.streamflixreborn.streamflix.scrapers

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider

/**
 * Base común para los providers basados en listas M3U/M3U8
 * (IPTV-Org, IPTV Spain, Pluto TV ES/MX, Sports Events...).
 *
 * Antes cada uno repetía ~250 líneas idénticas: caché manual con `var` (no
 * thread-safe), un parser propio con bugs distintos, el mismo `Base64` para
 * codificar ids y un `getVideo` que se olvidaba de devolver los headers
 * `User-Agent`/`Referer` del canal (la causa nº1 de "el canal no se ve").
 *
 * Aquí:
 * - descarga + parseo con [HttpScraper] / [M3UParser] (IO, reintentos, cierre);
 * - caché con TTL y single-flight;
 * - ids autocontenidos (url|nombre|logo|UA|referer) para que la reproducción
 *   reconstruya exactamente los headers del canal;
 * - categorías por `group-title`, búsqueda y paginación por género comunes.
 *
 * Contrato: **sólo se entrega lo que es un stream**. Se descartan las entradas
 * de la lista M3U sin URL reproducible (avisos, "próximamente", canales con
 * `#EXTVLCOPT` vacío, enlaces a Telegram/paypal) y este provider no genera
 * items de soporte, donación ni categorías que no sean canales.
 */
abstract class M3UProvider : Provider {

    companion object {
        /** Lo único que se puede entregar al player: HLS o MP4 sobre http(s). */
        private val STREAM_URL = Regex(
            """^https?://[^\s]+""",
            RegexOption.IGNORE_CASE,
        )

        private val NON_STREAM_URL = Regex(
            """^(?:about:|data:|javascript:|mailto:|tel:|intent:|tg:|whatsapp:)""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** Lista M3U a descargar. */
    protected abstract val playlistUrl: String

    /** Nombre que se muestra en las categorías de canales "sueltos". */
    protected open val ungroupedCategoryName: String = "General"

    /** Máximo de canales por categoría en el home. */
    protected open val homeLimit: Int = 25

    /** Tamaño de página para `getTvShows` y `getGenre`. */
    protected open val pageSize: Int = 40

    /** Caché de canales (30 min por defecto). */
    protected open val cacheDurationMs: Long = 30 * 60 * 1000L

    /** Grupos que se muestran en el home (vacío = todos). */
    protected open val homeGroups: List<String> = emptyList()

    protected open val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    /** Compartida por todas las instancias de esta subclase. */
    private val channelsCache = ScraperCache<List<M3UChannel>>(ttlMs = cacheDurationMs)

    private val tag: String get() = "M3U:${name}"

    protected open suspend fun getChannels(): List<M3UChannel> =
        channelsCache.getOrFetchOrNull("channels|$playlistUrl") {
            val body = HttpScraper.getText(
                url = playlistUrl,
                headers = mapOf("User-Agent" to userAgent),
                attempts = 3,
            ) ?: throw java.io.IOException("No se pudo descargar la lista M3U de $name")
            M3UParser.parse(body).filter(::isPlayableStream)
        }.orEmpty()

    /**
     * Un canal entra en la app sólo si apunta a algo que se puede reproducir con
     * los formatos soportados: **HLS (`application/x-mpegURL`) o MP4** sobre http(s).
     * Se descartan UDP/RTSP/RTMP y enlaces de relleno.
     */
    protected fun isPlayableStream(channel: M3UChannel): Boolean {
        val url = channel.url.trim()
        if (url.isEmpty() || NON_STREAM_URL.containsMatchIn(url)) return false
        if (channel.name.isBlank()) return false
        return STREAM_URL.containsMatchIn(url)
    }

    /** Fuerza una nueva descarga de la lista (p. ej. desde ajustes). */
    suspend fun refreshChannels(): List<M3UChannel> {
        channelsCache.invalidate("channels|$playlistUrl")
        return getChannels()
    }

    // region Ids

    protected fun encodeId(channel: M3UChannel): String {
        val raw = listOf(
            channel.url,
            channel.name,
            channel.logo.orEmpty(),
            channel.userAgent.orEmpty(),
            channel.referrer.orEmpty(),
        ).joinToString("|")
        return Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    protected data class DecodedChannel(
        val url: String,
        val name: String,
        val logo: String?,
        val headers: Map<String, String>,
    )

    protected fun decodeId(id: String): DecodedChannel {
        val parts = runCatching { String(Base64.decode(id, Base64.DEFAULT)).split("|") }
            .getOrNull()
            ?: return DecodedChannel(id, "Canal Desconocido", null, emptyMap())
        return DecodedChannel(
            url = parts.getOrNull(0).orEmpty(),
            name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Canal",
            logo = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
            headers = buildMap {
                parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            },
        )
    }

    // endregion

    // region Provider

    override suspend fun getHome(): List<Category> {
        val channels = getChannels()
        if (channels.isEmpty()) {
            Log.w(tag, "Lista M3U vacía o sin streams reproducibles")
            return emptyList()
        }

        val grouped = channels
            .filter { !it.group.isNullOrBlank() }
            .filter { homeGroups.isEmpty() || homeGroups.any { target -> it.group!!.contains(target, true) } }
            .groupBy { channel ->
                if (homeGroups.isEmpty()) channel.group!! else
                    homeGroups.firstOrNull { channel.group!!.contains(it, true) } ?: channel.group!!
            }
            .map { (groupName, channelList) -> groupName to channelList }
            .sortedBy { it.first }

        val categories = grouped.mapNotNull { (groupName, channelList) ->
            val items = channelList
                .distinctBy { it.name }
                .take(homeLimit)
                .map { toTvShow(it) }
            if (items.isEmpty()) null else Category(groupName, items)
        }.toMutableList()

        val ungrouped = channels.filter { it.group.isNullOrBlank() }.distinctBy { it.name }
        if (ungrouped.isNotEmpty()) {
            categories.add(
                Category(
                    name = ungroupedCategoryName,
                    list = ungrouped.take(homeLimit).map { toTvShow(it) },
                )
            )
        }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val normalized = ScraperParsing.normalizedKey(query)
        if (normalized.isBlank()) return emptyList()

        return getChannels()
            .asSequence()
            .filter { channel ->
                ScraperParsing.normalizedKey(channel.name).contains(normalized) ||
                    ScraperParsing.normalizedKey(channel.group).contains(normalized)
            }
            .distinctBy { it.name }
            .take(80)
            .map { toTvShow(it) }
            .toList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val channels = getChannels()
        val start = (page - 1) * pageSize
        if (start >= channels.size) return emptyList()
        return channels.drop(start).take(pageSize).map { toTvShow(it) }
    }

    /** Este provider sólo emite señales en directo: no hay catálogo de películas. */
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("$name sólo entrega transmisiones en directo")

    override suspend fun getGenre(id: String, page: Int): Genre {
        val channels = getChannels()
            .filter { it.group?.contains(id, ignoreCase = true) == true }
            .distinctBy { it.name }
        val start = (page - 1) * pageSize
        return Genre(
            id = id,
            name = getIdLabel(id),
            shows = if (start >= channels.size) emptyList()
            else channels.drop(start).take(pageSize).map { toTvShow(it) },
        )
    }

    /** Nombre legible para un id de género (por defecto el propio id). */
    protected open fun getIdLabel(id: String): String = id

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("$name no expone páginas de personas")

    override suspend fun getTvShow(id: String): TvShow {
        val channel = decodeId(id)
        return TvShow(
            id = id,
            title = channel.name,
            poster = channel.logo,
            banner = channel.logo,
            overview = "Canal en directo: ${channel.name}\nFuente: $name",
            seasons = listOf(Season(id = id, number = 1, title = "En vivo")),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return listOf(
            Episode(
                id = seasonId,
                number = 1,
                title = "Ver señal en directo",
                tvShow = getTvShow(seasonId),
                season = Season(id = seasonId, number = 1, title = "En vivo"),
            )
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val channel = decodeId(id)
        if (channel.url.isBlank()) return emptyList()
        return listOf(Video.Server(id = id, name = "Señal directa", src = channel.url))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val channel = decodeId(server.id)
        if (channel.url.isBlank() || NON_STREAM_URL.containsMatchIn(channel.url)) {
            throw Exception("Este item no es un stream reproducible")
        }
        Log.d(tag, "Play: ${channel.url} (headers: ${channel.headers.keys})")
        // El tipo se fija por extensión: HLS o MP4, nunca otra cosa.
        val type = when {
            channel.url.lowercase().contains(".mp4") -> MimeTypes.VIDEO_MP4
            else -> MimeTypes.APPLICATION_M3U8
        }
        return Video(
            source = channel.url,
            subtitles = emptyList(),
            headers = channel.headers.ifEmpty { null },
            type = type,
        )
    }

    // endregion

    protected fun toTvShow(channel: M3UChannel): TvShow = TvShow(
        id = encodeId(channel),
        title = channel.name,
        poster = channel.logo,
        banner = channel.logo,
    )
}
