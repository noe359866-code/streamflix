package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Turbovid.
 *
 * Usa el flujo del sitio original (Xtremeware/YetiShare): un `GET` que deja un
 * formulario con `hash`/`id`, una espera de ~5 s, un `POST download1` con
 * `imhuman` y las cookies, y recién entonces aparece el player empaquetado.
 * Sin ese POST la página sólo devuelve el contador y no hay `sources:`.
 */
class TurbovidExtractor : Extractor() {

    override val name = "Turbovid"
    override val mainUrl = "https://turbovid.me"

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .cookieJar(NetworkClient.cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun extract(link: String): Video {
        val getRequest = Request.Builder()
            .url(link)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        val (firstHtml, finalUrl) = httpClient.newCall(getRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Turbovid: GET ${response.code}")
            Pair(response.body?.string().orEmpty(), response.request.url.toString())
        }

        val hash = Regex("""name=["']hash["']\s+value=["']([^"']+)["']""")
            .find(firstHtml)?.groupValues?.get(1)
            ?: throw Exception("Turbovid: sin hash en la página")
        val id = Regex("""name=["']id["']\s+value=["']([^"']+)["']""")
            .find(firstHtml)?.groupValues?.get(1)
            ?: finalUrl.trimEnd('/').substringAfterLast('/')
        val fname = Regex("""name=["']fname["']\s+value=["']([^"']+)["']""")
            .find(firstHtml)?.groupValues?.get(1).orEmpty()
        val fileId = hash.substringBefore('-')

        // La cuenta atrás del sitio es de 5 s: antes de eso el POST se rechaza.
        delay(5_500)

        val body = FormBody.Builder()
            .add("op", "download1")
            .add("usr_login", "")
            .add("id", id)
            .add("fname", fname)
            .add("referer", "")
            .add("hash", hash)
            .add("imhuman", "Proceed to video")
            .build()

        val postRequest = Request.Builder()
            .url("https://turbovid.me/$id")
            .post(body)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Origin", "https://turbovid.me")
            .header("Referer", finalUrl)
            .header("Cookie", "file_id=$fileId; aff=5; ref_url=$finalUrl")
            .build()

        val secondHtml = httpClient.newCall(postRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Turbovid: POST ${response.code}")
            response.body?.string().orEmpty()
        }

        val packed = Regex(
            """<script[^>]*>\s*(eval\(function\(p,a,c,k,e,[rd].+?)\s*</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(secondHtml)?.groupValues?.get(1)
            ?: Regex("""(eval\(function\(p,a,c,k,e,[rd].+?\)\))""", RegexOption.DOT_MATCHES_ALL)
                .find(secondHtml)?.groupValues?.get(1)

        val unpacked = packed?.let { JsUnpacker(it).unpack() } ?: secondHtml

        val source = Regex("""sources:\s*\[\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""sources:\s*\[\s*["']([^"']+)["']""").find(secondHtml)?.groupValues?.get(1)
            ?: throw Exception("Turbovid: sin fuente de video")

        return Video(
            source = source,
            headers = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Referer" to "https://turbovid.me/",
            ),
        )
    }
}
