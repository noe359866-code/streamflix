package com.streamflixreborn.streamflix.search

import android.net.Uri
import com.streamflixreborn.streamflix.tv.model.TvMediaKind

data class TvSearchDeepLinkTarget(
    val kind: TvMediaKind,
    val contentId: String,
    val imdbId: String?,
    val title: String,
)

/**
 * Normalizes natural-language Spanish/English voice queries (e.g., "Ver Interstellar" -> "Interstellar")
 * and encodes/decodes Android TV Global Search deep-link URIs (`streamflix://tv/media/...`).
 */
object TvVoiceQueryNormalizer {

    private val leadingActionRegex = Regex(
        pattern = "^(?:quiero\\s+ver|ponme|poner|reproducir|reproduce|ver|buscar|busca|abrir|abre|watch|play|search\\s+for|search)\\s+(?:la\\s+pel[ií]cula\\s+|la\\s+serie\\s+|el\\s+dorama\\s+)?",
        option = RegexOption.IGNORE_CASE,
    )

    fun normalizeSpokenQuery(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val stripped = trimmed.replace(leadingActionRegex, "").trim()
        return stripped.ifBlank { trimmed }
    }

    fun buildSuggestDataId(
        kind: TvMediaKind,
        contentId: String,
        imdbId: String?,
        title: String,
    ): String {
        val kindToken = if (kind == TvMediaKind.SERIES) "series" else "movie"
        val safeImdb = imdbId.orEmpty()
        val encodedTitle = Uri.encode(title)
        return "$kindToken|$contentId|$safeImdb|$encodedTitle"
    }

    fun buildDeepLinkUri(
        kind: TvMediaKind,
        contentId: String,
        imdbId: String?,
        title: String,
    ): Uri {
        val dataId = buildSuggestDataId(kind, contentId, imdbId, title)
        return Uri.parse("streamflix://tv/media/$dataId")
    }

    fun parseDeepLinkUri(uri: Uri?): TvSearchDeepLinkTarget? {
        if (uri == null) return null
        if (!uri.scheme.equals("streamflix", ignoreCase = true)) return null
        if (!uri.host.equals("tv", ignoreCase = true)) return null

        val lastSegment = uri.lastPathSegment ?: return null
        val parts = lastSegment.split("|")
        if (parts.size < 2) return null

        val kind = if (parts[0].equals("series", ignoreCase = true)) {
            TvMediaKind.SERIES
        } else {
            TvMediaKind.MOVIE
        }
        val contentId = parts[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val imdbId = parts.getOrNull(2)?.trim()?.takeIf { it.startsWith("tt") }
            ?: contentId.takeIf { it.startsWith("tt") }
        val title = parts.getOrNull(3)?.let { Uri.decode(it) }.orEmpty()

        return TvSearchDeepLinkTarget(
            kind = kind,
            contentId = contentId,
            imdbId = imdbId,
            title = title,
        )
    }
}
