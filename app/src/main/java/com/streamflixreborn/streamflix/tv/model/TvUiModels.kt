package com.streamflixreborn.streamflix.tv.model

import androidx.compose.runtime.Immutable
import com.streamflixreborn.streamflix.models.Video

enum class TvTopSection(val title: String) {
    HOME("Inicio"),
    MOVIES("Películas"),
    SERIES("Series"),
    SEARCH("Buscar"),
    ADDONS("Add-ons"),
    SETTINGS("Ajustes"),
}

enum class TvMediaKind {
    MOVIE,
    SERIES,
}

@Immutable
data class TvMediaCardItem(
    val id: String,
    val imdbId: String?,
    val title: String,
    val overview: String?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val logoUrl: String?,
    val year: String?,
    val rating: String?,
    val genres: List<String>,
    val kind: TvMediaKind,
    val isDoramaOrAsian: Boolean = false,
)

@Immutable
data class TvShelfModel(
    val id: String,
    val title: String,
    val items: List<TvMediaCardItem>,
)

@Immutable
data class TvEpisodeModel(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String?,
    val thumbnailUrl: String?,
    val released: String?,
)

@Immutable
data class TvSeasonModel(
    val id: String,
    val number: Int,
    val title: String,
)

@Immutable
data class TvMediaDetail(
    val id: String,
    val imdbId: String?,
    val kind: TvMediaKind,
    val title: String,
    val originalTitle: String?,
    val overview: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val logoUrl: String?,
    val year: String?,
    val runtimeFormatted: String?,
    val rating: String?,
    val genres: List<String>,
    val castNames: List<String>,
    val seasons: List<TvSeasonModel>,
    val selectedSeasonNumber: Int,
    val episodes: List<TvEpisodeModel>,
    val isDoramaOrAsian: Boolean,
)

@Immutable
data class TvPlaybackSessionRequest(
    val videoType: Video.Type,
    val contentId: String,
    val imdbId: String?,
    val mediaKind: TvMediaKind,
    val displayTitle: String,
    val displaySubtitle: String?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val year: String?,
    val rating: String?,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val isDoramaOrAsian: Boolean,
    val initialResumePositionMs: Long = 0L,
)
