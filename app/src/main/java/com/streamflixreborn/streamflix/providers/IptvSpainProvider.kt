package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * IPTV Spain: canales en español de iptv-org (`languages/spa.m3u`).
 *
 * Lo que cambia respecto a la versión anterior (además de heredar la base):
 * - el `Referer` del canal viajaba en el id pero **nunca se enviaba al player**;
 *   ahora [M3UProvider.getVideo] devuelve `User-Agent` y `Referer`;
 * - el parser ahora respeta `#EXTGRP`, nombres con comas y `#KODIPROP`;
 * - sólo se listan canales con URL reproducible (sin soporte/donaciones).
 */
object IptvSpainProvider : M3UProvider() {

    override val name = "IPTV Spain"
    override val baseUrl = "https://iptv-org.github.io/iptv/languages/spa.m3u"
    override val logo = "https://i.ibb.co/SD7860Mv/IPTV-Spain-Canales.jpg"
    override val language = "es"

    override val playlistUrl = baseUrl
    override val homeGroups = listOf("News", "Sports", "Movies", "Entertainment", "Music", "General")
    override val homeLimit = 25
}
