package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * Videoapp: el embed sólo monta un `<iframe id="frame">`; el stream real lo
 * resuelve el extractor que corresponda a ese iframe.
 */
class VideoappExtractor : Extractor() {

    override val name = "Videoapp"
    override val mainUrl = "https://videoapp.zip"

    fun server(videoType: Video.Type): Video.Server {
        val url = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/e/movie/${videoType.id}"
            is Video.Type.Episode ->
                "$mainUrl/e/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
        }
        return Video.Server(id = url, name = "$name [LAT]", src = url)
    }

    override suspend fun extract(link: String): Video {
        val document = Service.build(mainUrl).get(link)
        val iframeSrc = document.selectFirst("#frame")?.attr("src")
            ?: throw Exception("Videoapp: sin iframe")
        return Extractor.extract(iframeSrc)
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(JsoupConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
            "Accept-Language: en-US,en;q=0.9",
        )
        suspend fun get(@Url url: String): Document
    }
}
