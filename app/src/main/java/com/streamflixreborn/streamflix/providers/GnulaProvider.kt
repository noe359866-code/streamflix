package com.streamflixreborn.streamflix.providers

import android.util.Log
import androidx.core.net.toUri
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GnulaProvider : Provider {

    private const val TAG = "GnulaProvider"
    private const val FALLBACK_URL = "https://ww3.gnulahd.nu"

    override val name = "Gnula"
    override val baseUrl = "https://gnula.life"
    override val language = "es"
    override val logo = "https://gnula.life/favicon.ico"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Accept-Language", "es-MX,es-419;q=0.95,es-ES;q=0.9,es;q=0.8")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private suspend fun getDocument(url: String, referer: String = baseUrl): Document? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Referer", referer)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val html = response.body?.string() ?: return@use null
                    Jsoup.parse(html, url)
                }
            }.onFailure {
                Log.w(TAG, "Error fetching $url: ${it.message}")
            }.getOrNull()
        }

    override suspend fun getHome(): List<Category> {
        val doc = getDocument(baseUrl) ?: return emptyList()
        val nextData = extractPageProps(doc) ?: return emptyList()
        val categories = mutableListOf<Category>()

        parseMediaList(nextData.optJSONArray("movies"), isMovie = true)
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Películas Destacadas", it)) }

        parseMediaList(nextData.optJSONArray("series"), isMovie = false)
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Series Destacadas", it)) }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1 || query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // 1. Primary Next.js search on gnula.life
        val primaryResults = searchNextJs("$baseUrl/search?q=$encoded")
        if (primaryResults.isNotEmpty()) return primaryResults

        // 2. Fallback HTML search on ww3.gnulahd.nu
        return searchFallbackWordPress("$FALLBACK_URL/?s=$encoded")
    }

    private suspend fun searchNextJs(url: String): List<AppAdapter.Item> {
        val doc = getDocument(url) ?: return emptyList()
        val pageProps = extractPageProps(doc)
        val items = mutableListOf<AppAdapter.Item>()

        if (pageProps != null) {
            items.addAll(parseMediaList(pageProps.optJSONArray("movies"), isMovie = true))
            items.addAll(parseMediaList(pageProps.optJSONArray("series"), isMovie = false))
            items.addAll(parseMixedSearchResults(pageProps.optJSONArray("results")))
            items.addAll(parseMixedSearchResults(pageProps.optJSONArray("posts")))
        }

        if (items.isEmpty()) {
            doc.select("a[href*=/movies/], a[href*=/series/]").forEach { link ->
                val href = link.attr("href")
                val title = link.selectFirst(".Title, h2, h3")?.text()?.trim()
                    ?: link.attr("title").trim()
                        .ifEmpty { link.text().trim() }
                if (title.isEmpty()) return@forEach

                val rawImg = link.selectFirst("img")?.attr("src").orEmpty()
                val poster = rawImg.toUri().getQueryParameter("url") ?: rawImg.takeIf { it.startsWith("http") }
                val year = Regex("(19|20)\\d{2}").find(link.text())?.value

                if (href.contains("/movies/")) {
                    val slug = href.substringAfter("/movies/").trim('/')
                    if (slug.isNotEmpty()) {
                        items.add(Movie(id = slug, title = title, poster = poster, released = year))
                    }
                } else if (href.contains("/series/")) {
                    val slug = href.substringAfter("/series/").substringBefore("/seasons").trim('/')
                    if (slug.isNotEmpty()) {
                        items.add(TvShow(id = slug, title = title, poster = poster, released = year))
                    }
                }
            }
        }

        return items.distinctBy {
            when (it) {
                is Movie -> "m:${it.id}"
                is TvShow -> "s:${it.id}"
                else -> it.hashCode().toString()
            }
        }
    }

    private suspend fun searchFallbackWordPress(url: String): List<AppAdapter.Item> {
        val doc = getDocument(url, referer = FALLBACK_URL) ?: return emptyList()
        return doc.select("article, .result-item, .post, li.TPostMv").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = el.selectFirst(".title, .Title, h2, h3")?.text()?.trim()
                ?: a.text().trim()
            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            val poster = el.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            val year = Regex("(19|20)\\d{2}").find(el.text())?.value
            if (href.contains("/serie", ignoreCase = true) || href.contains("temporada", ignoreCase = true)) {
                TvShow(id = "wp|$href", title = title, poster = poster, released = year)
            } else {
                Movie(id = "wp|$href", title = title, poster = poster, released = year)
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page <= 1) "$baseUrl/archives/movies" else "$baseUrl/archives/movies/page/$page"
        val doc = getDocument(url) ?: return emptyList()
        val pageProps = extractPageProps(doc)
        if (pageProps != null) {
            val parsed = parseMediaList(pageProps.optJSONArray("movies") ?: pageProps.optJSONArray("results"), isMovie = true)
            if (parsed.isNotEmpty()) return parsed.filterIsInstance<Movie>()
        }
        return emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page <= 1) "$baseUrl/archives/series" else "$baseUrl/archives/series/page/$page"
        val doc = getDocument(url) ?: return emptyList()
        val pageProps = extractPageProps(doc)
        if (pageProps != null) {
            val parsed = parseMediaList(pageProps.optJSONArray("series") ?: pageProps.optJSONArray("results"), isMovie = false)
            if (parsed.isNotEmpty()) return parsed.filterIsInstance<TvShow>()
        }
        return emptyList()
    }

    override suspend fun getMovie(id: String): Movie {
        val slug = id.removePrefix("movies/").trim('/')
        val doc = getDocument("$baseUrl/movies/$slug")
        val movieJson = doc?.let { extractPageProps(it)?.optJSONObject("post") ?: extractPageProps(it)?.optJSONObject("thisMovie") }
        return Movie(
            id = slug,
            title = movieJson?.extractTitle() ?: slug,
            overview = movieJson?.optString("overview"),
            released = movieJson?.optString("releaseDate")?.take(4),
            poster = movieJson?.extractImage("poster"),
            banner = movieJson?.extractImage("backdrop"),
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val slug = id.removePrefix("series/").trim('/')
        val doc = getDocument("$baseUrl/series/$slug")
        val pageProps = doc?.let { extractPageProps(it) }
        val showJson = pageProps?.optJSONObject("post") ?: pageProps?.optJSONObject("thisSerie")
        val seasonsArr = showJson?.optJSONArray("seasons") ?: JSONArray()
        val seasons = mutableListOf<Season>()
        for (i in 0 until seasonsArr.length()) {
            val sObj = seasonsArr.optJSONObject(i) ?: continue
            val sNum = sObj.optInt("number", i + 1)
            seasons.add(
                Season(
                    id = "$slug|$sNum",
                    number = sNum,
                    title = "Temporada $sNum",
                )
            )
        }
        return TvShow(
            id = slug,
            title = showJson?.extractTitle() ?: slug,
            overview = showJson?.optString("overview"),
            released = showJson?.optString("releaseDate")?.take(4),
            poster = showJson?.extractImage("poster"),
            banner = showJson?.extractImage("backdrop"),
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("|")
        val slug = parts.getOrNull(0) ?: return emptyList()
        val seasonNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val doc = getDocument("$baseUrl/series/$slug") ?: return emptyList()
        val showJson = extractPageProps(doc)?.let { it.optJSONObject("post") ?: it.optJSONObject("thisSerie") }
            ?: return emptyList()
        val seasonsArr = showJson.optJSONArray("seasons") ?: return emptyList()
        for (i in 0 until seasonsArr.length()) {
            val sObj = seasonsArr.optJSONObject(i) ?: continue
            if (sObj.optInt("number", -1) != seasonNum) continue
            val epsArr = sObj.optJSONArray("episodes") ?: return emptyList()
            val episodes = mutableListOf<Episode>()
            for (j in 0 until epsArr.length()) {
                val epObj = epsArr.optJSONObject(j) ?: continue
                val epNum = epObj.optInt("number", j + 1)
                val epSlug = epObj.optJSONObject("slug")?.let { slugObj ->
                    val namePart = slugObj.optString("name", slug)
                    "$namePart/seasons/$seasonNum/episodes/$epNum"
                } ?: "$slug/seasons/$seasonNum/episodes/$epNum"
                episodes.add(
                    Episode(
                        id = epSlug,
                        number = epNum,
                        title = epObj.optString("title").ifEmpty { "Episodio $epNum" },
                        poster = epObj.extractImage("image"),
                    )
                )
            }
            return episodes
        }
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id, shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("Not supported")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith("wp|")) {
            return getWordPressFallbackServers(id.removePrefix("wp|"))
        }

        val pageUrl = when (videoType) {
            is Video.Type.Movie -> {
                val slug = id.removePrefix("movies/").trim('/')
                "$baseUrl/movies/$slug"
            }
            is Video.Type.Episode -> {
                if (id.contains("/seasons/") && id.contains("/episodes/")) {
                    "$baseUrl/series/${id.trim('/')}"
                } else {
                    val showSlug = videoType.tvShow.id.removePrefix("series/").trim('/')
                    val s = videoType.season.number
                    val e = videoType.number
                    "$baseUrl/series/$showSlug/seasons/$s/episodes/$e"
                }
            }
        }

        val doc = getDocument(pageUrl) ?: return emptyList()
        val pageProps = extractPageProps(doc)
        val mediaNode = pageProps?.optJSONObject("episode")
            ?: pageProps?.optJSONObject("post")
            ?: pageProps?.optJSONObject("thisMovie")
            ?: pageProps?.optJSONObject("thisEpisode")

        val videosObj = mediaNode?.optJSONObject("videos")
            ?: pageProps?.optJSONObject("videos")

        if (videosObj != null) {
            // Strictly extract ONLY Spanish audio pools: Latino [LAT] and Castellano [CAST]
            val spanishPools = listOf(
                "latino" to "[LAT]",
                "spanish" to "[CAST]",
                "castellano" to "[CAST]",
            )

            return coroutineScope {
                val jobs = spanishPools.flatMap { (key, langTag) ->
                    val arr = videosObj.optJSONArray(key) ?: JSONArray()
                    (0 until arr.length()).mapNotNull { idx ->
                        val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                        val cyberlocker = item.optString("cyberlocker", "Server").trim()
                        val rawUrl = item.optString("result").ifEmpty { item.optString("url") }.trim()
                        if (rawUrl.isEmpty()) return@mapNotNull null

                        async {
                            val resolved = resolveBridgeUrl(rawUrl, pageUrl) ?: rawUrl
                            if (resolved.isBlank()) return@async null
                            Video.Server(
                                id = resolved,
                                name = "$cyberlocker $langTag",
                                src = resolved,
                            )
                        }
                    }
                }
                jobs.awaitAll().filterNotNull().distinctBy { it.src }
            }
        }

        return extractIframeServersFromHtml(doc)
    }

    private suspend fun getWordPressFallbackServers(url: String): List<Video.Server> {
        val doc = getDocument(url, referer = FALLBACK_URL) ?: return emptyList()
        return extractIframeServersFromHtml(doc)
    }

    private fun extractIframeServersFromHtml(doc: Document): List<Video.Server> {
        return doc.select("iframe[src], iframe[data-src]").mapIndexedNotNull { idx, iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }.trim()
            if (src.isEmpty() || !src.startsWith("http")) return@mapIndexedNotNull null
            val hint = (iframe.parent()?.text().orEmpty() + " " + iframe.attr("title")).lowercase()
            if (hint.contains("subtitulad") || hint.contains("vose") || hint.contains("english")) {
                return@mapIndexedNotNull null
            }
            val tag = if (hint.contains("castellano") || hint.contains("españa")) "[CAST]" else "[LAT]"
            val host = runCatching { src.toUri().host?.substringBeforeLast(".")?.substringAfterLast(".") ?: "Gnula" }.getOrDefault("Gnula")
            Video.Server(
                id = src,
                name = "${host.replaceFirstChar { it.uppercase() }} #$idx $tag",
                src = src,
            )
        }
    }

    private suspend fun resolveBridgeUrl(rawUrl: String, referer: String): String? =
        withContext(Dispatchers.IO) {
            val lower = rawUrl.lowercase()
            val needsBridgeUnwrap = lower.contains("/player.php") ||
                lower.contains("player.gnula") ||
                lower.contains("player.cuevana") ||
                lower.contains("/r.php")
            if (!needsBridgeUnwrap) return@withContext rawUrl

            runCatching {
                val req = Request.Builder()
                    .url(rawUrl)
                    .header("Referer", referer)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val finalRedirectUrl = resp.request.url.toString()
                    if (!finalRedirectUrl.equals(rawUrl, ignoreCase = true) &&
                        !finalRedirectUrl.contains("player.")
                    ) {
                        return@use finalRedirectUrl
                    }
                    val html = resp.body?.string().orEmpty()
                    if (html.contains("cf-browser-verification") || html.contains("Just a moment")) {
                        return@use null
                    }
                    val parsed = Jsoup.parse(html)
                    val iframe = parsed.selectFirst("iframe[src]")?.attr("src")
                    if (!iframe.isNullOrBlank() && iframe.startsWith("http")) {
                        return@use iframe
                    }
                    Regex("""(?:var|let|const)\s+url\s*=\s*['"](https?://[^'"]+)['"]""")
                        .find(html)
                        ?.groupValues
                        ?.getOrNull(1)
                }
            }.getOrNull()
        }

    override suspend fun getVideo(server: Video.Server): Video {
        var targetUrl = server.src
        if (targetUrl.contains("voe.sx") || server.name.contains("voe", ignoreCase = true)) {
            val path = targetUrl.toUri().path?.trimStart('/').orEmpty()
            if (path.isNotEmpty() && !path.startsWith("e/")) {
                targetUrl = "https://voe.sx/e/$path"
            }
        }
        return Extractor.extract(targetUrl, server)
    }

    private fun extractPageProps(doc: Document): JSONObject? {
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return runCatching {
            JSONObject(raw).optJSONObject("props")?.optJSONObject("pageProps")
        }.getOrNull()
    }

    private fun parseMediaList(arr: JSONArray?, isMovie: Boolean): List<AppAdapter.Item> {
        if (arr == null) return emptyList()
        val result = mutableListOf<AppAdapter.Item>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val slug = obj.extractSlug() ?: continue
            val title = obj.extractTitle() ?: continue
            val poster = obj.extractImage("poster")
            val banner = obj.extractImage("backdrop")
            val year = obj.optString("releaseDate").take(4).ifEmpty { null }
            val rating = obj.optJSONObject("rate")?.optDouble("average")?.takeIf { !it.isNaN() }
            if (isMovie) {
                result.add(
                    Movie(
                        id = slug,
                        title = title,
                        poster = poster,
                        banner = banner,
                        released = year,
                        rating = rating,
                    )
                )
            } else {
                result.add(
                    TvShow(
                        id = slug,
                        title = title,
                        poster = poster,
                        banner = banner,
                        released = year,
                        rating = rating,
                    )
                )
            }
        }
        return result
    }

    private fun parseMixedSearchResults(arr: JSONArray?): List<AppAdapter.Item> {
        if (arr == null) return emptyList()
        val list = mutableListOf<AppAdapter.Item>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val typeStr = obj.optString("type").lowercase()
            val urlSlug = obj.optJSONObject("url")?.optString("slug").orEmpty()
            val isSeries = typeStr == "series" || typeStr == "tv" || urlSlug.startsWith("series/")
            val slug = obj.extractSlug() ?: continue
            val title = obj.extractTitle() ?: continue
            val poster = obj.extractImage("poster")
            val year = obj.optString("releaseDate").take(4).ifEmpty { null }
            if (isSeries) {
                list.add(TvShow(id = slug, title = title, poster = poster, released = year))
            } else {
                list.add(Movie(id = slug, title = title, poster = poster, released = year))
            }
        }
        return list
    }

    private fun JSONObject.extractSlug(): String? {
        optJSONObject("slug")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { return it }
        optString("slug").takeIf { it.isNotEmpty() && !it.startsWith("{") }?.let { return it }
        val urlSlug = optJSONObject("url")?.optString("slug").orEmpty()
        if (urlSlug.isNotEmpty()) {
            return urlSlug.removePrefix("movies/").removePrefix("series/").trim('/')
        }
        return null
    }

    private fun JSONObject.extractTitle(): String? {
        optJSONObject("titles")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { return it }
        optString("title").takeIf { it.isNotEmpty() }?.let { return it }
        optString("name").takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun JSONObject.extractImage(field: String): String? {
        val images = optJSONObject("images")
        val value = images?.optString(field).orEmpty().ifEmpty { optString(field) }
        return value.takeIf { it.startsWith("http") }
    }
}
