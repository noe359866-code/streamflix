package com.streamflixreborn.streamflix.stremio

import android.util.Log
import android.util.LruCache
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@kotlinx.serialization.Serializable
data class NuvioAvatarAsset(
    val id: String,
    val label: String,
    val imageUrl: String,
    val accentColorHex: String,
)

data class MediaMetadataHint(
    val id: String,
    val imdbId: String? = null,
    val spanishTitle: String? = null,
    val englishTitle: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val country: String? = null,
    val language: String? = null,
    val logo: String? = null,
) {
    val isDoramaOrAsian: Boolean
        get() {
            val countryUpper = country?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (countryUpper in ASIAN_COUNTRY_CODES ||
                ASIAN_COUNTRY_KEYWORDS.any { countryUpper.contains(it) }
            ) {
                return true
            }

            val langLower = language?.trim()?.lowercase(Locale.ROOT)?.substringBefore("-").orEmpty()
            if (langLower in ASIAN_LANGUAGE_CODES) {
                return true
            }

            return genres.any { genre ->
                val g = genre.lowercase(Locale.ROOT)
                DORAMA_GENRE_KEYWORDS.any { g.contains(it) }
            }
        }

    val isAnimationOrAnime: Boolean
        get() = genres.any { genre ->
            val g = genre.lowercase(Locale.ROOT)
            ANIMATION_GENRE_KEYWORDS.any { g.contains(it) }
        }

    fun candidateSearchTitles(fallbackTitle: String? = null): List<String> {
        return listOfNotNull(
            spanishTitle?.trim()?.takeIf { it.isNotEmpty() },
            englishTitle?.trim()?.takeIf { it.isNotEmpty() },
            fallbackTitle?.trim()?.takeIf { it.isNotEmpty() },
        ).distinctBy { it.lowercase(Locale.ROOT) }
    }

    companion object {
        private val ASIAN_COUNTRY_CODES = setOf("KR", "JP", "CN", "TW", "TH", "HK", "SG")
        private val ASIAN_COUNTRY_KEYWORDS = listOf(
            "KOREA", "COREA", "JAPAN", "JAPON", "JAPÓN",
            "CHINA", "TAIWAN", "TAIWÁN", "THAILAND", "TAILANDIA"
        )
        private val ASIAN_LANGUAGE_CODES = setOf("ko", "ja", "zh", "th", "kor", "jpn", "zho", "tha")
        private val DORAMA_GENRE_KEYWORDS = listOf(
            "dorama", "k-drama", "kdrama", "j-drama", "cdrama", "c-drama",
            "corean", "asiátic", "asiatic", "korean", "lakorn"
        )
        private val ANIMATION_GENRE_KEYWORDS = listOf(
            "animation", "animación", "animacion", "anime", "cartoon", "caricatura"
        )
    }
}

object StremioAddonsClient {

    private const val TAG = "StremioAddonsClient"

    const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
    const val NUVIO_CONFIG_JSON = """{"language":"es-MX","region":"US"}"""
    const val NUVIO_BASE =
        "https://catalog.nuvio.tv/%7B%22language%22%3A%22es-MX%22%2C%22region%22%3A%22US%22%7D"
    const val NOTORRENT2_BASE = "https://addon.notorrent2.workers.dev"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "es-MX,es-419;q=0.95,es-ES;q=0.9,es;q=0.85")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private val hintsById = ConcurrentHashMap<String, MediaMetadataHint>()
    private val metaCache = object : LruCache<String, StremioMeta>(250) {}
    private val catalogCache = object : LruCache<String, List<StremioMeta>>(80) {}

    fun getHint(idOrImdbId: String?): MediaMetadataHint? {
        if (idOrImdbId.isNullOrBlank()) return null
        return hintsById[idOrImdbId]
    }

    fun recordHint(
        primaryId: String,
        nuvioMeta: StremioMeta? = null,
        cinemetaMeta: StremioMeta? = null,
    ): MediaMetadataHint {
        val existing = hintsById[primaryId]
            ?: nuvioMeta?.resolvedImdbId?.let { hintsById[it] }
            ?: cinemetaMeta?.resolvedImdbId?.let { hintsById[it] }

        val imdbId = nuvioMeta?.resolvedImdbId
            ?: cinemetaMeta?.resolvedImdbId
            ?: existing?.imdbId
            ?: primaryId.takeIf { it.startsWith("tt") }

        val spanishTitle = nuvioMeta?.name?.takeIf { it.isNotBlank() }
            ?: existing?.spanishTitle
        val englishTitle = cinemetaMeta?.name?.takeIf { it.isNotBlank() }
            ?: existing?.englishTitle
        val year = nuvioMeta?.resolvedYear
            ?: cinemetaMeta?.resolvedYear
            ?: existing?.year
        val genres = ((nuvioMeta?.resolvedGenres ?: emptyList()) +
            (cinemetaMeta?.resolvedGenres ?: emptyList()) +
            (existing?.genres ?: emptyList()))
            .distinct()
        val country = nuvioMeta?.country?.takeIf { it.isNotBlank() }
            ?: cinemetaMeta?.country?.takeIf { it.isNotBlank() }
            ?: existing?.country
        val language = nuvioMeta?.language?.takeIf { it.isNotBlank() }
            ?: cinemetaMeta?.language?.takeIf { it.isNotBlank() }
            ?: existing?.language
        val logo = nuvioMeta?.logo?.takeIf { it.isNotBlank() }
            ?: cinemetaMeta?.logo?.takeIf { it.isNotBlank() }
            ?: existing?.logo

        val merged = MediaMetadataHint(
            id = imdbId ?: primaryId,
            imdbId = imdbId,
            spanishTitle = spanishTitle,
            englishTitle = englishTitle,
            year = year,
            genres = genres,
            country = country,
            language = language,
            logo = logo,
        )

        hintsById[primaryId] = merged
        if (!imdbId.isNullOrBlank()) {
            hintsById[imdbId] = merged
        }
        nuvioMeta?.id?.takeIf { it.isNotBlank() }?.let { hintsById[it] = merged }
        cinemetaMeta?.id?.takeIf { it.isNotBlank() }?.let { hintsById[it] = merged }
        return merged
    }

    suspend fun getNuvioCatalog(
        type: String,
        catalogId: String,
        skip: Int = 0,
        extra: String? = null,
    ): List<StremioMeta> {
        val extraParts = buildList {
            if (!extra.isNullOrBlank()) add(extra)
            if (skip > 0) add("skip=$skip")
        }
        val suffix = if (extraParts.isEmpty()) {
            ".json"
        } else {
            "/${extraParts.joinToString("&")}.json"
        }
        val url = "$NUVIO_BASE/catalog/$type/$catalogId$suffix"
        return fetchCatalog(url, isNuvio = true)
    }

    suspend fun getCinemetaCatalog(
        type: String,
        catalogId: String,
        skip: Int = 0,
        searchQuery: String? = null,
        genre: String? = null,
    ): List<StremioMeta> {
        val extraParts = buildList {
            if (!searchQuery.isNullOrBlank()) {
                add("search=${encodePathParam(searchQuery)}")
            }
            if (!genre.isNullOrBlank()) {
                add("genre=${encodePathParam(genre)}")
            }
            if (skip > 0) {
                add("skip=$skip")
            }
        }
        val suffix = if (extraParts.isEmpty()) {
            ".json"
        } else {
            "/${extraParts.joinToString("&")}.json"
        }
        val url = "$CINEMETA_BASE/catalog/$type/$catalogId$suffix"
        return fetchCatalog(url, isNuvio = false)
    }

    suspend fun searchNuvio(
        type: String,
        query: String,
    ): List<StremioMeta> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val encoded = encodePathParam(clean)
        val searchCatalogId = if (type == "series") "tmdb-search-series" else "tmdb-search-movies"
        val direct = getNuvioCatalog(type = type, catalogId = searchCatalogId, extra = "search=$encoded")
        if (direct.isNotEmpty()) return direct
        val fallbackCatalogId = if (type == "series") "popular-series" else "popular-movies"
        return getNuvioCatalog(type = type, catalogId = fallbackCatalogId, extra = "search=$encoded")
    }

    suspend fun searchCinemeta(
        type: String,
        query: String,
    ): List<StremioMeta> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        return getCinemetaCatalog(type = type, catalogId = "top", searchQuery = clean)
    }

    suspend fun getNuvioMeta(type: String, id: String): StremioMeta? {
        val cacheKey = "nuvio:$type:$id"
        synchronized(metaCache) { metaCache.get(cacheKey) }?.let { return it }

        val encodedId = encodeAddonId(id)
        val url = "$NUVIO_BASE/meta/$type/$encodedId.json"
        val body = getJsonString(url) ?: return null
        val meta = runCatching {
            json.decodeFromString<StremioMetaResponse>(body).meta
        }.onFailure {
            Log.w(TAG, "Failed decoding Nuvio meta ($type/$id): ${it.message}")
        }.getOrNull() ?: return null

        synchronized(metaCache) { metaCache.put(cacheKey, meta) }
        recordHint(id, nuvioMeta = meta)
        return meta
    }

    suspend fun getCinemetaMeta(type: String, id: String): StremioMeta? {
        if (!id.startsWith("tt")) return null
        val cacheKey = "cinemeta:$type:$id"
        synchronized(metaCache) { metaCache.get(cacheKey) }?.let { return it }

        val url = "$CINEMETA_BASE/meta/$type/${encodeAddonId(id)}.json"
        val body = getJsonString(url) ?: return null
        val meta = runCatching {
            json.decodeFromString<StremioMetaResponse>(body).meta
        }.onFailure {
            Log.w(TAG, "Failed decoding Cinemeta meta ($type/$id): ${it.message}")
        }.getOrNull() ?: return null

        synchronized(metaCache) { metaCache.put(cacheKey, meta) }
        recordHint(id, cinemetaMeta = meta)
        return meta
    }

    suspend fun getMergedMeta(type: String, id: String): Pair<StremioMeta?, StremioMeta?> =
        coroutineScope {
            if (id.startsWith("tt")) {
                val nuvioDeferred = async { getNuvioMeta(type, id) }
                val cinemetaDeferred = async { getCinemetaMeta(type, id) }
                val nuvio = nuvioDeferred.await()
                val cinemeta = cinemetaDeferred.await()
                recordHint(id, nuvioMeta = nuvio, cinemetaMeta = cinemeta)
                nuvio to cinemeta
            } else {
                val nuvio = getNuvioMeta(type, id)
                val imdbId = nuvio?.resolvedImdbId ?: getHint(id)?.imdbId
                val cinemeta = if (!imdbId.isNullOrBlank()) {
                    getCinemetaMeta(type, imdbId)
                } else {
                    null
                }
                recordHint(id, nuvioMeta = nuvio, cinemetaMeta = cinemeta)
                nuvio to cinemeta
            }
        }

    private val NUVIO_AVATAR_ACCENT_COLORS = listOf(
        "#E53935",
        "#1E88E5",
        "#8E24AA",
        "#43A047",
        "#FFB300",
        "#D81B60",
        "#00ACC1",
        "#5E35B1",
    )

    @Volatile
    private var cachedNuvioAvatars: List<NuvioAvatarAsset> = emptyList()

    /**
     * Extracts graphical profile avatars directly from the Nuvio Catalog add-on ecosystem
     * (`catalog.nuvio.tv` Animation & Family character catalogs + `app_extras.cast` portraits).
     */
    suspend fun getNuvioProfileAvatars(): List<NuvioAvatarAsset> = coroutineScope {
        if (cachedNuvioAvatars.isNotEmpty()) return@coroutineScope cachedNuvioAvatars

        val animMoviesDef = async {
            getNuvioCatalog("movie", "popular-movies", extra = "genre=Animation")
        }
        val animSeriesDef = async {
            getNuvioCatalog("series", "popular-series", extra = "genre=Animation")
        }
        val trendingMoviesDef = async {
            getNuvioCatalog("movie", "trending-movies")
        }

        val animMovies = animMoviesDef.await()
        val animSeries = animSeriesDef.await()
        val trendingMovies = trendingMoviesDef.await()

        // Fetch cast/character portraits from top Nuvio meta entries when available
        val castPortraits = trendingMovies.take(3).flatMap { meta ->
            val detailed = runCatching { getNuvioMeta("movie", meta.id) }.getOrNull()
            detailed?.appExtras?.cast.orEmpty()
                .filter { !it.photo.isNullOrBlank() }
                .take(3)
                .mapIndexed { idx, member ->
                    NuvioAvatarAsset(
                        id = "nuvio_cast_${meta.id}_$idx",
                        label = member.character?.takeIf { it.isNotBlank() } ?: member.name,
                        imageUrl = member.photo.orEmpty().replace("/t/p/original/", "/t/p/w342/"),
                        accentColorHex = NUVIO_AVATAR_ACCENT_COLORS[idx % NUVIO_AVATAR_ACCENT_COLORS.size],
                    )
                }
        }

        val animatedIcons = (animMovies.take(10) + animSeries.take(8))
            .mapIndexedNotNull { idx, meta ->
                val rawImg = meta.poster?.takeIf { it.isNotBlank() }
                    ?: meta.background?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                val compactImg = rawImg
                    .replace("/t/p/original/", "/t/p/w342/")
                    .replace("/t/p/w780/", "/t/p/w342/")
                    .replace("/t/p/w500/", "/t/p/w342/")
                NuvioAvatarAsset(
                    id = "nuvio_art_${meta.id}",
                    label = meta.name.ifBlank { "Avatar ${idx + 1}" },
                    imageUrl = compactImg,
                    accentColorHex = NUVIO_AVATAR_ACCENT_COLORS[idx % NUVIO_AVATAR_ACCENT_COLORS.size],
                )
            }

        val combined = (animatedIcons + castPortraits)
            .distinctBy { it.imageUrl }
            .take(20)

        if (combined.isNotEmpty()) {
            cachedNuvioAvatars = combined
        }
        combined
    }

    suspend fun getNoTorrent2Streams(
        type: String,
        streamId: String,
        timeoutMs: Long = 3_500L,
    ): List<StremioStream> = withContext(Dispatchers.IO) {
        val normalizedId = normalizeNoTorrentId(streamId)
        val url = "$NOTORRENT2_BASE/stream/$type/${encodeAddonId(normalizedId)}.json"
        val client = httpClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).get().build()
        val body = runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext emptyList()

        runCatching {
            json.decodeFromString<StremioStreamResponse>(body).streams
        }.onFailure {
            Log.w(TAG, "Failed decoding NoTorrent2 streams ($type/$normalizedId): ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Asynchronously validates that a candidate URL points to a legitimate, reachable
     * Stremio `manifest.json` endpoint (`id`, `name`, `version`, `resources`/`catalogs`, `types`).
     */
    suspend fun validateAndFetchManifest(rawManifestUrl: String): Result<ValidatedStremioAddon> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = rawManifestUrl.trim()
                require(trimmed.isNotEmpty()) { "La URL del manifiesto está vacía." }

                val withHttpScheme = when {
                    trimmed.startsWith("stremio://", ignoreCase = true) ->
                        "https://" + trimmed.substring("stremio://".length)
                    trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                    else -> "https://$trimmed"
                }

                val withoutQuery = withHttpScheme.substringBefore("?").substringBefore("#").trimEnd('/')
                val normalizedManifestUrl = if (withoutQuery.endsWith(".json", ignoreCase = true)) {
                    withHttpScheme
                } else {
                    "$withoutQuery/manifest.json"
                }

                val request = Request.Builder()
                    .url(normalizedManifestUrl)
                    .get()
                    .build()

                val body = httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        throw IllegalArgumentException(
                            "El servidor respondió con código HTTP ${response.code}."
                        )
                    }
                    response.body?.string()
                } ?: throw IllegalArgumentException("Respuesta vacía al consultar manifest.json.")

                val manifest = runCatching {
                    json.decodeFromString<StremioAddonManifest>(body)
                }.getOrElse {
                    throw IllegalArgumentException("El archivo no tiene un formato JSON válido de Stremio.")
                }

                require(manifest.isValidStremioManifest()) {
                    "El manifiesto carece de la estructura obligatoria de Stremio (id, name, version, resources/types)."
                }

                val cleanUrlNoQuery = normalizedManifestUrl.substringBefore("?").substringBefore("#")
                val baseUrl = if (cleanUrlNoQuery.endsWith("/manifest.json", ignoreCase = true)) {
                    cleanUrlNoQuery.dropLast("/manifest.json".length).trimEnd('/')
                } else {
                    cleanUrlNoQuery.substringBeforeLast("/").trimEnd('/')
                }

                ValidatedStremioAddon(
                    manifest = manifest,
                    manifestUrl = normalizedManifestUrl,
                    baseUrl = baseUrl,
                )
            }
        }

    suspend fun getCustomAddonCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int = 0,
        searchQuery: String? = null,
    ): List<StremioMeta> {
        val cleanBase = baseUrl.trimEnd('/')
        val extraParts = buildList {
            if (!searchQuery.isNullOrBlank()) {
                add("search=${encodePathParam(searchQuery)}")
            }
            if (skip > 0) {
                add("skip=$skip")
            }
        }
        val suffix = if (extraParts.isEmpty()) {
            ".json"
        } else {
            "/${extraParts.joinToString("&")}.json"
        }
        val url = "$cleanBase/catalog/$type/${encodeAddonId(catalogId)}$suffix"
        return fetchCatalog(url, isNuvio = false)
    }

    suspend fun getCustomAddonStreams(
        baseUrl: String,
        type: String,
        streamId: String,
        timeoutMs: Long = 3_500L,
    ): List<StremioStream> = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trimEnd('/')
        val url = "$cleanBase/stream/$type/${encodeAddonId(streamId)}.json"
        val client = httpClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).get().build()
        val body = runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext emptyList()

        runCatching {
            json.decodeFromString<StremioStreamResponse>(body).streams
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchCatalog(url: String, isNuvio: Boolean): List<StremioMeta> {
        synchronized(catalogCache) { catalogCache.get(url) }?.let { return it }

        val body = getJsonString(url) ?: return emptyList()
        val metas = runCatching {
            json.decodeFromString<StremioCatalogResponse>(body).metas
        }.onFailure {
            Log.w(TAG, "Failed decoding Stremio catalog ($url): ${it.message}")
        }.getOrDefault(emptyList())

        metas.forEach { meta ->
            val key = meta.resolvedImdbId ?: meta.id
            if (key.isNotBlank()) {
                if (isNuvio) {
                    recordHint(key, nuvioMeta = meta)
                } else {
                    recordHint(key, cinemetaMeta = meta)
                }
            }
        }

        if (metas.isNotEmpty()) {
            synchronized(catalogCache) { catalogCache.put(url, metas) }
        }
        return metas
    }

    private suspend fun getJsonString(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.onFailure {
            Log.w(TAG, "HTTP error fetching $url: ${it.message}")
        }.getOrNull()
    }

    private fun normalizeNoTorrentId(rawId: String): String {
        return if (rawId.startsWith("tmdb:")) {
            "tmdb_" + rawId.removePrefix("tmdb:")
        } else {
            rawId
        }
    }

    private fun encodeAddonId(id: String): String {
        return URLEncoder.encode(id, "UTF-8")
            .replace("+", "%20")
            .replace("%3A", ":")
    }

    private fun encodePathParam(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
