package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.google.gson.Gson
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import com.streamflixreborn.streamflix.models.doramasflix.LinkOnline
import com.streamflixreborn.streamflix.models.doramasflix.TokenModel
import com.streamflixreborn.streamflix.models.doramasflix.VideoToken
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Doramasflix (LatAm): doramas, películas y variedades en español.
 *
 * La web dejó de servir su catálogo en `__NEXT_DATA__` (ese parseo lanzaba
 * "No se pudo encontrar el script de datos"), así que este provider habla
 * directamente con la API GraphQL del sitio para todo: catálogo, búsqueda,
 * detalle, temporadas, episodios y los enlaces (`links_online`) de cada video.
 *
 * Como el resto de providers del proyecto, sólo entrega streams: `getServers`
 * pasa por [Provider.playableServers] y `getVideo` por [Extractor].
 */
object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    private const val API_URL = "https://sv1.fluxcedene.net/api/"
    override val language = "es"
    override val logo =
        "https://assets.seriesapi.co/brands/doramasflix/websites/6a651fa138cbd16df74343be/logo/logo-1785013866419.png"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024))
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
    }

    private val service: DoramasflixService by lazy {
        Retrofit.Builder()
            .baseUrl(API_URL)
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .client(client)
            .build()
            .create(DoramasflixService::class.java)
    }

    private const val ACCESS_PLATFORM = "RxARncfg1S_MdpSrCvreoLu_SikCGMzE1NzQzODc3NjE2MQ=="

    private val languages = listOf(
        "36" to "[ENG]",
        "37" to "[CAST]",
        "38" to "[LAT]",
        "192" to "[SUB]",
        "1327" to "[POR]",
        "13109" to "[COR]",
        "13110" to "[JAP]",
        "13111" to "[MAN]",
        "13112" to "[TAI]",
        "13113" to "[FIL]",
        "13114" to "[IND]",
        "343422" to "[VIET]",
    )

    private fun String.getLang(): String = languages.firstOrNull { it.first == this }?.second ?: ""

    private interface DoramasflixService {
        @POST("gql")
        @Headers(
            "accept: application/json, text/plain, */*",
            "platform: doramasflix",
            "authorization: Bear",
            "x-access-jwt-token: ",
            "x-access-platform: $ACCESS_PLATFORM",
        )
        suspend fun getApiResponse(@Body body: okhttp3.RequestBody): ApiResponse

        @POST
        @Headers("Content-Type: application/json")
        suspend fun postApi(@Url url: String, @Body body: okhttp3.RequestBody): VideoToken
    }

    private fun getPosterUrl(path: String?): String = when {
        path.isNullOrBlank() -> ""
        path.startsWith("http") -> path
        else -> "https://image.tmdb.org/t/p/w500$path"
    }

    private suspend fun gql(query: String): ApiResponse =
        service.getApiResponse(query.trimIndent().toRequestBody("application/json".toMediaType()))

    // region catálogo

    override suspend fun getHome(): List<Category> {
        val featured: List<AppAdapter.Item> = getTvShows(1).take(6) + getMovies(1).take(4)
        val categories = mutableListOf<Category>()
        if (featured.isNotEmpty()) {
            categories += Category(Category.FEATURED, featured)
        }

        val doramas = runCatching { getTvShows(1) }.getOrDefault(emptyList())
        if (doramas.isNotEmpty()) categories += Category("Doramas Populares", doramas)

        val movies = runCatching { getMovies(1) }.getOrDefault(emptyList())
        if (movies.isNotEmpty()) categories += Category("Películas Populares", movies)

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("doramas", "Doramas"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades"),
            )
        }
        if (page > 1) return emptyList()

        val body = """
            {"operationName":"searchAll","variables":{"input":"$query"},"query":"query searchAll(${'$'}input: String!) {\n  searchDorama(input: ${'$'}input, limit: 32) {\n    _id\n    slug\n    name\n    name_es\n    poster_path\n    poster\n    __typename\n  }\n  searchMovie(input: ${'$'}input, limit: 32) {\n    _id\n    name\n    name_es\n    slug\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
        """

        return runCatching {
            val response = gql(body)
            val results = mutableListOf<AppAdapter.Item>()
            response.data?.searchDorama?.forEach { show ->
                results += TvShow(
                    id = "doramas-online/${show.slug}",
                    title = "${show.name} (${show.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(show.posterPath ?: show.poster),
                )
            }
            response.data?.searchMovie?.forEach { show ->
                results += Movie(
                    id = "peliculas-online/${show.slug}",
                    title = "${show.name} (${show.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(show.posterPath ?: show.poster),
                )
            }
            results
        }.getOrDefault(emptyList())
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val query = """
            {"operationName":"listMovies","variables":{"perPage":20,"sort":"POPULARITY_DESC","filter":{},"page":$page},"query":"query listMovies(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyMovieInput, ${'$'}filter: FilterFindManyMovieInput) {\n  paginationMovie(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """

        return runCatching {
            gql(query).data?.paginationMovie?.items.orEmpty().map {
                Movie(
                    id = "peliculas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster),
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val query = """
            {"operationName":"listDoramas","variables":{"page":$page,"sort":"POPULARITY_DESC","perPage":20,"filter":{"isTVShow":false}},"query":"query listDoramas(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyDoramaInput, ${'$'}filter: FilterFindManyDoramaInput) {\n  paginationDorama(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """

        return runCatching {
            gql(query).data?.paginationDorama?.items.orEmpty().map {
                TvShow(
                    id = "doramas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster),
                )
            }
        }.getOrDefault(emptyList())
    }

    // endregion

    // region detalle

    /**
     * Los ids del provider llevan el prefijo de la ruta (`doramas-online/slug`,
     * `peliculas-online/slug`), y la API sólo entiende el slug o el ObjectId.
     */
    private fun cleanSlugOrId(id: String): String = id
        .removePrefix(baseUrl)
        .removePrefix("https://doramasflix.in")
        .removePrefix("/")
        .removePrefix("peliculas-online/")
        .removePrefix("doramas-online/")
        .removePrefix("dorama/")
        .removePrefix("pelicula/")
        .removePrefix("series/")
        .trim('/')

    private fun filterFieldFor(value: String) =
        if (value.matches(Regex("^[0-9a-fA-F]{24}$"))) "_id" else "slug"

    override suspend fun getMovie(id: String): Movie {
        val cleanId = cleanSlugOrId(id)
        val field = filterFieldFor(cleanId)
        val query = """
            {"operationName":"detailMovie","variables":{"filter":{"$field":"$cleanId"}},"query":"query detailMovie(${'$'}filter: FilterFindOneMovieInput) {\n  detailMovie(filter: ${'$'}filter) {\n    _id\n    name\n    name_es\n    slug\n    overview\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
        """

        return try {
            val movie = gql(query).data?.detailMovie
                ?: throw Exception("No se encontraron datos de la película en la API.")
            Movie(
                id = movie.id,
                title = "${movie.name} (${movie.nameEs ?: ""})".trim(),
                overview = movie.overview,
                poster = getPosterUrl(movie.posterPath ?: movie.poster),
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles de la película: ${e.message}")
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val cleanId = cleanSlugOrId(id)
        val field = filterFieldFor(cleanId)
        val query = """
            {"operationName":"detailDorama","variables":{"filter":{"$field":"$cleanId"}},"query":"query detailDorama(${'$'}filter: FilterFindOneDoramaInput) {\n  detailDorama(filter: ${'$'}filter) {\n    _id\n    name\n    name_es\n    slug\n    overview\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
        """

        return try {
            val dorama = gql(query).data?.detailDorama
                ?: throw Exception("No se encontraron datos del dorama en la API.")
            val seasons = getSeasons(dorama.id)
            TvShow(
                id = dorama.id,
                title = "${dorama.name} (${dorama.nameEs ?: ""})".trim(),
                overview = dorama.overview,
                poster = getPosterUrl(dorama.posterPath ?: dorama.poster),
                seasons = seasons,
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles del dorama: ${e.message}")
        }
    }

    private suspend fun getSeasons(doramaId: String): List<Season> {
        val query = """
            {"operationName":"listSeasons","variables":{"serie_id":"$doramaId"},"query":"query listSeasons(${'$'}serie_id: MongoID!) {\n  listSeasons(sort: NUMBER_ASC, filter: {serie_id: ${'$'}serie_id}) {\n    slug\n    season_number\n    poster_path\n    __typename\n  }\n}\n"}
        """

        return runCatching {
            gql(query).data?.listSeasons.orEmpty().map {
                Season(
                    id = "$doramaId/${it.seasonNumber}",
                    number = it.seasonNumber,
                    title = "Temporada ${it.seasonNumber}",
                    poster = getPosterUrl(it.posterPath),
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val doramaId = seasonId.substringBefore("/")
        val seasonNumber = seasonId.substringAfter("/").toIntOrNull() ?: 1
        val query = """
            {"operationName":"listEpisodes","variables":{"serie_id":"$doramaId","season_number":$seasonNumber},"query":"query listEpisodes(${'$'}season_number: Float!, ${'$'}serie_id: MongoID!) {\n  listEpisodes(sort: NUMBER_ASC, filter: {type_serie: \"dorama\", serie_id: ${'$'}serie_id, season_number: ${'$'}season_number}) {\n    _id\n    name\n    slug\n    episode_number\n    season_number\n    still_path\n    __typename\n  }\n}\n"}
        """

        return runCatching {
            gql(query).data?.listEpisodes.orEmpty().map {
                Episode(
                    id = it.slug ?: "",
                    number = it.episodeNumber ?: 0,
                    title = "Episodio ${it.episodeNumber ?: 0}: ${it.name ?: ""}".trim(),
                    poster = getPosterUrl(it.stillPath),
                )
            }
        }.getOrDefault(emptyList())
    }

    // endregion

    // region streams

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        Provider.playableServers(getServersRaw(id, videoType))

    /**
     * Enlaces tal cual los entrega la API. [Provider.playableServers] filtra
     * después para que sólo queden reproductores reales (sin soporte/redes).
     */
    private suspend fun getServersRaw(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val cleanId = cleanSlugOrId(id)
            val field = filterFieldFor(cleanId)
            val links = mutableListOf<LinkOnline>()
            val seenUrls = mutableSetOf<String>()

            fun addLinks(items: List<LinkOnline>?) {
                items?.forEach { item ->
                    val url = item.embed?.takeIf { it.isNotBlank() } ?: item.link
                    if (!url.isNullOrBlank() && !url.endsWith("/e/") && seenUrls.add(url)) {
                        links += item
                    }
                }
            }

            when (videoType) {
                is Video.Type.Movie -> {
                    val detail = """
                        {"operationName":"detailMovie","variables":{"filter":{"$field":"$cleanId"}},"query":"query detailMovie(${'$'}filter: FilterFindOneMovieInput) {\n  detailMovie(filter: ${'$'}filter) {\n    _id\n    links_online\n  }\n}\n"}
                    """
                    addLinks(gql(detail).data?.detailMovie?.linksOnline)

                    val movieLinks = """
                        {"operationName":"getMovieLinks","variables":{"slug":"$cleanId"},"query":"query getMovieLinks(${'$'}slug: String) {\n  getMovieLinks(slug: ${'$'}slug) {\n    _id\n    links_online\n  }\n}\n"}
                    """
                    addLinks(gql(movieLinks).data?.getMovieLinks?.linksOnline)
                }

                is Video.Type.Episode -> {
                    var episodeId = cleanId
                    val detail = """
                        {"operationName":"detailEpisode","variables":{"filter":{"$field":"$cleanId"}},"query":"query detailEpisode(${'$'}filter: FilterFindOneEpisodeInput) {\n  detailEpisode(filter: ${'$'}filter) {\n    _id\n    links_online\n  }\n}\n"}
                    """
                    val episode = gql(detail).data?.detailEpisode
                    episode?.id?.takeIf { it.isNotBlank() }?.let { episodeId = it }
                    addLinks(episode?.linksOnline)

                    if (episodeId.matches(Regex("^[0-9a-fA-F]{24}$"))) {
                        val episodeLinks = """
                            {"operationName":"getEpisodeLinks","variables":{"id":"$episodeId"},"query":"query getEpisodeLinks(${'$'}id: MongoID!) {\n  getEpisodeLinks(id: ${'$'}id) {\n    _id\n    links_online\n  }\n}\n"}
                        """
                        addLinks(gql(episodeLinks).data?.getEpisodeLinks?.linksOnline)
                    }
                }
            }

            links.mapNotNull { item ->
                val embedUrl = item.embed?.takeIf { it.isNotBlank() } ?: item.link ?: return@mapNotNull null
                if (embedUrl.endsWith("/e/")) return@mapNotNull null

                val lang = item.lang?.getLang() ?: ""
                val serverName = runCatching {
                    URL(embedUrl).host.split(".").first { it != "www" }
                        .replaceFirstChar { it.titlecase(Locale.ROOT) }
                }.getOrDefault("Server")

                val finalUrl = getRealLink(embedUrl)
                Video.Server(id = finalUrl, name = "$serverName $lang".trim(), src = finalUrl)
            }.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Algunos enlaces pasan por `fkplayer.xyz`, que no entrega el video
     * directamente: la página trae un token en `__NEXT_DATA__` y la API
     * `/api/decoding` lo cambia por la URL real (en base64). Si algo falla se
     * devuelve el enlace original y lo intenta el extractor genérico.
     */
    private suspend fun getRealLink(link: String): String {
        if (!link.contains("fkplayer.xyz", ignoreCase = true)) return link

        return try {
            val html = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()
                .newCall(Request.Builder().url(link).build())
                .execute()
                .use { response -> response.body?.string().orEmpty() }

            val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()
                ?: return link
            val token = Gson().fromJson(script, TokenModel::class.java)
                ?.props?.pageProps?.token
                ?: return link

            val body = "{\"token\":\"$token\"}".toRequestBody("application/json".toMediaType())
            val decoded = service.postApi("https://fkplayer.xyz/api/decoding", body).link
                ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
            decoded?.takeIf { it.startsWith("http") } ?: link
        } catch (e: Exception) {
            link
        }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    // endregion

    override suspend fun getGenre(id: String, page: Int): Genre {
        val list: List<Show> = when (id) {
            "peliculas" -> getMovies(page)
            "variedades" -> getVariedades(page)
            else -> getTvShows(page)
        }
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = list)
    }

    private suspend fun getVariedades(page: Int): List<TvShow> {
        val query = """
            {"operationName":"listDoramas","variables":{"page":$page,"sort":"CREATEDAT_DESC","perPage":32,"filter":{"isTVShow":true}},"query":"query listDoramas(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyDoramaInput, ${'$'}filter: FilterFindManyDoramaInput) {\n  paginationDorama(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """

        return runCatching {
            gql(query).data?.paginationDorama?.items.orEmpty().map {
                TvShow(
                    id = "doramas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster),
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw Exception("Not yet implemented")
}
