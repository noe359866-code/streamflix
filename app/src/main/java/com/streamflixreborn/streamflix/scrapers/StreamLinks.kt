package com.streamflixreborn.streamflix.scrapers

import com.streamflixreborn.streamflix.models.Video
import java.net.URI

/**
 * Filtro único de "esto es un stream y no un enlace de relleno".
 *
 * Los providers en español mezclan en la misma lista reproductores reales con
 * enlaces de soporte, donaciones, grupos de Telegram, páginas de "próximamente"
 * o iframes de publicidad. Si eso llega a `getServers`, el usuario ve opciones
 * que nunca van a reproducir nada.
 *
 * Reglas:
 * - sólo `http(s)`;
 * - el resultado final que se entrega al player es **HLS (.m3u8) o MP4**; los
 *   enlaces de un player se aceptan aquí para poder resolverlos, pero el
 *   formato se valida en `Video`/`Extractor`;
 * - se descartan hosts sociales/monetización y rutas de soporte;
 * - se descartan páginas del propio sitio (`/genero/`, `/donacion`, `#`).
 */
object StreamLinks {

    /** Hosts que nunca sirven video. */
    private val BLOCKED_HOSTS = listOf(
        "t.me", "telegram.me", "telegram.org", "whatsapp.com", "wa.me", "discord.gg", "discord.com",
        "paypal.com", "paypal.me", "buymeacoffee.com", "patreon.com", "ko-fi.com", "linktr.ee",
        "facebook.com", "instagram.com", "twitter.com", "x.com", "tiktok.com", "threads.net",
        "google.com/ads", "doubleclick.net", "googlesyndication.com", "adsterra.com",
        "youtube.com", "youtu.be", // trailers, no streams
    )

    /** Rutas del propio sitio que son navegación, no reproducción. */
    private val BLOCKED_PATH_HINTS = listOf(
        "/genero/", "/generos/", "/genre/", "/donacion", "/donate", "/soporte", "/support",
        "/contacto", "/contact", "/privacidad", "/privacy", "/terminos", "/terms",
        "/reportar", "/report", "/telegram", "/buscar", "/search", "/tag/", "/author/",
    )

    /** `true` si la URL puede entregarse como reproductor. */
    fun isPlayable(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "#" || value.startsWith("#")) return false
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) return false

        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase().orEmpty()

        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") || value.lowercase().contains(it) }) return false
        if (BLOCKED_PATH_HINTS.any { path.contains(it) }) return false

        // Formatos entregables: HLS y MP4 (nada de DASH/MKV/WEBM/FLV).
        if (path.endsWith(".mpd") || path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".flv")) {
            return false
        }

        // Un iframe de video casi siempre trae un id/ruta; un "/" pelado es publicidad.
        val looksLikeMedia = path.endsWith(".m3u8") || path.endsWith(".mp4") ||
            path.contains("/embed") || path.contains("/e/") || path.contains("/v/") ||
            path.contains("/video") || path.contains("/player") || path.contains("/watch") ||
            path.contains("/ifr") || path.contains("/play") || path.contains("/jkplayer") ||
            uri.query?.isNotEmpty() == true
        return looksLikeMedia || path.length > 1
    }

    /** `true` si la URL ya es directamente HLS o MP4 (sin pasar por un player). */
    fun isDirectMedia(url: String?): Boolean {
        val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    /** Deja sólo servidores reproducibles, sin duplicados ni nombres vacíos. */
    fun sanitize(servers: List<Video.Server>): List<Video.Server> = servers
        .filter { isPlayable(it.src.ifBlank { it.id }) }
        .distinctBy { it.src.ifBlank { it.id } }
        .map { server ->
            if (server.name.isBlank()) server.copy(name = displayName(server.src.ifBlank { server.id })) else server
        }

    /** Nombre legible a partir del host (`voe.sx` -> `Voe`). */
    fun displayName(url: String): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            .removePrefix("www.")
            .substringBefore('.')
        if (host.isBlank()) return "Servidor"
        return host.replaceFirstChar { it.uppercase() }
    }
}
