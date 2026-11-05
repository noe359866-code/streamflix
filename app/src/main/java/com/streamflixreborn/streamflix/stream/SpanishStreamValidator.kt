package com.streamflixreborn.streamflix.stream

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.stremio.StremioStream
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

object SpanishStreamValidator {

    private const val TAG = "SpanishStreamValidator"

    private val ENGLISH_TOKEN_REGEX = Regex(
        """(?i)(?:\b(?:en|eng|english|ingl[eé]s|dub[-_\s]?en|audio[-_\s]?en)\b|\[(?:en|eng)\]|(?:\(|/)(?:en|eng)(?:\)|/)|original\s+audio)"""
    )

    private val NON_SPANISH_LANG_REGEX = Regex(
        """(?i)(?:\b(?:portugu[eê]s|pt-br|turkish|t[uü]rk[cç]e|french|fran[cç]ais|german|deutsch|italiano|russian)\b|🇺🇸|🇬🇧|🇹🇷|🇵🇹|🇧🇷|🇫🇷|🇩🇪|🇮🇹)"""
    )

    private val SUB_ONLY_OR_FORCED_REGEX = Regex(
        """(?i)(?:\b(?:vose|subtitulad[oa]s?|sub\s*esp(?:a[nñ]ol)?|forced\s*sub(?:title)?s?|subt[ií]tulos?\s*forzados?)\b|\[sub\]|\(sub\))"""
    )

    private val EXPLICIT_SPANISH_AUDIO_REGEX = Regex(
        """(?i)(?:\b(?:latino|lat|castellano|cast|espa[nñ]ol|espanol|es-la|es-mx|es-419|es-es|spa|audio\s+latino|audio\s+castellano|audio\s+espa[nñ]ol|dual\s+latino)\b|\[(?:lat|cast|esp)\]|🇲🇽|🇪🇸)"""
    )

    private val LATINO_AUDIO_REGEX = Regex(
        """(?i)(?:\b(?:latino|lat|es-la|es-mx|es-419|m[eé]xico)\b|\[lat\]|\(lat\)|🇲🇽)"""
    )

    private val CASTELLANO_AUDIO_REGEX = Regex(
        """(?i)(?:\b(?:castellano|cast|es-es|espa[nñ]a)\b|\[cast\]|\[esp\]|\(cast\)|🇪🇸)"""
    )

    private val headClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(2_200, TimeUnit.MILLISECONDS)
            .readTimeout(2_200, TimeUnit.MILLISECONDS)
            .callTimeout(2_800, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Evaluates a Stremio stream (e.g., NoTorrent2) and returns its Spanish audio variant,
     * or null if it is promo-only, torrent-only, English (`en`/`eng`), non-Spanish, or forced-sub-only.
     */
    fun classifyStremioStream(stream: StremioStream): SpanishAudioVariant? {
        val directUrl = stream.url?.trim().orEmpty()
        if (directUrl.isEmpty() || !directUrl.startsWith("http")) return null
        if (!stream.infoHash.isNullOrBlank() && stream.url.isNullOrBlank()) return null

        val combined = buildString {
            append(stream.name.orEmpty())
            append(" ")
            append(stream.title.orEmpty())
            append(" ")
            append(stream.description.orEmpty())
        }

        val hasExplicitSpanish = EXPLICIT_SPANISH_AUDIO_REGEX.containsMatchIn(combined)
        if (!hasExplicitSpanish) {
            return null
        }

        if (NON_SPANISH_LANG_REGEX.containsMatchIn(combined)) {
            return null
        }

        if (ENGLISH_TOKEN_REGEX.containsMatchIn(combined) && !LATINO_AUDIO_REGEX.containsMatchIn(combined) && !CASTELLANO_AUDIO_REGEX.containsMatchIn(combined)) {
            return null
        }

        if (SUB_ONLY_OR_FORCED_REGEX.containsMatchIn(combined) &&
            !combined.contains("audio latino", ignoreCase = true) &&
            !combined.contains("audio castellano", ignoreCase = true) &&
            !combined.contains("audio español", ignoreCase = true)
        ) {
            return null
        }

        return when {
            LATINO_AUDIO_REGEX.containsMatchIn(combined) -> SpanishAudioVariant.LATINO
            CASTELLANO_AUDIO_REGEX.containsMatchIn(combined) -> SpanishAudioVariant.CASTELLANO
            else -> SpanishAudioVariant.SPANISH_GENERAL
        }
    }

    private val LATINO_DEFAULT_SCRAPER_IDS = setOf(
        "cinehax",
        "cuevana",
        "sololatino",
        "poseidonhd2",
        "cinecalidad",
        "flixlatam",
        "pelisplus",
        "cine24h",
        "gnula",
        "latanime",
        "lacartoons",
    )

    private val ASIAN_AND_ANIME_ES_SCRAPER_IDS = setOf(
        "doramaflix",
        "animeav1",
        "animeflv",
        "animefenix",
        "animebum",
    )

    /**
     * Evaluates a scraper `Video.Server` from the Spanish scraper pool.
     * Discards English (`en`/`eng`), non-Spanish, and subtitle-only / forced-subtitle links
     * lacking main Spanish (`es-LA` / `es-ES`) audio (while allowing Spanish-subbed fallback
     * specifically on dedicated Anime/Dorama Spanish scrapers behind dubbed streams).
     */
    fun classifyScraperServer(
        serverName: String,
        serverUrl: String,
        scraperId: String,
    ): SpanishAudioVariant? {
        val text = "$serverName ${serverUrl.substringAfterLast('/')}"

        val hasExplicitLatino = LATINO_AUDIO_REGEX.containsMatchIn(serverName)
        val hasExplicitCastellano = CASTELLANO_AUDIO_REGEX.containsMatchIn(serverName)
        val hasExplicitSpanish = hasExplicitLatino || hasExplicitCastellano ||
            EXPLICIT_SPANISH_AUDIO_REGEX.containsMatchIn(serverName)

        // Discard English or non-Spanish audio servers
        if (ENGLISH_TOKEN_REGEX.containsMatchIn(text) && !hasExplicitSpanish) {
            return null
        }
        if (NON_SPANISH_LANG_REGEX.containsMatchIn(serverName) && !hasExplicitSpanish) {
            return null
        }

        val isAsianOrAnimeScraper = scraperId in ASIAN_AND_ANIME_ES_SCRAPER_IDS

        // Discard subtitle-only / forced-sub streams on general movie/series scrapers
        if (SUB_ONLY_OR_FORCED_REGEX.containsMatchIn(serverName) &&
            !hasExplicitLatino &&
            !hasExplicitCastellano &&
            !isAsianOrAnimeScraper
        ) {
            return null
        }

        val urlLangLower = serverUrl.lowercase(Locale.ROOT)
        if ((urlLangLower.contains("lang=en") ||
                urlLangLower.contains("idioma=en") ||
                ((urlLangLower.contains("lang=sub") || urlLangLower.contains("idioma=sub")) && !isAsianOrAnimeScraper)) &&
            !hasExplicitSpanish
        ) {
            return null
        }

        return when {
            hasExplicitCastellano -> SpanishAudioVariant.CASTELLANO
            hasExplicitLatino -> SpanishAudioVariant.LATINO
            scraperId in LATINO_DEFAULT_SCRAPER_IDS && !SUB_ONLY_OR_FORCED_REGEX.containsMatchIn(serverName) ->
                SpanishAudioVariant.LATINO
            else -> SpanishAudioVariant.SPANISH_GENERAL
        }
    }

    /**
     * Validates that a candidate playback URL is reachable and active via an HTTP `HEAD` request
     * returning a status code in `200..399`.
     * Returns a validated `Video` (updating redirected direct URL when safe), or null if inactive.
     */
    suspend fun validateVideoUrl(video: Video): Video? = withContext(Dispatchers.IO) {
        val rawUrl = video.source.trim()
        if (rawUrl.isEmpty() || !rawUrl.startsWith("http")) return@withContext null

        // Case-insensitive header merge: preserve custom User-Agent even if supplied as "user-agent" / "USER-AGENT"
        val existingHeadersLower = video.headers?.keys?.map { it.lowercase(Locale.ROOT) }?.toSet() ?: emptySet()
        val mergedHeaders = LinkedHashMap<String, String>().apply {
            if ("user-agent" !in existingHeadersLower) {
                put("User-Agent", NetworkClient.USER_AGENT)
            }
            video.headers?.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) put(k, v)
            }
        }

        runCatching {
            val headRequest = Request.Builder()
                .url(rawUrl)
                .head()
                .apply {
                    mergedHeaders.forEach { (k, v) -> header(k, v) }
                }
                .build()

            headClient.newCall(headRequest).execute().use { response ->
                val code = response.code
                if (code in 200..399) {
                    val resolvedUrl = resolveEffectiveStreamUrl(rawUrl, response.request.url.toString())
                    return@withContext video.copy(
                        source = resolvedUrl,
                        headers = mergedHeaders,
                    )
                }

                // Some HLS/MP4 edge CDNs disallow HEAD with 405; verify 200..399 with minimal 1-byte range GET
                if (code == 405) {
                    val rangeReq = Request.Builder()
                        .url(rawUrl)
                        .get()
                        .header("Range", "bytes=0-0")
                        .apply {
                            mergedHeaders.forEach { (k, v) -> header(k, v) }
                        }
                        .build()
                    headClient.newCall(rangeReq).execute().use { fallbackResp ->
                        if (fallbackResp.code in 200..399) {
                            val resolvedUrl = resolveEffectiveStreamUrl(rawUrl, fallbackResp.request.url.toString())
                            return@withContext video.copy(
                                source = resolvedUrl,
                                headers = mergedHeaders,
                            )
                        }
                    }
                }

                Log.d(TAG, "Discarding inactive stream ($code): $rawUrl")
                null
            }
        }.onFailure { err ->
            Log.d(TAG, "HEAD validation failed for $rawUrl: ${err.message}")
        }.getOrNull()
    }

    private fun resolveEffectiveStreamUrl(originalUrl: String, finalRedirectedUrl: String): String {
        // When NoTorrent2 /redirect?p=... redirects to the actual CDN URL, adopt the resolved URL
        return if (originalUrl.contains("/redirect?") && finalRedirectedUrl.startsWith("http")) {
            finalRedirectedUrl
        } else {
            originalUrl
        }
    }
}
