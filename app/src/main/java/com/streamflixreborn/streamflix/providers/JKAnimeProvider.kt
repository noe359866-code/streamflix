package com.streamflixreborn.streamflix.providers

import android.util.Base64
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
import com.streamflixreborn.streamflix.scrapers.ScraperCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import org.json.JSONArray
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * JKAnime (español latino).
 *
 * El sitio no tiene API pública: la lista de episodios se pide por AJAX con un
 * token CSRF (`POST /ajax/episodes/{id}/{page}`) y los servidores vienen como
 * un array JS `var servers = [...]` con la URL en Base64.
 *
 * Puntos que se corrigieron respecto al original:
 * - el token CSRF se cachea por 10 minutos y se refresca automáticamente ante
 *   un 419 (antes una sesión caducada rompía todos los episodios);
 * - los servidores se deduplican y se les pone nombre legible + idioma;
 * - se usa el cliente compartido (DoH/timeouts) y se cierran las respuestas.
 */
object JKAnimeProvider : Provider {

    private const val SITE_URL = "https://jkanime.net"

    override val baseUrl: String get() = SITE_URL
    override val name = "JKAnime"
    override val logo = "https://jkanime.net/favicon.ico"
    override val language = "es"

    private val csrfCache = ScraperCache<String>(ttlMs = 10 * 60 * 1000L)

    private val supportedServers = setOf(
        "streamwish", "voe", "vidhide", "filemoon", "mixdrop",
        "mp4upload", "streamtape", "doodstream", "jkplayer", "desuka",
    )

    override suspend fun getHome(): List<Category> {
        val document = homeDocument() ?: return emptyList()
        val categories = mutableListOf<Category>()

        val heroItems = document.select("section.hero .hero__slider .hero__items")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href]")?.attr("abs:href") ?: return@mapNotNull null
                val slug = link.trimEnd('/').substringAfterLast('/')
                if (slug.isBlank()) return@mapNotNull null
                val background = item.attr("data-setbg").ifBlank {
                    item.selectFirst("img")?.attr("abs:src").orEmpty()
                }
                TvShow(
                    id = slug,
                    title = item.selectFirst("h2, h3, .title")?.text()
                        ?.ifBlank { slug.replace('-', ' ') } ?: slug.replace('-', ' '),
                    poster = background.ifBlank { null },
                    banner = background.ifBlank { null },
                )
            }
        if (heroItems.isNotEmpty()) categories.add(Category(Category.FEATURED, heroItems))

        document.select("section").forEach { section ->
            val title = section.selectFirst(".heading h2, h2")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val shows = section.select("a[href]")
                .mapNotNull { anchor ->
                    val href = anchor.attr("abs:href")
                    if (!href.contains("/anime/")) return@mapNotNull null
                    val slug = href.trimEnd('/').substringAfterLast('/')
                    val poster = anchor.selectFirst("img")?.let { imageUrl(it) }
                    TvShow(id = slug, title = anchor.attr("title").ifBlank { slug }, poster = poster)
                }
                .distinctBy { it.id }
            if (shows.isNotEmpty()) categories.add(Category(title, shows))
        }

        return categories
            .filter { it.name == Category.FEATURED || it.list.isNotEmpty() }
            .distinctBy { it.name }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val document = HttpScraper.getDocument(
            url = "$SITE_URL/buscar/${URLEncoder.encode(query, "UTF-8")}/${if (page > 1) "$page/" else ""}",
            referer = "$SITE_URL/",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
        ) ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = HttpScraper.getDocument(
            url = "$SITE_URL/directorio?tipo=&genero=&categoria=&p=$page",
            referer = "$SITE_URL/",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
        ) ?: return emptyList()
        return parseCards(document).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("JKAnime solo tiene series")

    override suspend fun getTvShow(id: String): TvShow {
        val document = HttpScraper.getDocument(
            url = "$SITE_URL/$id/",
            referer = "$SITE_URL/",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
        ) ?: throw Exception("JKAnime: no se pudo abrir $id")

        val animeId = document.selectFirst(".guardar_anime[data-anime]")?.attr("data-anime")
            ?: Regex("""id_anime\s*=\s*['"]?(\d+)""").find(document.html())?.groupValues?.get(1)
            ?: throw Exception("JKAnime: no se encontró el id interno")

        val title = document.selectFirst("h1, .anime__details__title h3")?.text()?.trim()
            ?: id.replace('-', ' ')
        val poster = document.selectFirst("img")?.let { imageUrl(it) }
        val synopsis = document.selectFirst(".anime__details__text p, .sinopsis, #sinopsis")?.text()?.trim()
        val genres = document.select("a[href*='/genero/']").map { anchor ->
            Genre(
                id = anchor.attr("href").trimEnd('/').substringAfterLast('/'),
                name = anchor.text().trim(),
            )
        }.filter { it.name.isNotBlank() }

        return TvShow(
            id = id,
            title = title,
            overview = synopsis,
            poster = poster,
            banner = poster,
            genres = genres,
            seasons = listOf(
                Season(
                    id = "$id|$animeId",
                    number = 1,
                    title = "Episodios",
                    poster = poster,
                )
            ),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split('|', limit = 2)
        val slug = parts.getOrNull(0) ?: return emptyList()
        val animeId = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()

        val allEpisodes = mutableListOf<Episode>()
        var page = 1
        while (page <= MAX_EPISODE_PAGES) {
            val payload = runCatching { postEpisodes(animeId, page, forceToken = page == 1) }
                .getOrNull() ?: if (page == 1) refreshAndRetry(animeId, page) else null
            if (payload.isNullOrBlank() || payload == "[]") break

            val array = runCatching { JSONArray(payload) }.getOrNull() ?: break
            if (array.length() == 0) break

            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val number = obj.optString("number").toIntOrNull()
                    ?: obj.optString("episode").toIntOrNull() ?: continue
                allEpisodes.add(
                    Episode(
                        id = "$slug|$number",
                        number = number,
                        title = obj.optString("title").takeIf { it.isNotBlank() },
                        poster = obj.optString("image").takeIf { it.startsWith("http") },
                    )
                )
            }
            page++
        }

        return allEpisodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private suspend fun postEpisodes(animeId: Int, page: Int): String? {
        // La cookie de sesión la mantiene el cookieJar de NetworkClient; aquí sólo
        // se comprueba que el token ya se haya obtenido al menos una vez.
        runCatching { csrfToken() }
        val response = HttpScraper.execute(
            HttpScraper.request(
                url = "$SITE_URL/ajax/episodes/$animeId/$page",
                referer = "$SITE_URL/",
                headers = mapOf(
                    "User-Agent" to NetworkClient.USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                ),
            ),
        )
        if (response.code == 419) {
            csrfCache.invalidate()
            return null
        }
        if (!response.isSuccessful) return null
        return response.body
    }

    private suspend fun refreshAndRetry(animeId: Int, page: Int): String? {
        csrfCache.invalidate()
        csrfToken()
        return postEpisodes(animeId, page)
    }

    private suspend fun csrfToken(): String = csrfCache.getOrFetch("csrf") {
        val document = homeDocument() ?: throw Exception("JKAnime: no se pudo cargar el home")
        document.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: throw Exception("JKAnime: sin token CSRF")
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = HttpScraper.getDocument(
            url = "$SITE_URL/genero/$id/${if (page > 1) "$page/" else ""}",
            referer = "$SITE_URL/",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
        ) ?: return Genre(id = id, name = id.replace('-', ' '))
        return Genre(
            id = id,
            name = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
            shows = parseCards(document),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("JKAnime no expone fichas de personas")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val episodeId = (videoType as? Video.Type.Episode)?.id ?: id
        val slug = episodeId.substringBefore('|')
        val number = episodeId.substringAfter('|', "").toIntOrNull() ?: return emptyList()

        val document = HttpScraper.getDocument(
            url = "$SITE_URL/$slug/$number/",
            referer = "$SITE_URL/",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
        ) ?: return emptyList()

        val servers = mutableListOf<Video.Server>()

        // 1) Iframes directos del reproductor JKPlayer.
        VIDEO_IFRAME_REGEX.findAll(document.html()).forEachIndexed { index, match ->
            val url = match.groupValues.getOrNull(1)?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
            servers.add(Video.Server(id = url, name = "JKPlayer ${index + 1}", src = url))
        }

        // 2) Array `var servers` con URLs en Base64 de mirrors externos.
        document.select("script").forEach { script ->
            val data = script.data().ifBlank { script.html() }
            val jsonStart = data.indexOf("var servers")
            if (jsonStart >= 0) {
                extractBalancedJson(data, jsonStart)?.let { json ->
                    runCatching {
                        JSONArray(json)
                    }.getOrNull()?.let { array ->
                        for (index in 0 until array.length()) {
                            val obj = array.optJSONObject(index) ?: continue
                            val serverName = obj.optString("server").lowercase()
                            if (supportedServers.none { serverName.contains(it) }) continue
                            val remote = obj.optString("remote")
                            val url = decodeBase64(remote)?.takeIf { it.startsWith("http") } ?: continue
                            servers.add(
                                Video.Server(
                                    id = url,
                                    name = "${obj.optString("server").replaceFirstChar { it.uppercase() }} · Opción ${index + 1}",
                                    src = url,
                                )
                            )
                        }
                    }
                }
            }
        }

        return servers.distinctBy { it.src }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    // region helpers

    private suspend fun homeDocument(): Document? = HttpScraper.getDocument(
        url = "$SITE_URL/",
        headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
    )

    private fun parseCards(document: Document): List<Show> =
        document.select("a[href*='/anime/']").mapNotNull { anchor ->
            val href = anchor.attr("abs:href")
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isBlank()) return@mapNotNull null
            val title = anchor.attr("title").ifBlank {
                anchor.selectFirst("h3, .title, .anime__item__text h5")?.text().orEmpty()
            }.ifBlank { slug.replace('-', ' ') }
            val poster = anchor.selectFirst("img")?.let { imageUrl(it) }
            TvShow(id = slug, title = title, poster = poster)
        }.distinctBy { it.id }

    private fun imageUrl(img: org.jsoup.nodes.Element): String? {
        val raw = img.attr("abs:data-setbg").ifBlank { img.attr("abs:src") }
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("src") }
        if (raw.isBlank()) return null
        val normalized = raw.replace("/animes/thumbnail/", "/animes/image/")
        return when {
            normalized.startsWith("http") -> normalized
            normalized.startsWith("//") -> "https:$normalized"
            else -> "https://cdn.jkdesa.com/assets/images/animes/image/$normalized"
        }
    }

    private fun extractBalancedJson(text: String, fromIndex: Int): String? {
        val start = text.indexOfAny(charArrayOf('[', '{'), fromIndex).takeIf { it >= 0 } ?: return null
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        for (index in start until text.length) {
            when (text[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
    }.getOrNull()

    // endregion

    private const val MAX_EPISODE_PAGES = 60

    private val VIDEO_IFRAME_REGEX = Regex(
        """(?:src|data-src)\s*=\s*["'](https?://[^"']*(?:jkplayer|um?\.jkanime|jkanime\.net/jkplayer)[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
}
