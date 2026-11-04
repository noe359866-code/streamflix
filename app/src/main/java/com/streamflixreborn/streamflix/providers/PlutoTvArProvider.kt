package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * Pluto TV (Argentina / Latinoamérica). Misma base que [PlutoTvEsProvider] pero
 * con la lista regional `plutotv_ar.m3u`, útil en LatAm como respaldo del MX.
 */
object PlutoTvArProvider : M3UProvider() {

    override val name = "Pluto TV AR"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/jPsc2PMd/Logo-Pluto-TV-AR.jpg"
    override val language = "es"

    override val playlistUrl =
        "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_ar.m3u"

    override val ungroupedCategoryName = "General"
    override val homeLimit = 25
}
