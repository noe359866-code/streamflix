package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.ScraperCache
import com.streamflixreborn.streamflix.scrapers.ScraperParsing
import com.streamflixreborn.streamflix.scrapers.StremioAddon
import com.streamflixreborn.streamflix.scrapers.StreamLinks

/**
 * Provider basado en **addons de Stremio** (protocolo v3).
 *
 * Catálogos ya soportados de fábrica:
 * - **Cinemeta** — catálogos populares, por año, por nota y por género (cine y series).
 * - **Streaming Catalogs** — catálogos de Netflix, HBO Max, Disney+, Prime Video y Apple TV+.
 * - **NoTorrent** — catálogos de Netflix, Disney+, Prime Video, Apple TV+, HBO Max,
 *   Paramount+ y Crunchyroll (inglés y español) + streams sin torrent.
 *
 * Además acepta cualquier addon compatible que se añada por URL (`extraAddons`),
 * que es lo que hace la app "compatible con todo" sin escribir un provider por sitio.
 *
 * Contrato de entrega: **sólo HLS (.m3u8) y MP4**. Los catálogos de Stremio usan
 * ids `tt…` (IMDb) y `tmdb_…`; como el resto de la app trabaja con ids de TMDb,
 * se traducen aquí (sin `.meta()` extra) para no perder fichas, temporadas ni
 * recomendaciones, y el player sigue funcionando con todas sus funciones.
 */
object StremioCatalogsProvider : Provider {

    private const val TAG = "StremioCatalogs"

    override val name = "Stremio Catalogs"
    override val baseUrl = "https://v3-cinemeta.strem.io"
    override val logo = "https://www.stremio.com/website/stremio-logo-small.png"
    override val language = "es"

    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private const val STREAMING_CATALOGS = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
    private const val NOTORRENT = "https://addon.notorrent2.workers.dev"

    /** Addons de catálogo que se consultan, en orden de prioridad. */
    private val addons = listOf(CINEMETA, STREAMING_CATALOGS, NOTORRENT)

    /** Addons de los que se aceptan `stream` (además de resolver con nuestros extractores). */
    private val streamAddons = listOf(NOTORRENT)

    /** Addons adicionales configurados por el usuario (URL del `manifest.json`). */
    var extraAddons: List<String> = emptyList()

    var extraStreamAddons: List<String> = emptyList()

    private val manifests = ScraperCache<StremioAddon.Manifest>(ttlMs = 6 * 60 * 60 * 1000L)
    private val catalogCache = ScraperCache<List<StremioAddon.Meta>>(ttlMs = 15 * 60 * 1000L)

    private val allAddons get() = (addons + extraAddons).distinct()
    private val allStreamAddons get() = (streamAddons + extraStreamAddons).distinct()

    // region home

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()

        // 1) Destacados de Cinemeta (cine popular) para el carrusel superior.
        val featured = catalogMetasInternal(CINEMETA, "movie", "top", genre = null, limit = 12)
        if (featured.isNotEmpty()) {
            categories.add(Category(Category.FEATURED, featured.mapNotNull(::toMovie)))
        }

        // 2) Una categoría por cada catálogo publicado por cada addon.
        for (addonUrl in allAddons) {
            val manifest = manifest(addonUrl) ?: continue
            for (catalog in manifest.catalogs) {
                if (catalog.type != "movie" && catalog.type != "series") continue
                // Los catálogos con extras obligatorios (p. ej. "year") necesitan
                // un género fijo: se usa el primero disponible.
                val requiredGenre = catalog.extra
                    ?.firstOrNull { it.isRequired && it.name == "genre" }
                    ?.options?.firstOrNull()
                val metas = catalogMetasInternal(
                    addonUrl = addonUrl,
                    type = catalog.type,
                    catalogId = catalog.id,
                    genre = requiredGenre,
                    limit = 24,
                )
                if (metas.isEmpty()) continue
                val items: List<AppAdapter.Item> = if (catalog.type == "movie") {
                    metas.mapNotNull(::toMovie)
                } else {
                    metas.mapNotNull(::toTvShow)
                }
                if (items.isEmpty()) continue
                val label = buildString {
                    append(catalog.displayName)
                    append(" · ")
                    append(manifest.name.ifBlank { addonLabel(addonUrl) })
                }
                if (categories.none { it.name == label }) {
                    categories.add(Category(label, items))
                }
            }
        }

        return categories.distinctBy { it.name }
    }

    // endregion

    private suspend fun catalogMetasInternal(
        addonUrl: String,
        type: String,
        catalogId: String,
        genre: String?,
        limit: Int,
    ): List<StremioAddon.Meta> {
        val key = "catalog|$addonUrl|$type|$catalogId|${genre.orEmpty()}"
        val metas = catalogCache.getOrFetchOrNull(key) {
            StremioAddon.catalog(addonUrl, type, catalogId, genre = genre)
        }.orEmpty()
        return metas.take(limit)
    }

    private suspend fun manifest(addonUrl: String): StremioAddon.Manifest? =
        manifests.getOrFetchOrNull("manifest|$addonUrl") {
            StremioAddon.manifest(addonUrl)
                ?: throw IllegalStateException("manifest no disponible en $addonUrl")
        }

    private fun addonLabel(addonUrl: String): String =
        StremioAddon.normalize(addonUrl).substringAfter("//").substringBefore('/')

    // region mapeo

    private fun toMovie(meta: StremioAddon.Meta): Movie? {
        if (meta.name.isBlank()) return null
        return Movie(
            id = meta.id,
            title = ScraperParsing.cleanTitle(meta.name) ?: meta.name,
            overview = meta.description,
            released = meta.year,
            runtime = ScraperParsing.parseRuntimeMinutes(meta.runtime),
            rating = ScraperParsing.parseRating(meta.imdbRating),
            poster = meta.poster,
            banner = meta.background,
            imdbId = meta.imdbId,
            genres = meta.genres.map { Genre(id = ScraperParsing.slugify(it), name = it) },
        )
    }

    private fun toTvShow(meta: StremioAddon.Meta): TvShow? {
        if (meta.name.isBlank()) return null
        return TvShow(
            id = meta.id,
            title = ScraperParsing.cleanTitle(meta.name) ?: meta.name,
            overview = meta.description,
            released = meta.year,
            rating = ScraperParsing.parseRating(meta.imdbRating),
            poster = meta.poster,
            banner = meta.background,
            imdbId = meta.imdbId,
            genres = meta.genres.map { Genre(id = ScraperParsing.slugify(it), name = it) },
        )
    }

    // endregion

    // region búsqueda y listados

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<AppAdapter.Item>()

        // Cinemeta soporta `search` en su catálogo "top".
        StremioAddon.catalog(CINEMETA, "movie", "top", search = query).forEach { meta ->
            toMovie(meta)?.let(results::add)
        }
        StremioAddon.catalog(CINEMETA, "series", "top", search = query).forEach { meta ->
            toTvShow(meta)?.let(results::add)
        }

        // El resto de addons buscan por título en su propio catálogo (si lo permiten).
        for (addonUrl in allAddons - CINEMETA) {
            val manifest = manifest(addonUrl) ?: continue
            for (catalog in manifest.catalogs.filter { it.extra?.any { extra -> extra.name == "search" } == true }) {
                StremioAddon.catalog(addonUrl, catalog.type, catalog.id, search = query).forEach { meta ->
                    if (catalog.type == "movie") toMovie(meta)?.let(results::add)
                    else toTvShow(meta)?.let(results::add)
                }
            }
        }

        return results.distinctBy { "${it::class.simpleName}:${(it as? Movie)?.id ?: (it as? TvShow)?.id}" }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        catalogMetasInternal(CINEMETA, "movie", "top", null, limit = 40)
            .mapNotNull(::toMovie)

    override suspend fun getTvShows(page: Int): List<TvShow> =
        catalogMetasInternal(CINEMETA, "series", "top", null, limit = 40)
            .mapNotNull(::toTvShow)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val parts = id.split('|')
        val addonUrl = parts.getOrNull(0) ?: CINEMETA
        val type = parts.getOrNull(1) ?: "movie"
        val catalogId = parts.getOrNull(2) ?: "top"
        val genreName = parts.getOrNull(3) ?: ""

        val metas = StremioAddon.catalog(
            addonUrl = addonUrl,
            type = type,
            catalogId = catalogId,
            genre = genreName.takeIf { it.isNotBlank() },
            skip = (page - 1) * 40,
        )
        return Genre(
            id = id,
            name = genreName.ifBlank { catalogId },
            shows = if (type == "movie") metas.mapNotNull(::toMovie) else metas.mapNotNull(::toTvShow),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("$name no expone fichas de personas")

    // endregion

    // region fichas

    override suspend fun getMovie(id: String): Movie {
        val resolved = resolveMeta(id, "movie")
            ?: return Movie(id = id, title = id)
        return toMovie(resolved) ?: Movie(id = id, title = resolved.name)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val resolved = resolveMeta(id, "series")
            ?: return TvShow(id = id, title = id)
        val show = toTvShow(resolved) ?: TvShow(id = id, title = resolved.name)
        val seasons = resolved.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()
            .map { number ->
                Season(
                    id = "$id|$number",
                    number = number,
                    title = if (number == 0) "Especiales" else "Temporada $number",
                    poster = show.poster,
                )
            }
        return show.copy(seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore('|')
        val seasonNumber = seasonId.substringAfter('|', "1").toIntOrNull() ?: 1

        val meta = resolveMeta(showId, "series") ?: return emptyList()
        return meta.videos
            .filter { (it.season ?: 1) == seasonNumber }
            .sortedBy { it.episode ?: it.number ?: 0 }
            .map { video ->
                Episode(
                    id = video.id.ifBlank { "$showId:$seasonNumber:${video.episode}" },
                    number = video.episode ?: video.number ?: 0,
                    title = ScraperParsing.cleanText(video.name),
                    overview = video.overview,
                    poster = video.thumbnail,
                    released = video.released?.take(10),
                )
            }
    }

    /** `meta` del addon, tolerando ids `tt…`, `tmdb_…` o de un catálogo propio. */
    private suspend fun resolveMeta(id: String, type: String): StremioAddon.Meta? {
        if (id.startsWith(NOTORRENT)) return null

        // 1) Cinemeta entiende tt… y devuelve temporadas + episodios.
        StremioAddon.meta(CINEMETA, type, id)?.let { return it }

        // 2) Cualquier otro addon que exponga `meta`.
        for (addonUrl in allAddons - CINEMETA) {
            val manifest = manifest(addonUrl) ?: continue
            if (!manifest.has("meta")) continue
            val prefixes = manifest.idPrefixes
            if (prefixes != null && prefixes.none { id.startsWith(it) }) continue
            StremioAddon.meta(addonUrl, type, id)?.let { return it }
        }

        // 3) Último recurso: el propio catálogo (metas sin videos).
        for (addonUrl in allAddons) {
            val manifest = manifest(addonUrl) ?: continue
            for (catalog in manifest.catalogs.filter { it.type == type }) {
                val metas = catalogMetasInternal(addonUrl, type, catalog.id, null, 100)
                metas.firstOrNull { it.id == id }?.let { return it }
            }
        }
        return null
    }

    // endregion

    // region reproducción

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val (type, streamId) = streamTarget(id, videoType)

        val servers = mutableListOf<Video.Server>()

        // 1) Addons con recurso `stream` (p. ej. NoTorrent): sólo HLS/MP4.
        for (addonUrl in allStreamAddons) {
            StremioAddon.streams(addonUrl, type, streamId).forEach { stream ->
                servers.add(StremioAddon.toServer(stream))
            }
        }

        // 2) Nuestros resolvers por TMDb (VixSrc, VidLink, PrimeSrc…), igual que TMDb.
        runCatching { providersFallback(id, videoType) }
            .onSuccess { servers.addAll(it) }
            .onFailure { Log.w(TAG, "fallback de extractores falló: ${it.message}") }

        // 3) Deduplicado final: sólo streams reproducibles (HLS/MP4).
        return Provider.playableServers(servers)
    }

    /** Tipo y id que entienden los addons (`tt0903747:1:1` para episodios). */
    private suspend fun streamTarget(id: String, videoType: Video.Type): Pair<String, String> = when (videoType) {
        is Video.Type.Movie -> "movie" to (videoType.imdbId ?: id)
        is Video.Type.Episode -> {
            // Los addons esperan `tt<serie>:<temporada>:<episodio>`.
            val showId = videoType.tvShow.imdbId ?: id.substringBeforeLast(':')
            "series" to "$showId:${videoType.season.number}:${videoType.number}"
        }
    }

    /**
     * Servidores de nuestros propios resolvers, reutilizando la lógica de TMDb
     * (que ya sabe qué extractor sirve para cada idioma).
     */
    private suspend fun providersFallback(id: String, videoType: Video.Type): List<Video.Server> {
        val tmdbId = when (videoType) {
            is Video.Type.Movie -> videoType.id
            is Video.Type.Episode -> videoType.tvShow.id
        }.toIntOrNull() ?: return emptyList()

        return TmdbProvider(language).getServers(tmdbId.toString(), videoType)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifBlank { server.id }
        // Un servidor de addon puede ser una URL directa HLS/MP4: se entrega tal cual.
        if (StreamLinks.isDirectMedia(url)) {
            return Video(
                source = url,
                type = if (url.substringBefore('?').endsWith(".mp4")) "video/mp4" else "application/x-mpegURL",
            )
        }
        return Extractor.extract(url, server)
    }

    // endregion

    /** Codifica un género/catálogo como id de `getGenre`. */
    fun genreId(addonUrl: String, type: String, catalogId: String, genre: String): String =
        "$addonUrl|$type|$catalogId|$genre"

}
