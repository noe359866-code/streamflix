package com.streamflixreborn.streamflix.providers

import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.ScraperCache
import com.streamflixreborn.streamflix.scrapers.ScraperParsing
import com.streamflixreborn.streamflix.utils.NetworkClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale

/**
 * Fanpelis (español latino).
 *
 * Usa la API REST pública del sitio (`/api/rest/...`), mucho más estable que
 * scrapear el HTML. Cada servidor embebido se resuelve con [Extractor], por lo
 * que hereda automáticamente el soporte de Voe/StreamWish/Filemoon/Dood...
 *
 * Novedades frente a la versión original:
 * - la lista de episodios se cachea (antes se pedía en cada apertura de temporada);
 * - los servidores se deduplican y se les añade calidad/idioma si la API los da;
 * - las imágenes se resuelven con [ScraperParsing] (rutas relativas incluidas).
 */
object FanpelisProvider : Provider {

    private const val SITE_URL = "https://fanpelis.to"
    private const val API_URL = "$SITE_URL/api/rest/"

    override val baseUrl = SITE_URL
    override val name = "Fanpelis"
    override val logo =
        "https://fanpelis.to/wp-content/uploads/2025/02/cropped-play-button-icon-trendy-flat-260nw-752745979-e1738708582632-192x192.webp"
    override val language = "es"

    private const val PAGE_SIZE = 16

    private val service: FanpelisService by lazy {
        Retrofit.Builder()
            .baseUrl(API_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(NetworkClient.default)
            .build()
            .create(FanpelisService::class.java)
    }

    private val episodesCache = ScraperCache<List<FanEpisode>>(ttlMs = 15 * 60 * 1000L)

    override suspend fun getHome(): List<Category> {
        val movies = runCatching { service.listing(1, "movies", PAGE_SIZE).data?.posts.orEmpty() }
            .getOrDefault(emptyList())
        val shows = runCatching { service.listing(1, "tvshows", PAGE_SIZE).data?.posts.orEmpty() }
            .getOrDefault(emptyList())
        val animes = runCatching { service.listing(1, "animes", PAGE_SIZE).data?.posts.orEmpty() }
            .getOrDefault(emptyList())

        return buildList {
            val featured = (movies + shows).take(8).mapNotNull(::toShow)
            if (featured.isNotEmpty()) add(Category(Category.FEATURED, featured))
            movies.mapNotNull(::toMovie).takeIf { it.isNotEmpty() }
                ?.let { add(Category("Últimas películas", it)) }
            shows.mapNotNull(::toTvShow).takeIf { it.isNotEmpty() }
                ?.let { add(Category("Últimas series", it)) }
            animes.mapNotNull(::toTvShow).takeIf { it.isNotEmpty() }
                ?.let { add(Category("Últimos animes", it)) }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            service.search(query, page, "movies,tvshows,animes", PAGE_SIZE)
                .data?.posts.orEmpty()
                .mapNotNull(::toShow)
        }.getOrDefault(emptyList())
    }

    override suspend fun getMovies(page: Int): List<Movie> = runCatching {
        service.listing(page, "movies", PAGE_SIZE).data?.posts.orEmpty().mapNotNull(::toMovie)
    }.getOrDefault(emptyList())

    override suspend fun getTvShows(page: Int): List<TvShow> = runCatching {
        service.listing(page, "tvshows", PAGE_SIZE).data?.posts.orEmpty().mapNotNull(::toTvShow)
    }.getOrDefault(emptyList())

    override suspend fun getMovie(id: String): Movie {
        val item = runCatching { service.single(id, "movies").data }.getOrNull()
            ?: return Movie(id = id, title = id.replace('-', ' '))
        return toMovie(item) ?: Movie(id = id, title = item.title)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val item = runCatching { service.single(id, "tvshows").data }.getOrNull()
            ?: return TvShow(id = id, title = id.replace('-', ' '))
        val show = toTvShow(item) ?: TvShow(id = id, title = item.title)
        // Las temporadas se derivan de la lista de episodios (cacheada 15 min).
        val episodes = episodesCache.getOrFetchOrNull("episodes|${item.id}") {
            service.episodes(item.id).data.orEmpty()
        }.orEmpty()
        val seasonNumbers = episodes.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
            .ifEmpty { listOf(1) }
        val seasons = seasonNumbers.map { number ->
            Season(
                id = "${item.id}|$number",
                number = number,
                title = "Temporada $number",
                poster = show.poster,
            )
        }
        return show.copy(seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split('|', limit = 2)
        val showId = parts.firstOrNull()?.toIntOrNull() ?: return emptyList()
        val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()

        val episodes = episodesCache.getOrFetchOrNull("episodes|$showId") {
            service.episodes(showId).data.orEmpty()
        }.orEmpty()

        return episodes
            .filter { it.seasonNumber == seasonNumber }
            .sortedBy { it.episodeNumber }
            .map(::toEpisode)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val response = runCatching { service.taxonomy("genres", id, page) }.getOrNull()
        return Genre(
            id = id,
            name = ScraperParsing.cleanText(id.replace('-', ' ')) ?: id,
            shows = response?.data?.posts.orEmpty().mapNotNull(::toShow),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val response = runCatching { service.taxonomy("cast", id, page) }.getOrNull()
        return People(
            id = id,
            name = ScraperParsing.cleanText(id.replace('-', ' ')) ?: id,
            filmography = response?.data?.posts.orEmpty().mapNotNull(::toShow),
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val postId = when (videoType) {
            is Video.Type.Movie -> id.toIntOrNull() ?: service.single(id, "movies").data?.id
            is Video.Type.Episode -> id.toIntOrNull()
        } ?: return emptyList()

        val embeds = runCatching { service.player(postId).data?.embeds.orEmpty() }
            .getOrDefault(emptyList())

        return embeds.mapNotNull { embed ->
            val url = embed.url.trim().takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val host = ScraperParsing.cleanText(url.substringAfter("//").substringBefore('/'))
                ?.substringBefore('.')
                ?.replaceFirstChar { it.uppercase() }
                ?: "Servidor"
            val details = listOfNotNull(
                embed.lang?.takeIf { it.isNotBlank() }?.let { lang -> ifLangIsSpanish(lang) ?: lang.uppercase() },
                embed.quality?.takeIf { it.isNotBlank() }?.let { quality -> "${quality}p" },
            ).joinToString(" · ")
            Video.Server(
                id = url,
                name = if (details.isBlank()) host else "$host · $details",
                src = url,
            )
        }.distinctBy { it.src }
    }

    private fun ifLangIsSpanish(value: String): String? = when (value.lowercase(Locale.ROOT)) {
        "lat", "latino", "es", "es-la", "spanish-lat" -> "Latino"
        "esp", "cast", "castellano", "es-es" -> "Castellano"
        "sub", "subtitulado", "vose" -> "Subtitulado"
        else -> null
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    // region mapping

    private fun toShow(item: FanItem): Show? = when (item.type) {
        "movies" -> toMovie(item)
        "tvshows", "animes" -> toTvShow(item)
        else -> null
    }

    private fun toMovie(item: FanItem) = Movie(
        id = item.slug.ifBlank { item.id.toString() },
        title = ScraperParsing.cleanTitle(item.title) ?: item.title,
        overview = item.overview,
        released = ScraperParsing.parseYear(item.releaseDate),
        runtime = item.runtime.toDoubleOrNull()?.toInt() ?: ScraperParsing.parseRuntimeMinutes(item.runtime),
        trailer = item.trailer.takeIf(String::isNotBlank)?.let { "https://www.youtube.com/watch?v=$it" },
        quality = item.quality.firstOrNull()?.toString(),
        rating = ScraperParsing.parseRating(item.rating),
        poster = image(item.images?.poster),
        banner = image(item.images?.backdrop),
        genres = item.genres.map { genre -> Genre(id = genre.toString(), name = genre.toString()) },
    )

    private fun toTvShow(item: FanItem) = TvShow(
        id = item.slug.ifBlank { item.id.toString() },
        title = ScraperParsing.cleanTitle(item.title) ?: item.title,
        overview = item.overview,
        released = ScraperParsing.parseYear(item.releaseDate),
        runtime = item.runtime.toDoubleOrNull()?.toInt(),
        trailer = item.trailer.takeIf(String::isNotBlank)?.let { "https://www.youtube.com/watch?v=$it" },
        quality = item.quality.firstOrNull()?.toString(),
        rating = ScraperParsing.parseRating(item.rating),
        poster = image(item.images?.poster),
        banner = image(item.images?.backdrop),
        genres = item.genres.map { genre -> Genre(id = genre.toString(), name = genre.toString()) },
    )

    private fun toEpisode(item: FanEpisode) = Episode(
        id = item.id.toString(),
        number = item.episodeNumber,
        title = ScraperParsing.cleanText(item.title),
        overview = item.overview,
        poster = image(item.stillPath),
    )

    private fun image(path: String?): String? {
        val value = ScraperParsing.cleanText(path) ?: return null
        return when {
            value.startsWith("http") -> value
            value.contains("/wp-content/") -> SITE_URL + (if (value.startsWith('/')) value else "/$value")
            else -> "$SITE_URL/wp-content/uploads${if (value.startsWith('/')) value else "/$value"}"
        }
    }

    // endregion

    // region api model

    private data class ApiResponse<T>(val error: Boolean = false, val data: T? = null)
    private data class ListingData(val posts: List<FanItem> = emptyList())
    private data class PlayerData(val embeds: List<Embed> = emptyList())
    private data class Images(val poster: String? = null, val backdrop: String? = null)
    private data class Embed(
        val url: String = "",
        val lang: String? = null,
        val quality: String? = null,
    )

    private data class FanItem(
        @SerializedName("_id") val id: Int = 0,
        val title: String = "",
        val overview: String? = null,
        val slug: String = "",
        val images: Images? = null,
        val trailer: String = "",
        val rating: String = "",
        val genres: List<Int> = emptyList(),
        val quality: List<Int> = emptyList(),
        val type: String = "",
        @SerializedName("release_date") val releaseDate: String? = null,
        val runtime: String = "",

    )

    private data class FanEpisode(
        @SerializedName("_id") val id: Int = 0,
        val title: String = "",
        val overview: String? = null,
        @SerializedName("still_path") val stillPath: String? = null,
        @SerializedName("season_number") val seasonNumber: Int = 0,
        @SerializedName("episode_number") val episodeNumber: Int = 0,
    )

    private interface FanpelisService {
        @GET("listing")
        suspend fun listing(
            @Query("page") page: Int,
            @Query("post_type") postType: String,
            @Query("posts_per_page") postsPerPage: Int,
        ): ApiResponse<ListingData>

        @GET("search")
        suspend fun search(
            @Query("query") query: String,
            @Query("page") page: Int,
            @Query("post_type") postType: String,
            @Query("posts_per_page") postsPerPage: Int,
        ): ApiResponse<ListingData>

        @GET("single")
        suspend fun single(
            @Query("post_name") slug: String,
            @Query("post_type") type: String,
        ): ApiResponse<FanItem>

        @GET("listing")
        suspend fun taxonomy(
            @Query("tax") tax: String,
            @Query("term") term: String,
            @Query("page") page: Int,
            @Query("post_type") postType: String = "movies,tvshows,animes",
            @Query("posts_per_page") postsPerPage: Int = 16,
        ): ApiResponse<ListingData>

        @GET("episodes")
        suspend fun episodes(@Query("post_id") postId: Int): ApiResponse<List<FanEpisode>>

        @GET("player")
        suspend fun player(@Query("post_id") postId: Int, @Query("_any") any: Int = 1): ApiResponse<PlayerData>
    }

    // endregion

}
