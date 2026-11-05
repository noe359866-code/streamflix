package com.streamflixreborn.streamflix.scrapers

import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Helpers de parsing que se repetían (con bugs distintos) en cada provider:
 * limpiar títulos, sacar año/rating, resolver imágenes lazy, números de episodio...
 *
 * Todas las funciones son puras y tolerantes: nunca lanzan por datos raros.
 */
object ScraperParsing {

    private val MULTI_SPACE = Regex("""\s+""")
    private val YEAR_REGEX = Regex("""(?:^|[^\d])((?:19|20)\d{2})(?:[^\d]|$)""")
    private val RATING_REGEX = Regex("""(\d{1,2}(?:[.,]\d)?)""")
    private val EPISODE_REGEX = Regex("""(?i)(?:episode|ep|e|cap|capitulo|capítulo|episodio)\s*[._-]?\s*(\d{1,4})""")
    private val SEASON_REGEX = Regex("""(?i)(?:season|temporada|t|s)\s*[._-]?\s*(\d{1,3})""")
    private val NUMBER_REGEX = Regex("""(\d{1,4})""")

    /** Normaliza espacios y recorta. Devuelve `null` si queda vacío. */
    fun cleanText(value: String?): String? = value
        ?.replace('\u00A0', ' ')
        ?.replace(MULTI_SPACE, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Texto del primer selector que exista, ya normalizado. */
    fun textOrNull(element: Element?, vararg selectors: String): String? {
        if (element == null) return null
        for (selector in selectors) {
            val text = cleanText(element.selectFirst(selector)?.text())
            if (text != null) return text
        }
        return null
    }

    /**
     * Limpia títulos de los listados: quita sufijos de calidad/idioma y tags
     * tipo `(2024)`, `[HD]`, `SUB ESP`, `online`, `completa`...
     * Conserva el año si el título no tiene otro año.
     */
    fun cleanTitle(raw: String?): String? {
        var title = cleanText(raw) ?: return null
        title = title
            .replace(Regex("""(?i)\s*[\[(](?:hd|sd|4k|1080p|720p|480p|cam|ts|latino|castellano|subtitulado|sub|español|ingles|english)[\])]"""), "")
            .replace(Regex("""(?i)\s*[-–|]\s*(?:ver|watch|online|completa|completo|gratis|hd)\s*$"""), "")
            .replace(Regex("""(?i)\s+(?:online|completa|completo|gratis)$"""), "")
            .replace(MULTI_SPACE, " ")
            .trim()
            .trim('-', '–', '|', ':', '.', ',')
        return title.takeIf { it.isNotEmpty() }
    }

    /** Extrae un año (1900-2099) del texto. */
    fun parseYear(value: String?): String? =
        value?.let { YEAR_REGEX.find(it)?.groupValues?.get(1) }

    /**
     * Extrae un rating 0-10 tolerando formatos como `7.8`, `7,8`, `7.8/10`, `IMDb 7.8`.
     * Valores fuera de rango se descartan.
     */
    fun parseRating(value: String?): Double? {
        val raw = value ?: return null
        return RATING_REGEX.findAll(raw)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 }
    }

    /** Duración en minutos a partir de formatos `1h 32m`, `132 min`, `1:32:00`. */
    fun parseRuntimeMinutes(value: String?): Int? {
        val raw = cleanText(value) ?: return null
        val colon = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""").find(raw)
        if (colon != null) {
            val first = colon.groupValues[1].toInt()
            val second = colon.groupValues[2].toInt()
            val third = colon.groupValues[3].toIntOrNull()
            return if (third != null) first * 60 + second else first
        }
        val hours = Regex("""(\d{1,2})\s*h""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d{1,3})\s*m""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""^(\d{1,3})\s*(?:min|minutes?)$""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        val total = hours * 60 + minutes
        return total.takeIf { it > 0 }
    }

    /** Número de episodio a partir de un título (`Capítulo 12`, `EP 5`, `Episodio 3`). */
    fun parseEpisodeNumber(value: String?): Int? {
        val raw = cleanText(value) ?: return null
        EPISODE_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        // Fallback: primer número del texto (evitando años)
        return NUMBER_REGEX.findAll(raw)
            .map { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it != null && it in 1..3000 }
    }

    /** Número de temporada a partir de un título (`Temporada 2`, `Season 3`, `T2`). */
    fun parseSeasonNumber(value: String?): Int? {
        val raw = cleanText(value) ?: return null
        SEASON_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return NUMBER_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * URL de imagen resolviendo atributos lazy (`data-src`, `data-lazy-src`,
     * `data-original`, `srcset`) que usan casi todas estas webs.
     * Devuelve `null` si no hay ninguna candidata válida (evita guardar `data:` vacíos).
     */
    fun imageUrl(element: Element?, baseUrl: String? = null): String? {
        val img = element ?: return null
        val candidates = listOf(
            "abs:src", "src",
            "abs:data-src", "data-src",
            "abs:data-lazy-src", "data-lazy-src",
            "abs:data-original", "data-original",
            "abs:data-img", "data-img",
        )
        for (attribute in candidates) {
            val value = img.attr(attribute).trim()
            if (isUsableImageUrl(value)) return qualify(value, baseUrl)
        }
        // srcset: quedarnos con la última (mayor resolución) de las URLs
        val srcset = img.attr("data-srcset").ifBlank { img.attr("srcset") }
        if (srcset.isNotBlank()) {
            val best = srcset.split(',')
                .mapNotNull { it.trim().split(' ').firstOrNull()?.takeIf(::isUsableImageUrl) }
                .lastOrNull()
            if (best != null) return qualify(best, baseUrl)
        }
        return null
    }

    private fun isUsableImageUrl(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith("data:") &&
            !value.startsWith("blob:") &&
            !value.startsWith("about:") &&
            value != "#" &&
            !value.contains("placeholder", ignoreCase = true) &&
            !value.contains("lazy.png", ignoreCase = true) &&
            !value.contains("no-image", ignoreCase = true) &&
            !value.contains("noimage", ignoreCase = true) &&
            !value.contains("spacer", ignoreCase = true)

    private fun qualify(value: String, baseUrl: String?): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        baseUrl.isNullOrBlank() -> value
        else -> HttpScraper.absoluteUrl(baseUrl, value) ?: value
    }

    /** Normaliza un título para comparaciones/deduplicación. */
    fun normalizedKey(value: String?): String = (value ?: "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9áéíóúñü]+"""), " ")
        .trim()

    /** Slug seguro para construir ids/rutas a partir de un título. */
    fun slugify(value: String?): String = normalizedKey(value).replace(' ', '-')
}
