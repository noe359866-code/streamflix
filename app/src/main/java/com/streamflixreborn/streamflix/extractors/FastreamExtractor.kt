package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.JsUnpacker
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/**
 * Fastream: el player viene empaquetado con el packer de Dean Edwards
 * (`eval(function(p,a,c,k,e,d)...)`); después de descomprimir se lee el
 * `sources: [{file: ...}]` del jwplayer.
 */
class FastreamExtractor : Extractor() {

    override val name = "Fastream"
    override val mainUrl = "https://fastream.to"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val service: FastreamService by lazy {
        Retrofit.Builder()
            .baseUrl(mainUrl)
            .addConverterFactory(ScalarsConverterFactory.create())
            .client(OkHttpClient.Builder().dns(DnsResolver.doh).build())
            .build()
            .create(FastreamService::class.java)
    }

    private interface FastreamService {
        @GET
        suspend fun get(@Url url: String, @Header("User-Agent") userAgent: String): String
    }

    override suspend fun extract(link: String): Video {
        val html = service.get(link, USER_AGENT)

        val packed = html.substringAfter("eval(function(p,a,c,k,e,d)", "")
            .substringBefore("</script>")
            .let { if (it.isEmpty()) "" else "eval(function(p,a,c,k,e,d)$it" }

        if (!packed.startsWith("eval")) throw Exception("Fastream: no hay JS empaquetado")
        val unpacked = JsUnpacker(packed).unpack() ?: throw Exception("Fastream: fallo al descomprimir")

        if (!("jwplayer" in unpacked && "sources" in unpacked && "file" in unpacked)) {
            throw Exception("Fastream: sin player jwplayer")
        }

        val source = Regex("""sources:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
            .find(unpacked)?.groupValues?.get(1)
            ?: throw Exception("Fastream: sin URL de stream")

        return Video(
            source = source,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Sec-Fetch-Dest" to "empty",
                "Accept" to "*/*",
            ),
        )
    }
}
