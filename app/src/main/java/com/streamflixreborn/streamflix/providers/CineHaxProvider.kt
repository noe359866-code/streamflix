package com.streamflixreborn.streamflix.providers

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.MovieMasterExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CineHax Spanish/Latino provider (`cinehax.com`) resolving streams via `MovieMasterExtractor`
 * (with fallback to Unlimplay embeds).
 */
object CineHaxProvider : Provider {

    override val name = "CineHax"
    override val baseUrl = "https://cinehax.com"
    override val logo = "https://cinehax.com/content/uploads/1/logo-ed0267ff.png"
    override val language = "es"

    private const val TAG = "CineHaxProvider"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    private const val UNLIMPLAY_HOST = "unlimplay.com"
    private const val REMUX_HOST = "remux.unlimplay.com"

    private val LANGUAGE_ORDER = listOf("latino", "castellano", "subtitulado")
    private val LANGUAGE_LABELS = mapOf(
        "latino" to "Latino",
        "castellano" to "Castellano",
        "subtitulado" to "Subtitulado",
    )
    private val PRIORITY_SERVERS = listOf("remux")

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

    private val httpClient = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .build()
            chain.proceed(request)
        }
        .build()

    private suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        if (referer != null) {
            builder.header("Referer", referer)
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            response.body?.string().orEmpty()
        }
    }

    private fun JSONObject.toShow(): Show? {
        val id = optInt("id", -1).takeIf { it != -1 } ?: return null
        val isTv = optString("media_type") == "tv" || (!has("title") && has("name"))
        val poster = optString("poster_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val backdrop = optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val overview = optString("overview").takeIf { it.isNotEmpty() }
        val releasedRaw = optString("release_date")
            .ifEmpty { optString("first_air_date") }
            .ifEmpty { optString("year") }
            .takeIf { it.isNotEmpty() }

        return if (isTv) {
            TvShow(
                id = id.toString(),
                title = optString("name").ifEmpty { optString("title") },
                overview = overview,
                released = releasedRaw,
                poster = poster,
                banner = backdrop,
            )
        } else {
            Movie(
                id = id.toString(),
                title = optString("title").ifEmpty { optString("name") },
                overview = overview,
                released = releasedRaw,
                poster = poster,
                banner = backdrop,
            )
        }
    }

    private fun showKey(show: Show): String = when (show) {
        is Movie -> "movie:${show.id}"
        is TvShow -> "tv:${show.id}"
    }

    override suspend fun getHome(): List<Category> {
        val html = get(baseUrl, referer = "$baseUrl/")
        val categories = mutableListOf<Category>()

        val moviesMatch = Regex("""var\s+movies\s*=\s*(\[\s*\{.*?\}\s*\]);""").find(html)
        if (moviesMatch != null) {
            try {
                val jsonArr = JSONArray(moviesMatch.groupValues[1])
                val carouselShows = mutableListOf<Show>()
                for (i in 0 until jsonArr.length()) {
                    val it = jsonArr.optJSONObject(i) ?: continue
                    val link = it.optString("link").ifEmpty { it.optString("watch_link") }
                    val isTv = link.contains("/serie/") || it.optString("type").contains("serie", ignoreCase = true)
                    val id = Regex("""/(?:pelicula|serie)/(\d+)""").find(link)?.groupValues?.get(1)
                        ?: it.optInt("id", 0).takeIf { n -> n > 0 }?.toString() ?: continue
                    val title = it.optString("title_text").ifEmpty { it.optString("title") }
                    val poster = it.optString("thumbnail").takeIf { s -> s.isNotEmpty() }
                    val banner = it.optString("bg").takeIf { s -> s.isNotEmpty() }
                    val rating = it.optDouble("rating", Double.NaN).takeIf { d -> !d.isNaN() }
                    val released = it.optString("release").takeIf { s -> s.isNotEmpty() }
                    val desc = it.optString("desc").takeIf { s -> s.isNotEmpty() }

                    if (isTv) {
                        carouselShows.add(
                            TvShow(
                                id = id,
                                title = title,
                                overview = desc,
                                poster = poster,
                                banner = banner,
                                rating = rating,
                                released = released,
                            )
                        )
                    } else {
                        carouselShows.add(
                            Movie(
                                id = id,
                                title = title,
                                overview = desc,
                                poster = poster,
                                banner = banner,
                                rating = rating,
                                released = released,
                            )
                        )
                    }
                }
                if (carouselShows.isNotEmpty()) {
                    categories.add(Category(name = Category.FEATURED, list = carouselShows))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse var movies: ${e.message}")
            }
        }

        val doc = Jsoup.parse(html)

        fun parseCard(a: org.jsoup.nodes.Element): Show? {
            val href = a.attr("href")
            val id = Regex("""/(?:pelicula|serie)/(\d+)""").find(href)?.groupValues?.get(1) ?: return null
            val isTv = href.contains("/serie/")
            val img = a.selectFirst("img")
            var title = img?.attr("alt").orEmpty().ifEmpty { a.attr("title") }
            if (title.isEmpty()) {
                title = a.selectFirst("h2, h3, h4, span")?.text().orEmpty().ifEmpty { a.attr("title") }
            }
            title = title.replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "").trim()
            if (title.isEmpty()) {
                title = if (isTv) "Serie $id" else "Película $id"
            }
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
            val rating = a.selectFirst(".cx-vote, .rating")?.text()?.let {
                Regex("""[\d.]+""").find(it)?.value?.toDoubleOrNull()
            }
            val released = a.selectFirst("p")?.text()?.let {
                Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value
            }

            return if (isTv) {
                TvShow(id = id, title = title, poster = poster, banner = poster, rating = rating, released = released)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster, rating = rating, released = released)
            }
        }

        doc.select(".scroll-section, #top-slider-container, #top-series-slider-container").forEach { element ->
            if (element.id() == "cx-continue") return@forEach

            val (sectionName, container) = when (element.id()) {
                "top-slider-container" -> "Top Películas Hoy" to element
                "top-series-slider-container" -> "Top Series Hoy" to element
                else -> {
                    val hTag = element.selectFirst("h2, h3") ?: return@forEach
                    val title = hTag.text().replace(Regex("""Ver todo.*""", RegexOption.IGNORE_CASE), "").trim()
                    if (title.isEmpty()) return@forEach
                    title to element
                }
            }

            val shows = container.select("a[href]").mapNotNull { parseCard(it) }.distinctBy { showKey(it) }
            if (shows.isNotEmpty()) {
                categories.add(Category(name = sectionName, list = shows))
            }
        }

        return categories
    }

    private suspend fun fetchAjax(action: String, queryParams: String): JSONObject {
        val url = "$baseUrl/ajax?action=$action&$queryParams"
        val response = try {
            get(url, referer = "$baseUrl/")
        } catch (e: Exception) {
            Log.w(TAG, "Ajax error for $url: ${e.message}")
            ""
        }
        return if (response.isNotBlank()) runCatching { JSONObject(response) }.getOrDefault(JSONObject()) else JSONObject()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return GENRES.map { (id, genreName) -> Genre(id = id, name = genreName) }
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        if (page == 1) {
            val liveJson = fetchAjax("tmdb_live_search", "query=$encodedQuery")
            if (liveJson.optBoolean("success")) {
                val results = liveJson.optJSONArray("data")
                if (results != null) {
                    val shows = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toShow() }
                    if (shows.isNotEmpty()) return shows
                }
            }
        }

        val movies = fetchExplorePage("movie", page, q = query)
        val tvs = fetchExplorePage("tv", page, q = query)
        return movies + tvs
    }

    private suspend fun fetchExplorePage(
        type: String,
        page: Int,
        genre: String = "",
        sort: String = "popular",
        q: String = "",
    ): List<Show> {
        val encodedQ = URLEncoder.encode(q, "UTF-8")
        val queryParams = "page=$page&type=$type&genre=$genre&network=&language=&sort=$sort&time=week&q=$encodedQ"
        val json = fetchAjax("load_explore_data", queryParams)
        if (!json.optBoolean("success")) return emptyList()
        val html = json.optJSONObject("data")?.optString("html").orEmpty()
        if (html.isBlank()) return emptyList()

        return Jsoup.parse(html).select("a[href*=/pelicula/], a[href*=/serie/]").mapNotNull { a ->
            val href = a.attr("href")
            val id = Regex("""/(?:pelicula|serie)/(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val isTv = href.contains("/serie/") || type == "tv"
            val rawTitle = a.selectFirst("h2")?.text()?.ifEmpty { a.selectFirst("h3")?.text() }
                ?: a.attr("title").replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "").trim()
            val title = rawTitle.ifEmpty { if (isTv) "Serie $id" else "Película $id" }
            val poster = a.selectFirst("img")?.attr("src")
            val rating = a.selectFirst("span.drop-shadow-md, div.rounded-full span")?.text()?.toDoubleOrNull()
            val released = a.selectFirst("p")?.text()?.let {
                Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value ?: it
            }

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
        val shows = fetchExplorePage(type, page, genre = slug)
        return Genre(id = id, name = GENRES.toMap()[id] ?: slug, shows = shows)
    }

    private data class WatchPageMeta(
        val overview: String?,
        val poster: String?,
        val rating: Double?,
        val released: String?,
        val genres: List<Genre>,
        val trailer: String?,
    )

    private fun parseWatchPageFromDom(html: String): WatchPageMeta {
        val overview = Regex("""Descripción</h3>\s*<p[^>]*>([^<]*)</p>""")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        val rating = Regex("""font-bold text-white flex items-center gap-0\.5">([\d.]+)</div>""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        val released = Regex("""\bde (\d{4})</span>""").find(html)?.groupValues?.get(1)

        val genres = Regex("""px-2 py-1 bg-white/5 border border-gray-700 rounded-full text-white text-xs">\s*([^<]+?)\s*</div>""")
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .map { Genre(id = it.lowercase(), name = it) }
            .toList()

        return WatchPageMeta(
            overview = overview,
            poster = extractPoster(html),
            rating = rating,
            released = released,
            genres = genres,
            trailer = extractTrailer(html),
        )
    }

    private fun extractPoster(html: String) =
        Regex("""https://image\.tmdb\.org/t/p/w500/[^"]+""").find(html)?.value

    private fun extractTrailer(html: String): String? {
        val youtubeId = Regex("""id="trailer-thumb"[^>]*src="[^"]*/vi/([^/]+)/""").find(html)?.groupValues?.get(1)
        return youtubeId?.let { "https://www.youtube.com/watch?v=$it" }
    }

    override suspend fun getMovie(id: String): Movie {
        val urls = listOf(
            "$baseUrl/pelicula/$id/",
            "$baseUrl/ver/?tipo=pelicula&id=$id",
        )
        var html = ""
        for (u in urls) {
            html = runCatching { get(u, referer = "$baseUrl/") }.getOrDefault("")
            if (html.isNotBlank()) break
        }
        val meta = parseWatchPageFromDom(html)
        val tmdb = runCatching { id.toIntOrNull()?.let { TMDb3.Movies.details(it, language = language) } }.getOrNull()

        return Movie(
            id = id,
            title = tmdb?.title ?: "",
            overview = tmdb?.overview ?: meta.overview,
            released = meta.released,
            rating = meta.rating,
            poster = meta.poster,
            banner = tmdb?.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            trailer = meta.trailer,
            genres = meta.genres,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val urls = listOf(
            "$baseUrl/serie/$id/",
            "$baseUrl/ver/?tipo=serie&id=$id",
        )
        var html = ""
        for (u in urls) {
            html = runCatching { get(u, referer = "$baseUrl/") }.getOrDefault("")
            if (html.isNotBlank()) break
        }
        val meta = parseWatchPageFromDom(html)
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

        val tmdb = runCatching { id.toIntOrNull()?.let { TMDb3.TvSeries.details(it, language = language) } }.getOrNull()

        return TvShow(
            id = id,
            title = tmdb?.name ?: "",
            overview = tmdb?.overview ?: meta.overview,
            released = meta.released,
            rating = meta.rating,
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
        val urls = listOf(
            "$baseUrl/serie/$tvId/temporada-$seasonNumber/episodio-1/",
            "$baseUrl/serie/$tvId/",
            "$baseUrl/ver/?tipo=serie&id=$tvId&season=$seasonNumber&episode=1",
        )
        var html = ""
        for (u in urls) {
            html = runCatching { get(u, referer = "$baseUrl/") }.getOrDefault("")
            if (html.isNotBlank() && (html.contains("temporada-$seasonNumber") || html.contains("season=$seasonNumber"))) break
        }

        return Jsoup.parse(html).select("a[href*=temporada-$seasonNumber/episodio-], a[href*=season=$seasonNumber&episode=]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val number = Regex("""(?:episodio-|episode=)(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val isSeasonChip = (a.attr("aria-label").contains("Temporada", ignoreCase = true) ||
                    a.text().contains("Temporada", ignoreCase = true)) &&
                    a.selectFirst("span") == null && a.text().trim().length <= 4
                if (isSeasonChip) return@mapNotNull null

                var epTitle = a.text().trim()
                epTitle = epTitle.replace(Regex("""^E\d+\s*""", RegexOption.IGNORE_CASE), "").trim()
                if (epTitle.isEmpty() || Regex("""^(?:T\d+|E\d+|Temporada\s*\d+)$""", RegexOption.IGNORE_CASE).matches(epTitle)) {
                    epTitle = "Episodio $number"
                }

                Episode(
                    id = "$tvId|$seasonNumber|$number",
                    number = number,
                    title = epTitle,
                    poster = a.selectFirst("img")?.attr("src"),
                    tvShow = TvShow(id = tvId, title = ""),
                    season = Season(id = seasonId, number = seasonNumber),
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val movieMasterServers = MovieMasterExtractor().servers(videoType)
        if (movieMasterServers.isNotEmpty()) return movieMasterServers

        // Fallback to Unlimplay embed endpoint if MovieMaster returns empty
        val embedUrl = when (videoType) {
            is Video.Type.Movie -> "https://$UNLIMPLAY_HOST/f/embed/movie/$id"
            is Video.Type.Episode -> {
                val parts = id.split("|", ":")
                val tvId = videoType.tvShow.id.ifBlank { parts.getOrNull(0).orEmpty() }
                val season = videoType.season.number.takeIf { it > 0 }?.toString() ?: parts.getOrNull(1) ?: "1"
                val episode = videoType.number.takeIf { it > 0 }?.toString() ?: parts.getOrNull(2) ?: "1"
                "https://$UNLIMPLAY_HOST/f/embed/tv/$tvId/$season/$episode"
            }
        }
        return runCatching { resolveUnlimplayServers(embedUrl) }.getOrDefault(emptyList())
    }

    private suspend fun resolveUnlimplayServers(embedPageUrl: String): List<Video.Server> {
        val embedHtml = get(embedPageUrl, referer = "$baseUrl/")
        val embedsJson = Regex("""finalizePlayer\s*\(\s*(\{.*?\})\s*\)""")
            .find(embedHtml)?.groupValues?.get(1)
            ?: return emptyList()
        val embeds = JSONObject(embedsJson)

        val languages = embeds.keys().asSequence().toList()
        val orderedLanguages = LANGUAGE_ORDER.filter { it in languages } + (languages - LANGUAGE_ORDER.toSet())

        return orderedLanguages.flatMap { lang ->
            val langServers = embeds.optJSONObject(lang) ?: return@flatMap emptyList()
            val labels = langServers.keys().asSequence().toList()
            val orderedLabels = PRIORITY_SERVERS.filter { it in labels } + (labels - PRIORITY_SERVERS.toSet())

            orderedLabels.mapNotNull { serverLabel ->
                val url = langServers.optString(serverLabel).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val langLabel = LANGUAGE_LABELS[lang] ?: lang.replaceFirstChar { it.uppercase() }
                Video.Server(
                    id = url,
                    name = "${serverLabel.replaceFirstChar { it.uppercase() }} · $langLabel",
                    src = url,
                )
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.contains(REMUX_HOST)) {
            return Video(source = server.src, type = MimeTypes.VIDEO_MP4)
        }
        return Extractor.extract(server.src, server)
    }
}
