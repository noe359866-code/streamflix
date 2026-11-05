package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Videm: el catálogo de servidores vive en la variable `Q` del embed
 * (`ssr.servers[]` con `lang`/`ref`/`name`) y el stream real se pide a
 * `api.php?a=play&ref=...&t=...`, que devuelve un `url` relativo al m3u8.
 */
class VidemExtractor : Extractor() {

    override val name = "Videm"
    override val mainUrl = "https://videm.xyz"

    /**
     * Servidores de Videm para un título. Sólo devuelve los del idioma pedido:
     * el resto son doblajes que no vamos a reproducir.
     */
    suspend fun servers(videoType: Video.Type, language: String): List<Video.Server> {
        val url = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/embed/movie/${videoType.id}"
            is Video.Type.Episode ->
                "$mainUrl/embed/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
        }

        val targetLanguage = when (language) {
            "es" -> "Spanish"
            "fr" -> "French"
            "en" -> "English"
            else -> return emptyList()
        }

        return try {
            val html = Service.build(mainUrl).get(url)
            val qJson = Regex("""var Q = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?: throw Exception("Videm: no se encontró el JSON Q")

            val root = JSONObject(qJson)
            val token = root.optString("t")
            val serversArray = root.optJSONObject("ssr")?.optJSONArray("servers") ?: return emptyList()

            buildList {
                for (index in 0 until serversArray.length()) {
                    val server = serversArray.optJSONObject(index) ?: continue
                    if (!server.optString("lang").equals(targetLanguage, ignoreCase = true)) continue
                    val ref = server.optString("ref")
                    if (ref.isEmpty()) continue
                    add(
                        Video.Server(
                            id = "videm-$ref",
                            name = "${server.optString("name", "Server")} (Videm)",
                            src = "$mainUrl/api.php?a=play&ref=$ref&t=$token",
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("VidemExtractor", "Error obteniendo servidores de $url", e)
            emptyList()
        }
    }

    override suspend fun extract(link: String): Video {
        val json = JSONObject(Service.build(mainUrl).get(link))
        val streamPath = json.optString("url")
        if (streamPath.isEmpty()) throw Exception("Videm: sin url en la respuesta")

        return Video(
            source = "$mainUrl$streamPath",
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
            ),
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", "$baseUrl/")
                                .build()
                        )
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): String
    }
}
