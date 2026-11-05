package com.streamflixreborn.streamflix.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktDeviceCodeRequest(
    @SerialName("client_id")
    val clientId: String,
)

@Serializable
data class TraktDeviceCodeResponse(
    @SerialName("device_code")
    val deviceCode: String = "",
    @SerialName("user_code")
    val userCode: String = "",
    @SerialName("verification_url")
    val verificationUrl: String = "https://trakt.tv/activate",
    @SerialName("expires_in")
    val expiresIn: Int = 600,
    val interval: Int = 5,
)

@Serializable
data class TraktDeviceTokenRequest(
    val code: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("client_secret")
    val clientSecret: String,
)

@Serializable
data class TraktRefreshTokenRequest(
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("client_secret")
    val clientSecret: String,
    @SerialName("redirect_uri")
    val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
    @SerialName("grant_type")
    val grantType: String = "refresh_token",
)

@Serializable
data class TraktRevokeTokenRequest(
    val token: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("client_secret")
    val clientSecret: String,
)

@Serializable
data class TraktTokenResponse(
    @SerialName("access_token")
    val accessToken: String = "",
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 7_776_000L,
    @SerialName("refresh_token")
    val refreshToken: String = "",
    val scope: String = "public",
    @SerialName("created_at")
    val createdAt: Long = 0L,
)

@Serializable
data class TraktUserSettingsResponse(
    val user: TraktUser? = null,
)

@Serializable
data class TraktUser(
    val username: String = "",
    val name: String? = null,
)

@Serializable
data class TraktIds(
    val trakt: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)

@Serializable
data class TraktMediaSummary(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktEpisodeSummary(
    val season: Int = 1,
    val number: Int = 1,
    val title: String? = null,
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktPlaybackItem(
    val id: Long = 0L,
    val progress: Double = 0.0,
    @SerialName("paused_at")
    val pausedAt: String? = null,
    val type: String = "",
    val movie: TraktMediaSummary? = null,
    val episode: TraktEpisodeSummary? = null,
    val show: TraktMediaSummary? = null,
)

@Serializable
data class TraktWatchlistOrFavoriteItem(
    val rank: Int? = null,
    @SerialName("listed_at")
    val listedAt: String? = null,
    val type: String = "",
    val movie: TraktMediaSummary? = null,
    val show: TraktMediaSummary? = null,
)

@Serializable
data class TraktScrobbleMoviePayload(
    val movie: TraktMediaRef,
    val progress: Double,
    @SerialName("app_version")
    val appVersion: String = "1.7.116",
)

@Serializable
data class TraktScrobbleEpisodePayload(
    val show: TraktMediaRef,
    val episode: TraktEpisodeRef,
    val progress: Double,
    @SerialName("app_version")
    val appVersion: String = "1.7.116",
)

@Serializable
data class TraktMediaRef(
    val ids: TraktIds,
    val title: String? = null,
)

@Serializable
data class TraktEpisodeRef(
    val season: Int,
    val number: Int,
)

sealed interface TraktDevicePollResult {
    data object Pending : TraktDevicePollResult
    data object SlowDown : TraktDevicePollResult
    data class Authorized(val token: TraktTokenResponse) : TraktDevicePollResult
    data class Failed(val message: String) : TraktDevicePollResult
}
