package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * IPTV-Org (canales de todo el mundo).
 *
 * Antes este provider tenía su propio cliente OkHttp, su propio caché con `var`
 * y su propio parser. Ahora hereda de [M3UProvider] y sólo aporta lo específico:
 * la URL de la lista, los grupos que van al home y el índice de categorías
 * oficiales que se ofrece como "géneros" al buscar en vacío.
 */
object IptvOrgProvider : M3UProvider() {

    override val name = "IPTV-All World"
    override val baseUrl = "https://iptv-org.github.io/iptv"
    override val logo = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
    override val language = "en"

    override val playlistUrl = "$baseUrl/index.m3u"
    override val homeGroups = listOf(
        "Animation", "Comedy", "Series", "Entertainment", "News", "Movies", "Sports"
    )
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

    /** IPTV-Org agrupa con `group-title`; el id del género es el propio grupo. */
    override suspend fun getGenre(id: String, page: Int): Genre {
        // El original comparaba el grupo por igualdad exacta, pero el nombre del
        // grupo puede venir con sufijos; la base hace `contains`, más tolerante.
        return super.getGenre(id, page)
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
