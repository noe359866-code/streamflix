package com.streamflixreborn.streamflix.providers

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.MovieMasterExtractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.TMDb3
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CineHax (español / LatAm): catálogo TMDb + streams vía [MovieMasterExtractor].
 *
 * La web dejó de usar WordPress: ahora sirve `/pelicula/{id}` y `/serie/{id}`
 * con HTML/JSON propios y un endpoint `ajax` con varias acciones. Los servidores
 * ya no se pueden leer del embed de unlimplay (ese host está muerto para
 * reproducción), así que se delega en MovieMaster, que sí resuelve HLS.
 */
object CineHaxProvider : Provider {

    override val name = "CineHax"
    override val baseUrl = "https://cinehax.com"
    override val logo = "https://cinehax.com/content/uploads/1/logo-ed0267ff.png"
    override val language = "es"

    private const val TAG = "CineHaxProvider"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    private const val REMUX_HOST = "remux.unlimplay.com"

    private val GENRES = listOf(
        "movie:action" to "Acción",
        "movie:adventure" to "Aventura",
        "movie:comedy" to "Comedia",
        "movie:fantasy" to "Fantasía",
        "movie:history" to "Historia",
        "movie:horror" to "Terror",
        "movie:music" to "Música",
        "movie:mystery" to "Misterio",
        "movie:romance" to "Romance",
        "movie:war" to "Bélica",
        "movie:western" to "Western",
        "tv:action-adventure" to "Acción y aventura",
        "tv:kids" to "Infantil",
        "tv:sci-fi-fantasy" to "Ciencia ficción y fantasía",
        "tv:soap" to "Telenovela",
        "tv:war-politics" to "Guerra y política",
    )

    // region HTTP

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        )
                        .build()
                )
            }
            .build()
    }

    private fun get(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        if (referer != null) builder.header("Referer", referer)
        headers.forEach { (key, value) -> builder.header(key, value) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    // endregion

    // region catálogo

    private fun JSONObject.toShow(): Show? {
        val id = optInt("id", -1).takeIf { it != -1 } ?: return null
        val isTv = optString("media_type") == "tv" || (!has("title") && has("name"))
        val poster = optString("poster_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val backdrop = optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val overview = optString("overview").takeIf { it.isNotEmpty() }
        val title = if (isTv) optString("name").ifEmpty { optString("title") }
        else optString("title").ifEmpty { optString("name") }

        return if (isTv) {
            TvShow(id = id.toString(), title = title, overview = overview, poster = poster, banner = backdrop)
        } else {
            Movie(id = id.toString(), title = title, overview = overview, poster = poster, banner = backdrop)
        }
    }

    private fun showKey(show: Show): String = when (show) {
        is Movie -> "movie:${show.id}"
        is TvShow -> "tv:${show.id}"
    }

    override suspend fun getHome(): List<Category> {
        val html = get(baseUrl, referer = "$baseUrl/")
        val categories = mutableListOf<Category>()

        // Carrusel de destacados: `var movies = [...]` dentro del HTML.
        val carouselMatch = Regex("""var\s+movies\s*=\s*(\[\s*\{.*?\}\s*\]);""").find(html)
        if (carouselMatch != null) {
            runCatching {
                val array = JSONArray(carouselMatch.groupValues[1])
                val shows = mutableListOf<Show>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val link = item.optString("link").ifEmpty { item.optString("watch_link") }
                    val isTv = link.contains("/serie/") ||
                        item.optString("type").contains("serie", ignoreCase = true)
                    val id = Regex("""/(?:pelicula|serie)/(\d+)""")
                        .find(link)?.groupValues?.get(1)
                        ?: item.optInt("id", 0).takeIf { it > 0 }?.toString()
                        ?: continue
                    val title = item.optString("title_text").ifEmpty { item.optString("title") }
                    val poster = item.optString("thumbnail").takeIf { it.isNotEmpty() }
                    val banner = item.optString("bg").takeIf { it.isNotEmpty() }
                    val rating = item.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }
                    val released = item.optString("release").takeIf { it.isNotEmpty() }
                    val description = item.optString("desc").takeIf { it.isNotEmpty() }

                    shows += if (isTv) {
                        TvShow(
                            id = id,
                            title = title,
                            overview = description,
                            poster = poster,
                            banner = banner,
                            rating = rating,
                            released = released,
                        )
                    } else {
                        Movie(
                            id = id,
                            title = title,
                            overview = description,
                            poster = poster,
                            banner = banner,
                            rating = rating,
                            released = released,
                        )
                    }
                }
                if (shows.isNotEmpty()) {
                    categories += Category(name = Category.FEATURED, list = shows)
                }
            }.onFailure { Log.w(TAG, "No se pudo leer el carrusel: ${it.message}") }
        }

        val document = Jsoup.parse(html)

        fun parseCard(anchor: Element): Show? {
            val href = anchor.attr("href")
            val id = Regex("""/(?:pelicula|serie)/(\d+)""").find(href)?.groupValues?.get(1) ?: return null
            val isTv = href.contains("/serie/")
            val image = anchor.selectFirst("img")
            var title = image?.attr("alt").orEmpty().ifEmpty { anchor.attr("title") }
            if (title.isEmpty()) {
                title = anchor.selectFirst("h2, h3, h4, span")?.text().orEmpty()
                    .ifEmpty { anchor.attr("title") }
            }
            title = title.replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "").trim()
            if (title.isEmpty()) title = if (isTv) "Serie $id" else "Película $id"

            val poster = image?.attr("src")?.takeIf { it.isNotEmpty() }
            val rating = anchor.selectFirst(".cx-vote, .rating")?.text()
                ?.let { Regex("""[\d.]+""").find(it)?.value?.toDoubleOrNull() }
            val released = anchor.selectFirst("p")?.text()
                ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value }

            return if (isTv) {
                TvShow(id = id, title = title, poster = poster, banner = poster, rating = rating, released = released)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster, rating = rating, released = released)
            }
        }

        document.select(".scroll-section, #top-slider-container, #top-series-slider-container").forEach { section ->
            val name = when (section.id()) {
                "top-slider-container" -> "Top Películas Hoy"
                "top-series-slider-container" -> "Top Series Hoy"
                else -> {
                    val heading = section.selectFirst("h2, h3") ?: return@forEach
                    heading.text().replace(Regex("""Ver todo.*""", RegexOption.IGNORE_CASE), "").trim()
                }
            }
            if (name.isEmpty()) return@forEach

            val shows = section.select("a[href]").mapNotNull(::parseCard).distinctBy(::showKey)
            if (shows.isNotEmpty()) {
                categories += Category(name = name, list = shows)
            }
        }

        return categories
    }

    private fun fetchAjax(action: String, queryParams: String): JSONObject {
        val url = "$baseUrl/ajax?action=$action&$queryParams"
        val response = runCatching { get(url, referer = "$baseUrl/") }
            .onFailure { Log.w(TAG, "ajax $action falló: ${it.message}") }
            .getOrDefault("")
        return if (response.isNotBlank()) runCatching { JSONObject(response) }.getOrDefault(JSONObject()) else JSONObject()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return GENRES.map { (id, genreName) -> Genre(id = id, name = genreName) }
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        if (page == 1) {
            val live = fetchAjax("tmdb_live_search", "query=$encoded")
            if (live.optBoolean("success")) {
                live.optJSONArray("data")?.let { results ->
                    val shows = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toShow() }
                    if (shows.isNotEmpty()) return shows
                }
            }
        }

        return fetchExplorePage("movie", page, q = query) + fetchExplorePage("tv", page, q = query)
    }

    private fun fetchExplorePage(
        type: String,
        page: Int,
        genre: String = "",
        sort: String = "popular",
        q: String = "",
    ): List<Show> {
        val encodedQuery = URLEncoder.encode(q, "UTF-8")
        val params = "page=$page&type=$type&genre=$genre&network=&language=&sort=$sort&time=week&q=$encodedQuery"
        val json = fetchAjax("load_explore_data", params)
        if (!json.optBoolean("success")) return emptyList()
        val html = json.optJSONObject("data")?.optString("html").orEmpty()
        if (html.isBlank()) return emptyList()

        return Jsoup.parse(html).select("a[href*=/pelicula/], a[href*=/serie/]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            val id = Regex("""/(?:pelicula|serie)/(\d+)""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val isTv = href.contains("/serie/") || type == "tv"
            val rawTitle = anchor.selectFirst("h2")?.text()?.ifEmpty { anchor.selectFirst("h3")?.text() }
                ?: anchor.attr("title").replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "").trim()
            val title = rawTitle.ifEmpty { if (isTv) "Serie $id" else "Película $id" }
            val poster = anchor.selectFirst("img")?.attr("src")
            val rating = anchor.selectFirst("span.drop-shadow-md, div.rounded-full span")?.text()?.toDoubleOrNull()
            val released = anchor.selectFirst("p")?.text()

            if (isTv) {
                TvShow(id = id, title = title, poster = poster, banner = poster, rating = rating, released = released)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster, rating = rating, released = released)
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        fetchExplorePage("movie", page).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        fetchExplorePage("tv", page).filterIsInstance<TvShow>()

    override suspend fun getGenre(id: String, page: Int): Genre {
        val type = id.substringBefore(":")
        val slug = id.substringAfter(":")
        return Genre(
            id = id,
            name = GENRES.toMap()[id] ?: slug,
            shows = fetchExplorePage(type, page, genre = slug),
        )
    }

    // endregion

    // region detalle

    private data class WatchPageMeta(
        val overview: String?,
        val poster: String?,
        val rating: Double?,
        val released: String?,
        val genres: List<Genre>,
        val trailer: String?,
    )

    private fun parseWatchPage(html: String): WatchPageMeta {
        val overview = Regex("""Descripción</h3>\s*<p[^>]*>([^<]*)</p>""")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val rating = Regex("""font-bold text-white flex items-center gap-0\.5">([\d.]+)</div>""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val released = Regex("""\bde (\d{4})</span>""").find(html)?.groupValues?.get(1)
        val genres = Regex(
            """px-2 py-1 bg-white/5 border border-gray-700 rounded-full text-white text-xs">\s*([^<]+?)\s*</div>"""
        ).findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .map { Genre(id = it.lowercase(), name = it) }
            .toList()
        val poster = Regex("""https://image\.tmdb\.org/t/p/w500/[^"]+""").find(html)?.value
        val trailerId = Regex("""id="trailer-thumb"[^>]*src="[^"]*/vi/([^/]+)/""")
            .find(html)?.groupValues?.get(1)

        return WatchPageMeta(
            overview = overview,
            poster = poster,
            rating = rating,
            released = released,
            genres = genres,
            trailer = trailerId?.let { "https://www.youtube.com/watch?v=$it" },
        )
    }

    private fun firstPage(urls: List<String>): String {
        for (url in urls) {
            val html = runCatching { get(url, referer = "$baseUrl/") }.getOrDefault("")
            if (html.isNotBlank()) return html
        }
        return ""
    }

    override suspend fun getMovie(id: String): Movie {
        val html = firstPage(
            listOf("$baseUrl/pelicula/$id/", "$baseUrl/ver/?tipo=pelicula&id=$id")
        )
        val meta = parseWatchPage(html)
        val tmdb = runCatching { TMDb3.Movies.details(id.toInt(), language = language) }.getOrNull()

        return Movie(
            id = id,
            title = tmdb?.title ?: "",
            overview = tmdb?.overview ?: meta.overview,
            released = meta.released,
            rating = tmdb?.voteAverage?.toDouble() ?: meta.rating,
            poster = meta.poster,
            banner = tmdb?.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            trailer = meta.trailer,
            genres = meta.genres,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val html = firstPage(
            listOf("$baseUrl/serie/$id/", "$baseUrl/ver/?tipo=serie&id=$id")
        )
        val meta = parseWatchPage(html)
        val seasonNumbers = Regex("""(?:temporada-|season=)(\d+)""").findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty {
                Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE).findAll(html)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .distinct()
                    .sorted()
                    .toList()
            }
            .ifEmpty { listOf(1) }

        val tmdb = runCatching { TMDb3.TvSeries.details(id.toInt(), language = language) }.getOrNull()

        return TvShow(
            id = id,
            title = tmdb?.name ?: "",
            overview = tmdb?.overview ?: meta.overview,
            released = meta.released,
            rating = tmdb?.voteAverage?.toDouble() ?: meta.rating,
            poster = meta.poster,
            banner = tmdb?.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            trailer = meta.trailer,
            genres = meta.genres,
            seasons = seasonNumbers.map { number ->
                Season(id = "$id-$number", number = number, title = "Temporada $number")
            },
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val tvId = seasonId.substringBeforeLast("-")
        val seasonNumber = seasonId.substringAfterLast("-").toIntOrNull() ?: 1
        val html = firstPage(
            listOf(
                "$baseUrl/serie/$tvId/temporada-$seasonNumber/episodio-1/",
                "$baseUrl/serie/$tvId/",
                "$baseUrl/ver/?tipo=serie&id=$tvId&season=$seasonNumber&episode=1",
            )
        )

        return Jsoup.parse(html)
            .select("a[href*=temporada-$seasonNumber/episodio-], a[href*=season=$seasonNumber&episode=]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                val number = Regex("""(?:episodio-|episode=)(\d+)""")
                    .find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                // Algunos enlaces de la lista son los chips de temporada.
                val isSeasonChip = (
                    anchor.attr("aria-label").contains("Temporada", ignoreCase = true) ||
                        anchor.text().contains("Temporada", ignoreCase = true)
                    ) && anchor.selectFirst("span") == null && anchor.text().trim().length <= 4
                if (isSeasonChip) return@mapNotNull null

                var title = anchor.text().trim()
                    .replace(Regex("""^E\d+\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (title.isEmpty() || Regex("""^(?:T\d+|E\d+|Temporada\s*\d+)$""", RegexOption.IGNORE_CASE).matches(title)) {
                    title = "Episodio $number"
                }

                Episode(
                    id = "$tvId|$seasonNumber|$number",
                    number = number,
                    title = title,
                    poster = anchor.selectFirst("img")?.attr("src"),
                    tvShow = TvShow(id = tvId, title = ""),
                    season = Season(id = seasonId, number = seasonNumber),
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw Exception("Not yet implemented")

    // endregion

    // region streams

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        Provider.playableServers(MovieMasterExtractor().servers(videoType))

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifBlank { server.id }
        // Los enlaces de remux.unlimplay son MP4 directos, sin extractor.
        if (url.contains(REMUX_HOST)) {
            return Video(source = url, type = MimeTypes.VIDEO_MP4)
        }
        return Extractor.extract(url, server)
    }

    // endregion
}
