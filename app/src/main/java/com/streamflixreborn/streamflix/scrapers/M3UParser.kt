package com.streamflixreborn.streamflix.scrapers

/**
 * Parser M3U/M3U8 compartido por los providers IPTV.
 *
 * Antes cada provider tenía su copia del parser (con pequeñas diferencias que
 * provocaban bugs: `http-referrer` soportado en uno y no en otro, nombres con
 * comas truncados, canales sin logo descartados, etc). Ahora hay una sola
 * implementación, tolerante con las variantes que se encuentran en la práctica.
 */
object M3UParser {

    private val LOGO_REGEX = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val GROUP_REGEX = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val UA_REGEX = Regex("""http-user-agent="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val REFERRER_REGEX = Regex("""http-referrer="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TVG_ID_REGEX = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)

    /**
     * Parsea un documento M3U completo.
     *
     * Tolerante a:
     * - listas que empiezan con BOM o espacios;
     * - nombres con comas (`#EXTINF:-1 tvg-id="x",Canal, el bueno`);
     * - atributos entre comillas simples o sin comillas;
     * - `#EXTVLCOPT` colocados después del `#EXTINF` (UA y referrer);
     * - `#EXTGRP` como alternativa a `group-title`;
     * - líneas de URL relativas o con espacios.
     */
    fun parse(raw: String): List<M3UChannel> {
        if (raw.isBlank()) return emptyList()

        val channels = ArrayList<M3UChannel>(256)
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        fun reset() {
            name = null
            logo = null
            group = null
            userAgent = null
            referrer = null
        }

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    name = extractChannelName(line)
                    logo = attribute(LOGO_REGEX, line)
                    group = attribute(GROUP_REGEX, line)
                    userAgent = attribute(UA_REGEX, line)
                    referrer = attribute(REFERRER_REGEX, line)
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (group.isNullOrBlank()) group = line.substringAfter(':').trim()
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) ||
                    line.startsWith("#KODIPROP", ignoreCase = true) -> {
                    val value = line.substringAfter('=', "").trim()
                    if (value.isEmpty()) return@forEach
                    val lower = line.lowercase()
                    if ("user-agent" in lower) userAgent = value
                    else if ("referrer" in lower || "referer" in lower) referrer = value
                }

                line.startsWith("#") -> Unit

                else -> {
                    // La línea siguiente a un #EXTINF es la URL del canal.
                    val currentName = name
                    if (!currentName.isNullOrBlank()) {
                        channels.add(
                            M3UChannel(
                                name = currentName,
                                url = line.substringBefore(' ').trim(),
                                logo = logo?.takeIf { it.isNotBlank() },
                                group = group?.takeIf { it.isNotBlank() },
                                userAgent = userAgent?.takeIf { it.isNotBlank() },
                                referrer = referrer?.takeIf { it.isNotBlank() },
                            )
                        )
                    }
                    reset()
                }
            }
        }
        return channels.distinctBy { it.url }
    }

    /** Nombre del canal: todo lo que aparece después de la última coma del `#EXTINF`. */
    private fun extractChannelName(line: String): String {
        val name = line.substringAfterLast(',', "").trim()
        if (name.isNotEmpty()) return name
        // Fallback: algunos generadores usan `tvg-name` en lugar del nombre final.
        return Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim()
            .orEmpty()
    }

    private fun attribute(regex: Regex, line: String): String? =
        regex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** Id estable para un canal (útil para `TvShow.id`), basado en la URL. */
    fun channelId(channel: M3UChannel): String {
        val tvgId = TVG_ID_REGEX.find(channel.name)?.groupValues?.get(1)
        return tvgId ?: channel.url
    }
}
