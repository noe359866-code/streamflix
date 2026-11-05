package com.streamflixreborn.streamflix.trakt

import android.content.Context
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object TraktApiClient {

    private const val BASE_URL = "https://api.trakt.tv"
    private const val PREFS_NAME = "streamflix_tv_trakt_config"
    private const val KEY_CUSTOM_CLIENT_ID = "custom_client_id"
    private const val KEY_CUSTOM_CLIENT_SECRET = "custom_client_secret"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun resolveClientId(context: Context = StreamFlixApp.instance): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = sp.getString(KEY_CUSTOM_CLIENT_ID, null)?.trim()
        if (!custom.isNullOrBlank()) return custom
        return BuildConfig.TRAKT_CLIENT_ID.trim()
    }

    fun resolveClientSecret(context: Context = StreamFlixApp.instance): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = sp.getString(KEY_CUSTOM_CLIENT_SECRET, null)?.trim()
        if (!custom.isNullOrBlank()) return custom
        return BuildConfig.TRAKT_CLIENT_SECRET.trim()
    }

    fun saveCustomCredentials(
        clientId: String,
        clientSecret: String,
        context: Context = StreamFlixApp.instance,
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_CLIENT_ID, clientId.trim())
            .putString(KEY_CUSTOM_CLIENT_SECRET, clientSecret.trim())
            .apply()
    }

    fun isConfigured(context: Context = StreamFlixApp.instance): Boolean {
        return resolveClientId(context).isNotBlank() && resolveClientSecret(context).isNotBlank()
    }

    suspend fun requestDeviceCode(
        context: Context = StreamFlixApp.instance,
    ): Result<TraktDeviceCodeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val clientId = resolveClientId(context)
            if (clientId.isBlank()) {
                error("Configura primero el Client ID y Client Secret de Trakt.tv.")
            }
            val payload = json.encodeToString(TraktDeviceCodeRequest(clientId = clientId))
            val request = baseRequestBuilder("$BASE_URL/oauth/device/code", clientId)
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("No se pudo generar el código de activación de Trakt.tv (HTTP ${resp.code}).")
                }
                val body = resp.body?.string().orEmpty()
                val decoded = json.decodeFromString<TraktDeviceCodeResponse>(body)
                if (decoded.deviceCode.isBlank() || decoded.userCode.isBlank()) {
                    error("Respuesta inválida al generar código de dispositivo.")
                }
                decoded
            }
        }
    }

    suspend fun pollDeviceToken(
        deviceCode: String,
        context: Context = StreamFlixApp.instance,
    ): TraktDevicePollResult = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        val clientSecret = resolveClientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return@withContext TraktDevicePollResult.Failed(
                "Faltan las credenciales Client ID / Secret de Trakt.tv."
            )
        }

        runCatching {
            val payload = json.encodeToString(
                TraktDeviceTokenRequest(
                    code = deviceCode,
                    clientId = clientId,
                    clientSecret = clientSecret,
                )
            )
            val request = baseRequestBuilder("$BASE_URL/oauth/device/token", clientId)
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        val token = json.decodeFromString<TraktTokenResponse>(body)
                        if (token.accessToken.isBlank()) {
                            TraktDevicePollResult.Failed("Token vacío recibido desde Trakt.tv.")
                        } else {
                            TraktDevicePollResult.Authorized(token)
                        }
                    }
                    400 -> TraktDevicePollResult.Pending
                    429 -> TraktDevicePollResult.SlowDown
                    404 -> TraktDevicePollResult.Failed("Código de dispositivo inválido.")
                    409 -> TraktDevicePollResult.Failed("Este código ya fue aprobado previamente.")
                    410 -> TraktDevicePollResult.Failed("El código expiró. Genera uno nuevo.")
                    418 -> TraktDevicePollResult.Failed("Autorización rechazada por el usuario.")
                    else -> TraktDevicePollResult.Failed("Error de servidor Trakt.tv (HTTP ${resp.code}).")
                }
            }
        }.getOrElse { err ->
            TraktDevicePollResult.Failed(err.message ?: "Error de red al verificar Trakt.tv.")
        }
    }

    suspend fun refreshAccessToken(
        refreshToken: String,
        context: Context = StreamFlixApp.instance,
    ): TraktTokenResponse? = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        val clientSecret = resolveClientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            return@withContext null
        }

        runCatching {
            val payload = json.encodeToString(
                TraktRefreshTokenRequest(
                    refreshToken = refreshToken,
                    clientId = clientId,
                    clientSecret = clientSecret,
                )
            )
            val request = baseRequestBuilder("$BASE_URL/oauth/token", clientId)
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<TraktTokenResponse>(body).takeIf { it.accessToken.isNotBlank() }
            }
        }.getOrNull()
    }

    suspend fun revokeToken(
        accessToken: String,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        val clientSecret = resolveClientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank() || accessToken.isBlank()) return@withContext

        runCatching {
            val payload = json.encodeToString(
                TraktRevokeTokenRequest(
                    token = accessToken,
                    clientId = clientId,
                    clientSecret = clientSecret,
                )
            )
            val request = baseRequestBuilder("$BASE_URL/oauth/revoke", clientId)
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().close()
        }
    }

    suspend fun fetchUsername(
        accessToken: String,
        context: Context = StreamFlixApp.instance,
    ): String? = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        if (clientId.isBlank() || accessToken.isBlank()) return@withContext null

        runCatching {
            val request = baseRequestBuilder("$BASE_URL/users/settings", clientId, accessToken)
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<TraktUserSettingsResponse>(body)
                    .user
                    ?.username
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    suspend fun fetchPlaybackProgress(
        accessToken: String,
        type: String,
        limit: Int = 20,
        context: Context = StreamFlixApp.instance,
    ): List<TraktPlaybackItem> = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        if (clientId.isBlank() || accessToken.isBlank()) return@withContext emptyList()

        runCatching {
            val request = baseRequestBuilder(
                url = "$BASE_URL/sync/playback/$type?limit=$limit",
                clientId = clientId,
                accessToken = accessToken,
            ).get().build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<List<TraktPlaybackItem>>(body)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchUserSavedList(
        accessToken: String,
        endpointPath: String,
        context: Context = StreamFlixApp.instance,
    ): List<TraktWatchlistOrFavoriteItem> = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        if (clientId.isBlank() || accessToken.isBlank()) return@withContext emptyList()

        runCatching {
            val request = baseRequestBuilder(
                url = "$BASE_URL$endpointPath",
                clientId = clientId,
                accessToken = accessToken,
            ).get().build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<List<TraktWatchlistOrFavoriteItem>>(body)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun postScrobble(
        accessToken: String,
        action: String,
        jsonPayload: String,
        context: Context = StreamFlixApp.instance,
    ): Boolean = withContext(Dispatchers.IO) {
        val clientId = resolveClientId(context)
        if (clientId.isBlank() || accessToken.isBlank()) return@withContext false

        runCatching {
            val request = baseRequestBuilder(
                url = "$BASE_URL/scrobble/$action",
                clientId = clientId,
                accessToken = accessToken,
            ).post(jsonPayload.toRequestBody(jsonMediaType)).build()
            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun baseRequestBuilder(
        url: String,
        clientId: String,
        accessToken: String? = null,
    ): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .apply {
                if (!accessToken.isNullOrBlank()) {
                    header("Authorization", "Bearer $accessToken")
                }
            }
    }
}
