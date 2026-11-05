package com.streamflixreborn.streamflix.stream

import android.util.Log
import com.streamflixreborn.streamflix.addons.CustomAddonRepository
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.providers.AnimeAv1Provider
import com.streamflixreborn.streamflix.providers.AnimeBumProvider
import com.streamflixreborn.streamflix.providers.AnimeFlvProvider
import com.streamflixreborn.streamflix.providers.AnimefenixProvider
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.providers.CineCalidadProvider
import com.streamflixreborn.streamflix.providers.CineHaxProvider
import com.streamflixreborn.streamflix.providers.CuevanaEuProvider
import com.streamflixreborn.streamflix.providers.DoramasflixProvider
import com.streamflixreborn.streamflix.providers.FlixLatamProvider
import com.streamflixreborn.streamflix.providers.GnulaProvider
import com.streamflixreborn.streamflix.providers.LaCartoonsProvider
import com.streamflixreborn.streamflix.providers.LatanimeProvider
import com.streamflixreborn.streamflix.providers.PelisplustoProvider
import com.streamflixreborn.streamflix.providers.PoseidonHD2Provider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.SoloLatinoProvider
import com.streamflixreborn.streamflix.stremio.MediaMetadataHint
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

sealed interface StreamResolutionEvent {
    data class Loading(val statusText: String) : StreamResolutionEvent
    data class FirstReady(
        val primary: ResolvedStreamCandidate,
        val candidates: List<ResolvedStreamCandidate>,
        val fromMemoryCache: Boolean,
    ) : StreamResolutionEvent

    data class FallbacksUpdated(
        val candidates: List<ResolvedStreamCandidate>,
    ) : StreamResolutionEvent

    data class Failed(
        val message: String,
    ) : StreamResolutionEvent
}

/**
 * Automated Spanish/Latino VOD stream resolver combining:
 * 1. Primary Stremio Direct Add-on: NoTorrent2 (`addon.notorrent2.workers.dev`)
 * 2. Complete Spanish/Latino VOD scraper pool (16 scrapers: `cinehax`, `cuevana`, `sololatino`,
 *    `poseidonhd2`, `cinecalidad`, `flixlatam`, `pelisplus`, `cine24h`, `gnula`, `doramaflix`,
 *    `latanime`, `lacartoons`, `animeav1`, `animeflv`, `animefenix`, `animebum`)
 *
 * All sources are queried under bounded concurrency with a strict 3.5s per-scraper timeout,
 * filtered for Spanish audio (`es-LA` / `es-ES`), validated via HTTP `HEAD` (`200..399`),
 * and cached in memory (`StreamUrlMemoryCache`).
 */
object AutoStreamResolver {

    private const val TAG = "AutoStreamResolver"
    const val SCRAPER_TIMEOUT_MS = 3_500L
    private const val MAX_CONCURRENT_SCRAPERS = 8
    private const val MAX_SERVERS_PER_SCRAPER = 3

    enum class ScraperSpecialty {
        GENERAL_VOD,
        DORAMA_ASIAN,
        ANIME_ANIMATION,
    }

    data class ScraperSourceSpec(
        val id: String,
        val poolSlot: Int,
        val provider: Provider,
        val basePriority: Int,
        val supportsMovies: Boolean = true,
        val supportsTvShows: Boolean = true,
        val specialty: ScraperSpecialty = ScraperSpecialty.GENERAL_VOD,
    )

    val CLOSED_SCRAPER_POOL: List<ScraperSourceSpec> = listOf(
        // General Movies & Series (Latino / Castellano)
        ScraperSourceSpec("cinehax", poolSlot = 1, provider = CineHaxProvider, basePriority = 1),
        ScraperSourceSpec("cuevana", poolSlot = 2, provider = CuevanaEuProvider, basePriority = 2),
        ScraperSourceSpec("sololatino", poolSlot = 3, provider = SoloLatinoProvider, basePriority = 3),
        ScraperSourceSpec("poseidonhd2", poolSlot = 4, provider = PoseidonHD2Provider, basePriority = 4),
        ScraperSourceSpec("cinecalidad", poolSlot = 5, provider = CineCalidadProvider, basePriority = 5),
        ScraperSourceSpec("flixlatam", poolSlot = 6, provider = FlixLatamProvider, basePriority = 6),
        ScraperSourceSpec("pelisplus", poolSlot = 7, provider = PelisplustoProvider, basePriority = 7),
        ScraperSourceSpec("cine24h", poolSlot = 8, provider = Cine24hProvider, basePriority = 8),
        ScraperSourceSpec("gnula", poolSlot = 9, provider = GnulaProvider, basePriority = 9),

        // Doramas & Asian Content (Auto-boosted to #1 on Asian/Dorama titles)
        ScraperSourceSpec(
            id = "doramaflix",
            poolSlot = 10,
            provider = DoramasflixProvider,
            basePriority = 10,
            specialty = ScraperSpecialty.DORAMA_ASIAN,
        ),

        // Animation, Cartoons & Anime in Spanish (Auto-boosted on Animation/Anime titles)
        ScraperSourceSpec(
            id = "latanime",
            poolSlot = 11,
            provider = LatanimeProvider,
            basePriority = 11,
            supportsMovies = true,
            supportsTvShows = true,
            specialty = ScraperSpecialty.ANIME_ANIMATION,
        ),
        ScraperSourceSpec(
            id = "lacartoons",
            poolSlot = 12,
            provider = LaCartoonsProvider,
            basePriority = 12,
            supportsMovies = false,
            supportsTvShows = true,
            specialty = ScraperSpecialty.ANIME_ANIMATION,
        ),
        ScraperSourceSpec(
            id = "animeav1",
            poolSlot = 13,
            provider = AnimeAv1Provider,
            basePriority = 13,
            supportsMovies = true,
            supportsTvShows = true,
            specialty = ScraperSpecialty.ANIME_ANIMATION,
        ),
        ScraperSourceSpec(
            id = "animeflv",
            poolSlot = 14,
            provider = AnimeFlvProvider,
            basePriority = 14,
            supportsMovies = false,
            supportsTvShows = true,
            specialty = ScraperSpecialty.ANIME_ANIMATION,
        ),
        ScraperSourceSpec(
            id = "animefenix",
            poolSlot = 15,
            provider = AnimefenixProvider,
            basePriority = 15,
            supportsMovies = false,
            supportsTvShows = true,
            specialty = ScraperSpecialty.ANIME_ANIMATION,
        ),
        ScraperSourceSpec(
            id = "animebum",
            poolSlot = 16,
            provider = AnimeBumProvider,
            basePriority = 16,
            supportsMovies = true,
            supportsTvShows = true,
            specialty = ScraperSpecialty.ANIME_ANIMATION,
        ),
    )

    fun resolveStreams(
        videoType: Video.Type,
        forceRefresh: Boolean = false,
        doramaTagHint: Boolean = false,
    ): Flow<StreamResolutionEvent> = channelFlow {
        val mediaKey = StreamUrlMemoryCache.buildMediaKey(videoType)
        val preferCastellano = runCatching {
            TvProfileRepository.getActiveProfilePreferences().preferredAudioLang == "es-ES"
        }.getOrDefault(false)

        if (!forceRefresh) {
            val cached = sortCandidatesByAudioPreference(
                StreamUrlMemoryCache.getValidCandidates(mediaKey),
                preferCastellano = preferCastellano,
            )
            if (cached.isNotEmpty()) {
                send(
                    StreamResolutionEvent.FirstReady(
                        primary = cached.first(),
                        candidates = cached,
                        fromMemoryCache = true,
                    )
                )
                return@channelFlow
            }
        }

        send(StreamResolutionEvent.Loading("Preparando tu transmisión..."))

        val hint = resolveMetadataHint(videoType)
        val isMovieRequest = videoType is Video.Type.Movie
        val isDoramaContent = doramaTagHint || (hint?.isDoramaOrAsian == true)
        val isAnimationContent = hint?.isAnimationOrAnime == true

        val eligibleScrapers = CLOSED_SCRAPER_POOL.filter { spec ->
            (if (isMovieRequest) spec.supportsMovies else spec.supportsTvShows) &&
                (spec.specialty != ScraperSpecialty.ANIME_ANIMATION || isAnimationContent || isDoramaContent)
        }

        val orderedScrapers = eligibleScrapers.sortedBy { spec ->
            when {
                isDoramaContent && spec.specialty == ScraperSpecialty.DORAMA_ASIAN -> -20
                isAnimationContent && spec.specialty == ScraperSpecialty.ANIME_ANIMATION ->
                    spec.basePriority - 15
                else -> spec.basePriority
            }
        }

        val firstEmitted = AtomicBoolean(false)
        val emissionMutex = Mutex()

        suspend fun onValidatedCandidate(candidate: ResolvedStreamCandidate) {
            emissionMutex.withLock {
                val updatedList = sortCandidatesByAudioPreference(
                    StreamUrlMemoryCache.putOrAppendCandidate(mediaKey, candidate),
                    preferCastellano = preferCastellano,
                )
                if (firstEmitted.compareAndSet(false, true)) {
                    send(
                        StreamResolutionEvent.FirstReady(
                            primary = candidate,
                            candidates = updatedList,
                            fromMemoryCache = false,
                        )
                    )
                } else {
                    send(StreamResolutionEvent.FallbacksUpdated(updatedList))
                }
            }
        }

        supervisorScope {
            // 1. Primary Direct Stream Add-on (strict 3.5s timeout)
            val noTorrentJob = launch(Dispatchers.IO) {
                withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                    resolveFromNoTorrent2(videoType, hint, preferCastellano) { candidate ->
                        onValidatedCandidate(candidate)
                    }
                }
            }

            // 2. User-installed Custom Stremio Stream Add-ons (strict 3.5s timeout each)
            val customAddons = runCatching {
                CustomAddonRepository.getEnabledCustomAddons()
                    .filter { CustomAddonRepository.supportsResource(it, "stream") }
            }.getOrDefault(emptyList())

            val customAddonJobs = customAddons.mapIndexed { addonIdx, customAddon ->
                launch(Dispatchers.IO) {
                    withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                        resolveFromCustomStremioAddon(
                            baseUrl = customAddon.baseUrl,
                            poolSlot = 100 + addonIdx,
                            videoType = videoType,
                            hint = hint,
                            preferCastellano = preferCastellano,
                            onValid = { candidate -> onValidatedCandidate(candidate) },
                        )
                    }
                }
            }

            // 3. Expanded Spanish/Latino scraper pool under bounded concurrency (strict 3.5s per scraper)
            val scraperSemaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)
            val scraperJobs = orderedScrapers.map { spec ->
                launch(Dispatchers.IO) {
                    scraperSemaphore.withPermit {
                        withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                            resolveFromScraper(
                                spec = spec,
                                videoType = videoType,
                                hint = hint,
                                preferCastellano = preferCastellano,
                                onValid = { candidate -> onValidatedCandidate(candidate) },
                            )
                        }
                    }
                }
            }

            noTorrentJob.join()
            customAddonJobs.forEach { it.join() }
            scraperJobs.forEach { it.join() }
        }

        val finalCandidates = StreamUrlMemoryCache.getValidCandidates(mediaKey)
        if (!firstEmitted.get() && finalCandidates.isEmpty()) {
            send(
                StreamResolutionEvent.Failed(
                    "No se pudo iniciar la transmisión en este momento. Inténtalo de nuevo."
                )
            )
        }
    }

    /**
     * Snapshot helper for `Provider.getServers` / `PlayerViewModel` compatibility.
     */
    suspend fun resolveCandidatesSnapshot(
        videoType: Video.Type,
        doramaTagHint: Boolean = false,
    ): List<ResolvedStreamCandidate> = withContext(Dispatchers.IO) {
        val mediaKey = StreamUrlMemoryCache.buildMediaKey(videoType)
        val cached = StreamUrlMemoryCache.getValidCandidates(mediaKey)
        if (cached.isNotEmpty()) return@withContext cached

        var latest: List<ResolvedStreamCandidate> = emptyList()
        resolveStreams(videoType = videoType, doramaTagHint = doramaTagHint).collect { event ->
            when (event) {
                is StreamResolutionEvent.FirstReady -> latest = event.candidates
                is StreamResolutionEvent.FallbacksUpdated -> latest = event.candidates
                else -> Unit
            }
        }
        latest
    }

    private suspend fun resolveMetadataHint(videoType: Video.Type): MediaMetadataHint? {
        val rawId = when (videoType) {
            is Video.Type.Movie -> videoType.imdbId?.takeIf { it.isNotBlank() } ?: videoType.id
            is Video.Type.Episode -> videoType.tvShow.id.ifBlank { videoType.id.substringBefore(":") }
        }
        StremioAddonsClient.getHint(rawId)?.let { existing ->
            if (!existing.spanishTitle.isNullOrBlank() && !existing.imdbId.isNullOrBlank()) {
                return existing
            }
        }

        val stremioType = if (videoType is Video.Type.Movie) "movie" else "series"
        return withTimeoutOrNull(1_800L) {
            val (nuvio, cinemeta) = StremioAddonsClient.getMergedMeta(stremioType, rawId)
            StremioAddonsClient.recordHint(rawId, nuvioMeta = nuvio, cinemetaMeta = cinemeta)
        } ?: StremioAddonsClient.getHint(rawId)
    }

    private suspend fun resolveFromNoTorrent2(
        videoType: Video.Type,
        hint: MediaMetadataHint?,
        preferCastellano: Boolean,
        onValid: suspend (ResolvedStreamCandidate) -> Unit,
    ) {
        val (stremioType, streamIds) = when (videoType) {
            is Video.Type.Movie -> {
                val ids = listOfNotNull(
                    videoType.imdbId?.takeIf { it.startsWith("tt") },
                    hint?.imdbId?.takeIf { it.startsWith("tt") },
                    videoType.id.takeIf { it.startsWith("tt") || it.startsWith("tmdb:") },
                ).distinct()
                "movie" to ids
            }
            is Video.Type.Episode -> {
                val s = videoType.season.number
                val e = videoType.number
                val baseIds = listOfNotNull(
                    videoType.tvShow.imdbId?.takeIf { it.startsWith("tt") },
                    hint?.imdbId?.takeIf { it.startsWith("tt") },
                    videoType.tvShow.id.takeIf { it.startsWith("tt") || it.startsWith("tmdb:") },
                    videoType.id.substringBefore(":").takeIf { it.startsWith("tt") },
                ).distinct()
                "series" to baseIds.map { "$it:$s:$e" }
            }
        }

        for (streamId in streamIds) {
            val rawStreams = StremioAddonsClient.getNoTorrent2Streams(
                type = stremioType,
                streamId = streamId,
                timeoutMs = SCRAPER_TIMEOUT_MS,
            )
            if (rawStreams.isEmpty()) continue

            val orderedStreams = rawStreams
                .mapNotNull { stream ->
                    SpanishStreamValidator.classifyStremioStream(stream)?.let { stream to it }
                }
                .sortedBy { (_, variant) -> variantOrderScore(variant, preferCastellano) }

            coroutineScope {
                orderedStreams.mapIndexed { index, (stremioStream, variant) ->
                    async {
                        val url = stremioStream.url?.trim() ?: return@async
                        val subtitles = stremioStream.subtitles
                            .filter { it.url.startsWith("http") }
                            .map { sub ->
                                Video.Subtitle(
                                    label = sub.lang.ifBlank { "Español" },
                                    file = sub.url,
                                )
                            }
                        val headers = stremioStream.behaviorHints?.proxyHeaders?.request
                            ?.takeIf { it.isNotEmpty() }

                        val candidateVideo = Video(
                            source = url,
                            subtitles = subtitles,
                            headers = headers,
                        )

                        val validated = SpanishStreamValidator.validateVideoUrl(candidateVideo)
                            ?: return@async

                        // Zero-Provider: opaque candidate ID and language-only badge
                        onValid(
                            ResolvedStreamCandidate(
                                id = "stream_0_${index}_${validated.source.hashCode()}",
                                poolSlot = 0,
                                label = variant.badge,
                                audioVariant = variant,
                                video = validated,
                            )
                        )
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun resolveFromCustomStremioAddon(
        baseUrl: String,
        poolSlot: Int,
        videoType: Video.Type,
        hint: MediaMetadataHint?,
        preferCastellano: Boolean,
        onValid: suspend (ResolvedStreamCandidate) -> Unit,
    ) {
        val (stremioType, streamIds) = when (videoType) {
            is Video.Type.Movie -> {
                val ids = listOfNotNull(
                    videoType.imdbId?.takeIf { it.startsWith("tt") },
                    hint?.imdbId?.takeIf { it.startsWith("tt") },
                    videoType.id.takeIf { it.isNotBlank() },
                ).distinct()
                "movie" to ids
            }
            is Video.Type.Episode -> {
                val s = videoType.season.number
                val e = videoType.number
                val baseIds = listOfNotNull(
                    videoType.tvShow.imdbId?.takeIf { it.startsWith("tt") },
                    hint?.imdbId?.takeIf { it.startsWith("tt") },
                    videoType.tvShow.id.takeIf { it.isNotBlank() },
                ).distinct()
                "series" to baseIds.map { "$it:$s:$e" }
            }
        }

        for (streamId in streamIds) {
            val rawStreams = StremioAddonsClient.getCustomAddonStreams(
                baseUrl = baseUrl,
                type = stremioType,
                streamId = streamId,
                timeoutMs = SCRAPER_TIMEOUT_MS,
            )
            if (rawStreams.isEmpty()) continue

            val orderedStreams = rawStreams
                .mapNotNull { stream ->
                    SpanishStreamValidator.classifyStremioStream(stream)?.let { stream to it }
                }
                .sortedBy { (_, variant) -> variantOrderScore(variant, preferCastellano) }

            coroutineScope {
                orderedStreams.mapIndexed { index, (stremioStream, variant) ->
                    async {
                        val url = stremioStream.url?.trim() ?: return@async
                        val subtitles = stremioStream.subtitles
                            .filter { it.url.startsWith("http") }
                            .map { sub ->
                                Video.Subtitle(
                                    label = sub.lang.ifBlank { "Español" },
                                    file = sub.url,
                                )
                            }
                        val headers = stremioStream.behaviorHints?.proxyHeaders?.request
                            ?.takeIf { it.isNotEmpty() }

                        val validated = SpanishStreamValidator.validateVideoUrl(
                            Video(source = url, subtitles = subtitles, headers = headers)
                        ) ?: return@async

                        onValid(
                            ResolvedStreamCandidate(
                                id = "stream_${poolSlot}_${index}_${validated.source.hashCode()}",
                                poolSlot = poolSlot,
                                label = variant.badge,
                                audioVariant = variant,
                                video = validated,
                            )
                        )
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun resolveFromScraper(
        spec: ScraperSourceSpec,
        videoType: Video.Type,
        hint: MediaMetadataHint?,
        preferCastellano: Boolean,
        onValid: suspend (ResolvedStreamCandidate) -> Unit,
    ) {
        val fallbackTitle = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val expectedYear = hint?.year
            ?: when (videoType) {
                is Video.Type.Movie -> videoType.releaseDate?.take(4)
                is Video.Type.Episode -> null
            }

        val searchTitles = hint?.candidateSearchTitles(fallbackTitle)
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(fallbackTitle.takeIf { it.isNotBlank() })

        if (searchTitles.isEmpty()) return

        val servers = when (videoType) {
            is Video.Type.Movie -> findMovieServers(
                spec = spec,
                searchTitles = searchTitles,
                expectedYear = expectedYear,
                imdbId = videoType.imdbId ?: hint?.imdbId,
            )
            is Video.Type.Episode -> findEpisodeServers(
                spec = spec,
                searchTitles = searchTitles,
                expectedYear = expectedYear,
                episodeRequest = videoType,
            )
        }

        if (servers.isEmpty()) return

        val filteredSpanishServers = servers.mapNotNull { server ->
            val variant = SpanishStreamValidator.classifyScraperServer(
                serverName = server.name,
                serverUrl = server.src.ifBlank { server.id },
                scraperId = spec.id,
            ) ?: return@mapNotNull null
            server to variant
        }
            .sortedBy { (_, variant) -> variantOrderScore(variant, preferCastellano) }
            .take(MAX_SERVERS_PER_SCRAPER)

        if (filteredSpanishServers.isEmpty()) return

        coroutineScope {
            filteredSpanishServers.mapIndexed { idx, (server, variant) ->
                async {
                    val extracted = runCatching {
                        spec.provider.getVideo(server)
                    }.getOrElse { err ->
                        Log.d(TAG, "Slot ${spec.poolSlot} primary extractor skipped (${err.message}); trying lightweight regex fallback")
                        val fallbackUrl = server.src.ifBlank { server.id }
                        LightweightEmbedRegexExtractor.extractDirectStream(
                            embedUrl = fallbackUrl,
                            refererHint = "${spec.provider.baseUrl.trimEnd('/')}/",
                        )
                    } ?: return@async

                    val validated = SpanishStreamValidator.validateVideoUrl(extracted)
                        ?: return@async

                    // Zero-Provider: opaque candidate ID and language-only badge
                    onValid(
                        ResolvedStreamCandidate(
                            id = "stream_${spec.poolSlot}_${idx}_${validated.source.hashCode()}",
                            poolSlot = spec.poolSlot,
                            label = variant.badge,
                            audioVariant = variant,
                            video = validated,
                        )
                    )
                }
            }.awaitAll()
        }
    }

    private fun sortCandidatesByAudioPreference(
        list: List<ResolvedStreamCandidate>,
        preferCastellano: Boolean,
    ): List<ResolvedStreamCandidate> {
        return list.sortedBy { variantOrderScore(it.audioVariant, preferCastellano) }
    }

    private fun variantOrderScore(
        variant: SpanishAudioVariant,
        preferCastellano: Boolean,
    ): Int {
        return if (preferCastellano) {
            when (variant) {
                SpanishAudioVariant.CASTELLANO -> 0
                SpanishAudioVariant.LATINO -> 1
                SpanishAudioVariant.SPANISH_GENERAL -> 2
            }
        } else {
            when (variant) {
                SpanishAudioVariant.LATINO -> 0
                SpanishAudioVariant.CASTELLANO -> 1
                SpanishAudioVariant.SPANISH_GENERAL -> 2
            }
        }
    }

    private suspend fun findMovieServers(
        spec: ScraperSourceSpec,
        searchTitles: List<String>,
        expectedYear: String?,
        imdbId: String?,
    ): List<Video.Server> {
        val matchedMovie = searchBestMovie(spec.provider, searchTitles, expectedYear) ?: return emptyList()
        val movieType = Video.Type.Movie(
            id = matchedMovie.id,
            title = matchedMovie.title,
            releaseDate = matchedMovie.released?.get(Calendar.YEAR)?.toString() ?: expectedYear.orEmpty(),
            poster = matchedMovie.poster.orEmpty(),
            imdbId = imdbId,
        )
        return runCatching {
            spec.provider.getServers(matchedMovie.id, movieType)
        }.getOrDefault(emptyList())
    }

    private suspend fun findEpisodeServers(
        spec: ScraperSourceSpec,
        searchTitles: List<String>,
        expectedYear: String?,
        episodeRequest: Video.Type.Episode,
    ): List<Video.Server> {
        val matchedShow = searchBestTvShow(spec.provider, searchTitles, expectedYear) ?: return emptyList()
        val seasonNum = episodeRequest.season.number
        val episodeNum = episodeRequest.number

        val baseEpisodeType = Video.Type.Episode(
            id = "${matchedShow.id}:$seasonNum:$episodeNum",
            number = episodeNum,
            title = episodeRequest.title,
            poster = episodeRequest.poster,
            overview = episodeRequest.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = matchedShow.id,
                title = matchedShow.title,
                poster = matchedShow.poster,
                banner = matchedShow.banner,
                releaseDate = matchedShow.released?.get(Calendar.YEAR)?.toString(),
                imdbId = episodeRequest.tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = seasonNum,
                title = episodeRequest.season.title,
            ),
        )

        return runCatching {
            when (spec.id) {
                "cinehax", "cuevana", "pelisplus", "gnula" -> {
                    spec.provider.getServers(baseEpisodeType.id, baseEpisodeType)
                }
                "poseidonhd2" -> {
                    spec.provider.getServers(matchedShow.id, baseEpisodeType)
                }
                "sololatino" -> {
                    val episodes = spec.provider.getEpisodesBySeason("${matchedShow.id}@$seasonNum")
                    val ep = episodes.firstOrNull { it.number == episodeNum } ?: return@runCatching emptyList()
                    spec.provider.getServers(ep.id, baseEpisodeType.copy(id = ep.id))
                }
                "flixlatam", "cinecalidad" -> {
                    val episodes = spec.provider.getEpisodesBySeason("${matchedShow.id}|$seasonNum")
                    val ep = episodes.firstOrNull { it.number == episodeNum } ?: return@runCatching emptyList()
                    spec.provider.getServers(ep.id, baseEpisodeType.copy(id = ep.id))
                }
                "cine24h" -> {
                    val episodes = spec.provider.getEpisodesBySeason("${matchedShow.id}/$seasonNum")
                    val ep = episodes.firstOrNull { it.number == episodeNum } ?: return@runCatching emptyList()
                    spec.provider.getServers(ep.id, baseEpisodeType.copy(id = ep.id))
                }
                "doramaflix", "lacartoons", "latanime", "animeav1", "animeflv", "animefenix", "animebum" -> {
                    val detailedShow = spec.provider.getTvShow(matchedShow.id)
                    val targetSeason = detailedShow.seasons.firstOrNull { it.number == seasonNum }
                        ?: detailedShow.seasons.firstOrNull()
                        ?: return@runCatching emptyList()
                    val episodes = targetSeason.episodes.ifEmpty {
                        spec.provider.getEpisodesBySeason(targetSeason.id)
                    }
                    val ep = episodes.firstOrNull { it.number == episodeNum } ?: return@runCatching emptyList()
                    spec.provider.getServers(ep.id, baseEpisodeType.copy(id = ep.id))
                }
                else -> spec.provider.getServers(baseEpisodeType.id, baseEpisodeType)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun searchBestMovie(
        provider: Provider,
        titles: List<String>,
        expectedYear: String?,
    ): Movie? {
        for (query in titles) {
            val results = runCatching { provider.search(query, 1) }
                .getOrDefault(emptyList())
                .filterIsInstance<Movie>()
            val best = results
                .map { movie ->
                    val releaseYear = movie.released?.get(Calendar.YEAR)?.toString()
                    movie to scoreTitleMatch(movie.title, titles, releaseYear, expectedYear)
                }
                .filter { (_, score) -> score >= 58 }
                .maxByOrNull { (_, score) -> score }
                ?.first
            if (best != null) return best
        }
        return null
    }

    private suspend fun searchBestTvShow(
        provider: Provider,
        titles: List<String>,
        expectedYear: String?,
    ): TvShow? {
        for (query in titles) {
            val results = runCatching { provider.search(query, 1) }
                .getOrDefault(emptyList())
                .filterIsInstance<TvShow>()
            val best = results
                .map { show ->
                    val releaseYear = show.released?.get(Calendar.YEAR)?.toString()
                    show to scoreTitleMatch(show.title, titles, releaseYear, expectedYear)
                }
                .filter { (_, score) -> score >= 58 }
                .maxByOrNull { (_, score) -> score }
                ?.first
            if (best != null) return best
        }
        return null
    }

    internal fun scoreTitleMatch(
        candidateTitle: String,
        targetTitles: List<String>,
        candidateYear: String?,
        expectedYear: String?,
    ): Int {
        return TitleYearFuzzyMatcher.scoreMatch(
            candidateTitle = candidateTitle,
            targetTitles = targetTitles,
            candidateYearRaw = candidateYear,
            expectedYearRaw = expectedYear,
        )
    }
}
