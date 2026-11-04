package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * Pluto TV (España).
 *
 * Pluto no publica una lista M3U oficial: se usa el agregado mantenido por
 * BuddyChewChew, que ya trae `group-title` y logos por canal. La lógica de
 * descarga, caché, parseo M3U e inyección de headers al reproducir está en
 * [M3UProvider].
 */
object PlutoTvEsProvider : M3UProvider() {

    override val name = "Pluto TV ES"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/TBjWz2Zw/Pluto-TV-es.jpg"
    override val language = "es"

    override val playlistUrl =
        "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_es.m3u"

    override val ungroupedCategoryName = "General"
    override val homeLimit = 25

    override fun isInfoId(id: String) = id == INFO_REPORT || id == INFO_DONATE

    override fun infoItem(id: String): TvShow = liveSupportItem(id)

    override fun extraCategories(): List<Category> = listOf(
        Category(
            name = "Soporte y Ayuda",
            list = listOf(infoItem(INFO_REPORT)!!, infoItem(INFO_DONATE)!!),
        )
    )
}
