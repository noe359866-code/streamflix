package com.streamflixreborn.streamflix.profiles

import com.streamflixreborn.streamflix.stremio.StremioMeta
import com.streamflixreborn.streamflix.tv.model.TvMediaCardItem
import com.streamflixreborn.streamflix.tv.model.TvShelfModel
import java.util.Locale

/**
 * Mature-catalog gatekeeper for Kids Mode (`isKidsProfile == true`).
 * Restricts adult, horror, crime, thriller, war, and +18 titles across shelves, Hero Banner,
 * Live-Search, and Android TV Global Voice Search.
 */
object KidsContentFilter {

    private val matureGenreTokens = setOf(
        "horror",
        "terror",
        "thriller",
        "suspenso",
        "suspense",
        "crime",
        "crimen",
        "adult",
        "adultos",
        "erotic",
        "erótico",
        "erotico",
        "war",
        "guerra",
        "bélico",
        "belico",
        "gore",
        "slasher",
        "18+",
        "+18",
        "tv-ma",
        "nc-17",
    )

    private val matureKeywordRegex = Regex(
        pattern = "\\b(asesino|asesinato|serial killer|masacre|narcotr[aá]fico|cartel|sicario|psic[oó]pata|exorcismo|demonio|demonios|gore|er[oó]tic[oa]|adultos|\\+18|tv-ma)\\b",
        option = RegexOption.IGNORE_CASE,
    )

    private val blockedQuickGenreIds = setOf("Horror", "Thriller")

    fun isMetaAllowedForKids(meta: StremioMeta): Boolean {
        val genres = meta.resolvedGenres
        if (genres.any { g -> isMatureGenre(g) }) return false
        val combinedText = "${meta.name} ${meta.description.orEmpty()}"
        if (matureKeywordRegex.containsMatchIn(combinedText)) return false
        return true
    }

    fun isCardAllowedForKids(item: TvMediaCardItem): Boolean {
        if (item.genres.any { g -> isMatureGenre(g) }) return false
        val combinedText = "${item.title} ${item.overview.orEmpty()}"
        if (matureKeywordRegex.containsMatchIn(combinedText)) return false
        return true
    }

    fun filterCards(
        items: List<TvMediaCardItem>,
        isKidsProfile: Boolean,
    ): List<TvMediaCardItem> {
        if (!isKidsProfile) return items
        return items.filter(::isCardAllowedForKids)
    }

    fun filterShelves(
        shelves: List<TvShelfModel>,
        isKidsProfile: Boolean,
    ): List<TvShelfModel> {
        if (!isKidsProfile) return shelves
        return shelves.mapNotNull { shelf ->
            val safeItems = shelf.items.filter(::isCardAllowedForKids)
            if (safeItems.isEmpty()) null else shelf.copy(items = safeItems)
        }
    }

    fun filterQuickGenres(
        genres: List<Pair<String, String>>,
        isKidsProfile: Boolean,
    ): List<Pair<String, String>> {
        if (!isKidsProfile) return genres
        return genres.filterNot { (id, _) -> id in blockedQuickGenreIds }
    }

    private fun isMatureGenre(rawGenre: String): Boolean {
        val normalized = rawGenre.trim().lowercase(Locale.ROOT)
        return matureGenreTokens.any { token ->
            normalized == token || normalized.contains(token)
        }
    }
}
