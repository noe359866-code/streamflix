package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jsoup.parser.Parser
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import java.net.URL

/**
 * Hexload: el embed no trae el video, hay que pedirlo a `POST /download` con
 * `op=download3` en modo ajax y leer `result.url` (que viene escapado con
 * entidades HTML, de ahí el `unescapeEntities`).
 */
class HexloadExtractor : Extractor() {

    override val name = "Hexload"
    override val mainUrl = "https://hexload.com"

    override suspend fun extract(link: String): Video {
        val segments = URL(link).path.split("/").filter { it.isNotEmpty() }
        val target = segments.firstOrNull { it.startsWith("embed-") }
            ?: segments.firstOrNull { it != "e" && it != "d" && it != "embed" }
            ?: throw Exception("Hexload: sin código de archivo en la URL")
        val fileCode = target.removePrefix("embed-").substringBefore(".")
        if (fileCode.isEmpty()) throw Exception("Hexload: código vacío en $link")

        val baseUrl = URL(link).protocol + "://" + URL(link).host
        val response = Service.build(baseUrl).getDownload(
            op = "download3",
            id = fileCode,
            ajax = "1",
            methodFree = "1",
            dataType = "json",
            referer = link,
        ).string()

        val json = runCatching { JsonParser.parseString(response).asJsonObject }
            .getOrElse { throw Exception("Hexload: respuesta no JSON") }
        val result = json.getAsJsonObject("result") ?: throw Exception("Hexload: sin result")
        val rawUrl = result.get("url")?.asString ?: throw Exception("Hexload: sin url")

        return Video(source = Parser.unescapeEntities(rawUrl, false))
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(OkHttpClient.Builder().dns(DnsResolver.doh).build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @POST("download")
        @FormUrlEncoded
        @Headers("x-requested-with: XMLHttpRequest")
        suspend fun getDownload(
            @Field("op") op: String,
            @Field("id") id: String,
            @Field("ajax") ajax: String,
            @Field("method_free") methodFree: String,
            @Field("dataType") dataType: String,
            @Header("Referer") referer: String,
        ): ResponseBody
    }
}
