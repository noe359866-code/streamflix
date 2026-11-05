package com.streamflixreborn.streamflix.profiles

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.stremio.NuvioAvatarAsset
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Immutable
data class ProfileSubtitleStyle(
    val sizeScale: Float = 1.0f,
    val textColorHex: String = "#FFFFFF",
    val bgOpacity: Float = 0.55f,
    val syncOffsetMs: Long = 0L,
)

data class ProfilePlaybackPreferences(
    val preferredAudioLang: String = "es-LA",
    val subtitlesEnabled: Boolean = false,
    val preferredSubtitleLang: String = "es",
    val subtitleStyle: ProfileSubtitleStyle = ProfileSubtitleStyle(),
)

object TvProfileRepository {

    private const val PREFS_NAME = "streamflix_tv_profile_prefs"
    private const val KEY_CACHED_NUVIO_AVATARS = "cached_nuvio_avatars_json"
    private const val KEY_LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
    private const val KEY_LAST_ACTIVE_IS_KIDS = "last_active_is_kids"

    private val profileGateMutex = Mutex()

    private val _activeProfile = MutableStateFlow<TvUserProfileEntity?>(null)
    val activeProfile: StateFlow<TvUserProfileEntity?> = _activeProfile.asStateFlow()

    private val defaultNuvioSeedAvatars = listOf(
        NuvioAvatarAsset(
            id = "nuvio_seed_coco",
            label = "Coco",
            imageUrl = "https://image.tmdb.org/t/p/w342/oburjHqzOrTtDLYbLunKLwUHhk1.jpg",
            accentColorHex = "#E53935",
        ),
        NuvioAvatarAsset(
            id = "nuvio_seed_coraline",
            label = "Coraline",
            imageUrl = "https://image.tmdb.org/t/p/w342/sYXXUeSrx9TBbFpbgtLklW5kmVR.jpg",
            accentColorHex = "#1E88E5",
        ),
        NuvioAvatarAsset(
            id = "nuvio_seed_jack",
            label = "Jack",
            imageUrl = "https://image.tmdb.org/t/p/w342/pqFPpQ77VZ0moRgRRsxx7p39CS3.jpg",
            accentColorHex = "#8E24AA",
        ),
        NuvioAvatarAsset(
            id = "nuvio_seed_wildwood",
            label = "Wildwood",
            imageUrl = "https://image.tmdb.org/t/p/w342/3AxfnEViGaVspgifEag5au5bVJc.jpg",
            accentColorHex = "#43A047",
        ),
        NuvioAvatarAsset(
            id = "nuvio_seed_toystory",
            label = "Toy Story",
            imageUrl = "https://image.tmdb.org/t/p/w342/zkQmhKTszD48vEcjIfwAlqLnGSr.jpg",
            accentColorHex = "#FFB300",
        ),
    )

    private fun prefs(context: Context = StreamFlixApp.instance): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun profileScopedPrefs(profileId: Long, context: Context = StreamFlixApp.instance): SharedPreferences {
        return context.applicationContext.getSharedPreferences(
            "streamflix_tv_profile_${profileId}_settings",
            Context.MODE_PRIVATE,
        )
    }

    private fun dao(context: Context = StreamFlixApp.instance): TvProfileDao {
        return TvProfileDatabase.getInstance(context).profileDao()
    }

    fun observeProfiles(context: Context = StreamFlixApp.instance): Flow<List<TvUserProfileEntity>> {
        return dao(context).observeProfiles()
    }

    fun observeContinueWatching(
        profileId: Long,
        context: Context = StreamFlixApp.instance,
    ): Flow<List<TvProfileWatchHistoryEntity>> {
        return dao(context).observeContinueWatching(profileId)
    }

    fun observeFavorites(
        profileId: Long,
        context: Context = StreamFlixApp.instance,
    ): Flow<List<TvProfileFavoriteEntity>> {
        return dao(context).observeFavorites(profileId)
    }

    fun isLastActiveProfileKids(context: Context = StreamFlixApp.instance): Boolean {
        return _activeProfile.value?.isKidsProfile
            ?: prefs(context).getBoolean(KEY_LAST_ACTIVE_IS_KIDS, false)
    }

    /**
     * Ensures at least one local profile exists on first launch while strictly respecting `MAX_PROFILES = 5`.
     */
    suspend fun ensureInitialized(context: Context = StreamFlixApp.instance): List<TvUserProfileEntity> =
        withContext(Dispatchers.IO) {
            ParentalPinManager.ensureLoaded(context)
            profileGateMutex.withLock {
                val existing = dao(context).getProfiles()
                if (existing.isNotEmpty()) return@withLock existing

                val avatars = loadNuvioAvatars(context)
                val firstAvatar = avatars.firstOrNull() ?: defaultNuvioSeedAvatars.first()
                val defaultProfile = TvUserProfileEntity(
                    name = "Principal",
                    avatarUrl = firstAvatar.imageUrl,
                    avatarColorHex = firstAvatar.accentColorHex,
                    preferredAudioLang = "es-LA",
                    subtitlesEnabled = false,
                    preferredSubtitleLang = "es",
                )
                val newId = dao(context).insertProfile(defaultProfile)
                saveProfilePreferencesSync(
                    profileId = newId,
                    preferences = ProfilePlaybackPreferences(),
                    context = context,
                )
                dao(context).getProfiles()
            }
        }

    /**
     * Loads graphical avatars directly from the Nuvio Catalog add-on, persisting a local copy
     * in SharedPreferences for immediate availability on the Profile Gate.
     */
    suspend fun loadNuvioAvatars(context: Context = StreamFlixApp.instance): List<NuvioAvatarAsset> =
        withContext(Dispatchers.IO) {
            val remote = runCatching { StremioAddonsClient.getNuvioProfileAvatars() }.getOrDefault(emptyList())
            if (remote.isNotEmpty()) {
                runCatching {
                    val encoded = StremioAddonsClient.json.encodeToString(remote)
                    prefs(context).edit().putString(KEY_CACHED_NUVIO_AVATARS, encoded).apply()
                }
                return@withContext remote
            }

            val cachedJson = prefs(context).getString(KEY_CACHED_NUVIO_AVATARS, null)
            if (!cachedJson.isNullOrBlank()) {
                val decoded = runCatching {
                    StremioAddonsClient.json.decodeFromString<List<NuvioAvatarAsset>>(cachedJson)
                }.getOrDefault(emptyList())
                if (decoded.isNotEmpty()) return@withContext decoded
            }

            defaultNuvioSeedAvatars
        }

    /**
     * Creates a new local profile only if total count is strictly below `MAX_PROFILES` (5).
     */
    suspend fun createProfile(
        name: String,
        avatar: NuvioAvatarAsset,
        preferredAudioLang: String = "es-LA",
        isKidsProfile: Boolean = false,
        rawPin: String? = null,
        context: Context = StreamFlixApp.instance,
    ): TvUserProfileEntity? = withContext(Dispatchers.IO) {
        profileGateMutex.withLock {
            val currentCount = dao(context).getProfileCount()
            if (currentCount >= MAX_PROFILES) {
                return@withLock null
            }
            val cleanName = name.trim().take(18).ifBlank {
                if (isKidsProfile) "Niños" else "Perfil ${currentCount + 1}"
            }
            val hashedPin = rawPin?.takeIf { ParentalPinManager.isValidRawPin(it) }
                ?.let { ParentalPinManager.hashPin(it) }

            val entity = TvUserProfileEntity(
                name = cleanName,
                avatarUrl = avatar.imageUrl,
                avatarColorHex = avatar.accentColorHex,
                preferredAudioLang = preferredAudioLang,
                subtitlesEnabled = false,
                preferredSubtitleLang = "es",
                isKidsProfile = isKidsProfile,
                pinHash = hashedPin,
            )
            val id = dao(context).insertProfile(entity)
            saveProfilePreferencesSync(
                profileId = id,
                preferences = ProfilePlaybackPreferences(preferredAudioLang = preferredAudioLang),
                context = context,
            )
            dao(context).getProfileById(id)
        }
    }

    suspend fun updateProfile(
        profile: TvUserProfileEntity,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val updated = profile.copy(name = profile.name.trim().take(18).ifBlank { "Perfil" })
        dao(context).updateProfile(updated)
        saveProfilePreferencesSync(
            profileId = updated.id,
            preferences = ProfilePlaybackPreferences(
                preferredAudioLang = updated.preferredAudioLang,
                subtitlesEnabled = updated.subtitlesEnabled,
                preferredSubtitleLang = updated.preferredSubtitleLang,
                subtitleStyle = ProfileSubtitleStyle(
                    sizeScale = updated.subtitleSizeScale,
                    textColorHex = updated.subtitleTextColorHex,
                    bgOpacity = updated.subtitleBgOpacity,
                    syncOffsetMs = updated.subtitleSyncOffsetMs,
                ),
            ),
            context = context,
        )
        if (_activeProfile.value?.id == updated.id) {
            _activeProfile.value = updated
            prefs(context).edit().putBoolean(KEY_LAST_ACTIVE_IS_KIDS, updated.isKidsProfile).apply()
        }
    }

    suspend fun getProfileById(
        profileId: Long,
        context: Context = StreamFlixApp.instance,
    ): TvUserProfileEntity? = withContext(Dispatchers.IO) {
        dao(context).getProfileById(profileId)
    }

    suspend fun deleteProfile(
        profileId: Long,
        context: Context = StreamFlixApp.instance,
    ): Boolean = withContext(Dispatchers.IO) {
        profileGateMutex.withLock {
            val all = dao(context).getProfiles()
            if (all.size <= 1) return@withLock false
            dao(context).clearHistoryForProfile(profileId)
            dao(context).clearFavoritesForProfile(profileId)
            dao(context).deleteProfile(profileId)
            profileScopedPrefs(profileId, context).edit().clear().apply()
            if (_activeProfile.value?.id == profileId) {
                _activeProfile.value = null
            }
            true
        }
    }

    fun selectActiveProfile(profile: TvUserProfileEntity, context: Context = StreamFlixApp.instance) {
        _activeProfile.value = profile
        prefs(context).edit()
            .putLong(KEY_LAST_ACTIVE_PROFILE_ID, profile.id)
            .putBoolean(KEY_LAST_ACTIVE_IS_KIDS, profile.isKidsProfile)
            .apply()
    }

    fun clearActiveProfileSession() {
        _activeProfile.value = null
    }

    fun getActiveProfilePreferences(context: Context = StreamFlixApp.instance): ProfilePlaybackPreferences {
        val active = _activeProfile.value ?: return ProfilePlaybackPreferences()
        val sp = profileScopedPrefs(active.id, context)
        return ProfilePlaybackPreferences(
            preferredAudioLang = sp.getString("preferred_audio_lang", active.preferredAudioLang) ?: "es-LA",
            subtitlesEnabled = sp.getBoolean("subtitles_enabled", active.subtitlesEnabled),
            preferredSubtitleLang = sp.getString("preferred_subtitle_lang", active.preferredSubtitleLang) ?: "es",
            subtitleStyle = ProfileSubtitleStyle(
                sizeScale = sp.getFloat("subtitle_size_scale", active.subtitleSizeScale),
                textColorHex = sp.getString("subtitle_text_color", active.subtitleTextColorHex) ?: "#FFFFFF",
                bgOpacity = sp.getFloat("subtitle_bg_opacity", active.subtitleBgOpacity),
                syncOffsetMs = sp.getLong("subtitle_sync_offset_ms", active.subtitleSyncOffsetMs),
            ),
        )
    }

    suspend fun updateActiveProfilePlaybackPreferences(
        preferredAudioLang: String? = null,
        subtitlesEnabled: Boolean? = null,
        preferredSubtitleLang: String? = null,
        subtitleStyle: ProfileSubtitleStyle? = null,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext
        val currentPrefs = getActiveProfilePreferences(context)
        val mergedStyle = subtitleStyle ?: currentPrefs.subtitleStyle
        val mergedPrefs = ProfilePlaybackPreferences(
            preferredAudioLang = preferredAudioLang ?: currentPrefs.preferredAudioLang,
            subtitlesEnabled = subtitlesEnabled ?: currentPrefs.subtitlesEnabled,
            preferredSubtitleLang = preferredSubtitleLang ?: currentPrefs.preferredSubtitleLang,
            subtitleStyle = mergedStyle,
        )
        saveProfilePreferencesSync(active.id, mergedPrefs, context)
        val updatedEntity = active.copy(
            preferredAudioLang = mergedPrefs.preferredAudioLang,
            subtitlesEnabled = mergedPrefs.subtitlesEnabled,
            preferredSubtitleLang = mergedPrefs.preferredSubtitleLang,
            subtitleSizeScale = mergedStyle.sizeScale,
            subtitleTextColorHex = mergedStyle.textColorHex,
            subtitleBgOpacity = mergedStyle.bgOpacity,
            subtitleSyncOffsetMs = mergedStyle.syncOffsetMs,
        )
        dao(context).updateProfile(updatedEntity)
        _activeProfile.value = updatedEntity
    }

    private fun saveProfilePreferencesSync(
        profileId: Long,
        preferences: ProfilePlaybackPreferences,
        context: Context,
    ) {
        profileScopedPrefs(profileId, context).edit()
            .putString("preferred_audio_lang", preferences.preferredAudioLang)
            .putBoolean("subtitles_enabled", preferences.subtitlesEnabled)
            .putString("preferred_subtitle_lang", preferences.preferredSubtitleLang)
            .putFloat("subtitle_size_scale", preferences.subtitleStyle.sizeScale)
            .putString("subtitle_text_color", preferences.subtitleStyle.textColorHex)
            .putFloat("subtitle_bg_opacity", preferences.subtitleStyle.bgOpacity)
            .putLong("subtitle_sync_offset_ms", preferences.subtitleStyle.syncOffsetMs)
            .apply()
    }

    suspend fun saveTraktTokensForProfile(
        profileId: Long,
        accessToken: String?,
        refreshToken: String?,
        expiresAtEpochSec: Long,
        username: String?,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val target = dao(context).getProfileById(profileId) ?: return@withContext
        val updated = target.copy(
            traktAccessToken = accessToken,
            traktRefreshToken = refreshToken,
            traktExpiresAtEpochSec = expiresAtEpochSec,
            traktUsername = username,
        )
        dao(context).updateProfile(updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated
        }
    }

    suspend fun mergeCloudWatchHistory(
        profileId: Long,
        cloudEntries: List<TvProfileWatchHistoryEntity>,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        if (cloudEntries.isEmpty()) return@withContext
        val toInsert = mutableListOf<TvProfileWatchHistoryEntity>()
        for (remote in cloudEntries) {
            val existing = dao(context).getWatchEntry(profileId, remote.mediaKey)
            if (existing == null || remote.updatedAtMs >= existing.updatedAtMs) {
                toInsert.add(remote.copy(profileId = profileId))
            }
        }
        if (toInsert.isNotEmpty()) {
            dao(context).upsertWatchEntries(toInsert)
        }
    }

    suspend fun upsertCloudFavorites(
        profileId: Long,
        favorites: List<TvProfileFavoriteEntity>,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        if (favorites.isEmpty()) return@withContext
        dao(context).upsertFavorites(favorites.map { it.copy(profileId = profileId) })
    }

    suspend fun toggleFavorite(
        favorite: TvProfileFavoriteEntity,
        context: Context = StreamFlixApp.instance,
    ): Boolean = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext false
        val existing = dao(context).getFavorites(active.id).any { it.contentId == favorite.contentId }
        if (existing) {
            dao(context).deleteFavorite(active.id, favorite.contentId)
            false
        } else {
            dao(context).upsertFavorite(favorite.copy(profileId = active.id))
            true
        }
    }

    suspend fun getSavedResumePositionMs(
        mediaKey: String,
        context: Context = StreamFlixApp.instance,
    ): Long = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext 0L
        dao(context).getWatchEntry(active.id, mediaKey)?.positionMs ?: 0L
    }

    suspend fun getLatestWatchEntryForContent(
        contentId: String,
        context: Context = StreamFlixApp.instance,
    ): TvProfileWatchHistoryEntity? = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext null
        dao(context).getLatestWatchEntryForContent(active.id, contentId)
    }

    suspend fun recordPlaybackProgress(
        entry: TvProfileWatchHistoryEntity,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext
        if (entry.durationMs > 0L && entry.positionMs.toDouble() / entry.durationMs.toDouble() >= 0.94) {
            dao(context).deleteWatchEntry(active.id, entry.mediaKey)
            return@withContext
        }
        if (entry.positionMs < 3_000L) return@withContext
        dao(context).upsertWatchEntry(
            entry.copy(
                profileId = active.id,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearWatchEntry(
        mediaKey: String,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext
        dao(context).deleteWatchEntry(active.id, mediaKey)
    }
}
