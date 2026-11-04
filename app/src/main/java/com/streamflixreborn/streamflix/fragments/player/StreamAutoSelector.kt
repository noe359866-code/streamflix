package com.streamflixreborn.streamflix.fragments.player

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.StreamLinks

/**
 * Elige el stream que se va a reproducir sin preguntar nada al usuario.
 *
 * Antes el reproductor hacía `getVideo(servers.first())`: el orden lo decidía la
 * web del proveedor (a veces alfabético, a veces el último añadido), así que el
 * primer intento caía muchas veces en un 480p o en un host muerto y el usuario
 * tenía que abrir Ajustes → Servidores y elegir a mano.
 *
 * Aquí se puntúa cada servidor con señales que ya vienen en `Video.Server`
 * (id/name/src), sin conocer ningún proveedor concreto, y se ordena de mejor a
 * peor. Como el reproductor ya reintenta con el siguiente de la lista cuando uno
 * falla, el orden es también la cadena de respaldo.
 */
object StreamAutoSelector {

    /** Puntaje mínimo para considerar que un servidor es "bueno". */
    private const val SCORE_DIRECT = 120
    private const val SCORE_4K = 45
    private const val SCORE_1080 = 35
    private const val SCORE_720 = 20
    private const val SCORE_SD = -25
    private const val SCORE_LATINO = 30
    private const val SCORE_CASTELLANO = 22
    private const val SCORE_SUBTITLED = 6
    private const val SCORE_HTTPS = 12
    private const val SCORE_LAST_GOOD = 200
    private const val SCORE_DEAD_HOST = -80

    /**
     * Ordena la lista de mejor a peor. El orden original se conserva entre
     * servidores con el mismo puntaje (orden estable), para que el resultado sea
     * predecible y no "bailen" los reintentos.
     */
    fun order(servers: List<Video.Server>, lastGoodHost: String? = null): List<Video.Server> =
        servers
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Video.Server>> { score(it.value, lastGoodHost) }
                    .thenBy { it.index }
            )
            .map { it.value }

    /** Identificador estable de un servidor para recordar "este funcionó". */
    fun hostKey(server: Video.Server): String =
        (server.src.ifBlank { server.id })
            .substringAfter("://", "")
            .substringBefore("/")
            .substringBefore("?")
            .lowercase()

    internal fun score(server: Video.Server, lastGoodHost: String?): Int {
        val url = server.src.ifBlank { server.id }
        val haystack = "${server.name} $url".lowercase()
        var score = 0

        // 1. Un HLS/MP4 directo se reproduce sin pasar por un extractor: es lo
        //    más fiable y lo más rápido (nada que pueda romperse a mitad).
        if (StreamLinks.isDirectMedia(url)) score += SCORE_DIRECT
        if (endsWithMedia(url, ".mp4")) score += 15

        // 2. Calidad anunciada en el nombre o en la URL.
        score += when {
            haystack.contains("2160") || haystack.contains("4k") -> SCORE_4K
            haystack.contains("1080") || haystack.contains("fullhd") -> SCORE_1080
            haystack.contains("720") || haystack.contains("hd") -> SCORE_720
            haystack.contains("480") || haystack.contains("360") ||
                haystack.contains(" sd") -> SCORE_SD
            else -> 0
        }

        // 3. Idioma: latino > castellano > subtitulado.
        score += when {
            haystack.contains("latino") || haystack.contains("latam") -> SCORE_LATINO
            haystack.contains("castellano") || haystack.contains("español") ||
                haystack.contains("espanol") -> SCORE_CASTELLANO
            haystack.contains("sub") -> SCORE_SUBTITLED
            else -> 0
        }

        // 4. HTTPS: con HTTP plano muchos hosts fallan por cleartext.
        if (url.startsWith("https://")) score += SCORE_HTTPS

        // 5. Host que ya reprodujo bien con este proveedor.
        if (!lastGoodHost.isNullOrBlank() && hostKey(server) == lastGoodHost) {
            score += SCORE_LAST_GOOD
        }

        // 6. Hosts que el player ya marcó como problemáticos en esta sesión.
        if (hostKey(server) in deadHosts) score += SCORE_DEAD_HOST

        return score
    }

    private fun endsWithMedia(url: String, ext: String) =
        url.substringBefore('?').lowercase().endsWith(ext)

    /**
     * Hosts que fallaron al reproducir en esta sesión. Vive en memoria: si el
     * primer intento de un host falla, el reintento lo pone al final.
     */
    private val deadHosts = mutableSetOf<String>()

    fun markFailed(server: Video.Server) {
        if (deadHosts.size > 40) deadHosts.clear()
        deadHosts += hostKey(server)
    }

    fun markWorked(server: Video.Server) {
        deadHosts -= hostKey(server)
    }

    @Suppress("unused")
    fun clearSession() = deadHosts.clear()
}
