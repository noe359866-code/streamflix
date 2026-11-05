package com.streamflixreborn.streamflix.tv.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.addons.CustomAddonRepository
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.profiles.KidsContentFilter
import com.streamflixreborn.streamflix.profiles.ParentalPinManager
import com.streamflixreborn.streamflix.profiles.ParentalProtectionConfig
import com.streamflixreborn.streamflix.profiles.TvCustomAddonEntity
import com.streamflixreborn.streamflix.profiles.TvProfileFavoriteEntity
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.profiles.TvProfileWatchHistoryEntity
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.search.TvSearchDeepLinkTarget
import com.streamflixreborn.streamflix.stremio.NuvioAvatarAsset
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.stremio.StremioMeta
import com.streamflixreborn.streamflix.stremio.StremioVideo
import com.streamflixreborn.streamflix.trakt.TraktSyncManager
import com.streamflixreborn.streamflix.tv.model.TvEpisodeModel
import com.streamflixreborn.streamflix.tv.model.TvMediaCardItem
import com.streamflixreborn.streamflix.tv.model.TvMediaDetail
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import com.streamflixreborn.streamflix.tv.model.TvPlaybackSessionRequest
import com.streamflixreborn.streamflix.tv.model.TvSeasonModel
import com.streamflixreborn.streamflix.tv.model.TvShelfModel
import com.streamflixreborn.streamflix.tv.model.TvTopSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class AppleTvCatalogUiState(
    val activeSection: TvTopSection = TvTopSection.HOME,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val heroItems: List<TvMediaCardItem> = emptyList(),
    val homeShelves: List<TvShelfModel> = emptyList(),
    val movieShelves: List<TvShelfModel> = emptyList(),
    val seriesShelves: List<TvShelfModel> = emptyList(),
    val searchQuery: String = "",
    val selectedGenreFilter: String? = null,
    val isSearching: Boolean = false,
    val searchResults: List<TvMediaCardItem> = emptyList(),
)

data class AppleTvDetailUiState(
    val isLoading: Boolean = false,
    val detail: TvMediaDetail? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppleTvMainViewModel : ViewModel() {

    private val _catalogState = MutableStateFlow(AppleTvCatalogUiState())
    val catalogState: StateFlow<AppleTvCatalogUiState> = _catalogState.asStateFlow()

    private val _detailState = MutableStateFlow(AppleTvDetailUiState())
    val detailState: StateFlow<AppleTvDetailUiState> = _detailState.asStateFlow()

    private val _nuvioAvatars = MutableStateFlow<List<NuvioAvatarAsset>>(emptyList())
    val nuvioAvatars: StateFlow<List<NuvioAvatarAsset>> = _nuvioAvatars.asStateFlow()

    val profiles: StateFlow<List<TvUserProfileEntity>> = TvProfileRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val customAddons: StateFlow<List<TvCustomAddonEntity>> = CustomAddonRepository.observeCustomAddons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val activeProfile: StateFlow<TvUserProfileEntity?> = TvProfileRepository.activeProfile

    val parentalConfig: StateFlow<ParentalProtectionConfig> = ParentalPinManager.config

    val isSyncingTrakt: StateFlow<Boolean> = TraktSyncManager.isSyncingCloud

    val continueWatching: StateFlow<List<TvProfileWatchHistoryEntity>> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                TvProfileRepository.observeContinueWatching(profile.id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val favoritesShelf: StateFlow<TvShelfModel?> = activeProfile
        .flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                TvProfileRepository.observeFavorites(profile.id).map { favs ->
                    val cards = favs.map { it.toCardItem() }
                        .let { KidsContentFilter.filterCards(it, profile.isKidsProfile) }
                    if (cards.isEmpty()) {
                        null
                    } else {
                        TvShelfModel(
                            id = "profile_favorites_shelf",
                            title = if (profile.isTraktConnected) {
                                "Favoritos y Mi Lista (Trakt.tv)"
                            } else {
                                "Favoritos"
                            },
                            items = cards,
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private var searchJob: Job? = null
    private var cachedDetailVideos: List<StremioVideo> = emptyList()

    val quickGenres: List<Pair<String, String>> = listOf(
        "Action" to "Acción",
        "Sci-Fi" to "Ciencia Ficción",
        "Comedy" to "Comedia",
        "Drama" to "Drama",
        "Thriller" to "Suspenso",
        "Horror" to "Terror",
        "Animation" to "Animación",
        "Romance" to "Romance",
        "DORAMA_KR" to "Doramas",
    )

    init {
        initializeProfilesAndAvatars()
        loadCatalogs()
    }

    private fun initializeProfilesAndAvatars() {
        viewModelScope.launch {
            TvProfileRepository.ensureInitialized()
            _nuvioAvatars.value = TvProfileRepository.loadNuvioAvatars()
        }
    }

    fun selectProfile(profile: TvUserProfileEntity) {
        TvProfileRepository.selectActiveProfile(profile)
        if (profile.isKidsProfile && _catalogState.value.selectedGenreFilter in setOf("Horror", "Thriller")) {
            selectGenreFilter("Animation")
        }
        if (profile.isTraktConnected) {
            viewModelScope.launch {
                TraktSyncManager.restoreCloudStateForProfile(profile)
            }
        }
    }

    fun openProfileGate() {
        TvProfileRepository.clearActiveProfileSession()
    }

    fun createProfile(
        name: String,
        avatar: NuvioAvatarAsset,
        preferredAudioLang: String,
        isKidsProfile: Boolean = false,
        rawPin: String? = null,
    ) {
        viewModelScope.launch {
            TvProfileRepository.createProfile(
                name = name,
                avatar = avatar,
                preferredAudioLang = preferredAudioLang,
                isKidsProfile = isKidsProfile,
                rawPin = rawPin,
            )
        }
    }

    fun syncActiveProfileTraktNow() {
        val current = activeProfile.value ?: return
        viewModelScope.launch {
            TraktSyncManager.restoreCloudStateForProfile(current)
        }
    }

    fun disconnectActiveProfileTrakt() {
        val current = activeProfile.value ?: return
        viewModelScope.launch {
            TraktSyncManager.disconnectProfileFromTrakt(current)
        }
    }

    fun updateProfile(profile: TvUserProfileEntity) {
        viewModelScope.launch {
            TvProfileRepository.updateProfile(profile)
        }
    }

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch {
            TvProfileRepository.deleteProfile(profileId)
        }
    }

    fun toggleCustomAddonEnabled(addonId: String, enabled: Boolean) {
        viewModelScope.launch {
            CustomAddonRepository.setAddonEnabled(addonId, enabled)
            loadCatalogs()
        }
    }

    fun deleteCustomAddon(addonId: String) {
        viewModelScope.launch {
            CustomAddonRepository.removeAddon(addonId)
            loadCatalogs()
        }
    }

    fun onCustomAddonInstalled() {
        loadCatalogs()
    }

    fun selectSection(section: TvTopSection) {
        _catalogState.update { it.copy(activeSection = section) }
        if (section == TvTopSection.SEARCH &&
            _catalogState.value.searchResults.isEmpty() &&
            _catalogState.value.searchQuery.isBlank() &&
            _catalogState.value.selectedGenreFilter == null
        ) {
            val defaultGenre = if (activeProfile.value?.isKidsProfile == true) "Animation" else "Action"
            selectGenreFilter(defaultGenre)
        }
    }

    fun loadCatalogs() {
        viewModelScope.launch {
            _catalogState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                coroutineScope {
                    val trendingMoviesDef = async {
                        StremioAddonsClient.getNuvioCatalog("movie", "trending-movies")
                    }
                    val trendingSeriesDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "trending-series")
                    }
                    val popularMoviesDef = async {
                        StremioAddonsClient.getNuvioCatalog("movie", "popular-movies")
                    }
                    val popularSeriesDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "popular-series")
                    }
                    val newMoviesDef = async {
                        StremioAddonsClient.getNuvioCatalog("movie", "new-and-upcoming-movies")
                    }
                    val newSeriesDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "new-and-returning-series")
                    }
                    val doramasDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "series-by-language", extra = "genre=Korean")
                    }
                    val acclaimedMoviesDef = async {
                        StremioAddonsClient.getNuvioCatalog("movie", "critically-acclaimed-movies")
                    }
                    val streamingMoviesDef = async {
                        StremioAddonsClient.getNuvioCatalog("movie", "netflix-movies")
                    }
                    val streamingSeriesDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "netflix-series")
                    }
                    val cinemetaMoviesDef = async {
                        StremioAddonsClient.getCinemetaCatalog("movie", "top")
                    }
                    val cinemetaSeriesDef = async {
                        StremioAddonsClient.getCinemetaCatalog("series", "top")
                    }

                    val trendingMovies = trendingMoviesDef.await()
                    val trendingSeries = trendingSeriesDef.await()
                    val popularMovies = popularMoviesDef.await()
                    val popularSeries = popularSeriesDef.await()
                    val newMovies = newMoviesDef.await()
                    val newSeries = newSeriesDef.await()
                    val doramas = doramasDef.await()
                    val acclaimedMovies = acclaimedMoviesDef.await()
                    val streamingMovies = streamingMoviesDef.await()
                    val streamingSeries = streamingSeriesDef.await()
                    val cinemetaMovies = cinemetaMoviesDef.await()
                    val cinemetaSeries = cinemetaSeriesDef.await()

                    val cinemetaMap = (cinemetaMovies + cinemetaSeries)
                        .mapNotNull { m -> m.resolvedImdbId?.let { it to m } }
                        .toMap()

                    val heroPool = interleave(trendingMovies, trendingSeries)
                        .filter { !it.background.isNullOrBlank() }
                        .ifEmpty {
                            interleave(cinemetaMovies, cinemetaSeries).filter { !it.background.isNullOrBlank() }
                        }
                        .take(8)
                        .mapNotNull { it.toCardItem(cinemetaMap[it.resolvedImdbId]) }

                    val trendingMoviesCards = trendingMovies.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.MOVIE)
                    }
                    val trendingSeriesCards = trendingSeries.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.SERIES)
                    }
                    val popularMoviesCards = mergeToCards(popularMovies, cinemetaMovies, TvMediaKind.MOVIE)
                    val popularSeriesCards = mergeToCards(popularSeries, cinemetaSeries, TvMediaKind.SERIES)
                    val doramaCards = doramas.mapNotNull { meta ->
                        val tagged = meta.copy(
                            country = meta.country ?: "KR",
                            language = meta.language ?: "ko",
                        )
                        StremioAddonsClient.recordHint(
                            primaryId = tagged.resolvedImdbId ?: tagged.id,
                            nuvioMeta = tagged,
                            cinemetaMeta = cinemetaMap[tagged.resolvedImdbId],
                        )
                        tagged.toCardItem(
                            cinemetaFallback = cinemetaMap[tagged.resolvedImdbId],
                            forcedKind = TvMediaKind.SERIES,
                            forceDorama = true,
                        )
                    }
                    val newMoviesCards = newMovies.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.MOVIE)
                    }
                    val newSeriesCards = newSeries.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.SERIES)
                    }
                    val acclaimedCards = acclaimedMovies.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.MOVIE)
                    }
                    val streamingMovieCards = streamingMovies.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.MOVIE)
                    }
                    val streamingSeriesCards = streamingSeries.mapNotNull {
                        it.toCardItem(cinemetaMap[it.resolvedImdbId], TvMediaKind.SERIES)
                    }

                    // Append shelves from enabled user-installed custom Stremio add-ons
                    val customAddonShelves = CustomAddonRepository.getEnabledCustomAddons()
                        .flatMap { addon ->
                            CustomAddonRepository.decodeCatalogs(addon)
                                .filter { it.type in setOf("movie", "series") && it.id.isNotBlank() }
                                .take(2)
                                .map { cat ->
                                    async {
                                        val metas = StremioAddonsClient.getCustomAddonCatalog(
                                            baseUrl = addon.baseUrl,
                                            type = cat.type,
                                            catalogId = cat.id,
                                        )
                                        val kind = if (cat.type == "series") TvMediaKind.SERIES else TvMediaKind.MOVIE
                                        val cards = metas.mapNotNull { it.toCardItem(cinemetaMap[it.resolvedImdbId], kind) }
                                        val shelfTitle = cat.name?.takeIf { it.isNotBlank() }
                                            ?: addon.name
                                        kind to shelfOrNull("custom_${addon.addonId}_${cat.type}_${cat.id}", shelfTitle, cards)
                                    }
                                }
                        }
                        .map { it.await() }

                    val extraMovieShelves = customAddonShelves.filter { it.first == TvMediaKind.MOVIE }.mapNotNull { it.second }
                    val extraSeriesShelves = customAddonShelves.filter { it.first == TvMediaKind.SERIES }.mapNotNull { it.second }

                    val homeShelves = listOfNotNull(
                        shelfOrNull("home_trend_movies", "Tendencias en Películas", trendingMoviesCards),
                        shelfOrNull("home_trend_series", "Tendencias en Series", trendingSeriesCards),
                        shelfOrNull("home_doramas", "Doramas y Éxitos Asiáticos", doramaCards),
                        shelfOrNull("home_pop_movies", "Populares en Latinoamérica y España", popularMoviesCards),
                        shelfOrNull("home_pop_series", "Series más Vistas", popularSeriesCards),
                        shelfOrNull("home_new_movies", "Estrenos de Cine", newMoviesCards),
                        shelfOrNull("home_new_series", "Nuevas Temporadas", newSeriesCards),
                        shelfOrNull("home_acclaimed", "Aclamadas por la Crítica", acclaimedCards),
                    ) + extraMovieShelves + extraSeriesShelves

                    val movieShelves = listOfNotNull(
                        shelfOrNull("mov_trend", "Tendencias en Películas", trendingMoviesCards),
                        shelfOrNull("mov_pop", "Películas Populares", popularMoviesCards),
                        shelfOrNull("mov_new", "Estrenos Recientes", newMoviesCards),
                        shelfOrNull("mov_featured", "Selección Destacada", streamingMovieCards),
                        shelfOrNull("mov_acclaimed", "Aclamadas por la Crítica", acclaimedCards),
                    ) + extraMovieShelves

                    val seriesShelves = listOfNotNull(
                        shelfOrNull("ser_trend", "Tendencias en Series", trendingSeriesCards),
                        shelfOrNull("ser_doramas", "Doramas y K-Dramas", doramaCards),
                        shelfOrNull("ser_pop", "Series Imprescindibles", popularSeriesCards),
                        shelfOrNull("ser_new", "Nuevos Episodios y Temporadas", newSeriesCards),
                        shelfOrNull("ser_featured", "Selección de Series", streamingSeriesCards),
                    ) + extraSeriesShelves

                    _catalogState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (homeShelves.isEmpty()) "No se pudo cargar el catálogo en este momento." else null,
                            heroItems = heroPool,
                            homeShelves = homeShelves,
                            movieShelves = movieShelves,
                            seriesShelves = seriesShelves,
                        )
                    }
                }
            }.onFailure {
                _catalogState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error de conexión al cargar el catálogo.",
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _catalogState.update {
            it.copy(
                searchQuery = query,
                selectedGenreFilter = if (query.isNotBlank()) null else it.selectedGenreFilter,
            )
        }
        searchJob?.cancel()
        if (query.trim().isEmpty()) {
            val defaultGenre = if (activeProfile.value?.isKidsProfile == true) "Animation" else "Action"
            selectGenreFilter(_catalogState.value.selectedGenreFilter ?: defaultGenre)
            return
        }
        searchJob = viewModelScope.launch {
            delay(220L)
            performTextSearch(query.trim())
        }
    }

    fun selectGenreFilter(genreId: String) {
        searchJob?.cancel()
        val isKids = activeProfile.value?.isKidsProfile == true
        val safeGenre = if (isKids && genreId in setOf("Horror", "Thriller")) "Animation" else genreId
        _catalogState.update {
            it.copy(
                searchQuery = "",
                selectedGenreFilter = safeGenre,
                isSearching = true,
            )
        }
        searchJob = viewModelScope.launch {
            val cards = if (safeGenre == "DORAMA_KR") {
                coroutineScope {
                    val krDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "series-by-language", extra = "genre=Korean")
                    }
                    val jpDef = async {
                        StremioAddonsClient.getNuvioCatalog("series", "series-by-language", extra = "genre=Japanese")
                    }
                    (krDef.await() + jpDef.await()).mapNotNull {
                        it.toCardItem(forcedKind = TvMediaKind.SERIES, forceDorama = true)
                    }
                }
            } else {
                coroutineScope {
                    val mDef = async {
                        StremioAddonsClient.getCinemetaCatalog("movie", "top", genre = safeGenre)
                    }
                    val sDef = async {
                        StremioAddonsClient.getCinemetaCatalog("series", "top", genre = safeGenre)
                    }
                    interleave(mDef.await(), sDef.await()).mapNotNull { meta ->
                        val hint = StremioAddonsClient.getHint(meta.resolvedImdbId ?: meta.id)
                        val localized = if (!hint?.spanishTitle.isNullOrBlank()) {
                            meta.copy(name = hint?.spanishTitle ?: meta.name)
                        } else {
                            meta
                        }
                        localized.toCardItem(cinemetaFallback = meta)
                    }
                }
            }
            val filtered = KidsContentFilter.filterCards(cards, activeProfile.value?.isKidsProfile == true)
            _catalogState.update {
                it.copy(
                    isSearching = false,
                    searchResults = filtered,
                )
            }
        }
    }

    private suspend fun performTextSearch(query: String) {
        _catalogState.update { it.copy(isSearching = true) }
        val results = coroutineScope {
            val nuvioMoviesDef = async { StremioAddonsClient.searchNuvio("movie", query) }
            val nuvioSeriesDef = async { StremioAddonsClient.searchNuvio("series", query) }
            val cinemetaMoviesDef = async { StremioAddonsClient.searchCinemeta("movie", query) }
            val cinemetaSeriesDef = async { StremioAddonsClient.searchCinemeta("series", query) }

            val mergedMovies = mergeToCards(nuvioMoviesDef.await(), cinemetaMoviesDef.await(), TvMediaKind.MOVIE)
            val mergedSeries = mergeToCards(nuvioSeriesDef.await(), cinemetaSeriesDef.await(), TvMediaKind.SERIES)
            interleaveCards(mergedMovies, mergedSeries)
        }
        val filtered = KidsContentFilter.filterCards(results, activeProfile.value?.isKidsProfile == true)
        _catalogState.update {
            it.copy(
                isSearching = false,
                searchResults = filtered,
            )
        }
    }

    suspend fun openFromVoiceDeepLink(target: TvSearchDeepLinkTarget): Boolean {
        // Ensure a profile is selected if launched cold from Android TV Global Voice Search
        if (activeProfile.value == null) {
            val available = TvProfileRepository.ensureInitialized()
            val unlocked = available.firstOrNull { !it.hasPinLock }
            if (unlocked != null) {
                selectProfile(unlocked)
            } else {
                return false
            }
        }
        val card = TvMediaCardItem(
            id = target.contentId,
            imdbId = target.imdbId ?: target.contentId.takeIf { it.startsWith("tt") },
            title = target.title.ifBlank { "Cargando…" },
            overview = null,
            posterUrl = null,
            backgroundUrl = null,
            logoUrl = null,
            year = null,
            rating = null,
            genres = emptyList(),
            kind = target.kind,
        )
        openMediaDetail(card)
        return true
    }

    fun openMediaDetail(card: TvMediaCardItem) {
        viewModelScope.launch {
            val fallbackOverview = card.overview ?: "Cargando detalles…"
            _detailState.value = AppleTvDetailUiState(
                isLoading = true,
                detail = TvMediaDetail(
                    id = card.id,
                    imdbId = card.imdbId,
                    kind = card.kind,
                    title = card.title,
                    originalTitle = null,
                    overview = fallbackOverview,
                    posterUrl = card.posterUrl,
                    backgroundUrl = card.backgroundUrl ?: card.posterUrl,
                    logoUrl = card.logoUrl,
                    year = card.year,
                    runtimeFormatted = null,
                    rating = card.rating,
                    genres = card.genres,
                    castNames = emptyList(),
                    seasons = emptyList(),
                    selectedSeasonNumber = 1,
                    episodes = emptyList(),
                    isDoramaOrAsian = card.isDoramaOrAsian,
                ),
            )

            val stremioType = if (card.kind == TvMediaKind.SERIES) "series" else "movie"
            val lookupId = card.imdbId ?: card.id

            runCatching {
                val (nuvio, cinemeta) = StremioAddonsClient.getMergedMeta(stremioType, lookupId)
                val hint = StremioAddonsClient.recordHint(
                    primaryId = lookupId,
                    nuvioMeta = nuvio,
                    cinemetaMeta = cinemeta,
                )
                val primary = nuvio ?: cinemeta
                val canonicalId = primary?.resolvedImdbId ?: card.imdbId ?: card.id
                val imdbId = primary?.resolvedImdbId ?: card.imdbId

                val mergedVideos = if (card.kind == TvMediaKind.SERIES) {
                    mergeSeriesVideos(
                        nuvioVideos = nuvio?.videos.orEmpty(),
                        cinemetaVideos = cinemeta?.videos.orEmpty(),
                    )
                } else {
                    emptyList()
                }
                cachedDetailVideos = mergedVideos

                val seasonNumbers = mergedVideos
                    .map { it.resolvedSeasonNumber }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()

                val seasons = seasonNumbers.map { num ->
                    TvSeasonModel(
                        id = "$canonicalId|$num",
                        number = num,
                        title = "Temporada $num",
                    )
                }

                // Default to the active profile's last watched season if available
                val savedShowProgress = TvProfileRepository.getLatestWatchEntryForContent(canonicalId)
                val initialSeason = savedShowProgress?.seasonNumber
                    ?.takeIf { s -> seasons.any { it.number == s } }
                    ?: seasons.firstOrNull()?.number
                    ?: 1

                val initialEpisodes = buildEpisodeModels(
                    canonicalShowId = canonicalId,
                    seasonNumber = initialSeason,
                    videos = mergedVideos,
                    fallbackBackdrop = nuvio?.background ?: cinemeta?.background ?: card.backgroundUrl,
                )

                val runtimeMinutes = nuvio?.resolvedRuntimeMinutes ?: cinemeta?.resolvedRuntimeMinutes
                val runtimeFormatted = runtimeMinutes?.let { mins ->
                    if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins} min"
                }

                val castNames = nuvio?.appExtras?.cast?.map { it.name }?.takeIf { it.isNotEmpty() }
                    ?: nuvio?.cast?.takeIf { it.isNotEmpty() }
                    ?: cinemeta?.cast.orEmpty()

                val ratingVal = cinemeta?.resolvedRating ?: nuvio?.resolvedRating
                val ratingStr = ratingVal?.let { String.format(Locale.US, "%.1f", it) } ?: card.rating

                val detail = TvMediaDetail(
                    id = canonicalId,
                    imdbId = imdbId,
                    kind = card.kind,
                    title = nuvio?.name?.takeIf { it.isNotBlank() }
                        ?: card.title.ifBlank { cinemeta?.name.orEmpty() },
                    originalTitle = cinemeta?.name?.takeIf {
                        it.isNotBlank() && !it.equals(nuvio?.name, ignoreCase = true)
                    },
                    overview = nuvio?.description?.takeIf { it.isNotBlank() }
                        ?: cinemeta?.description?.takeIf { it.isNotBlank() }
                        ?: card.overview
                        ?: "Sin descripción disponible.",
                    posterUrl = nuvio?.poster ?: cinemeta?.poster ?: card.posterUrl,
                    backgroundUrl = nuvio?.background ?: cinemeta?.background ?: card.backgroundUrl ?: card.posterUrl,
                    logoUrl = nuvio?.logo ?: cinemeta?.logo ?: card.logoUrl,
                    year = nuvio?.resolvedYear ?: cinemeta?.resolvedYear ?: card.year,
                    runtimeFormatted = runtimeFormatted,
                    rating = ratingStr,
                    genres = (nuvio?.resolvedGenres?.takeIf { it.isNotEmpty() }
                        ?: cinemeta?.resolvedGenres?.takeIf { it.isNotEmpty() }
                        ?: card.genres).take(4),
                    castNames = castNames.take(5),
                    seasons = seasons,
                    selectedSeasonNumber = initialSeason,
                    episodes = initialEpisodes,
                    isDoramaOrAsian = card.isDoramaOrAsian || hint.isDoramaOrAsian,
                )

                _detailState.value = AppleTvDetailUiState(
                    isLoading = false,
                    detail = detail,
                )
            }.onFailure { err ->
                _detailState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.localizedMessage,
                    )
                }
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        val current = _detailState.value.detail ?: return
        if (current.selectedSeasonNumber == seasonNumber) return
        val episodes = buildEpisodeModels(
            canonicalShowId = current.id,
            seasonNumber = seasonNumber,
            videos = cachedDetailVideos,
            fallbackBackdrop = current.backgroundUrl,
        )
        _detailState.update {
            it.copy(
                detail = current.copy(
                    selectedSeasonNumber = seasonNumber,
                    episodes = episodes,
                )
            )
        }
    }

    fun buildPlaybackFromWatchHistory(entry: TvProfileWatchHistoryEntity): TvPlaybackSessionRequest {
        val kind = if (entry.mediaKind == "SERIES") TvMediaKind.SERIES else TvMediaKind.MOVIE
        return if (kind == TvMediaKind.MOVIE) {
            TvPlaybackSessionRequest(
                videoType = Video.Type.Movie(
                    id = entry.contentId,
                    title = entry.title,
                    releaseDate = entry.year.orEmpty(),
                    poster = entry.posterUrl.orEmpty(),
                    imdbId = entry.imdbId,
                ),
                contentId = entry.contentId,
                imdbId = entry.imdbId,
                mediaKind = TvMediaKind.MOVIE,
                displayTitle = entry.title,
                displaySubtitle = entry.subtitle,
                overview = entry.overview,
                posterUrl = entry.posterUrl,
                backdropUrl = entry.backgroundUrl ?: entry.posterUrl,
                logoUrl = entry.logoUrl,
                year = entry.year,
                rating = entry.rating,
                isDoramaOrAsian = entry.isDoramaOrAsian,
                initialResumePositionMs = entry.positionMs,
            )
        } else {
            val s = entry.seasonNumber.coerceAtLeast(1)
            val e = entry.episodeNumber.coerceAtLeast(1)
            TvPlaybackSessionRequest(
                videoType = Video.Type.Episode(
                    id = "${entry.imdbId ?: entry.contentId}:$s:$e",
                    number = e,
                    title = entry.subtitle ?: "Episodio $e",
                    poster = entry.backgroundUrl ?: entry.posterUrl,
                    overview = entry.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = entry.contentId,
                        title = entry.title,
                        poster = entry.posterUrl,
                        banner = entry.backgroundUrl,
                        releaseDate = entry.year,
                        imdbId = entry.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = s,
                        title = "Temporada $s",
                    ),
                ),
                contentId = entry.contentId,
                imdbId = entry.imdbId,
                mediaKind = TvMediaKind.SERIES,
                displayTitle = entry.title,
                displaySubtitle = entry.subtitle ?: "T$s:E$e",
                overview = entry.overview,
                posterUrl = entry.posterUrl,
                backdropUrl = entry.backgroundUrl ?: entry.posterUrl,
                logoUrl = entry.logoUrl,
                year = entry.year,
                rating = entry.rating,
                seasonNumber = s,
                episodeNumber = e,
                isDoramaOrAsian = entry.isDoramaOrAsian,
                initialResumePositionMs = entry.positionMs,
            )
        }
    }

    fun buildPlaybackFromCard(card: TvMediaCardItem): TvPlaybackSessionRequest {
        val savedEntry = continueWatching.value.firstOrNull { it.contentId == card.id }
        if (savedEntry != null) {
            return buildPlaybackFromWatchHistory(savedEntry)
        }

        return if (card.kind == TvMediaKind.MOVIE) {
            TvPlaybackSessionRequest(
                videoType = Video.Type.Movie(
                    id = card.id,
                    title = card.title,
                    releaseDate = card.year.orEmpty(),
                    poster = card.posterUrl.orEmpty(),
                    imdbId = card.imdbId,
                ),
                contentId = card.id,
                imdbId = card.imdbId,
                mediaKind = TvMediaKind.MOVIE,
                displayTitle = card.title,
                displaySubtitle = card.year,
                overview = card.overview,
                posterUrl = card.posterUrl,
                backdropUrl = card.backgroundUrl ?: card.posterUrl,
                logoUrl = card.logoUrl,
                year = card.year,
                rating = card.rating,
                isDoramaOrAsian = card.isDoramaOrAsian,
            )
        } else {
            TvPlaybackSessionRequest(
                videoType = Video.Type.Episode(
                    id = "${card.imdbId ?: card.id}:1:1",
                    number = 1,
                    title = "Episodio 1",
                    poster = card.backgroundUrl ?: card.posterUrl,
                    overview = card.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = card.id,
                        title = card.title,
                        poster = card.posterUrl,
                        banner = card.backgroundUrl,
                        releaseDate = card.year,
                        imdbId = card.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = 1,
                        title = "Temporada 1",
                    ),
                ),
                contentId = card.id,
                imdbId = card.imdbId,
                mediaKind = TvMediaKind.SERIES,
                displayTitle = card.title,
                displaySubtitle = "T1:E1",
                overview = card.overview,
                posterUrl = card.posterUrl,
                backdropUrl = card.backgroundUrl ?: card.posterUrl,
                logoUrl = card.logoUrl,
                year = card.year,
                rating = card.rating,
                seasonNumber = 1,
                episodeNumber = 1,
                isDoramaOrAsian = card.isDoramaOrAsian,
            )
        }
    }

    fun buildPlaybackFromDetail(
        detail: TvMediaDetail,
        episode: TvEpisodeModel? = null,
    ): TvPlaybackSessionRequest {
        if (episode == null) {
            val savedEntry = continueWatching.value.firstOrNull { it.contentId == detail.id }
            if (savedEntry != null) {
                return buildPlaybackFromWatchHistory(savedEntry)
            }
        }

        return if (detail.kind == TvMediaKind.MOVIE) {
            TvPlaybackSessionRequest(
                videoType = Video.Type.Movie(
                    id = detail.id,
                    title = detail.title,
                    releaseDate = detail.year.orEmpty(),
                    poster = detail.posterUrl.orEmpty(),
                    imdbId = detail.imdbId,
                ),
                contentId = detail.id,
                imdbId = detail.imdbId,
                mediaKind = TvMediaKind.MOVIE,
                displayTitle = detail.title,
                displaySubtitle = listOfNotNull(detail.year, detail.runtimeFormatted)
                    .joinToString(" · ")
                    .ifBlank { null },
                overview = detail.overview,
                posterUrl = detail.posterUrl,
                backdropUrl = detail.backgroundUrl ?: detail.posterUrl,
                logoUrl = detail.logoUrl,
                year = detail.year,
                rating = detail.rating,
                isDoramaOrAsian = detail.isDoramaOrAsian,
            )
        } else {
            val chosenEpisode = episode
                ?: detail.episodes.firstOrNull()
                ?: TvEpisodeModel(
                    id = "${detail.imdbId ?: detail.id}:1:1",
                    seasonNumber = detail.selectedSeasonNumber,
                    episodeNumber = 1,
                    title = "Episodio 1",
                    overview = detail.overview,
                    thumbnailUrl = detail.backgroundUrl,
                    released = detail.year,
                )

            val savedEpProgress = continueWatching.value.firstOrNull {
                it.contentId == detail.id &&
                    it.seasonNumber == chosenEpisode.seasonNumber &&
                    it.episodeNumber == chosenEpisode.episodeNumber
            }?.positionMs ?: 0L

            TvPlaybackSessionRequest(
                videoType = Video.Type.Episode(
                    id = chosenEpisode.id,
                    number = chosenEpisode.episodeNumber,
                    title = chosenEpisode.title,
                    poster = chosenEpisode.thumbnailUrl ?: detail.backgroundUrl,
                    overview = chosenEpisode.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = detail.id,
                        title = detail.title,
                        poster = detail.posterUrl,
                        banner = detail.backgroundUrl,
                        releaseDate = detail.year,
                        imdbId = detail.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = chosenEpisode.seasonNumber,
                        title = "Temporada ${chosenEpisode.seasonNumber}",
                    ),
                ),
                contentId = detail.id,
                imdbId = detail.imdbId,
                mediaKind = TvMediaKind.SERIES,
                displayTitle = detail.title,
                displaySubtitle = "T${chosenEpisode.seasonNumber}:E${chosenEpisode.episodeNumber} · ${chosenEpisode.title}",
                overview = chosenEpisode.overview ?: detail.overview,
                posterUrl = detail.posterUrl,
                backdropUrl = chosenEpisode.thumbnailUrl ?: detail.backgroundUrl,
                logoUrl = detail.logoUrl,
                year = detail.year,
                rating = detail.rating,
                seasonNumber = chosenEpisode.seasonNumber,
                episodeNumber = chosenEpisode.episodeNumber,
                isDoramaOrAsian = detail.isDoramaOrAsian,
                initialResumePositionMs = savedEpProgress,
            )
        }
    }

    private fun buildEpisodeModels(
        canonicalShowId: String,
        seasonNumber: Int,
        videos: List<StremioVideo>,
        fallbackBackdrop: String?,
    ): List<TvEpisodeModel> {
        return videos
            .filter { it.resolvedSeasonNumber == seasonNumber && it.resolvedEpisodeNumber > 0 }
            .sortedBy { it.resolvedEpisodeNumber }
            .map { video ->
                val epNum = video.resolvedEpisodeNumber
                TvEpisodeModel(
                    id = "$canonicalShowId:$seasonNumber:$epNum",
                    seasonNumber = seasonNumber,
                    episodeNumber = epNum,
                    title = video.resolvedTitle,
                    overview = video.resolvedOverview,
                    thumbnailUrl = video.thumbnail ?: fallbackBackdrop,
                    released = (video.released ?: video.firstAired)?.take(10),
                )
            }
    }

    private fun mergeSeriesVideos(
        nuvioVideos: List<StremioVideo>,
        cinemetaVideos: List<StremioVideo>,
    ): List<StremioVideo> {
        if (nuvioVideos.isEmpty()) return cinemetaVideos
        if (cinemetaVideos.isEmpty()) return nuvioVideos

        val nuvioMap = nuvioVideos.associateBy {
            it.resolvedSeasonNumber to it.resolvedEpisodeNumber
        }
        val merged = cinemetaVideos.map { cine ->
            val nuvio = nuvioMap[cine.resolvedSeasonNumber to cine.resolvedEpisodeNumber]
            if (nuvio != null) {
                cine.copy(
                    title = nuvio.title?.takeIf { it.isNotBlank() } ?: cine.title ?: cine.name,
                    overview = nuvio.overview?.takeIf { it.isNotBlank() }
                        ?: nuvio.description?.takeIf { it.isNotBlank() }
                        ?: cine.overview ?: cine.description,
                    thumbnail = nuvio.thumbnail?.takeIf { it.isNotBlank() } ?: cine.thumbnail,
                )
            } else {
                cine
            }
        }
        val seen = merged.map { it.resolvedSeasonNumber to it.resolvedEpisodeNumber }.toSet()
        return merged + nuvioVideos.filter { (it.resolvedSeasonNumber to it.resolvedEpisodeNumber) !in seen }
    }

    private fun mergeToCards(
        nuvioList: List<StremioMeta>,
        cinemetaList: List<StremioMeta>,
        kind: TvMediaKind,
    ): List<TvMediaCardItem> {
        val cineMap = cinemetaList
            .mapNotNull { m -> m.resolvedImdbId?.let { it to m } }
            .toMap()

        val primary = nuvioList.mapNotNull { it.toCardItem(cineMap[it.resolvedImdbId], kind) }
        val seen = primary.map { it.imdbId ?: it.id }.toMutableSet()
        val extras = cinemetaList.mapNotNull { meta ->
            val key = meta.resolvedImdbId ?: meta.id
            if (key.isBlank() || !seen.add(key)) return@mapNotNull null
            meta.toCardItem(forcedKind = kind)
        }
        return primary + extras
    }

    private fun StremioMeta.toCardItem(
        cinemetaFallback: StremioMeta? = null,
        forcedKind: TvMediaKind? = null,
        forceDorama: Boolean = false,
    ): TvMediaCardItem? {
        val canonicalId = resolvedImdbId
            ?: cinemetaFallback?.resolvedImdbId
            ?: id.takeIf { it.isNotBlank() }
            ?: return null

        val titleText = name.takeIf { it.isNotBlank() }
            ?: cinemetaFallback?.name?.takeIf { it.isNotBlank() }
            ?: return null

        val hint = StremioAddonsClient.recordHint(
            primaryId = canonicalId,
            nuvioMeta = this.takeIf { !id.startsWith("tt") || cinemetaFallback != null },
            cinemetaMeta = cinemetaFallback,
        )

        val resolvedKind = forcedKind ?: if (
            type.equals("series", ignoreCase = true) ||
            cinemetaFallback?.type.equals("series", ignoreCase = true)
        ) {
            TvMediaKind.SERIES
        } else {
            TvMediaKind.MOVIE
        }

        val ratingDouble = resolvedRating ?: cinemetaFallback?.resolvedRating
        val ratingFormatted = ratingDouble?.let { String.format(Locale.US, "%.1f", it) }

        return TvMediaCardItem(
            id = canonicalId,
            imdbId = resolvedImdbId ?: cinemetaFallback?.resolvedImdbId,
            title = titleText,
            overview = description?.takeIf { it.isNotBlank() } ?: cinemetaFallback?.description,
            posterUrl = poster ?: cinemetaFallback?.poster,
            backgroundUrl = background ?: cinemetaFallback?.background,
            logoUrl = logo ?: cinemetaFallback?.logo,
            year = resolvedYear ?: cinemetaFallback?.resolvedYear,
            rating = ratingFormatted,
            genres = (resolvedGenres.ifEmpty { cinemetaFallback?.resolvedGenres.orEmpty() }).take(3),
            kind = resolvedKind,
            isDoramaOrAsian = forceDorama || hint.isDoramaOrAsian,
        )
    }

    private fun shelfOrNull(
        id: String,
        title: String,
        items: List<TvMediaCardItem>,
    ): TvShelfModel? {
        return if (items.isEmpty()) null else TvShelfModel(id = id, title = title, items = items)
    }

    private fun interleave(a: List<StremioMeta>, b: List<StremioMeta>): List<StremioMeta> {
        val out = ArrayList<StremioMeta>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out.distinctBy { it.resolvedImdbId ?: it.id }
    }

    private fun interleaveCards(a: List<TvMediaCardItem>, b: List<TvMediaCardItem>): List<TvMediaCardItem> {
        val out = ArrayList<TvMediaCardItem>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out.distinctBy { "${it.kind}:${it.id}" }
    }

    private fun TvProfileFavoriteEntity.toCardItem(): TvMediaCardItem {
        return TvMediaCardItem(
            id = contentId,
            imdbId = imdbId,
            title = title,
            overview = overview,
            posterUrl = posterUrl,
            backgroundUrl = backgroundUrl,
            logoUrl = logoUrl,
            year = year,
            rating = rating,
            genres = genresCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            kind = if (mediaKind == "SERIES") TvMediaKind.SERIES else TvMediaKind.MOVIE,
            isDoramaOrAsian = isDoramaOrAsian,
        )
    }
}
