package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Goodstream: el embed trae un jwplayer con `sources: [{file: "...m3u8"}]`
 * dentro de un `<script type="text/javascript">`. Se busca el `file:` del bloque
 * que realmente contiene `jwplayer` y `sources` (hay varios scripts con
 * `file:` de configuración que no son video).
 */
class GoodstreamExtractor : Extractor() {

    override val name = "Goodstream"
    override val mainUrl = "https://goodstream.one"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun extract(link: String): Video {
        val document = Service.build(mainUrl).get(link)

        var m3u8: String? = null
        for (script in document.select("script[type*=javascript]")) {
            val data = script.data()
            if ("jwplayer" in data && "sources" in data && "file" in data) {
                m3u8 = Regex("""file\s*:\s*["']([^"']+)["']""").find(data)?.groupValues?.get(1)
                if (m3u8 != null) break
            }
        }

        return Video(
            source = m3u8 ?: throw Exception("Goodstream: sin fuente"),
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to mainUrl,
            ),
        )
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .addHeader(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                )
                                .addHeader("Accept-Language", "en-US,en;q=0.9")
                                .build()
                        )
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }
}
