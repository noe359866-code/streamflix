package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * Pluto TV (México / Latinoamérica). Misma base que [PlutoTvEsProvider] pero con
 * la lista regional `plutotv_mx.m3u`.
 */
object PlutoTvMxProvider : M3UProvider() {

    override val name = "Pluto TV MX"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/9mGNwYVS/Logo-Pluto-TV.jpg"
    override val language = "es"

    override val playlistUrl =
        "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_mx.m3u"

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
