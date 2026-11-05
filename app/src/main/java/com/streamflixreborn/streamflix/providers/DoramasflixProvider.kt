package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.google.gson.Gson
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
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import com.streamflixreborn.streamflix.models.doramasflix.LinkOnline
import com.streamflixreborn.streamflix.models.doramasflix.TokenModel
import com.streamflixreborn.streamflix.models.doramasflix.VideoToken
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    private const val apiUrl = "https://sv1.fluxcedene.net/api/"
    override val language = "es"

    private val client = getOkHttpClient()

    private val service = Retrofit.Builder()
        .baseUrl(apiUrl)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val serviceHtml = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private fun getOkHttpClient(): OkHttpClient {
        // No disk cache: Android TV working dir "/" is read-only, OkHttp cache must remain in-memory only
        // to avoid FileNotFound / IOException on leanback devices.
        return OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
    }

    private const val accessPlatform = "RxARncfg1S_MdpSrCvreoLu_SikCGMzE1NzQzODc3NjE2MQ=="

    private val languages = arrayOf(
        Pair("36", "[ENG]"),
        Pair("37", "[CAST]"),
        Pair("38", "[LAT]"),
        Pair("192", "[SUB]"),
        Pair("1327", "[POR]"),
        Pair("13109", "[COR]"),
        Pair("13110", "[JAP]"),
        Pair("13111", "[MAN]"),
        Pair("13112", "[TAI]"),
        Pair("13113", "[FIL]"),
        Pair("13114", "[IND]"),
        Pair("343422", "[VIET]"),
    )

    private fun String.getLang(): String {
        return languages.firstOrNull { it.first == this }?.second ?: ""
    }

    private interface DoramasflixService {
        @POST("gql")
        @Headers(
            "accept: application/json, text/plain, */*",
            "platform: doramasflix",
            "authorization: Bear",
            "x-access-jwt-token: ",
            "x-access-platform: $accessPlatform"
        )
        suspend fun getApiResponse(@Body body: okhttp3.RequestBody): ApiResponse

        @GET
        suspend fun getPage(@Url url: String): Document

        @POST
        @Headers("Content-Type: application/json")
        suspend fun postApi(@Url url: String, @Body body: okhttp3.RequestBody): VideoToken
    }

    private fun getPosterUrl(path: String?): String {
        return if (path?.startsWith("http") == true) {
            path
        } else {
            "https://image.tmdb.org/t/p/w500$path"
        }
    }

    override suspend fun getHome(): List<Category> {
        return try {
            coroutineScope {
                val homeDeferred = async { serviceHtml.getPage(baseUrl) }
                val popularDoramasDeferred = async { getTvShows(1) }
                val popularMoviesDeferred = async { getMovies(1) }

                val homeDocument = homeDeferred.await()
                val bannerShows = homeDocument.select("article.styles__Article-nxyw6x-3").mapNotNull { element ->
                    val href = element.selectFirst("div.styles__Buttons-sc-78uayx-17 a")?.attr("href") ?: return@mapNotNull null
                    val bannerUrl = element.selectFirst("noscript img")?.attr("src")
                    val title = element.selectFirst("h2.styles__Title-sc-78uayx-1")?.text() ?: return@mapNotNull null

                    val id = href.removePrefix("/")

                    if (href.contains("/peliculas-online/")) {
                        Movie(
                            id = id,
                            title = title,
                            banner = getPosterUrl(bannerUrl)
                        )
                    } else {
                        TvShow(
                            id = id,
                            title = title,
                            banner = getPosterUrl(bannerUrl)
                        )
                    }
                }

                mutableListOf(
                    Category(name = Category.FEATURED, list = bannerShows),
                    Category(name = "Doramas Populares", list = popularDoramasDeferred.await()),
                    Category(name = "Películas Populares", list = popularMoviesDeferred.await())
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("doramas", "Doramas"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades")
            )
        }

        val searchQuery = """
            {"operationName":"searchAll","variables":{"input":"$query"},"query":"query searchAll(${'$'}input: String!) {\n  searchDorama(input: ${'$'}input, limit: 32) {\n    _id\n    slug\n    name\n    name_es\n    poster_path\n    poster\n    __typename\n  }\n  searchMovie(input: ${'$'}input, limit: 32) {\n    _id\n    name\n    name_es\n    slug\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
        """.trimIndent()
        val body = searchQuery.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            val results = mutableListOf<AppAdapter.Item>()

            response.data?.searchDorama?.forEach { show ->
                results.add(
                    TvShow(
                        id = "doramas-online/${show.slug}",
                        title = "${show.name} (${show.nameEs ?: ""})".trim(),
                        poster = getPosterUrl(show.posterPath ?: show.poster)
                    )
                )
            }

            response.data?.searchMovie?.forEach { show ->
                results.add(
                    Movie(
                        id = "peliculas-online/${show.slug}",
                        title = "${show.name} (${show.nameEs ?: ""})".trim(),
                        poster = getPosterUrl(show.posterPath ?: show.poster)
                    )
                )
            }

            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val query = """
            {"operationName":"listMovies","variables":{"perPage":20,"sort":"POPULARITY_DESC","filter":{},"page":$page},"query":"query listMovies(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyMovieInput, ${'$'}filter: FilterFindManyMovieInput) {\n  paginationMovie(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """.trimIndent()
        val body = query.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            response.data?.paginationMovie?.items?.map {
                Movie(
                    id = "peliculas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster)
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val query = """
            {"operationName":"listDoramas","variables":{"page":$page,"sort":"POPULARITY_DESC","perPage":20,"filter":{"isTVShow":false}},"query":"query listDoramas(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyDoramaInput, ${'$'}filter: FilterFindManyDoramaInput) {\n  paginationDorama(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """.trimIndent()
        val body = query.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            response.data?.paginationDorama?.items?.map {
                TvShow(
                    id = "doramas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster)
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cleanSlugOrId(id: String): String {
        return id.removePrefix(baseUrl)
            .removePrefix("https://doramasflix.in")
            .removePrefix("/")
            .removePrefix("peliculas-online/")
            .removePrefix("doramas-online/")
            .removePrefix("dorama/")
            .removePrefix("pelicula/")
            .removePrefix("series/")
            .trim('/')
    }

    override suspend fun getMovie(id: String): Movie {
        return try {
            val cleanId = cleanSlugOrId(id)
            val filterField = if (cleanId.matches(Regex("^[0-9a-fA-F]{24}$"))) "_id" else "slug"
            val movieQuery = """
                {"operationName":"detailMovie","variables":{"filter":{"$filterField":"$cleanId"}},"query":"query detailMovie(${'$'}filter: FilterFindOneMovieInput) {\n  detailMovie(filter: ${'$'}filter) {\n    _id\n    name\n    name_es\n    slug\n    overview\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
            """.trimIndent()
            val movieBody = movieQuery.toRequestBody("application/json".toMediaType())
            val response = service.getApiResponse(movieBody)
            val movieData = response.data?.detailMovie
                ?: throw Exception("No se encontraron datos de la película en la API.")

            Movie(
                id = movieData.id,
                title = "${movieData.name} (${movieData.nameEs ?: ""})".trim(),
                overview = movieData.overview,
                poster = getPosterUrl(movieData.posterPath ?: movieData.poster),
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles de la película: ${e.message}")
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        return try {
            val cleanId = cleanSlugOrId(id)
            val filterField = if (cleanId.matches(Regex("^[0-9a-fA-F]{24}$"))) "_id" else "slug"
            val doramaQuery = """
                {"operationName":"detailDorama","variables":{"filter":{"$filterField":"$cleanId"}},"query":"query detailDorama(${'$'}filter: FilterFindOneDoramaInput) {\n  detailDorama(filter: ${'$'}filter) {\n    _id\n    name\n    name_es\n    slug\n    overview\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
            """.trimIndent()
            val doramaBody = doramaQuery.toRequestBody("application/json".toMediaType())
            val response = service.getApiResponse(doramaBody)
            val doramaData = response.data?.detailDorama
                ?: throw Exception("No se encontraron datos del dorama en la API.")

            val doramaId = doramaData.id

            val seasonQuery = """
                {"operationName":"listSeasons","variables":{"serie_id":"$doramaId"},"query":"query listSeasons(${'$'}serie_id: MongoID!) {\n  listSeasons(sort: NUMBER_ASC, filter: {serie_id: ${'$'}serie_id}) {\n    slug\n    season_number\n    poster_path\n    __typename\n  }\n}\n"}
            """.trimIndent()
            val seasonBody = seasonQuery.toRequestBody("application/json".toMediaType())
            val seasonResponse = service.getApiResponse(seasonBody)

            val seasons = seasonResponse.data?.listSeasons?.map {
                Season(
                    id = "$doramaId/${it.seasonNumber}",
                    number = it.seasonNumber,
                    title = "Temporada ${it.seasonNumber}",
                    poster = getPosterUrl(it.posterPath)
                )
            } ?: emptyList()

            TvShow(
                id = doramaId,
                title = "${doramaData.name} (${doramaData.nameEs ?: ""})".trim(),
                overview = doramaData.overview,
                poster = getPosterUrl(doramaData.posterPath ?: doramaData.poster),
                seasons = seasons
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles del dorama: ${e.message}")
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val doramaId = seasonId.substringBefore("/")
        val seasonNumber = seasonId.substringAfter("/").toInt()

        val episodeQuery = """
            {"operationName":"listEpisodes","variables":{"serie_id":"$doramaId","season_number":$seasonNumber},"query":"query listEpisodes(${'$'}season_number: Float!, ${'$'}serie_id: MongoID!) {\n  listEpisodes(sort: NUMBER_ASC, filter: {type_serie: \"dorama\", serie_id: ${'$'}serie_id, season_number: ${'$'}season_number}) {\n    _id\n    name\n    slug\n    episode_number\n    season_number\n    still_path\n    __typename\n  }\n}\n"}
        """.trimIndent()
        val body = episodeQuery.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            response.data?.listEpisodes?.map {
                Episode(
                    id = it.slug ?: "",
                    number = it.episodeNumber ?: 0,
                    title = "Episodio ${it.episodeNumber ?: 0}: ${it.name ?: ""}".trim(),
                    poster = getPosterUrl(it.stillPath)
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val cleanId = cleanSlugOrId(id)
            val allLinks = mutableListOf<LinkOnline>()
            val seenUrls = mutableSetOf<String>()

            fun addLinks(links: List<LinkOnline>?) {
                links?.forEach { item ->
                    val url = item.embed?.takeIf { it.isNotBlank() } ?: item.link
                    if (!url.isNullOrBlank() && !url.endsWith("/e/") && seenUrls.add(url)) {
                        allLinks.add(item)
                    }
                }
            }

            when (videoType) {
                is Video.Type.Movie -> {
                    val filterField = if (cleanId.matches(Regex("^[0-9a-fA-F]{24}$"))) "_id" else "slug"
                    val detailMovieQuery = """
                        {"operationName":"detailMovie","variables":{"filter":{"$filterField":"$cleanId"}},"query":"query detailMovie(${'$'}filter: FilterFindOneMovieInput) {\n  detailMovie(filter: ${'$'}filter) {\n    _id\n    links_online\n  }\n}\n"}
                    """.trimIndent()
                    val detRes = service.getApiResponse(detailMovieQuery.toRequestBody("application/json".toMediaType()))
                    addLinks(detRes.data?.detailMovie?.linksOnline)

                    val getMovieLinksQuery = """
                        {"operationName":"getMovieLinks","variables":{"slug":"$cleanId"},"query":"query getMovieLinks(${'$'}slug: String) {\n  getMovieLinks(slug: ${'$'}slug) {\n    _id\n    links_online\n  }\n}\n"}
                    """.trimIndent()
                    val gmlRes = service.getApiResponse(getMovieLinksQuery.toRequestBody("application/json".toMediaType()))
                    addLinks(gmlRes.data?.getMovieLinks?.linksOnline)
                }
                is Video.Type.Episode -> {
                    var epId = cleanId
                    val filterField = if (cleanId.matches(Regex("^[0-9a-fA-F]{24}$"))) "_id" else "slug"
                    val detailEpisodeQuery = """
                        {"operationName":"detailEpisode","variables":{"filter":{"$filterField":"$cleanId"}},"query":"query detailEpisode(${'$'}filter: FilterFindOneEpisodeInput) {\n  detailEpisode(filter: ${'$'}filter) {\n    _id\n    links_online\n  }\n}\n"}
                    """.trimIndent()
                    val detRes = service.getApiResponse(detailEpisodeQuery.toRequestBody("application/json".toMediaType()))
                    val epObj = detRes.data?.detailEpisode
                    if (!epObj?.id.isNullOrBlank()) {
                        epId = epObj!!.id!!
                    }
                    addLinks(epObj?.linksOnline)

                    if (epId.matches(Regex("^[0-9a-fA-F]{24}$"))) {
                        val getEpisodeLinksQuery = """
                            {"operationName":"getEpisodeLinks","variables":{"id":"$epId"},"query":"query getEpisodeLinks(${'$'}id: MongoID!) {\n  getEpisodeLinks(id: ${'$'}id) {\n    _id\n    links_online\n  }\n}\n"}
                        """.trimIndent()
                        val gelRes = service.getApiResponse(getEpisodeLinksQuery.toRequestBody("application/json".toMediaType()))
                        addLinks(gelRes.data?.getEpisodeLinks?.linksOnline)
                    }
                }
            }

            allLinks.mapNotNull { item ->
                val embedUrl = item.embed?.takeIf { it.isNotBlank() } ?: item.link ?: return@mapNotNull null
                if (embedUrl.endsWith("/e/")) return@mapNotNull null

                val lang = item.lang?.getLang() ?: ""
                val serverName = try {
                    URL(embedUrl).host.split(".").first { it != "www" }.replaceFirstChar { it.titlecase(Locale.ROOT) }
                } catch (_: Exception) {
                    "Server"
                }

                val finalUrl = getRealLink(embedUrl)
                Video.Server(id = finalUrl, name = "$serverName $lang".trim())
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getRealLink(link: String): String {
        if (!link.contains("fkplayer.xyz")) return link

        return try {
            val document = serviceHtml.getPage(link)
            val script = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return link

            val tokenData = Gson().fromJson(script, TokenModel::class.java)
            val token = tokenData.props?.pageProps?.token ?: return link

            val requestBody = "{\"token\":\"$token\"}".toRequestBody("application/json".toMediaType())

            val videoResponse = service.postApi("https://fkplayer.xyz/api/decoding", requestBody)
            String(Base64.decode(videoResponse.link, Base64.DEFAULT))
        } catch (_: Exception) {
            link
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    override val logo: String = "https://assets.seriesapi.co/brands/doramasflix/websites/6a651fa138cbd16df74343be/logo/logo-1785013866419.png"

    override suspend fun getGenre(id: String, page: Int): Genre {
        val list: List<Show> = when (id) {
            "peliculas" -> getMovies(page)
            "variedades" -> {
                val query = """
                    {"operationName":"listDoramas","variables":{"page":$page,"sort":"CREATEDAT_DESC","perPage":32,"filter":{"isTVShow":true}},"query":"query listDoramas(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyDoramaInput, ${'$'}filter: FilterFindManyDoramaInput) {\n  paginationDorama(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
                """.trimIndent()
                val body = query.toRequestBody("application/json".toMediaType())
                val response = service.getApiResponse(body)
                response.data?.paginationDorama?.items?.map {
                    TvShow(
                        id = it.slug,
                        title = "${it.name} (${it.nameEs ?: ""})".trim(),
                        poster = getPosterUrl(it.posterPath ?: it.poster)
                    )
                } ?: emptyList()
            }
            else -> getTvShows(page)
        }
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = list)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
