package com.streamflixreborn.streamflix.extractors

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * Vidlove: la API entrega un JSON con el HLS (`source.url`) y los subtítulos.
 *
 * Expone `servers(videoType)` porque sus "servidores" son en realidad **fuentes
 * de la misma API** (moviebox, dcloud, tcloud, vidapi…). La de `moviebox2` sólo
 * sirve HEVC, así que se oculta en dispositivos sin decoder HEVC: si no, el
 * usuario elige un servidor que nunca va a reproducir.
 */
class VidloveExtractor : Extractor() {

    override val name = "Vidlove"
    override val mainUrl = "https://api.vidlove.cc"

    companion object {
        val supportsHevc: Boolean by lazy {
            try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { type ->
                        type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    data class ServerConfig(val name: String, val sourceKey: String)

    private val serversConfig = listOf(
        ServerConfig("Barbarian King 2.0", "moviebox2"),
        ServerConfig("Barbarian King", "moviebox"),
        ServerConfig("Electro Dragon", "dcloud"),
        ServerConfig("Ice Wizard", "tcloud"),
        ServerConfig("Archer Queen", "vidapi"),
    )

    fun servers(videoType: Video.Type): List<Video.Server> = serversConfig
        .filter { config -> config.sourceKey != "moviebox2" || supportsHevc }
        .map { config ->
            val extraParams = when (config.sourceKey) {
                "moviebox", "moviebox2" -> if (supportsHevc) "&hevc=1&vh=1" else "&vh=1"
                "dcloud" -> "&sw=1"
                else -> ""
            }
            val url = when (videoType) {
                is Video.Type.Movie ->
                    "$mainUrl/movie?id=${videoType.id}&mode=json&sources=${config.sourceKey}$extraParams"

                is Video.Type.Episode ->
                    "$mainUrl/tv?id=${videoType.tvShow.id}&season=${videoType.season.number}" +
                        "&episode=${videoType.number}&mode=json&sources=${config.sourceKey}$extraParams"
            }
            Video.Server(id = config.name, name = "${config.name} (Vidlove)", src = url)
        }

    override suspend fun extract(link: String): Video {
        val json = JSONObject(Service.build(mainUrl).get(link))

        val source = json.optJSONObject("source")?.optString("url").orEmpty()
        if (source.isEmpty()) throw Exception("Vidlove: sin fuente de video")

        val subtitles = buildList {
            val array = json.optJSONArray("subtitles") ?: return@buildList
            for (index in 0 until array.length()) {
                val subtitle = array.optJSONObject(index) ?: continue
                val file = subtitle.optString("file")
                if (file.isNotEmpty()) {
                    add(Video.Subtitle(label = subtitle.optString("label", "Subtitle"), file = file))
                }
            }
        }

        return Video(
            source = source,
            type = MimeTypes.APPLICATION_M3U8,
            subtitles = subtitles,
            headers = mapOf("Referer" to "https://player.vidlove.cc/"),
        )
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(ScalarsConverterFactory.create())
                .client(OkHttpClient.Builder().build())
                .build()
                .create(Service::class.java)
        }

        @Headers(
            "Origin: https://player.vidlove.cc",
            "Referer: https://player.vidlove.cc/",
        )
        @GET
        suspend fun get(@Url url: String): String
    }
}
