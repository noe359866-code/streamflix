package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * IPTV Spain: canales en español de iptv-org (`languages/spa.m3u`).
 *
 * Lo que cambia respecto a la versión anterior (además de heredar la base):
 * - el `Referer` del canal viajaba en el id pero **nunca se enviaba al player**;
 *   ahora [M3UProvider.getVideo] devuelve `User-Agent` y `Referer`;
 * - el parser ahora respeta `#EXTGRP`, nombres con comas y `#KODIPROP`.
 */
object IptvSpainProvider : M3UProvider() {

    override val name = "IPTV Spain"
    override val baseUrl = "https://iptv-org.github.io/iptv/languages/spa.m3u"
    override val logo = "https://i.ibb.co/SD7860Mv/IPTV-Spain-Canales.jpg"
    override val language = "es"

    override val playlistUrl = baseUrl
    override val homeGroups = listOf("News", "Sports", "Movies", "Entertainment", "Music", "General")
    override val homeLimit = 25

    private val officialCategories = listOf(
        "Animation", "Auto", "Business", "Classic", "Comedy", "Cooking", "Culture",
        "Documentary", "Education", "Entertainment", "Family", "General", "Interactive",
        "Kids", "Legislative", "Lifestyle", "Movies", "Music", "News", "Outdoor",
        "Public", "Relax", "Religious", "Science", "Series", "Shop", "Sports",
        "Travel", "Weather", "Undefined",
    )

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return officialCategories.map { name -> Genre(id = name, name = "📂 $name") }
        }
        return super.search(query, page)
    }

    override fun extraCategories(): List<Category> = listOf(
        Category(
            name = "Soporte y Ayuda",
            list = listOf(liveSupportItem(INFO_REPORT), liveSupportItem(INFO_DONATE)),
        )
    )

    override fun isInfoId(id: String) = id == INFO_REPORT || id == INFO_DONATE

    override fun infoItem(id: String): TvShow = liveSupportItem(id)
}
