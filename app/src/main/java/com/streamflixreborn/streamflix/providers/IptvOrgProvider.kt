package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * IPTV-Org (canales de todo el mundo).
 *
 * Antes este provider tenía su propio cliente OkHttp, su propio caché con `var`
 * y su propio parser. Ahora hereda de [M3UProvider] y sólo aporta lo específico:
 * la URL de la lista y los grupos que van al home.
 *
 * No expone "géneros" como items del buscador ni items de soporte: la búsqueda
 * devuelve directamente canales que se pueden reproducir.
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
}
