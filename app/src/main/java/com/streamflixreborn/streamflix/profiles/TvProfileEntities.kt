package com.streamflixreborn.streamflix.profiles

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val MAX_PROFILES = 5

@Immutable
@Entity(tableName = "tv_user_profiles")
data class TvUserProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val avatarUrl: String,
    val avatarColorHex: String = "#1E88E5",
    val preferredAudioLang: String = "es-LA",
    val subtitlesEnabled: Boolean = false,
    val preferredSubtitleLang: String = "es",
    val isKidsProfile: Boolean = false,
    val pinHash: String? = null,
    val traktAccessToken: String? = null,
    val traktRefreshToken: String? = null,
    val traktExpiresAtEpochSec: Long = 0L,
    val traktUsername: String? = null,
    val subtitleSizeScale: Float = 1.0f,
    val subtitleTextColorHex: String = "#FFFFFF",
    val subtitleBgOpacity: Float = 0.55f,
    val subtitleSyncOffsetMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val hasPinLock: Boolean
        get() = !pinHash.isNullOrBlank()

    val isTraktConnected: Boolean
        get() = !traktAccessToken.isNullOrBlank()
}

@Immutable
@Entity(
    tableName = "tv_profile_watch_history",
    primaryKeys = ["profileId", "mediaKey"],
    indices = [
        Index(value = ["profileId", "updatedAtMs"]),
        Index(value = ["profileId", "contentId"]),
    ],
)
data class TvProfileWatchHistoryEntity(
    val profileId: Long,
    val mediaKey: String,
    val contentId: String,
    val imdbId: String?,
    val mediaKind: String,
    val title: String,
    val subtitle: String?,
    val overview: String?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val logoUrl: String?,
    val year: String?,
    val rating: String?,
    val isDoramaOrAsian: Boolean = false,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

@Immutable
@Entity(
    tableName = "tv_profile_favorites",
    primaryKeys = ["profileId", "contentId"],
    indices = [
        Index(value = ["profileId", "addedAtMs"]),
    ],
)
data class TvProfileFavoriteEntity(
    val profileId: Long,
    val contentId: String,
    val imdbId: String?,
    val mediaKind: String,
    val title: String,
    val overview: String?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val logoUrl: String?,
    val year: String?,
    val rating: String?,
    val genresCsv: String = "",
    val isDoramaOrAsian: Boolean = false,
    val addedAtMs: Long = System.currentTimeMillis(),
)

@Immutable
@Entity(tableName = "tv_custom_addons")
data class TvCustomAddonEntity(
    @PrimaryKey
    val addonId: String,
    val name: String,
    val version: String,
    val description: String?,
    val logoUrl: String?,
    val manifestUrl: String,
    val baseUrl: String,
    val typesCsv: String,
    val resourcesCsv: String,
    val catalogsJson: String,
    val idPrefixesCsv: String = "",
    val isEnabled: Boolean = true,
    val installedAtMs: Long = System.currentTimeMillis(),
)
