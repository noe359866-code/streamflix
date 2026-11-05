package com.streamflixreborn.streamflix.stremio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class StremioCatalogResponse(
    val metas: List<StremioMeta> = emptyList(),
)

@Serializable
data class StremioMetaResponse(
    val meta: StremioMeta? = null,
)

@Serializable
data class StremioMeta(
    val id: String = "",
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("moviedb_id")
    val movieDbId: JsonElement? = null,
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    val year: JsonElement? = null,
    val runtime: JsonElement? = null,
    val imdbRating: JsonElement? = null,
    val genres: List<String> = emptyList(),
    val genre: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val country: String? = null,
    val language: String? = null,
    val trailers: List<StremioTrailer> = emptyList(),
    val links: List<StremioLink> = emptyList(),
    val videos: List<StremioVideo> = emptyList(),
    @SerialName("app_extras")
    val appExtras: StremioAppExtras? = null,
    val behaviorHints: StremioBehaviorHints? = null,
) {
    val resolvedImdbId: String?
        get() = imdbId?.takeIf { it.startsWith("tt") }
            ?: id.takeIf { it.startsWith("tt") }
            ?: links.firstOrNull { it.url.contains("imdb.com/title/tt") }
                ?.url
                ?.substringAfter("imdb.com/title/")
                ?.substringBefore("/")
                ?.takeIf { it.startsWith("tt") }

    val resolvedRating: Double?
        get() = imdbRating.asContentString()?.toDoubleOrNull()
            ?: appExtras?.ratings?.imdb?.rating.asContentString()?.toDoubleOrNull()
            ?: appExtras?.ratings?.tmdb?.rating.asContentString()?.toDoubleOrNull()

    val resolvedYear: String?
        get() {
            val raw = releaseInfo?.takeIf { it.isNotBlank() }
                ?: year.asContentString()?.takeIf { it.isNotBlank() }
                ?: released?.take(4)?.takeIf { it.length == 4 }
            return raw?.let { Regex("(19|20)\\d{2}").find(it)?.value ?: it.take(4) }
        }

    val resolvedRuntimeMinutes: Int?
        get() {
            val raw = runtime.asContentString() ?: return null
            val hoursMatch = Regex("(\\d+)\\s*h", RegexOption.IGNORE_CASE).find(raw)
            val minsMatch = Regex("(\\d+)\\s*m", RegexOption.IGNORE_CASE).find(raw)
            if (hoursMatch != null || minsMatch != null) {
                val h = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val m = minsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val total = h * 60 + m
                if (total > 0) return total
            }
            return Regex("\\d+").find(raw)?.value?.toIntOrNull()
        }

    val resolvedGenres: List<String>
        get() = (genres + genre).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

@Serializable
data class StremioVideo(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val released: String? = null,
    val firstAired: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val seasonPoster: String? = null,
) {
    val resolvedEpisodeNumber: Int
        get() = episode ?: number ?: id.substringAfterLast(":").toIntOrNull() ?: 0

    val resolvedSeasonNumber: Int
        get() = season ?: id.split(":").getOrNull(1)?.toIntOrNull() ?: 1

    val resolvedTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: "Episodio $resolvedEpisodeNumber"

    val resolvedOverview: String?
        get() = overview?.takeIf { it.isNotBlank() }
            ?: description?.takeIf { it.isNotBlank() }
}

@Serializable
data class StremioTrailer(
    val source: String? = null,
    val type: String? = null,
)

@Serializable
data class StremioLink(
    val name: String = "",
    val category: String = "",
    val url: String = "",
)

@Serializable
data class StremioAppExtras(
    val cast: List<StremioCastMember> = emptyList(),
    val directors: List<StremioCastMember> = emptyList(),
    val ratings: StremioRatings? = null,
    val seasonPosters: List<String> = emptyList(),
)

@Serializable
data class StremioCastMember(
    val name: String = "",
    val character: String? = null,
    val photo: String? = null,
)

@Serializable
data class StremioRatings(
    val tmdb: StremioRatingValue? = null,
    val imdb: StremioRatingValue? = null,
)

@Serializable
data class StremioRatingValue(
    val rating: JsonElement? = null,
    val votes: Int? = null,
)

@Serializable
data class StremioBehaviorHints(
    val defaultVideoId: String? = null,
)

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream> = emptyList(),
)

@Serializable
data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val externalUrl: String? = null,
    val infoHash: String? = null,
    val subtitles: List<StremioSubtitle> = emptyList(),
    val behaviorHints: StremioStreamBehaviorHints? = null,
)

@Serializable
data class StremioSubtitle(
    val id: String? = null,
    val url: String = "",
    val lang: String = "",
)

@Serializable
data class StremioStreamBehaviorHints(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: StremioProxyHeaders? = null,
)

@Serializable
data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap(),
)

internal fun JsonElement?.asContentString(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}
