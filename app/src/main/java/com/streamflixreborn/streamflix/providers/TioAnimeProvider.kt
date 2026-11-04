package com.streamflixreborn.streamflix.providers

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
import com.streamflixreborn.streamflix.scrapers.StreamLinks
import com.streamflixreborn.streamflix.utils.NetworkClient
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * TioAnime (español latino).
 *
 * Estructura: directorio paginado (`/directorio?p=N`), ficha con sinopsis y
 * lista de episodios (`/anime/{slug}`), y reproducción por episodio
 * (`/ver/{slug}-{n}`), donde el player incrusta uno o varios iframes.
 */
object TioAnimeProvider : Provider {

    private const val SITE_URL = "https://tioanime.com"

    override val baseUrl: String get() = SITE_URL
    override val name = "TioAnime"
    override val logo = "$SITE_URL/favicon.ico"
    override val language = "es"

    override suspend fun getHome(): List<Category> {
        val document = document("$SITE_URL/") ?: return emptyList()
        val categories = mutableListOf<Category>()

        val featured = document.select(".slider .owl-item a, #slider a[href*='/anime/']")
            .mapNotNull { anchor -> toShow(anchor) }
            .distinctBy { it.id }
        if (featured.isNotEmpty()) categories.add(Category(Category.FEATURED, featured))

        document.select("section, .seccion, div.container").forEach { section ->
            val heading = section.selectFirst("h2, .titulo, .section-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val shows = section.select("a[href*='/anime/']")
                .mapNotNull { toShow(it) }
                .distinctBy { it.id }
            if (shows.isNotEmpty() && shows.size >= 3) {
                categories.add(Category(heading, shows))
            }
        }

        if (categories.isEmpty()) {
            val shows = parseDirectory(document)
            if (shows.isNotEmpty()) categories.add(Category("Animes", shows))
        }
        return categories.distinctBy { it.name }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val document = document("$SITE_URL/directorio?q=${URLEncoder.encode(query, "UTF-8")}&p=$page")
            ?: return emptyList()
        return parseDirectory(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = document("$SITE_URL/directorio?p=$page") ?: return emptyList()
        return parseDirectory(document).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("TioAnime solo tiene series")

    override suspend fun getTvShow(id: String): TvShow {
        val document = document("$SITE_URL/anime/$id") ?: throw Exception("TioAnime: no se pudo abrir $id")
        val title = document.selectFirst("h1, .anime-title, .title")?.text()?.trim()
            ?: id.replace('-', ' ')
        val poster = document.selectFirst(".anime-poster img, .thumb img, .portada img")
            ?.let { poster(it) }
        val synopsis = document.selectFirst(".anime-synopsis, .sinopsis, #sinopsis")?.text()?.trim()
        val genres = document.select("a[href*='/genero/']").map { anchor ->
            Genre(
                id = anchor.attr("href").trimEnd('/').substringAfterLast('/'),
                name = anchor.text().trim(),
            )
        }.filter { it.name.isNotBlank() }
        val episodeCount = document.select("a[href*='/ver/']").size
        val seasons = if (episodeCount > 0) {
            listOf(Season(id = id, number = 1, title = "Episodios", poster = poster))
        } else emptyList()

        return TvShow(
            id = id,
            title = title,
            overview = synopsis,
            poster = poster,
            banner = poster,
            genres = genres,
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val document = document("$SITE_URL/anime/$seasonId") ?: return emptyList()
        val series = TvShow(id = seasonId, title = seasonId)
        return document.select("a[href*='/ver/']").mapNotNull { anchor ->
            val href = anchor.attr("abs:href")
            val number = Regex("""-(\d+)/?$""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            Episode(
                id = "${show.id}|$number",
                number = number,
                title = "Episodio $number",
                tvShow = series,
                season = Season(id = seasonId, number = 1, title = "Episodios"),
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = document("$SITE_URL/genero/$id?p=$page")
            ?: return Genre(id = id, name = id.replace('-', ' '))
        return Genre(
            id = id,
            name = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
            shows = parseDirectory(document),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("TioAnime no expone fichas de personas")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        Provider.playableServers(getServersRaw(id, videoType))

    /**
     * Servidores tal cual los entrega la web. `getServers` filtra después para que
     * sólo salgan reproductores reales (ver [Provider.playableServers]).
     */
    private suspend fun getServersRaw(id: String, videoType: Video.Type): List<Video.Server> {
        val slug = id.substringBefore('|')
        val number = id.substringAfter('|', "").toIntOrNull() ?: return emptyList()
        val document = document("$SITE_URL/ver/$slug-$number") ?: return emptyList()

        val embeds = document.select("iframe[src], iframe[data-src], video source[src]")
            .mapNotNull { element ->
                val raw = element.attr("abs:src").ifBlank {
                    element.attr("data-src").ifBlank { element.attr("src") }
                }
                val url = when {
                    raw.startsWith("http") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.isBlank() -> null
                    else -> null
                }
                url
            }
            .distinct()

        val servers = embeds.mapIndexed { index, url ->
            val host = StreamLinks.displayName(url)
            Video.Server(
                id = url,
                name = if (host.isBlank()) "Servidor ${index + 1}" else "$host · Opción ${index + 1}",
                src = url,
            )
        }
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    // region helpers

    private suspend fun document(url: String): Document? = HttpScraper.getDocument(
        url = url,
        referer = "$SITE_URL/",
        headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
    )

    private fun parseDirectory(document: Document): List<Show> =
        document.select("article, .anime, .thumb, li").mapNotNull { element ->
            val anchor = element.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            toShow(anchor)
        }.distinctBy { it.id }

    private fun toShow(anchor: Element): TvShow? {
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!href.contains("/anime/")) return null
        val slug = href.substringAfter("/anime/").trimEnd('/').substringBefore('?')
        if (slug.isBlank()) return null
        val title = anchor.selectFirst("h3, .title, .anime-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: anchor.attr("title").ifBlank { slug.replace('-', ' ') }
        val image = anchor.selectFirst("img")?.let { poster(it) }
        return TvShow(id = slug, title = title, poster = image, banner = image)
    }

    private fun poster(img: Element): String? {
        val raw = img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
            .ifBlank { img.attr("data-src").ifBlank { img.attr("src") } }
        if (raw.isBlank() || raw.startsWith("data:")) return null
        return if (raw.startsWith("http")) raw else "$SITE_URL/${raw.trimStart('/')}"
    }

    // endregion
}
