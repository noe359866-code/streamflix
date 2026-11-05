package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.stream.AutoStreamResolver
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.stremio.StremioMeta
import com.streamflixreborn.streamflix.stremio.StremioVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

object StremioUnifiedProvider : Provider {

    override val name: String = "Catálogo Principal"
    override val baseUrl: String = StremioAddonsClient.NUVIO_BASE
    override val language: String = "es"
    override val logo: String = ""

    private val prevalidatedVideos = ConcurrentHashMap<String, Video>()

    private val SPANISH_GENRES = listOf(
        "Action" to "Acción",
        "Adventure" to "Aventura",
        "Animation" to "Animación",
        "Comedy" to "Comedia",
        "Crime" to "Crimen",
        "Documentary" to "Documental",
        "Drama" to "Drama",
        "Family" to "Familiar",
        "Fantasy" to "Fantasía",
        "History" to "Historia",
        "Horror" to "Terror",
        "Mystery" to "Misterio",
        "Romance" to "Romance",
        "Sci-Fi" to "Ciencia Ficción",
        "Thriller" to "Suspenso",
    )

    override suspend fun getHome(): List<Category> = coroutineScope {
        val nuvioTrendingMoviesDef = async {
            StremioAddonsClient.getNuvioCatalog("movie", "trending-movies")
        }
        val nuvioTrendingSeriesDef = async {
            StremioAddonsClient.getNuvioCatalog("series", "trending-series")
        }
        val nuvioPopularMoviesDef = async {
            StremioAddonsClient.getNuvioCatalog("movie", "popular-movies")
        }
        val nuvioPopularSeriesDef = async {
            StremioAddonsClient.getNuvioCatalog("series", "popular-series")
        }
        val nuvioNewMoviesDef = async {
            StremioAddonsClient.getNuvioCatalog("movie", "new-and-upcoming-movies")
        }
        val nuvioNewSeriesDef = async {
            StremioAddonsClient.getNuvioCatalog("series", "new-and-returning-series")
        }
        val nuvioKoreanDef = async {
            StremioAddonsClient.getNuvioCatalog("series", "series-by-language", extra = "genre=Korean")
        }
        val nuvioCriticallyAcclaimedDef = async {
            StremioAddonsClient.getNuvioCatalog("movie", "critically-acclaimed-movies")
        }
        val cinemetaMoviesTopDef = async {
            StremioAddonsClient.getCinemetaCatalog("movie", "top")
        }
        val cinemetaSeriesTopDef = async {
            StremioAddonsClient.getCinemetaCatalog("series", "top")
        }

        val trendingMovies = nuvioTrendingMoviesDef.await()
        val trendingSeries = nuvioTrendingSeriesDef.await()
        val popularMovies = nuvioPopularMoviesDef.await()
        val popularSeries = nuvioPopularSeriesDef.await()
        val newMovies = nuvioNewMoviesDef.await()
        val newSeries = nuvioNewSeriesDef.await()
        val koreanSeries = nuvioKoreanDef.await()
        val acclaimedMovies = nuvioCriticallyAcclaimedDef.await()
        val cinemetaMovies = cinemetaMoviesTopDef.await()
        val cinemetaSeries = cinemetaSeriesTopDef.await()

        val cinemetaByImdb = (cinemetaMovies + cinemetaSeries)
            .mapNotNull { meta -> meta.resolvedImdbId?.let { it to meta } }
            .toMap()

        val heroItems = interleaveMetas(trendingMovies, trendingSeries)
            .filter { !it.background.isNullOrBlank() }
            .ifEmpty { interleaveMetas(cinemetaMovies, cinemetaSeries).filter { !it.background.isNullOrBlank() } }
            .take(10)
            .mapNotNull { meta -> meta.toShowItem(cinemetaByImdb[meta.resolvedImdbId]) }

        val categories = mutableListOf<Category>()

        if (heroItems.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = heroItems))
        }

        trendingMovies.mapNotNull { it.toShowItem(cinemetaByImdb[it.resolvedImdbId]) }
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Tendencias en Películas", it)) }

        trendingSeries.mapNotNull { it.toShowItem(cinemetaByImdb[it.resolvedImdbId]) }
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Tendencias en Series", it)) }

        mergeCatalogShows(popularMovies, cinemetaMovies)
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Películas Populares", it)) }

        mergeCatalogShows(popularSeries, cinemetaSeries)
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Series más Vistas", it)) }

        koreanSeries.mapNotNull { meta ->
            StremioAddonsClient.recordHint(
                primaryId = meta.resolvedImdbId ?: meta.id,
                nuvioMeta = meta.copy(country = meta.country ?: "KR", language = meta.language ?: "ko"),
            )
            meta.toShowItem(cinemetaByImdb[meta.resolvedImdbId])
        }.takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Doramas y Series Asiáticas", it)) }

        newMovies.mapNotNull { it.toShowItem(cinemetaByImdb[it.resolvedImdbId]) }
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Estrenos de Cine", it)) }

        newSeries.mapNotNull { it.toShowItem(cinemetaByImdb[it.resolvedImdbId]) }
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Nuevas Temporadas", it)) }

        acclaimedMovies.mapNotNull { it.toShowItem(cinemetaByImdb[it.resolvedImdbId]) }
            .takeIf { it.isNotEmpty() }
            ?.let { categories.add(Category("Aclamadas por la Crítica", it)) }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = coroutineScope {
        if (query.isBlank()) {
            if (page > 1) return@coroutineScope emptyList()
            return@coroutineScope SPANISH_GENRES.map { (id, label) -> Genre(id = id, name = label) }
        }
        if (page > 1) return@coroutineScope emptyList()

        val moviesDef = async {
            StremioAddonsClient.getCinemetaCatalog("movie", "top", searchQuery = query)
        }
        val seriesDef = async {
            StremioAddonsClient.getCinemetaCatalog("series", "top", searchQuery = query)
        }

        val movies = moviesDef.await()
        val series = seriesDef.await()

        interleaveMetas(movies, series).mapNotNull { meta ->
            val hint = StremioAddonsClient.getHint(meta.resolvedImdbId ?: meta.id)
            val localizedMeta = if (!hint?.spanishTitle.isNullOrBlank()) {
                meta.copy(name = hint?.spanishTitle ?: meta.name)
            } else {
                meta
            }
            localizedMeta.toShowItem(cinemetaFallback = meta)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        val skip = ((page - 1).coerceAtLeast(0)) * 20
        val nuvioDef = async {
            StremioAddonsClient.getNuvioCatalog("movie", "popular-movies", skip = skip)
        }
        val cinemetaDef = async {
            StremioAddonsClient.getCinemetaCatalog("movie", "top", skip = skip)
        }
        mergeCatalogShows(nuvioDef.await(), cinemetaDef.await())
            .filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        val skip = ((page - 1).coerceAtLeast(0)) * 20
        val nuvioDef = async {
            StremioAddonsClient.getNuvioCatalog("series", "popular-series", skip = skip)
        }
        val cinemetaDef = async {
            StremioAddonsClient.getCinemetaCatalog("series", "top", skip = skip)
        }
        mergeCatalogShows(nuvioDef.await(), cinemetaDef.await())
            .filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val (nuvio, cinemeta) = StremioAddonsClient.getMergedMeta("movie", id)
        val primary = nuvio ?: cinemeta ?: return Movie(id = id, title = id)
        val canonicalId = primary.resolvedImdbId ?: cinemeta?.resolvedImdbId ?: primary.id.ifBlank { id }
        val imdbId = primary.resolvedImdbId ?: cinemeta?.resolvedImdbId

        val genresList = (nuvio?.resolvedGenres?.takeIf { it.isNotEmpty() }
            ?: cinemeta?.resolvedGenres.orEmpty())
            .map { Genre(id = it, name = it) }

        val castPeople = buildCastList(nuvio, cinemeta)
        val directorPeople = buildDirectorList(nuvio, cinemeta)
        val trailerUrl = (nuvio?.trailers.orEmpty() + cinemeta?.trailers.orEmpty())
            .firstOrNull { !it.source.isNullOrBlank() }
            ?.source
            ?.let { "https://www.youtube.com/watch?v=$it" }

        return Movie(
            id = canonicalId,
            title = nuvio?.name?.takeIf { it.isNotBlank() } ?: cinemeta?.name ?: id,
            overview = nuvio?.description?.takeIf { it.isNotBlank() } ?: cinemeta?.description,
            released = nuvio?.released ?: cinemeta?.released ?: primary.resolvedYear,
            runtime = nuvio?.resolvedRuntimeMinutes ?: cinemeta?.resolvedRuntimeMinutes,
            trailer = trailerUrl,
            rating = cinemeta?.resolvedRating ?: nuvio?.resolvedRating,
            poster = nuvio?.poster ?: cinemeta?.poster,
            banner = nuvio?.background ?: cinemeta?.background,
            imdbId = imdbId,
            genres = genresList,
            directors = directorPeople,
            cast = castPeople,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val (nuvio, cinemeta) = StremioAddonsClient.getMergedMeta("series", id)
        val primary = nuvio ?: cinemeta ?: return TvShow(id = id, title = id)
        val canonicalId = primary.resolvedImdbId ?: cinemeta?.resolvedImdbId ?: primary.id.ifBlank { id }
        val imdbId = primary.resolvedImdbId ?: cinemeta?.resolvedImdbId

        val mergedVideos = mergeEpisodeVideos(
            nuvioVideos = nuvio?.videos.orEmpty(),
            cinemetaVideos = cinemeta?.videos.orEmpty(),
        )

        val seasonPosters = nuvio?.appExtras?.seasonPosters.orEmpty()
        val seasons = mergedVideos
            .filter { it.resolvedSeasonNumber > 0 }
            .groupBy { it.resolvedSeasonNumber }
            .toSortedMap()
            .map { (seasonNumber, videosInSeason) ->
                val poster = videosInSeason.firstNotNullOfOrNull { it.seasonPoster }
                    ?: seasonPosters.getOrNull(seasonNumber - 1)
                    ?: nuvio?.poster
                    ?: cinemeta?.poster
                Season(
                    id = "$canonicalId|$seasonNumber",
                    number = seasonNumber,
                    title = "Temporada $seasonNumber",
                    poster = poster,
                )
            }

        val genresList = (nuvio?.resolvedGenres?.takeIf { it.isNotEmpty() }
            ?: cinemeta?.resolvedGenres.orEmpty())
            .map { Genre(id = it, name = it) }

        val castPeople = buildCastList(nuvio, cinemeta)
        val directorPeople = buildDirectorList(nuvio, cinemeta)
        val trailerUrl = (nuvio?.trailers.orEmpty() + cinemeta?.trailers.orEmpty())
            .firstOrNull { !it.source.isNullOrBlank() }
            ?.source
            ?.let { "https://www.youtube.com/watch?v=$it" }

        return TvShow(
            id = canonicalId,
            title = nuvio?.name?.takeIf { it.isNotBlank() } ?: cinemeta?.name ?: id,
            overview = nuvio?.description?.takeIf { it.isNotBlank() } ?: cinemeta?.description,
            released = nuvio?.released ?: cinemeta?.released ?: primary.resolvedYear,
            runtime = nuvio?.resolvedRuntimeMinutes ?: cinemeta?.resolvedRuntimeMinutes,
            trailer = trailerUrl,
            rating = cinemeta?.resolvedRating ?: nuvio?.resolvedRating,
            poster = nuvio?.poster ?: cinemeta?.poster,
            banner = nuvio?.background ?: cinemeta?.background,
            imdbId = imdbId,
            seasons = seasons,
            genres = genresList,
            directors = directorPeople,
            cast = castPeople,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("|")
        val seasonNum = seasonId.substringAfter("|", "1").toIntOrNull() ?: 1
        val (nuvio, cinemeta) = StremioAddonsClient.getMergedMeta("series", showId)
        val canonicalShowId = nuvio?.resolvedImdbId ?: cinemeta?.resolvedImdbId ?: showId

        val mergedVideos = mergeEpisodeVideos(
            nuvioVideos = nuvio?.videos.orEmpty(),
            cinemetaVideos = cinemeta?.videos.orEmpty(),
        )

        return mergedVideos
            .filter { it.resolvedSeasonNumber == seasonNum && it.resolvedEpisodeNumber > 0 }
            .sortedBy { it.resolvedEpisodeNumber }
            .map { video ->
                val epNum = video.resolvedEpisodeNumber
                Episode(
                    id = "$canonicalShowId:$seasonNum:$epNum",
                    number = epNum,
                    title = video.resolvedTitle,
                    released = video.released ?: video.firstAired,
                    poster = video.thumbnail ?: nuvio?.background ?: cinemeta?.background,
                    overview = video.resolvedOverview,
                )
            }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = coroutineScope {
        val skip = ((page - 1).coerceAtLeast(0)) * 20
        val moviesDef = async {
            StremioAddonsClient.getCinemetaCatalog("movie", "top", skip = skip, genre = id)
        }
        val seriesDef = async {
            StremioAddonsClient.getCinemetaCatalog("series", "top", skip = skip, genre = id)
        }
        val shows = interleaveMetas(moviesDef.await(), seriesDef.await())
            .mapNotNull { it.toShowItem() }
        val spanishLabel = SPANISH_GENRES.firstOrNull { it.first.equals(id, ignoreCase = true) }?.second ?: id
        Genre(id = id, name = spanishLabel, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id, filmography = emptyList())
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val candidates = AutoStreamResolver.resolveCandidatesSnapshot(videoType)
        return candidates.map { candidate ->
            prevalidatedVideos[candidate.id] = candidate.video
            Video.Server(
                id = candidate.id,
                name = candidate.label,
                src = candidate.video.source,
            ).also { it.video = candidate.video }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        server.video?.let { return it }
        prevalidatedVideos[server.id]?.let { return it }
        return Video(source = server.src)
    }

    private fun mergeCatalogShows(
        nuvioList: List<StremioMeta>,
        cinemetaList: List<StremioMeta>,
    ): List<Show> {
        val cinemetaMap = cinemetaList
            .mapNotNull { m -> m.resolvedImdbId?.let { it to m } }
            .toMap()

        val primary = nuvioList.mapNotNull { meta ->
            meta.toShowItem(cinemetaMap[meta.resolvedImdbId])
        }
        val seenIds = primary.map {
            when (it) {
                is Movie -> it.imdbId ?: it.id
                is TvShow -> it.imdbId ?: it.id
            }
        }.toMutableSet()

        val secondary = cinemetaList.mapNotNull { meta ->
            val key = meta.resolvedImdbId ?: meta.id
            if (key.isBlank() || !seenIds.add(key)) return@mapNotNull null
            meta.toShowItem()
        }
        return primary + secondary
    }

    private fun StremioMeta.toShowItem(cinemetaFallback: StremioMeta? = null): Show? {
        val canonicalId = resolvedImdbId
            ?: cinemetaFallback?.resolvedImdbId
            ?: id.takeIf { it.isNotBlank() }
            ?: return null

        val displayTitle = name.takeIf { it.isNotBlank() }
            ?: cinemetaFallback?.name?.takeIf { it.isNotBlank() }
            ?: return null

        StremioAddonsClient.recordHint(
            primaryId = canonicalId,
            nuvioMeta = this.takeIf { !id.startsWith("tt") || cinemetaFallback != null },
            cinemetaMeta = cinemetaFallback,
        )

        val genreModels = resolvedGenres.map { Genre(id = it, name = it) }
        val effectiveType = type.ifBlank { cinemetaFallback?.type.orEmpty() }

        return if (effectiveType.equals("series", ignoreCase = true)) {
            TvShow(
                id = canonicalId,
                title = displayTitle,
                overview = description ?: cinemetaFallback?.description,
                released = released ?: resolvedYear ?: cinemetaFallback?.resolvedYear,
                rating = resolvedRating ?: cinemetaFallback?.resolvedRating,
                poster = poster ?: cinemetaFallback?.poster,
                banner = background ?: cinemetaFallback?.background,
                imdbId = resolvedImdbId ?: cinemetaFallback?.resolvedImdbId,
                genres = genreModels,
            )
        } else {
            Movie(
                id = canonicalId,
                title = displayTitle,
                overview = description ?: cinemetaFallback?.description,
                released = released ?: resolvedYear ?: cinemetaFallback?.resolvedYear,
                rating = resolvedRating ?: cinemetaFallback?.resolvedRating,
                poster = poster ?: cinemetaFallback?.poster,
                banner = background ?: cinemetaFallback?.background,
                imdbId = resolvedImdbId ?: cinemetaFallback?.resolvedImdbId,
                genres = genreModels,
            )
        }
    }

    private fun mergeEpisodeVideos(
        nuvioVideos: List<StremioVideo>,
        cinemetaVideos: List<StremioVideo>,
    ): List<StremioVideo> {
        if (nuvioVideos.isEmpty()) return cinemetaVideos
        if (cinemetaVideos.isEmpty()) return nuvioVideos

        val nuvioBySeasonEp = nuvioVideos.associateBy {
            it.resolvedSeasonNumber to it.resolvedEpisodeNumber
        }

        val combined = cinemetaVideos.map { cineEp ->
            val key = cineEp.resolvedSeasonNumber to cineEp.resolvedEpisodeNumber
            val nuvioEp = nuvioBySeasonEp[key]
            if (nuvioEp != null) {
                cineEp.copy(
                    title = nuvioEp.title?.takeIf { it.isNotBlank() } ?: cineEp.title ?: cineEp.name,
                    overview = nuvioEp.overview?.takeIf { it.isNotBlank() }
                        ?: nuvioEp.description?.takeIf { it.isNotBlank() }
                        ?: cineEp.overview ?: cineEp.description,
                    thumbnail = nuvioEp.thumbnail?.takeIf { it.isNotBlank() } ?: cineEp.thumbnail,
                    seasonPoster = nuvioEp.seasonPoster ?: cineEp.seasonPoster,
                )
            } else {
                cineEp
            }
        }

        val existingKeys = combined.map { it.resolvedSeasonNumber to it.resolvedEpisodeNumber }.toSet()
        val extraNuvio = nuvioVideos.filter { (it.resolvedSeasonNumber to it.resolvedEpisodeNumber) !in existingKeys }
        return combined + extraNuvio
    }

    private fun buildCastList(nuvio: StremioMeta?, cinemeta: StremioMeta?): List<People> {
        val extrasCast = nuvio?.appExtras?.cast.orEmpty()
        if (extrasCast.isNotEmpty()) {
            return extrasCast.map { member ->
                People(
                    id = member.name,
                    name = member.name,
                    image = member.photo,
                )
            }
        }
        return (nuvio?.cast?.takeIf { it.isNotEmpty() } ?: cinemeta?.cast.orEmpty())
            .map { actorName -> People(id = actorName, name = actorName) }
    }

    private fun buildDirectorList(nuvio: StremioMeta?, cinemeta: StremioMeta?): List<People> {
        val extrasDirectors = nuvio?.appExtras?.directors.orEmpty()
        if (extrasDirectors.isNotEmpty()) {
            return extrasDirectors.map { member ->
                People(
                    id = member.name,
                    name = member.name,
                    image = member.photo,
                )
            }
        }
        return (nuvio?.director?.takeIf { it.isNotEmpty() } ?: cinemeta?.director.orEmpty())
            .map { dirName -> People(id = dirName, name = dirName) }
    }

    private fun interleaveMetas(a: List<StremioMeta>, b: List<StremioMeta>): List<StremioMeta> {
        val result = ArrayList<StremioMeta>(a.size + b.size)
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            if (i < a.size) result.add(a[i])
            if (i < b.size) result.add(b[i])
        }
        return result.distinctBy { it.resolvedImdbId ?: it.id }
    }
}
