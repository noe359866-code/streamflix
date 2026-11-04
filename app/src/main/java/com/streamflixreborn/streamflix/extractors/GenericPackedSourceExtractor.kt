package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.HttpScraper
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.Jsoup
import java.net.URL

/**
 * Extractor genérico para players "empaquetados" (JS ofuscado con P.A.C.K.E.R.,
 * redirects por `location.replace`, `<meta refresh>`, `sources: [{file: ...}]`).
 *
 * Se usa con [Esprinahy](https://esprinahy.com), StreamSB, Mp4Upload y como
 * comodín para hosts nuevos: sigue hasta [MAX_REDIRECT_HOPS] saltos y busca la
 * fuente real tanto en el HTML como en los scripts desempaquetados.
 *
 * Cambios frente a la versión original:
 * - descarga con [HttpScraper] (IO + reintentos + cierre de respuesta) en cada
 *   salto, no con un Retrofit que fallaba ante un 3xx o un challenge;
 * - sólo devuelve **HLS (.m3u8) o MP4**, que son los formatos que reproduce la app;
 * - acepta fuentes sin extensión (los HLS con token en el query string) y
 *   normaliza `\/` / `\u0026` / `&amp;`;
 * - devuelve también el `Referer`/`Origin` correcto del último salto para que
 *   ExoPlayer no reciba 403.
 */
abstract class GenericPackedSourceExtractor : Extractor() {

    /** Referer inicial: normalmente la web que contiene el embed. */
    protected open val refererUrl: String
        get() = mainUrl

    override suspend fun extract(link: String): Video {
        val resolved = resolveChain(link)
        val videoSource = resolved.source
            ?: throw Exception("No se pudo obtener la fuente de video de $name")

        val playbackBase = runCatching { URL(resolved.pageUrl).let { "${it.protocol}://${it.host}" } }
            .getOrDefault(mainUrl)

        return Video(
            source = videoSource,
            type = when {
                videoSource.lowercase().contains(".mp4") -> MimeTypes.VIDEO_MP4
                else -> MimeTypes.APPLICATION_M3U8
            },
            headers = mapOf(
                "Referer" to "$playbackBase/",
                "Origin" to playbackBase,
                "User-Agent" to USER_AGENT,
            ),
        )
    }

    private data class Resolved(
        val source: String?,
        val pageUrl: String,
    )

    private suspend fun resolveChain(link: String): Resolved {
        var currentUrl = link
        var referer = "$refererUrl/"
        var source: String? = null
        var hops = 0

        while (hops < MAX_REDIRECT_HOPS) {
            val html = HttpScraper.getText(
                url = currentUrl,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT),
                attempts = 2,
            )
            if (html.isNullOrBlank()) {
                Log.w(TAG, "$name: respuesta vacía en $currentUrl")
                break
            }

            source = findSource(html) ?: unpackedScripts(html).firstNotNullOfOrNull(::findSource)
            if (source != null) break

            val redirect = findRedirectUrl(html)
                ?: unpackedScripts(html).firstNotNullOfOrNull(::findRedirectUrl)
                ?: break

            val nextUrl = runCatching { URL(URL(currentUrl), redirect).toString() }.getOrNull() ?: break
            if (nextUrl == currentUrl) break

            referer = currentUrl
            currentUrl = nextUrl
            hops++
            Log.d(TAG, "$name: redirect -> $currentUrl")
        }

        return Resolved(source, currentUrl)
    }

    /** Scripts que se pueden desempaquetar con [JsUnpacker] (con o sin `eval(`). */
    private fun unpackedScripts(html: String): Sequence<String> =
        Jsoup.parse(html).select("script").asSequence()
            .mapNotNull { script ->
                val raw = script.data().ifBlank { script.html() }
                JsUnpacker(raw).unpack() ?: JsUnpacker("eval$raw").unpack()
            }

    private fun findRedirectUrl(text: String): String? {
        val decoded = normalize(text)
        return redirectPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(decoded)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
    }

    private fun findSource(text: String): String? {
        val decoded = normalize(text)
        return sourcePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(decoded)?.groupValues?.getOrNull(1)
                ?.takeIf { it.startsWith("http") }
        }
    }

    private fun normalize(text: String): String = text
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    companion object {
        private const val TAG = "PackedExtractor"
        private const val MAX_REDIRECT_HOPS = 5
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val redirectPatterns = listOf(
            Regex("""(?is)window\.location(?:\.href)?\.replace\(\s*['"]([^'"]+)['"]\s*\)"""),
            Regex("""(?is)window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""(?is)location\.replace\(\s*['"]([^'"]+)['"]\s*\)"""),
            Regex("""(?is)location\.href\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""(?is)<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"'>]+)["']"""),
            Regex("""(?is)document\.write\([^)]*?href\s*=\s*["']([^"']+)["']"""),
        )

        private val sourcePatterns = listOf(
            // file / src con extensión soportada (sólo HLS y MP4)
            Regex("""(?i)(?:file|src|hls|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']"""),
            // arrays de fuentes
            Regex("""(?i)sources?\s*[:=]\s*\[\s*["'](https?://[^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']"""),
            // m3u8 con token (sin extensión limpia)
            Regex("""(?i)["'](https?://[^"']*(?:master|playlist|index|manifest)[^"']*\.m3u8[^"']*)["']"""),
            // último recurso: cualquier m3u8/mp4 entrecomillado
            Regex("""(?i)["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
        )
    }
}

/** Esprinahy: player usado por Series Turcas y otros sitios en español. */
class EsprinahyExtractor(private val referer: String = "https://esprinahy.com") :
    GenericPackedSourceExtractor() {
    override val name = "Esprinahy"
    override val mainUrl = "https://esprinahy.com"
    override val refererUrl: String get() = referer
}

class StreamSBExtractor : GenericPackedSourceExtractor() {
    override val name = "StreamSB"
    override val mainUrl = "https://streamsb.net"
    override val aliasUrls = listOf(
        "https://sbembed.com",
        "https://sbplay.org",
        "https://sbrapid.com",
        "https://sbvideo.net",
        "https://ssbstream.net",
        "https://streamsss.net",
    )

    override suspend fun extract(link: String): Video =
        runCatching { super.extract(link) }
            .getOrElse { error ->
                throw Exception(
                    "StreamSB no devolvió un reproductor (el dominio actual redirige a parking/publicidad).",
                    error,
                )
            }
}

class Mp4UploadExtractor : GenericPackedSourceExtractor() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://mp4upload.com"
    override val aliasUrls = listOf("https://www.mp4upload.com")
}

class StreamlareExtractor : GenericPackedSourceExtractor() {
    override val name = "Streamlare"
    override val mainUrl = "https://streamlare.com"

    override suspend fun extract(link: String): Video =
        runCatching { super.extract(link) }
            .getOrElse { error ->
                throw Exception("Streamlare no devolvió un reproductor (dominio aparcado).", error)
            }
}

class NinjaStreamExtractor : GenericPackedSourceExtractor() {
    override val name = "NinjaStream"
    override val mainUrl = "https://ninjastream"
    override val rotatingDomain = listOf(Regex("""(^|\.)ninjastream(\.|/)"""))
}

/**
 * Último recurso del registro: cualquier host sin extractor propio se trata como
 * fuente empaquetada (P.A.C.K.E.R., `sources`, redirects, HLS con token).
 *
 * Así un provider nunca se queda con "No extractors found": si el player no se
 * puede descifrar, el error que llega es el del player concreto, y si sirve HLS
 * o MP4 directo se reproduce igualmente.
 */
class FallbackPackedExtractor(link: String) : GenericPackedSourceExtractor() {

    private val origin: String = runCatching {
        URL(link).let { "${it.protocol}://${it.host}" }
    }.getOrDefault("")

    override val name = "Generic"
    override val mainUrl: String get() = origin
    override val refererUrl: String get() = "$origin/"
}

class UchExtractor : GenericPackedSourceExtractor() {
    override val name = "Uch"
    override val mainUrl = "https://uch"
    override val rotatingDomain = listOf(Regex("""(^|\.)uch(\.|/)"""))
}
