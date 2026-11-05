package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * Sports Events: eventos deportivos en directo desde una lista M3U agregada.
 *
 * La URL de la lista estaba "ofuscada" en Base64 dentro del provider original;
 * aquí se conserva ese detalle (evita que el scraping automático la encuentre
 * fácilmente) pero la decodificamos una sola vez.
 *
 * No se añade ningún canal de relleno: si la lista no trae eventos, el provider
 * devuelve el home vacío en lugar de un "sin señal" que nunca es un stream.
 */
object PelotaLibreTvHdProvider : M3UProvider() {

    override val name = "Sports Events"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/3s2mhm6/sports-logo.png"
    override val language = "es"

    private const val OBFUSCATED_PLAYLIST =
        "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL0J1ZGR5Q2hld0NoZXcvc3BvcnRzL3JlZnMvaGVhZHMvbWFpbi9saXZlZXZlbnRzZmlsdGVyLm0zdTg="

    override val playlistUrl: String =
        String(Base64.decode(OBFUSCATED_PLAYLIST, Base64.DEFAULT), Charsets.UTF_8).trim()

    /** Los eventos deportivos se refrescan mucho más a menudo que una lista IPTV normal. */
    override val cacheDurationMs = 10 * 60 * 1000L
    override val homeLimit = 30
    override val pageSize = 40
    override val ungroupedCategoryName = "Eventos"
}
