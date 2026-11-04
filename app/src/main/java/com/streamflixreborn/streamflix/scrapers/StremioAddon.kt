package com.streamflixreborn.streamflix.scrapers

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient

/**
 * Cliente mínimo del protocolo **Stremio addon** (v3): `manifest.json`,
 * `catalog/{type}/{id}[/extra].json`, `meta/{type}/{id}.json` y
 * `stream/{type}/{id}.json`.
 *
 * Sirve para cualquier addon compatible (Cinemeta, catálogos de plataformas,
 * agregadores…) sin escribir un provider por cada uno. Sólo devuelve los
 * streams que la app puede reproducir: **HLS (.m3u8) o MP4** — igual que el
 * resto de providers ([StreamLinks]).
 */
object StremioAddon {

    private const val TAG = "StremioAddon"

    private val gson = Gson()

    data class Manifest(
        val id: String = "",
        val version: String = "",
        val name: String = "",
        val description: String? = null,
        val logo: String? = null,
        val background: String? = null,
        val resources: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val idPrefixes: List<String>? = null,
        val catalogs: List<Catalog> = emptyList(),
    ) {
        val baseId: String get() = id
        fun has(resource: String): Boolean = resources.any { it == resource || it.startsWith("$resource,") }
    }

    data class Catalog(
        val type: String = "movie",
        val id: String = "",
        val name: String? = null,
        val genres: List<String>? = null,
        val extra: List<Extra>? = null,
    ) {
        val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
    }

    data class Extra(
        val name: String = "",
        val isRequired: Boolean = false,
        val options: List<String>? = null,
    )

    /** Un `meta` de Stremio, normalizado a lo que pinta la app. */
    data class Meta(
        val id: String = "",
        val type: String = "movie",
        val name: String = "",
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val imdbRating: String? = null,
        val runtime: String? = null,
        val genres: List<String> = emptyList(),
        val videos: List<MetaVideo> = emptyList(),
        val moviedbId: Int? = null,
        val imdbId: String? = null,
    ) {
        val isSeries: Boolean get() = type == "series"
        val year: String? get() = releaseInfo?.take(4)?.takeIf { it.all(Char::isDigit) }
    }

    data class MetaVideo(
        val id: String = "",
        val season: Int? = null,
        val episode: Int? = null,
        val number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val thumbnail: String? = null,
        val released: String? = null,
        val rating: String? = null,
    )

    /** Stream resuelto listo para `Extractor` / `Video`. */
    data class Stream(
        val url: String,
        val name: String,
        val title: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val type: String? = null,
    )

    // region endpoints

    suspend fun manifest(addonUrl: String): Manifest? {
        val base = normalize(addonUrl)
        val json = HttpScraper.getText(
            url = "$base/manifest.json",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
            attempts = 2,
        ) ?: return null
        return runCatching { gson.fromJson(json, Manifest::class.java) }
            .onFailure { Log.w(TAG, "manifest inválido en $base: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() || it.catalogs.isNotEmpty() }
    }

    suspend fun catalog(
        addonUrl: String,
        type: String,
        catalogId: String,
        skip: Int = 0,
        genre: String? = null,
        search: String? = null,
    ): List<Meta> {
        val base = normalize(addonUrl)
        val extra = buildList {
            if (genre != null) add("genre=${HttpScraper.encode(genre)}")
            if (search != null) add("search=${HttpScraper.encode(search)}")
            if (skip > 0) add("skip=$skip")
        }.joinToString("&")

        val url = if (extra.isEmpty()) {
            "$base/catalog/$type/$catalogId.json"
        } else {
            "$base/catalog/$type/$catalogId/$extra.json"
        }
        return metas(url, base)
    }

    suspend fun meta(addonUrl: String, type: String, id: String): Meta? {
        val base = normalize(addonUrl)
        val json = HttpScraper.getText(
            url = "$base/meta/$type/${HttpScraper.encode(id)}.json",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
            attempts = 2,
        ) ?: return null
        val response = runCatching { gson.fromJson(json, MetaResponse::class.java) }.getOrNull()
        return response?.meta?.toMeta()
    }

    /** Streams del addon que la app puede reproducir (HLS o MP4, http(s)). */
    suspend fun streams(addonUrl: String, type: String, id: String): List<Stream> {
        val base = normalize(addonUrl)
        val json = HttpScraper.getText(
            url = "$base/stream/$type/${HttpScraper.encode(id)}.json",
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
            attempts = 2,
        ) ?: return emptyList()

        val response = runCatching { gson.fromJson(json, StreamResponse::class.java) }.getOrNull()
        return response?.streams.orEmpty().mapNotNull { raw ->
            val url = raw.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            // Sólo HLS o MP4: se descarta DASH/MKV/torrent/infoHash.
            if (!StreamLinks.isDirectMedia(url)) {
                Log.d(TAG, "stream descartado (no HLS/MP4): ${url.take(80)}")
                return@mapNotNull null
            }
            Stream(
                url = url,
                name = raw.name?.takeIf { it.isNotBlank() }
                    ?: raw.title?.takeIf { it.isNotBlank() }
                    ?: StreamLinks.displayName(url),
                title = raw.title,
                headers = buildMap {
                    raw.behaviorHints?.proxyHeaders?.request?.forEach { (key, value) -> put(key, value) }
                    raw.behaviorHints?.headers?.forEach { (key, value) -> put(key, value) }
                    putIfAbsent("User-Agent", NetworkClient.USER_AGENT)
                },
                type = if (url.substringBefore('?').endsWith(".mp4")) "mp4" else "hls",
            )
        }
    }

    private suspend fun metas(url: String, base: String): List<Meta> {
        val json = HttpScraper.getText(
            url = url,
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
            attempts = 2,
        ) ?: return emptyList()
        val response = runCatching { gson.fromJson(json, CatalogResponse::class.java) }.getOrNull()
            ?: return emptyList()
        return response.metas.mapNotNull { it.toMeta() }
    }

    // endregion

    /** Acepta `https://host/.../manifest.json`, con o sin barra final. */
    fun normalize(addonUrl: String): String = addonUrl.trim()
        .removeSuffix("/")
        .removeSuffix("/manifest.json")
        .removeSuffix("/manifest.json/")

    /** Un stream de Stremio convertido a `Video` (con sus headers). */
    fun toVideo(stream: Stream): Video = Video(
        source = stream.url,
        headers = stream.headers.ifEmpty { null },
        type = if (stream.type == "mp4") "video/mp4" else "application/x-mpegURL",
    )

    fun toServer(stream: Stream): Video.Server = Video.Server(
        id = stream.url,
        name = stream.name,
        src = stream.url,
    )

    // region json

    private data class CatalogResponse(val metas: List<RawMeta> = emptyList())
    private data class MetaResponse(val meta: RawMeta? = null)
    private data class StreamResponse(val streams: List<RawStream> = emptyList())

    private data class RawMeta(
        val id: String = "",
        val type: String = "movie",
        val name: String = "",
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        @SerializedName("releaseInfo") val releaseInfo: String? = null,
        @SerializedName("imdbRating") val imdbRating: String? = null,
        val runtime: String? = null,
        val genres: List<String>? = null,
        val genre: List<String>? = null,
        val videos: List<RawVideo> = emptyList(),
        @SerializedName("moviedb_id") val moviedbId: Int? = null,
        @SerializedName("imdb_id") val imdbId: String? = null,
    ) {
        fun toMeta(): Meta? {
            if (id.isBlank()) return null
            return Meta(
                id = id,
                type = type,
                name = name.ifBlank { id },
                poster = poster,
                background = background,
                logo = logo,
                description = description,
                releaseInfo = releaseInfo,
                imdbRating = imdbRating,
                runtime = runtime,
                genres = (genres ?: genre).orEmpty(),
                videos = videos.mapNotNull { video ->
                    video.toVideo(id)
                },
                moviedbId = moviedbId,
                imdbId = imdbId ?: id.takeIf { it.startsWith("tt") },
            )
        }
    }

    private data class RawVideo(
        val id: String = "",
        val season: Int? = null,
        val episode: Int? = null,
        val number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val description: String? = null,
        val thumbnail: String? = null,
        val released: String? = null,
        val firstAired: String? = null,
        val rating: String? = null,
    ) {
        fun toVideo(parentId: String): MetaVideo? {
            val number = episode ?: number ?: return null
            return MetaVideo(
                id = id.ifBlank { "$parentId:${season ?: 1}:$number" },
                season = season ?: 1,
                episode = number,
                number = number,
                name = name,
                overview = overview ?: description,
                thumbnail = thumbnail,
                released = released ?: firstAired,
                rating = rating,
            )
        }
    }

    private data class RawStream(
        val url: String? = null,
        val name: String? = null,
        val title: String? = null,
        @SerializedName("behaviorHints") val behaviorHints: BehaviorHints? = null,
    )

    private data class BehaviorHints(
        @SerializedName("proxyHeaders") val proxyHeaders: RequestHeaders? = null,
        val headers: Map<String, String>? = null,
    )

    private data class RequestHeaders(val request: Map<String, String>? = null)

    // endregion
}
