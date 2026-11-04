package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DeltaBit.
 *
 * Mismo esquema XFileSharing que Turbovid (formulario + `download1`), pero el
 * `imhuman` va **vacío** y el host del POST está fijado. Se mantiene separado
 * porque cada host valida ese campo de forma distinta.
 */
class DeltaBitExtractor : Extractor() {

    override val name = "DeltaBit"
    override val mainUrl = "https://deltabit.co"

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
            if (!response.isSuccessful) throw Exception("DeltaBit: GET ${response.code}")
            Pair(response.body?.string().orEmpty(), response.request.url.toString())
        }

        val hash = Regex("""name=["']hash["']\s+value=["']([^"']+)["']""")
            .find(firstHtml)?.groupValues?.get(1)
            ?: throw Exception("DeltaBit: sin hash en la página")
        val id = Regex("""name=["']id["']\s+value=["']([^"']+)["']""")
            .find(firstHtml)?.groupValues?.get(1)
            ?: finalUrl.trimEnd('/').substringAfterLast('/')
        val fname = Regex("""name=["']fname["']\s+value=["']([^"']+)["']""")
            .find(firstHtml)?.groupValues?.get(1).orEmpty()
        val fileId = hash.substringBefore('-')

        delay(5_500)

        val body = FormBody.Builder()
            .add("op", "download1")
            .add("usr_login", "")
            .add("id", id)
            .add("fname", fname)
            .add("referer", "")
            .add("hash", hash)
            .add("imhuman", "")
            .build()

        val postRequest = Request.Builder()
            .url("https://deltabit.co/$id")
            .post(body)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Origin", "https://deltabit.co")
            .header("Referer", finalUrl)
            .header("Cookie", "file_id=$fileId; aff=5; ref_url=$finalUrl")
            .build()

        val secondHtml = httpClient.newCall(postRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("DeltaBit: POST ${response.code}")
            response.body?.string().orEmpty()
        }

        val source = Regex("""sources:\s*\[\s*["']([^"']+)["']""").find(secondHtml)?.groupValues?.get(1)
            ?: throw Exception("DeltaBit: sin fuente de video")

        return Video(
            source = source,
            headers = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Referer" to "https://deltabit.co/",
            ),
        )
    }
}
