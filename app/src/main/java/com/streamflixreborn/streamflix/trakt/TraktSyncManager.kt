package com.streamflixreborn.streamflix.trakt

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.profiles.KidsContentFilter
import com.streamflixreborn.streamflix.profiles.TvProfileFavoriteEntity
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.profiles.TvProfileWatchHistoryEntity
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import com.streamflixreborn.streamflix.tv.model.TvPlaybackSessionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.Locale

enum class TraktScrobbleAction(val endpoint: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop"),
}

/**
 * Coordinates Trakt.tv OAuth Device Token lifecycle, real-time video Scrobbling,
 * and automatic Cloud State Restoration ("Continuar viendo" + Favoritos/Watchlist) on profile login.
 */
object TraktSyncManager {

    private const val TAG = "TraktSyncManager"
    private const val MIN_SCROBBLE_INTERVAL_MS = 2_200L

    private val scrobbleMutex = Mutex()
    private var lastScrobbleAtMs: Long = 0L
    private var lastScrobbleSignature: String = ""

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    suspend fun completeDeviceAuthForProfile(
        profileId: Long,
        token: TraktTokenResponse,
        context: Context = StreamFlixApp.instance,
    ): String? = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1_000L
        val baseCreated = if (token.createdAt > 0L) token.createdAt else nowSec
        val expiresAtSec = baseCreated + token.expiresIn
        val username = TraktApiClient.fetchUsername(token.accessToken, context)

        TvProfileRepository.saveTraktTokensForProfile(
            profileId = profileId,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtEpochSec = expiresAtSec,
            username = username,
            context = context,
        )

        val refreshedProfile = TvProfileRepository.getProfileById(profileId, context)
        if (refreshedProfile != null) {
            restoreCloudStateForProfile(refreshedProfile, context)
        }
        username
    }

    suspend fun disconnectProfileFromTrakt(
        profile: TvUserProfileEntity,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val currentToken = profile.traktAccessToken
        if (!currentToken.isNullOrBlank()) {
            TraktApiClient.revokeToken(currentToken, context)
        }
        TvProfileRepository.saveTraktTokensForProfile(
            profileId = profile.id,
            accessToken = null,
            refreshToken = null,
            expiresAtEpochSec = 0L,
            username = null,
            context = context,
        )
    }

    /**
     * Ensures the active profile's Trakt access token is fresh before cloud sync or scrobbling.
     */
    private suspend fun ensureValidAccessToken(
        profile: TvUserProfileEntity,
        context: Context,
    ): String? {
        val token = profile.traktAccessToken?.takeIf { it.isNotBlank() } ?: return null
        val nowSec = System.currentTimeMillis() / 1_000L
        if (profile.traktExpiresAtEpochSec <= 0L || nowSec < profile.traktExpiresAtEpochSec - 3_600L) {
            return token
        }

        val refresh = profile.traktRefreshToken?.takeIf { it.isNotBlank() } ?: return token
        val renewed = TraktApiClient.refreshAccessToken(refresh, context) ?: return token
        val expiresAt = (if (renewed.createdAt > 0L) renewed.createdAt else nowSec) + renewed.expiresIn
        TvProfileRepository.saveTraktTokensForProfile(
            profileId = profile.id,
            accessToken = renewed.accessToken,
            refreshToken = renewed.refreshToken.ifBlank { refresh },
            expiresAtEpochSec = expiresAt,
            username = profile.traktUsername,
            context = context,
        )
        return renewed.accessToken
    }

    /**
     * Automatically downloads "Continuar viendo" (`/sync/playback`) and Favoritos / Watchlist
     * directly from Trakt.tv when a profile signs in or becomes active.
     */
    suspend fun restoreCloudStateForProfile(
        profile: TvUserProfileEntity,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        if (!profile.isTraktConnected) return@withContext
        val accessToken = ensureValidAccessToken(profile, context) ?: return@withContext

        _isSyncingCloud.value = true
        try {
            coroutineScope {
                val moviesPlaybackDef = async {
                    TraktApiClient.fetchPlaybackProgress(accessToken, "movies", limit = 20, context = context)
                }
                val episodesPlaybackDef = async {
                    TraktApiClient.fetchPlaybackProgress(accessToken, "episodes", limit = 20, context = context)
                }
                val favMoviesDef = async {
                    TraktApiClient.fetchUserSavedList(accessToken, "/users/me/favorites/movies", context)
                }
                val favShowsDef = async {
                    TraktApiClient.fetchUserSavedList(accessToken, "/users/me/favorites/shows", context)
                }
                val watchMoviesDef = async {
                    TraktApiClient.fetchUserSavedList(accessToken, "/sync/watchlist/movies", context)
                }
                val watchShowsDef = async {
                    TraktApiClient.fetchUserSavedList(accessToken, "/sync/watchlist/shows", context)
                }

                val playbackItems = (moviesPlaybackDef.await() + episodesPlaybackDef.await())
                    .filter { it.progress in 1.0..94.0 }
                    .take(20)

                val cloudWatchEntries = playbackItems.map { item ->
                    async { mapPlaybackItemToWatchEntity(profile, item) }
                }.awaitAll().filterNotNull()

                if (cloudWatchEntries.isNotEmpty()) {
                    TvProfileRepository.mergeCloudWatchHistory(profile.id, cloudWatchEntries, context)
                }

                val savedRaw = (favMoviesDef.await() + favShowsDef.await() + watchMoviesDef.await() + watchShowsDef.await())
                    .distinctBy { item ->
                        item.movie?.ids?.imdb ?: item.show?.ids?.imdb ?: "${item.type}:${item.movie?.title ?: item.show?.title}"
                    }
                    .take(28)

                val cloudFavorites = savedRaw.map { item ->
                    async { mapSavedItemToFavoriteEntity(profile, item) }
                }.awaitAll().filterNotNull()

                if (cloudFavorites.isNotEmpty()) {
                    TvProfileRepository.upsertCloudFavorites(profile.id, cloudFavorites, context)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cloud restoration error: ${t.message}")
        } finally {
            _isSyncingCloud.value = false
        }
    }

    /**
     * Real-time Trakt.tv Scrobbling (`start`, `pause`, `stop`) for movies & series episodes.
     */
    suspend fun scrobblePlayback(
        session: TvPlaybackSessionRequest,
        action: TraktScrobbleAction,
        positionMs: Long,
        durationMs: Long,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val activeProfile = TvProfileRepository.activeProfile.value ?: return@withContext
        if (!activeProfile.isTraktConnected) return@withContext

        val imdbId = session.imdbId?.takeIf { it.startsWith("tt") }
            ?: session.contentId.takeIf { it.startsWith("tt") }
            ?: return@withContext

        val progressPct = if (durationMs > 0L) {
            ((positionMs.toDouble() / durationMs.toDouble()) * 100.0).coerceIn(0.5, 100.0)
        } else {
            1.0
        }

        val signature = "${action.endpoint}:$imdbId:${session.seasonNumber}:${session.episodeNumber}:${progressPct.toInt()}"
        val shouldSend = scrobbleMutex.withLock {
            val now = System.currentTimeMillis()
            if (signature == lastScrobbleSignature && now - lastScrobbleAtMs < 15_000L) {
                false
            } else if (action != TraktScrobbleAction.STOP && now - lastScrobbleAtMs < MIN_SCROBBLE_INTERVAL_MS) {
                false
            } else {
                lastScrobbleAtMs = now
                lastScrobbleSignature = signature
                true
            }
        }
        if (!shouldSend) return@withContext

        val accessToken = ensureValidAccessToken(activeProfile, context) ?: return@withContext

        val jsonPayload = if (session.mediaKind == TvMediaKind.SERIES) {
            TraktApiClient.json.encodeToString(
                TraktScrobbleEpisodePayload(
                    show = TraktMediaRef(
                        ids = TraktIds(imdb = imdbId),
                        title = session.displayTitle,
                    ),
                    episode = TraktEpisodeRef(
                        season = session.seasonNumber.coerceAtLeast(1),
                        number = session.episodeNumber.coerceAtLeast(1),
                    ),
                    progress = progressPct,
                )
            )
        } else {
            TraktApiClient.json.encodeToString(
                TraktScrobbleMoviePayload(
                    movie = TraktMediaRef(
                        ids = TraktIds(imdb = imdbId),
                        title = session.displayTitle,
                    ),
                    progress = progressPct,
                )
            )
        }

        TraktApiClient.postScrobble(
            accessToken = accessToken,
            action = action.endpoint,
            jsonPayload = jsonPayload,
            context = context,
        )
    }

    private suspend fun mapPlaybackItemToWatchEntity(
        profile: TvUserProfileEntity,
        item: TraktPlaybackItem,
    ): TvProfileWatchHistoryEntity? {
        val updatedAt = parseIsoMillis(item.pausedAt)
        val isEpisode = item.type.equals("episode", ignoreCase = true) || item.episode != null
        val summary = if (isEpisode) item.show else item.movie
        val imdbId = summary?.ids?.imdb?.takeIf { it.startsWith("tt") } ?: return null

        val stremioType = if (isEpisode) "series" else "movie"
        val meta = StremioAddonsClient.getNuvioMeta(stremioType, imdbId)
            ?: StremioAddonsClient.getCinemetaMeta(stremioType, imdbId)

        if (profile.isKidsProfile && meta != null && !KidsContentFilter.isMetaAllowedForKids(meta)) {
            return null
        }

        val defaultDurMs = if (isEpisode) 2_700_000L else 6_600_000L
        val resolvedDurMs = meta?.resolvedRuntimeMinutes?.let { it * 60_000L }?.takeIf { it > 0L }
            ?: defaultDurMs
        val posMs = ((item.progress.coerceIn(1.0, 93.0) / 100.0) * resolvedDurMs).toLong()

        val seasonNum = item.episode?.season?.coerceAtLeast(1) ?: 1
        val epNum = item.episode?.number?.coerceAtLeast(1) ?: 1
        val mediaKey = if (isEpisode) {
            "ep:$imdbId:S${seasonNum}E${epNum}"
        } else {
            "movie:$imdbId"
        }

        val subtitle = if (isEpisode) {
            val epTitle = item.episode?.title?.takeIf { it.isNotBlank() } ?: "Episodio $epNum"
            "T$seasonNum · E$epNum — $epTitle"
        } else {
            meta?.resolvedYear ?: summary.year?.toString()
        }

        return TvProfileWatchHistoryEntity(
            profileId = profile.id,
            mediaKey = mediaKey,
            contentId = imdbId,
            imdbId = imdbId,
            mediaKind = if (isEpisode) "SERIES" else "MOVIE",
            title = meta?.name?.takeIf { it.isNotBlank() } ?: summary.title,
            subtitle = subtitle,
            overview = meta?.description,
            posterUrl = meta?.poster,
            backgroundUrl = meta?.background ?: meta?.poster,
            logoUrl = meta?.logo,
            year = meta?.resolvedYear ?: summary.year?.toString(),
            rating = meta?.resolvedRating?.let { String.format(Locale.US, "%.1f", it) },
            isDoramaOrAsian = false,
            seasonNumber = seasonNum,
            episodeNumber = epNum,
            positionMs = posMs,
            durationMs = resolvedDurMs,
            updatedAtMs = updatedAt,
        )
    }

    private suspend fun mapSavedItemToFavoriteEntity(
        profile: TvUserProfileEntity,
        item: TraktWatchlistOrFavoriteItem,
    ): TvProfileFavoriteEntity? {
        val isSeries = item.type.equals("show", ignoreCase = true) || item.show != null
        val summary = if (isSeries) item.show else item.movie
        val imdbId = summary?.ids?.imdb?.takeIf { it.startsWith("tt") } ?: return null

        val stremioType = if (isSeries) "series" else "movie"
        val meta = StremioAddonsClient.getNuvioMeta(stremioType, imdbId)
            ?: StremioAddonsClient.getCinemetaMeta(stremioType, imdbId)

        if (profile.isKidsProfile && meta != null && !KidsContentFilter.isMetaAllowedForKids(meta)) {
            return null
        }

        val title = meta?.name?.takeIf { it.isNotBlank() } ?: summary.title.takeIf { it.isNotBlank() } ?: return null
        val poster = meta?.poster ?: meta?.background ?: return null

        return TvProfileFavoriteEntity(
            profileId = profile.id,
            contentId = imdbId,
            imdbId = imdbId,
            mediaKind = if (isSeries) "SERIES" else "MOVIE",
            title = title,
            overview = meta?.description,
            posterUrl = poster,
            backgroundUrl = meta.background ?: poster,
            logoUrl = meta.logo,
            year = meta.resolvedYear ?: summary.year?.toString(),
            rating = meta.resolvedRating?.let { String.format(Locale.US, "%.1f", it) },
            genresCsv = meta.resolvedGenres.joinToString(","),
            isDoramaOrAsian = false,
            addedAtMs = parseIsoMillis(item.listedAt),
        )
    }

    private fun parseIsoMillis(rawIso: String?): Long {
        if (rawIso.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(rawIso).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }
}
