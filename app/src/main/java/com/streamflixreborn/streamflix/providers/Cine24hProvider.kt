package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.scrapers.BypassFetcher
import com.streamflixreborn.streamflix.scrapers.HttpScraper
import com.streamflixreborn.streamflix.scrapers.ScraperParsing
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

object Cine24hProvider : Provider {

    override val name = "Cine24h"
    override val baseUrl = "https://cine24h.online"
    override val language = "es"
    override val logo = "https://i.ibb.co/kgjcsFmj/Image-1.png"

    private val providerMutex = Mutex()
    private const val TAG = "Cine24h"

    /**
     * Descarga con cascada compartida: primero OkHttp con timeout corto y, sólo si
     * hay challenge o error, WebView. El resultado se cachea 5 minutos.
     */
    private val bypassFetcher = BypassFetcher(webViewTimeoutMs = 90_000L)

    fun init(context: Context) {
        bypassFetcher.attach(context)
    }

    private suspend fun getDocument(url: String): Document {
        val html = bypassFetcher.fetch(url, referer = baseUrl)
        return Jsoup.parse(html, baseUrl)
    }

    override suspend fun getHome(): List<Category> = providerMutex.withLock {
        val categories = mutableListOf<Category>()
        coroutineScope {
            try {
                // Caricamento parallelo istantaneo delle sezioni
                val bannerAsync = async { getDocument("$baseUrl/release/2025/") }
                val moviesAsync = async { getDocument("$baseUrl/estrenos/?type=movies") }
                val tvAsync = async { getDocument("$baseUrl/estrenos/?type=series") }

                val bannerDoc = bannerAsync.await()
                val bannerItems = parseShows(bannerDoc)
                val featured = bannerItems.mapNotNull { 
                    if (it is Movie) it.copy(poster = null, banner = it.poster) 
                    else if (it is TvShow) it.copy(poster = null, banner = it.poster) 
                    else null 
                }.take(10)
                if (featured.isNotEmpty()) categories.add(Category(Category.FEATURED, featured))

                val movies = parseShows(moviesAsync.await()).filterIsInstance<Movie>()
                if (movies.isNotEmpty()) categories.add(Category("Estrenos de Películas", movies)) 
                
                val tvShows = parseShows(tvAsync.await()).filterIsInstance<TvShow>()
                if (tvShows.isNotEmpty()) categories.add(Category("Estrenos de Series", tvShows)) 
                
            } catch (e: Exception) {
                Log.e(TAG, "[Provider] Error loading home", e)
            }
        }
        return@withLock categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf("accion", "animacion", "anime", "aventura", "belica", "ciencia-ficcion", "comedia", "crimen", "documental", "drama", "familia", "fantasia", "historia", "misterio", "musica", "romance", "suspense", "terror", "western").map { Genre(id = "category/$it/", name = it.replace("-", " ").replaceFirstChar { c -> c.uppercase() }) }
        return try { parseShows(getDocument("$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}&paged=$page")) } catch (_: Exception) { emptyList() }
    }

    private fun parseShows(doc: Document): List<AppAdapter.Item> {
        val items = doc.select("article.TPost, li.TPostMv article, .TPost, .poster, .grid-item, .item, article[class*='post-']")
        return items.mapNotNull { el ->
            val anchor = el.selectFirst("a") ?: return@mapNotNull null
            val url = anchor.attr("href")
            var titleText = anchor.selectFirst("h2, h3, .Title, .text-md, .name, .poster__title")?.text()?.trim()
            if (titleText.isNullOrEmpty()) titleText = el.selectFirst("h2, h3, .Title, .name")?.text()?.trim()
            val finalTitle = ScraperParsing.cleanTitle(titleText) ?: return@mapNotNull null
            var processedTitle = finalTitle
            anchor.selectFirst(".language-box .lang-item span")?.text()?.trim()?.let { if (it.isNotEmpty()) processedTitle += " [$it]" }
            val img = anchor.selectFirst("img") ?: el.selectFirst("img")
            // helper compartido: resuelve lazy-load (data-src/srcset) y descarta placeholders
            val poster = ScraperParsing.imageUrl(img, baseUrl)
                ?.replace("/w185/", "/w300/")
                ?.replace("/w92/", "/w300/")
                ?: ""
            if (url.contains("/peliculas/") || url.contains("/movies/")) {
                Movie(id = url.substringAfter("/peliculas/").substringAfter("/movies/").removeSuffix("/"), title = processedTitle, poster = poster)
            } else if (url.contains("/series/")) {
                TvShow(id = url.substringAfter("/series/").removeSuffix("/"), title = processedTitle, poster = poster)
            } else null
        }.distinctBy { if (it is Movie) it.id else if (it is TvShow) it.id else "" }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try { parseShows(getDocument("$baseUrl/peliculas/page/$page")).filterIsInstance<Movie>() } catch (_: Exception) { emptyList() }
    override suspend fun getTvShows(page: Int): List<TvShow> = try { parseShows(getDocument("$baseUrl/series/page/$page")).filterIsInstance<TvShow>() } catch (_: Exception) { emptyList() }
    
    override suspend fun getGenre(id: String, page: Int): Genre = try { 
        val shows = parseShows(getDocument("$baseUrl/${id}page/$page")).filterIsInstance<Show>()
        Genre(id = id, name = id.removePrefix("category/").removeSuffix("/").replaceFirstChar { it.uppercase() }, shows = shows) 
    } catch (_: Exception) { Genre(id = id, name = "Error") }

    private fun parseDetails(doc: Document, id: String): Details = Details(
        title = ScraperParsing.textOrNull(doc, ".TPost header .Title", "h1", ".Title") ?: "",
        overview = ScraperParsing.textOrNull(doc, ".TPost .Description", ".Description", ".page__text"),
        poster = ScraperParsing.imageUrl(doc.selectFirst(".TPost .Image img, .pmovie__poster img"), baseUrl)
            ?.replace("/w185/", "/w500/"),
        rating = ScraperParsing.parseRating(doc.selectFirst(".Rank")?.text()),
        year = ScraperParsing.parseYear(doc.selectFirst(".Date")?.text()),
        runtime = ScraperParsing.parseRuntimeMinutes(doc.selectFirst(".Time")?.text()),
        genres = doc.select(".TPost .Description .Genre a, a[href*='/category/']")
            .map { Genre(id = it.attr("href"), name = it.text()) }
            .distinctBy { it.id },
    )

    private data class Details(
        val title: String,
        val overview: String?,
        val poster: String?,
        val rating: Double?,
        val year: String?,
        val runtime: Int?,
        val genres: List<Genre>,
    )

    override suspend fun getMovie(id: String): Movie {
        val details = parseDetails(getDocument("$baseUrl/peliculas/$id"), id)
        return Movie(
            id = id,
            title = details.title,
            overview = details.overview,
            poster = details.poster,
            rating = details.rating,
            released = details.year,
            runtime = details.runtime,
            genres = details.genres,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = getDocument("$baseUrl/series/$id")
        val details = parseDetails(doc, id)
        // El número de temporada puede venir como "Temporada 2", "Season 2" o solo "2".
        val seasons = doc.select(".AABox").mapNotNull { el ->
            val title = ScraperParsing.cleanText(el.selectFirst(".Title")?.text()) ?: return@mapNotNull null
            val number = ScraperParsing.parseSeasonNumber(title) ?: return@mapNotNull null
            Season(id = "$id/$number", number = number, title = title)
        }.distinctBy { it.number }.sortedByDescending { it.number }

        return TvShow(
            id = id,
            title = details.title,
            overview = details.overview,
            poster = details.poster,
            rating = details.rating,
            released = details.year,
            genres = details.genres,
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = try {
        val (showId, sNum) = seasonId.split("/"); val doc = getDocument("$baseUrl/series/$showId")
        doc.select(".AABox")
            .find { (it.selectFirst(".Title")?.text() ?: "").trim().endsWith(sNum) }
            ?.select(".TPTblCn tr, .TPTblCn li")
            ?.mapNotNull { row ->
                val a = row.selectFirst(".MvTbTtl a, a") ?: return@mapNotNull null
                val title = ScraperParsing.cleanText(a.text())
                val number = row.selectFirst(".Num")?.text()?.trim()?.toIntOrNull()
                    ?: ScraperParsing.parseEpisodeNumber(title)
                    ?: return@mapNotNull null
                Episode(
                    id = a.attr("abs:href").ifEmpty { a.attr("href") },
                    number = number,
                    title = title,
                    poster = ScraperParsing.imageUrl(row.selectFirst(".MvTbImg img, img"), baseUrl)
                        ?.replace("/w154/", "/w300/"),
                    released = ScraperParsing.parseYear(row.selectFirst(".MvTbTtl span")?.text()),
                )
            }
            ?.distinctBy { it.number }
            ?.sortedBy { it.number }
            ?: emptyList()
    } catch (_: Exception) { emptyList() }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val fullUrl = if (id.startsWith("http")) id else if (videoType is Video.Type.Movie) "$baseUrl/peliculas/$id" else "$baseUrl/series/$id"
        val doc = getDocument(fullUrl)
        val serverElements = doc.select("ul.optnslst li[data-src], .optnslst li")
        
        coroutineScope {
            serverElements.map { el ->
                async {
                    val info = el.selectFirst("button")?.text()
                        ?.replace(el.selectFirst(".nmopt")?.text() ?: "", "")
                        ?.replace("\u00a0", " ")
                        ?.trim()
                        ?: ""
                    val dataSrc = el.attr("data-src")
                    val decoded = if (dataSrc.isNotEmpty()) {
                        try { String(Base64.decode(dataSrc, Base64.DEFAULT)) } catch(_:Exception) { "" }
                    } else ""
                    if (decoded.isBlank()) return@async null
                    
                    try {
                        val finalUrl = getIframeOptimized(decoded) ?: return@async null
                        Video.Server(
                            id = finalUrl, 
                            name = "${runCatching { java.net.URL(finalUrl).host }.getOrNull()?.replace("www.", "")?.substringBefore(".") ?: "server"} ($info)", 
                            src = finalUrl
                        )
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun getIframeOptimized(url: String, referer: String = baseUrl): String? {
        // Intento rápido: la mayoría de embeds exponen el iframe directamente en el HTML.
        val html = HttpScraper.getText(url, referer = referer, attempts = 1)
        if (!html.isNullOrBlank()) {
            val doc = Jsoup.parse(html, url)
            doc.selectFirst("iframe")?.let { iframe ->
                val src = iframe.attr("abs:src").ifEmpty { iframe.attr("src") }
                if (src.startsWith("http")) return src
            }
            // Algunos embeds generan el player por JS con `file:` o `<source src>`.
            Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""")
                .find(html)?.groupValues?.get(1)?.let { return it }
        }
        // Fallback con bypass (por si hay Cloudflare en el embed).
        return getDocument(url).selectFirst("iframe")?.attr("abs:src")?.ifEmpty { null }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
