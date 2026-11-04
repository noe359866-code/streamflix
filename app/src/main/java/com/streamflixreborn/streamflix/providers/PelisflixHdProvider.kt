package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
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
import com.streamflixreborn.streamflix.scrapers.HttpScraper
import com.streamflixreborn.streamflix.scrapers.ScraperParsing
import com.streamflixreborn.streamflix.utils.NetworkClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.streamflixreborn.streamflix.utils.format
import java.net.URLEncoder

/**
 * PelisflixHD (español latino).
 *
 * WordPress con secciones `section.section-separator` en el home, buscador
 * `/busqueda/{query}?p=N` y jerarquía película/serie → temporada → episodio.
 * Los reproductores se listan en `#player li[data-server]` con el id en Base64.
 *
 * Mejoras frente al original: parseo compartido con [ScraperParsing], servidores
 * deduplicados con nombre legible, y `Referer` correcto al resolver el embed
 * (muchos players fallaban con 403 sin él).
 */
object PelisflixHdProvider : Provider {

    private const val SITE_URL = "https://pelisflixhd.win"

    override val baseUrl: String get() = SITE_URL
    override val name = "PelisflixHD"
    override val logo = "$SITE_URL/favicon.ico"
    override val language = "es"

    override suspend fun getHome(): List<Category> {
        val document = document(SITE_URL) ?: return emptyList()
        val categories = mutableListOf<Category>()

        val featured = document.select("section.section-separator .item, .slider .item")
            .mapNotNull { element -> element.selectFirst("a[href]")?.let { toShow(it) } }
            .distinctBy { it.id }
        if (featured.isNotEmpty()) categories.add(Category(Category.FEATURED, featured))

        document.select("section.section-separator.container").forEach { section ->
            val heading = section.selectFirst(".section-title h2, .section-title, h2")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val shows = section.select(".item").mapNotNull { item ->
                item.selectFirst("a[href*='/pelicula/'], a[href*='/serie/']")?.let { toShow(it) }
            }.distinctBy { it.id }
            if (shows.isNotEmpty()) categories.add(Category(heading, shows))
        }

        val genres = document.select("a[href*='/genero/']")
            .map { anchor ->
                Genre(
                    id = anchor.attr("abs:href").trimEnd('/').substringAfterLast('/'),
                    name = anchor.text().trim(),
                )
            }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id }
        if (genres.isNotEmpty()) categories.add(Category("Géneros", genres))

        return categories.distinctBy { it.name }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = document("$SITE_URL/busqueda/$encoded?p=$page") ?: return emptyList()
        return document.select(".item").mapNotNull { item ->
            item.selectFirst("a[href*='/pelicula/'], a[href*='/serie/']")?.let { toShow(it) }
        }.distinctBy { it.id }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val document = document("$SITE_URL/peliculas?p=$page") ?: return emptyList()
        return document.select("a[href*='/pelicula/']").mapNotNull(::toMovie).distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = document("$SITE_URL/series?p=$page") ?: return emptyList()
        return document.select("a[href*='/serie/']").mapNotNull(::toTvShow).distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie {
        val document = document("$SITE_URL/pelicula/$id/") ?: throw Exception("PelisflixHD: no se pudo abrir $id")
        val base = toMovie(document.selectFirst("a[href*='/pelicula/']") ?: document.body())
            ?: Movie(id = id, title = id.replace('-', ' '))
        val poster = document.selectFirst(".poster img, .thumb img, .wp-post-image")
            ?.let(::image) ?: document.selectFirst("img")?.let(::image)
        val genres = document.select(".showcase-sidebar-navigation a[href*='/genero/']").map { anchor ->
            Genre(
                id = anchor.attr("abs:href").trimEnd('/').substringAfterLast('/'),
                name = anchor.text().trim(),
            )
        }.filter { it.name.isNotBlank() }

        return base.copy(
            id = id,
            title = ScraperParsing.cleanTitle(document.selectFirst("h1")?.text()) ?: base.title,
            overview = document.selectFirst(".wp-content p, .description p, .overview p")?.text()?.trim()
                ?: base.overview,
            released = ScraperParsing.parseYear(document.text()) ?: base.released?.format("yyyy-MM-dd"),
            runtime = ScraperParsing.parseRuntimeMinutes(document.text()) ?: base.runtime,
            poster = poster ?: base.poster,
            banner = backdrop(document) ?: poster ?: base.banner,
            genres = genres.ifEmpty { base.genres },
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val document = document("$SITE_URL/serie/$id/") ?: throw Exception("PelisflixHD: no se pudo abrir $id")
        val poster = document.selectFirst(".poster img, .thumb img, .wp-post-image")
            ?.let(::image) ?: document.selectFirst("img")?.let(::image)
        val base = TvShow(
            id = id,
            title = ScraperParsing.cleanTitle(document.selectFirst("h1")?.text()) ?: id.replace('-', ' '),
            overview = document.selectFirst(".wp-content p, .description p, .overview p")?.text()?.trim(),
            released = ScraperParsing.parseYear(document.text()),
            poster = poster,
            banner = backdrop(document) ?: poster,
            genres = document.select(".showcase-sidebar-navigation a[href*='/genero/']").map { anchor ->
                Genre(
                    id = anchor.attr("abs:href").trimEnd('/').substringAfterLast('/'),
                    name = anchor.text().trim(),
                )
            }.filter { it.name.isNotBlank() },
        )

        val seasonIds = document.select("a[href*='/temporada/']")
            .mapNotNull { anchor ->
                val match = Regex("""/temporada/-?(\d+)/?""").find(anchor.attr("abs:href"))
                    ?: return@mapNotNull null
                match.groupValues[1].toIntOrNull()
            }
            .distinct()
            .sorted()

        val seasons = seasonIds.ifEmpty { listOf(1) }.map { number ->
            Season(id = "$id|$number", number = number, title = "Temporada $number", poster = poster)
        }
        return base.copy(seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split('|', limit = 2)
        val showId = parts.firstOrNull() ?: return emptyList()
        val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: 1

        val document = document("$SITE_URL/serie/$showId/temporada/-$seasonNumber/")
            ?: document("$SITE_URL/serie/$showId/temporada/$seasonNumber/")
            ?: return emptyList()

        return document.select("a[href*='/episodio/']").mapNotNull { anchor ->
            val href = anchor.attr("abs:href")
            val code = Regex("""(\d+)x(\d+)""").find(anchor.text() + href)
            val number = code?.groupValues?.get(2)?.toIntOrNull()
                ?: ScraperParsing.parseEpisodeNumber(anchor.text())
                ?: return@mapNotNull null
            Episode(
                id = "$showId|$seasonNumber|$number",
                number = number,
                title = ScraperParsing.cleanText(anchor.text()) ?: "Episodio $number",
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = document("$SITE_URL/genero/$id?p=$page")
            ?: return Genre(id = id, name = id.replace('-', ' '))
        return Genre(
            id = id,
            name = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
            shows = document.select(".item").mapNotNull { item ->
                item.selectFirst("a[href*='/pelicula/'], a[href*='/serie/']")?.let { toShow(it) }
            }.distinctBy { it.id },
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("PelisflixHD no expone fichas de personas")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val pageUrl = when (videoType) {
            is Video.Type.Movie -> "$SITE_URL/pelicula/$id/"
            is Video.Type.Episode -> {
                val parts = id.split('|')
                val showId = parts.getOrNull(0) ?: return emptyList()
                val season = parts.getOrNull(1) ?: "1"
                val number = parts.getOrNull(2) ?: return emptyList()
                "$SITE_URL/episodio/$showId-${season}x$number/"
            }
        }
        val document = document(pageUrl) ?: return emptyList()

        return document.select("#player li[data-server]").mapIndexedNotNull { index, item ->
            val decoded = decodeBase64(item.attr("data-server")) ?: return@mapIndexedNotNull null
            val url = decoded.substringAfter("|", decoded).ifBlank { decoded }
            if (!url.startsWith("http")) return@mapIndexedNotNull null
            val label = item.selectFirst("span")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "Opción ${index + 1}"
            Video.Server(id = url, name = label, src = url)
        }.distinctBy { it.src }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        Log.d(TAG, "Resolviendo ${server.name}")
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }

    // region helpers

    private const val TAG = "PelisflixHD"

    private suspend fun document(url: String): Document? = HttpScraper.getDocument(
        url = url,
        referer = "$SITE_URL/",
        headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
    )

    private fun toShow(anchor: Element): Show? =
        if (anchor.attr("abs:href").contains("/serie/")) toTvShow(anchor) else toMovie(anchor)

    private fun toMovie(anchor: Element): Movie? {
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!href.contains("/pelicula/")) return null
        val slug = href.substringAfter("/pelicula/").trimEnd('/').substringBefore('?')
        if (slug.isBlank()) return null
        val title = ScraperParsing.cleanTitle(
            anchor.selectFirst("h2, h3, .title")?.text() ?: anchor.attr("title")
        ) ?: slug.replace('-', ' ')
        val image = anchor.selectFirst("img")?.let(::image)
        return Movie(id = slug, title = title, poster = image, banner = image)
    }

    private fun toTvShow(anchor: Element): TvShow? {
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!href.contains("/serie/")) return null
        val slug = href.substringAfter("/serie/").trimEnd('/').substringBefore('?')
        if (slug.isBlank()) return null
        val title = ScraperParsing.cleanTitle(
            anchor.selectFirst("h2, h3, .title")?.text() ?: anchor.attr("title")
        ) ?: slug.replace('-', ' ')
        val image = anchor.selectFirst("img")?.let(::image)
        return TvShow(id = slug, title = title, poster = image, banner = image)
    }

    private fun image(img: Element): String? = ScraperParsing.imageUrl(img, SITE_URL)

    private fun backdrop(document: Document): String? {
        val raw = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".backdrop img, .banner img")?.let(::image)
        return raw?.takeIf { it.startsWith("http") }
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    // endregion
}
