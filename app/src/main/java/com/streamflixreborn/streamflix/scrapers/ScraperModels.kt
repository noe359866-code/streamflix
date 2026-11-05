package com.streamflixreborn.streamflix.scrapers

/**
 * Modelos compartidos por los scrapers (providers + extractors).
 *
 * Viven en su propio paquete para no arrastrar dependencias de Android y poder
 * reutilizarse desde cualquier provider/extractor sin duplicar código.
 */

/**
 * Canal de una lista M3U/M3U8 (IPTV).
 *
 * @param name      nombre visible del canal
 * @param url       URL de streaming del canal
 * @param logo      logo opcional
 * @param group     group-title opcional
 * @param userAgent `http-user-agent` opcional (algunos canales exigen UA propio)
 * @param referrer  `http-referrer` opcional (necesario para los canales que bloquean el hotlinking)
 */
data class M3UChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val userAgent: String? = null,
    val referrer: String? = null,
)
