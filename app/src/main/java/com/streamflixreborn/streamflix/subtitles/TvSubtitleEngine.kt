package com.streamflixreborn.streamflix.subtitles

import android.content.Context
import androidx.compose.runtime.Immutable
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.addons.CustomAddonRepository
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.stremio.StremioSubtitle
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
private data class StremioSubtitlesResponse(
    val subtitles: List<StremioSubtitle> = emptyList(),
)

@Immutable
data class ResolvedExternalSubtitle(
    val id: String,
    val label: String,
    val langCode: String,
    val uriOrUrl: String,
    val isSpanishLatino: Boolean,
    val isSpanish: Boolean,
)

/**
 * Automatic subtitle engine connecting to OpenSubtitles (Stremio v3 + OpenSubtitles REST API)
 * and user-installed Stremio subtitle add-ons to download Spanish/Latino subtitles cleanly
 * under the strict Zero-Provider UI policy.
 */
object TvSubtitleEngine {

    private const val OPENSUBTITLES_STREMIO_BASE = "https://opensubtitles-v3.strem.io"
    private const val FETCH_TIMEOUT_MS = 4_500L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Resolves Spanish/Latino (and fallback) external subtitles for a movie or episode.
     */
    suspend fun fetchSubtitlesForSession(
        imdbId: String?,
        mediaKind: TvMediaKind,
        seasonNumber: Int,
        episodeNumber: Int,
        context: Context = StreamFlixApp.instance,
    ): List<ResolvedExternalSubtitle> = withContext(Dispatchers.IO) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: return@withContext emptyList()
        val stremioType = if (mediaKind == TvMediaKind.SERIES) "series" else "movie"
        val stremioVideoId = if (mediaKind == TvMediaKind.SERIES) {
            "$cleanImdb:${seasonNumber.coerceAtLeast(1)}:${episodeNumber.coerceAtLeast(1)}"
        } else {
            cleanImdb
        }

        coroutineScope {
            // 1. Official OpenSubtitles v3 Stremio endpoint + enabled custom subtitle add-ons
            val addonBases = buildList {
                add(OPENSUBTITLES_STREMIO_BASE)
                CustomAddonRepository.getEnabledCustomAddons(context)
                    .filter { CustomAddonRepository.supportsResource(it, "subtitles") }
                    .forEach { add(it.baseUrl) }
            }.distinct()

            val addonTasks = addonBases.map { base ->
                async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                        fetchFromStremioSubtitlesEndpoint(base, stremioType, stremioVideoId)
                    }.orEmpty()
                }
            }

            // 2. Direct OpenSubtitles REST API (`spl` Español Latino + `spa` Español)
            val restLatinoTask = async {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    fetchFromOpenSubtitlesRest(
                        numericImdbId = cleanImdb.removePrefix("tt"),
                        mediaKind = mediaKind,
                        season = seasonNumber,
                        episode = episodeNumber,
                        subLang = "spl",
                        isLatino = true,
                    )
                }.orEmpty()
            }

            val restSpanishTask = async {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    fetchFromOpenSubtitlesRest(
                        numericImdbId = cleanImdb.removePrefix("tt"),
                        mediaKind = mediaKind,
                        season = seasonNumber,
                        episode = episodeNumber,
                        subLang = "spa",
                        isLatino = false,
                    )
                }.orEmpty()
            }

            val combined = (addonTasks.awaitAll().flatten() + restLatinoTask.await() + restSpanishTask.await())
                .filter { it.uriOrUrl.isNotBlank() }
                .distinctBy { it.uriOrUrl }

            // Prioritize Español Latino -> Español (España/General) -> English fallback, max 8 clean tracks
            val sorted = combined.sortedWith(
                compareByDescending<ResolvedExternalSubtitle> { it.isSpanishLatino }
                    .thenByDescending { it.isSpanish }
            ).take(8)

            // Assign clean numbered Zero-Provider Spanish labels
            var latinoCounter = 0
            var esCounter = 0
            var otherCounter = 0

            sorted.map { item ->
                val cleanLabel = when {
                    item.isSpanishLatino -> {
                        latinoCounter += 1
                        if (latinoCounter == 1) "Español (Latinoamérica)" else "Español (Latinoamérica) #$latinoCounter"
                    }
                    item.isSpanish -> {
                        esCounter += 1
                        if (esCounter == 1) "Español" else "Español #$esCounter"
                    }
                    else -> {
                        otherCounter += 1
                        if (otherCounter == 1) item.label else "${item.label} #$otherCounter"
                    }
                }
                item.copy(label = cleanLabel)
            }
        }
    }

    /**
     * Loads and parses a local `file://` or remote `http(s)://` `.srt`/`.vtt` subtitle into `TimedSubtitleCue`s
     * so `AppleTvPlayerScreen` can render cues with live millisecond Sync Offset (`±ms`).
     */
    suspend fun loadParsedCues(
        uriOrUrl: String,
    ): List<TimedSubtitleCue> = withContext(Dispatchers.IO) {
        if (uriOrUrl.isBlank()) return@withContext emptyList()
        runCatching {
            val rawBytes: ByteArray = if (uriOrUrl.startsWith("file://")) {
                val path = uriOrUrl.removePrefix("file://")
                File(path).readBytes()
            } else if (uriOrUrl.startsWith("/")) {
                File(uriOrUrl).readBytes()
            } else {
                val req = Request.Builder()
                    .url(uriOrUrl)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use ByteArray(0)
                    resp.body?.bytes() ?: ByteArray(0)
                }
            }
            if (rawBytes.isEmpty()) return@runCatching emptyList()

            val decodedText = decodeSubtitleCharset(rawBytes)
            SubtitleCueParser.parse(decodedText)
        }.getOrDefault(emptyList())
    }

    private fun fetchFromStremioSubtitlesEndpoint(
        baseUrl: String,
        type: String,
        videoId: String,
    ): List<ResolvedExternalSubtitle> {
        return runCatching {
            val encodedId = videoId.replace(":", "%3A")
            val url = "${baseUrl.trimEnd('/')}/subtitles/$type/$encodedId.json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList()
                val parsed = StremioAddonsClient.json.decodeFromString<StremioSubtitlesResponse>(body)
                parsed.subtitles
                    .filter { sub -> sub.url.startsWith("http") && isRelevantSubtitleLang(sub.lang) }
                    .take(8)
                    .mapIndexed { idx, sub ->
                        val normLang = sub.lang.trim().lowercase(Locale.ROOT)
                        val isLatino = normLang in setOf("spl", "es-la", "es-mx", "es-419", "lat", "pob-es")
                        val isEs = isLatino || normLang.startsWith("es") || normLang in setOf("spa", "spn", "spanish")
                        ResolvedExternalSubtitle(
                            id = sub.id?.takeIf { it.isNotBlank() } ?: "stremio_sub_${idx}_${sub.url.hashCode()}",
                            label = if (isEs) "Español" else "Inglés",
                            langCode = if (isLatino) "es-419" else if (isEs) "es" else "en",
                            uriOrUrl = sub.url,
                            isSpanishLatino = isLatino,
                            isSpanish = isEs,
                        )
                    }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchFromOpenSubtitlesRest(
        numericImdbId: String,
        mediaKind: TvMediaKind,
        season: Int,
        episode: Int,
        subLang: String,
        isLatino: Boolean,
    ): List<ResolvedExternalSubtitle> {
        return runCatching {
            val results = OpenSubtitles.search(
                imdbId = numericImdbId,
                season = if (mediaKind == TvMediaKind.SERIES) season.coerceAtLeast(1) else null,
                episode = if (mediaKind == TvMediaKind.SERIES) episode.coerceAtLeast(1) else null,
                subLanguageId = subLang,
            )
                .filter { sub ->
                    sub.subDownloadLink.isNotBlank() &&
                        (sub.subFormat?.lowercase(Locale.ROOT) in setOf("srt", "vtt", null))
                }
                .sortedByDescending { it.subDownloadsCnt?.toIntOrNull() ?: 0 }
                .take(2)

            results.mapNotNull { sub ->
                val localUri = runCatching { OpenSubtitles.download(sub) }.getOrNull() ?: return@mapNotNull null
                ResolvedExternalSubtitle(
                    id = "os_rest_${sub.idSubtitleFile ?: localUri.hashCode()}",
                    label = if (isLatino) "Español (Latinoamérica)" else "Español",
                    langCode = if (isLatino) "es-419" else "es",
                    uriOrUrl = localUri.toString(),
                    isSpanishLatino = isLatino,
                    isSpanish = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun isRelevantSubtitleLang(rawLang: String): Boolean {
        val norm = rawLang.trim().lowercase(Locale.ROOT)
        return norm in setOf("spa", "spl", "spn", "es", "es-es", "es-la", "es-mx", "es-419", "spanish", "eng", "en")
    }

    private fun decodeSubtitleCharset(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // Fallback to Windows-1252 / ISO-8859-1 if classic Spanish SRT contains replacement characters
        return if (utf8.contains('\uFFFD')) {
            runCatching { String(bytes, Charset.forName("windows-1252")) }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        } else {
            utf8
        }
    }
}
