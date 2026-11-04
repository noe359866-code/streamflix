package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.HttpScraper
import com.streamflixreborn.streamflix.scrapers.await
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URI
import java.net.URL

/**
 * Firestream (`firestream.to` / `firestream.site`).
 *
 * El player incrusta un `<script id="video-data">` con el JSON de la película.
 * Si no trae `signedVideoUrl` hay que canjear el `<script id="token-blob">` en
 * `POST /api/videos/{slug}/resolve` — y el token es de un solo uso, por lo que
 * **cada reproducción** vuelve a pedir el embed.
 *
 * Mejoras respecto al original:
 * - usa el cliente compartido (DoH, timeouts, cookies de sesión) y `await` en IO;
 * - propaga el `User-Agent`/`Origin` en el `Video` para que ExoPlayer no reciba 403;
 * - resuelve subtítulos relativos y deduplica por URL.
 */
class FirestreamExtractor : Extractor() {

    override val name = "Firestream"
    override val mainUrl = "https://firestream.to"
    override val aliasUrls = listOf("https://firestream.site")

    override suspend fun extract(link: String): Video {
        val parsed = URL(link)
        val hostBase = "${parsed.protocol}://${parsed.host}"
        val slug = parsed.path.trim('/').substringAfterLast('/')
        if (slug.isBlank()) throw Exception("Firestream: no se pudo obtener el slug de $link")

        val client = NetworkClient.default.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val pageRequest = HttpScraper.request(
            url = link,
            referer = "$hostBase/",
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml",
                "User-Agent" to USER_AGENT,
            ),
        )
        val page = client.await(pageRequest).use { response ->
            PageLoad(
                code = response.code,
                html = response.body?.string().orEmpty(),
                cookies = response.headers("Set-Cookie")
                    .map { it.substringBefore(';') }
                    .filter { it.isNotBlank() }
                    .joinToString("; "),
            )
        }
        if (page.code !in 200..299) throw Exception("Firestream embed HTTP ${page.code}")
        if (page.html.isBlank()) throw Exception("Firestream embed vacío")
        Log.d(TAG, "Firestream embed cargado (${page.html.length} bytes)")

        val document = Jsoup.parse(page.html, link)
        val videoDataRaw = scriptData(document, "video-data")
            ?: throw Exception("Firestream: falta #video-data")

        val root = runCatching { JsonParser.parseString(videoDataRaw).asJsonObject }
            .getOrElse { throw Exception("Firestream: #video-data inválido") }
        val videoObject = root.get("video")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw Exception("Firestream: no hay objeto video")

        var source = stringValue(videoObject, "signedVideoUrl")
            ?: stringValue(videoObject, "signedVideoSdUrl")

        if (source.isNullOrBlank()) {
            val tokenBlob = scriptData(document, "token-blob")
                ?: throw Exception("Firestream: falta #token-blob")
            val payload = JsonObject().apply { addProperty("blob", tokenBlob) }
            val resolveRequest = Request.Builder()
                .url("$hostBase/api/videos/$slug/resolve")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", hostBase)
                .header("Referer", link)
                .apply { if (page.cookies.isNotBlank()) header("Cookie", page.cookies) }
                .build()

            source = client.await(resolveRequest).use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code !in 200..299) {
                    throw Exception("Firestream resolve HTTP ${response.code}: ${raw.take(200)}")
                }
                val resolved = JsonParser.parseString(raw).asJsonObject
                stringValue(resolved, "signedVideoUrl") ?: stringValue(resolved, "signedVideoSdUrl")
            }
        }

        if (source.isNullOrBlank()) throw Exception("Firestream: no se encontró la URL firmada")

        val subtitles = videoObject.get("subtitles")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                val obj = element.asJsonObject
                val rawUrl = stringValue(obj, "url") ?: return@mapNotNull null
                val subtitleUrl = runCatching { URI("$hostBase/").resolve(rawUrl).toString() }
                    .getOrDefault(rawUrl)
                Video.Subtitle(
                    label = stringValue(obj, "label") ?: stringValue(obj, "language") ?: "Subtitle",
                    file = subtitleUrl,
                    default = obj.get("default")?.takeIf { !it.isJsonNull }
                        ?.runCatching { asBoolean }?.getOrNull() ?: false,
                )
            }
            ?.distinctBy { it.file }
            ?: emptyList()

        return Video(
            source = source,
            subtitles = subtitles,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$hostBase/",
                "Origin" to hostBase,
            ),
        )
    }

    private data class PageLoad(val code: Int, val html: String, val cookies: String)

    private fun scriptData(document: org.jsoup.nodes.Document, id: String): String? {
        val element = document.getElementById(id) ?: return null
        return element.data().trim().ifBlank { element.html().trim() }.ifBlank { null }
    }

    private fun stringValue(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asString }?.getOrNull()
            ?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "Firestream"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
