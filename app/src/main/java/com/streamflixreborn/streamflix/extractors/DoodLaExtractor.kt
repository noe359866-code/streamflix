package com.streamflixreborn.streamflix.extractors

import android.webkit.CookieManager
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.StringConverterFactory
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Url
import java.net.URI
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * DoodStream (y su alias actual playmogo).
 *
 * El flujo cambió: `/pass_md5/...` ya no devuelve un prefijo "pelado", hay que
 * mandarle `Range: bytes=0-` y las **cookies** que dejó el embed, y la URL
 * final necesita `?token=...&expiry=...` con un hash aleatorio de 10 caracteres.
 * Antes se construía la URL sin `expiry`, sin cookies y con el `Referer` del
 * host base, y el resultado era un 403 o un MP4 truncado.
 *
 * Se conserva el nombre/tipo de la respuesta: sólo HLS/MP4 sobre http(s), como
 * exige el reproductor.
 */
open class DoodLaExtractor : Extractor() {

    override val name = "DoodStream"
    override val mainUrl = "https://dood.la"
    override val aliasUrls = listOf(
        "https://dsvplay.com",
        "https://mikaylaarealike.com",
        "https://myvidplay.com",
        "https://playmogo.com",
        "https://do7go.com",
        "https://d000d.com",
    )

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = Random()

    private val browserHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    /**
     * User-Agent de reproducción: el flujo que funciona hoy imita a Safari iOS
     * (el del escritorio recibe el manifiesto con 403).
     */
    private val playbackUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 " +
            "Mobile/15E148 Safari/604.1"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val embedUrl = link.replace("/d/", "/e/")

        val response = service.get(embedUrl, browserHeaders + mapOf("Referer" to link))
        val document = response.body() ?: throw Exception("No se pudo cargar el embed de DoodStream")

        val finalUrl = response.raw().request.url.toString()
        val finalBaseUrl = getBaseUrl(finalUrl)
        val html = document.toString()

        val md5Path = Regex("""/pass_md5/[^'"\s<]+""").find(html)?.value
            ?: throw Exception("DoodStream: sin ruta pass_md5")
        val pageToken = Regex("""[?&]token=([A-Za-z0-9_-]+)""")
            .find(html)?.groupValues?.getOrNull(1)

        val md5Url = finalBaseUrl + md5Path
        val passHeaders = mapOf(
            "User-Agent" to browserHeaders.getValue("User-Agent"),
            "Accept" to "*/*",
            "Range" to "bytes=0-",
            "Referer" to finalUrl,
        )
        val videoPrefix = service.getString(md5Url, passHeaders).trim()
        if (videoPrefix.isBlank()) throw Exception("DoodStream: pass_md5 vacío")

        val token = pageToken ?: md5Url.substringAfterLast("/")
        val url = videoPrefix + createHashTable() +
            "?token=$token&expiry=${System.currentTimeMillis()}"

        // Las cookies del embed son obligatorias para servir los bytes.
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(finalUrl) }
            .getOrNull().orEmpty().trim()

        val playbackHeaders = linkedMapOf(
            "Referer" to finalUrl,
            "Origin" to finalBaseUrl,
            "User-Agent" to playbackUserAgent,
            "Accept" to "*/*",
        )
        if (cookieHeader.isNotBlank()) {
            playbackHeaders["Cookie"] = cookieHeader
        }

        return Video(source = url, headers = playbackHeaders)
    }

    private fun createHashTable(): String = buildString {
        repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    class DoodLiExtractor : DoodLaExtractor() {
        override val mainUrl = "https://dood.li"
    }

    class DoodExtractor : DoodLaExtractor() {
        override val mainUrl = "https://vide0.net"
    }

    private interface Service {

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .cookieJar(NetworkClient.cookieJar)
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(StringConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Response<Document>

        @GET
        suspend fun getString(@Url url: String, @HeaderMap headers: Map<String, String>): String
    }
}
