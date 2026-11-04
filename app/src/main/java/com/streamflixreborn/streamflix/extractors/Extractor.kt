package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.scrapers.HttpScraper

abstract class Extractor {

    abstract val name: String
    abstract val mainUrl: String
    open val aliasUrls: List<String> = emptyList()
    open val rotatingDomain: List<Regex> = emptyList()

    // THIS is the main method all subclasses must implement
    abstract suspend fun extract(link: String): Video

    /**
     * Punto de entrada que usan los providers. Por defecto delega en [extract],
     * pero los extractores pueden sobrescribirlo para aprovechar el [server]
     * (nombre visible, subtítulos o headers asociados al servidor elegido).
     */
    open suspend fun extract(link: String, server: Video.Server? = null): Video {
        return extract(link)
    }

    companion object {
        private val extractors = listOf(
            RabbitstreamExtractor(),
            RabbitstreamExtractor.MegacloudExtractor(),
            RabbitstreamExtractor.DokicloudExtractor(),
            RabbitstreamExtractor.PremiumEmbedingExtractor(),
            UpzoneExtractor(),
            StreamhubExtractor(),
            VtubeExtractor(),
            VoeExtractor(),
            StreamtapeExtractor(),
            VidozaExtractor(),
            VidsrcToExtractor(),
            VidplayExtractor(),
            FilemoonExtractor(),
            VidplayExtractor.MyCloud(),
            VidplayExtractor.VidplayOnline(),
            MyFileStorageExtractor(),
            MoflixExtractor(),
            MStreamDayExtractor(),
            MStreamClickExtractor(),
            VidsrcNetExtractor(),
            StreamWishExtractor(),
            StreamWishExtractor.UqloadsXyz(),
            StreamWishExtractor.SwishExtractor(),
            StreamWishExtractor.HlswishExtractor(),
            StreamWishExtractor.PlayerwishExtractor(),
            StreamWishExtractor.SwiftPlayersExtractor(),
            TwoEmbedExtractor(),
            ChillxExtractor(),
            ChillxExtractor.JeanExtractor(),
            MoviesapiExtractor(),
            CloseloadExtractor(),
            LuluVdoExtractor(),
            DoodLaExtractor(),
            DoodLaExtractor.DoodLiExtractor(),
            VidPlyExtractor(),
            MagaSavorExtractor(),
            VidMoLyExtractor(),
            VidMoLyExtractor.ToDomain(),
            VideoSibNetExtractor(),
            SaveFilesExtractor(),
            BigWarpExtractor(),
            DoodLaExtractor.DoodExtractor(),
            LoadXExtractor(),
            VidHideExtractor(),
            VeevExtractor(),
            RidooExtractor(),
            USTRExtractor(),
            VidGuardExtractor(),
            OkruExtractor(),
            VixSrcExtractor(),
            GoodstreamExtractor(),
            LamovieExtractor(),
            UqloadExtractor(),
            MailRuExtractor(),
            MixDropExtractor(),
            SupervideoExtractor(),
            DroploadExtractor(),
            RpmvidExtractor(),
            YourUploadExtractor(),
            PlusPomlaExtractor(),
            OneuploadExtractor(),
            FsvidExtractor(),
            GoogleDriveExtractor(),
            PcloudExtractor(),
            AmazonDriveExtractor(),
            VidzyExtractor(),
            GuploadExtractor(),
            StreamUpExtractor(),
            EinschaltenExtractor(),
            VidLinkExtractor(),
            VidsrcRuExtractor(),
            VidflixExtractor(),
            VidrockExtractor(),
            VideasyExtractor(),
            VidzeeExtractor(),
            VidnestExtractor(),
            PrimeSrcExtractor(),
            VidoraExtractor(),
            GxPlayerExtractor(),
            UpZurExtractor(),
            DailymotionExtractor(),
            ApiVoirFilmExtractor(),
            StreamixExtractor(),
            ShareCloudyExtractor(),
            StreamrubyExtractor(),
            VidaraExtractor(),
            VidsonicExtractor(),
            HxfileExtractor(),
            ZillaExtractor(),
            PDrainExtractor(),
            MaxstreamExtractor(),
            // --- Portados de upstream main (resolvedores de hosts concretos) ---
            VidemExtractor(),
            VidloveExtractor(),
            VideoappExtractor(),
            HexloadExtractor(),
            TurbovidExtractor(),
            DeltaBitExtractor(),
            FastreamExtractor(),
            // --- Español / latino ---
            // Los extractores genéricos van al final: se usan como último recurso
            // cuando ningún extractor específico coincide con el host.
            FirestreamExtractor(),
            JKPlayerExtractor(),
            MovieMasterExtractor(),
            EsprinahyExtractor(),
            StreamSBExtractor(),
            Mp4UploadExtractor(),
            NinjaStreamExtractor(),
            UchExtractor(),
        )

        private const val TAG = "Extractor"

        /**
         * Resolución de "bridge" universal (StreamHG/Sync/Cuevana).
         *
         * Antes esta lógica era una petición bloqueante sin timeout ni reintentos.
         * Ahora usa [HttpScraper], así que corre en IO, cierra la respuesta y
         * entiende tanto `window.location.replace` como `href`, `<iframe src>`
         * o meta-refresh.
         */
        private suspend fun resolveBridge(link: String): String? {
            val html = HttpScraper.getText(link, attempts = 1) ?: return null
            val candidates = listOf(
                html.substringAfter("window.location.replace(\"", "").substringBefore("\""),
                html.substringAfter("window.location.href = \"", "").substringBefore("\""),
                html.substringAfter("location.href=\"", "").substringBefore("\""),
                html.substringAfter("src=\"", "").substringBefore("\""),
                Regex("""(?i)<meta[^>]+url=([\"'])?""").find(html)?.groupValues?.get(1).orEmpty(),
            )
            return candidates.firstOrNull { it.startsWith("http") }
        }

        /** Normaliza un host para comparar: sin esquema, sin `www.`, sin ruta, en minúsculas. */
        private fun hostOf(value: String): String = value
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .removePrefix("www.")

        /** Registro del extractor, con su host normalizado ya precalculado. */
        private class Registration(
            val extractor: Extractor,
            val hosts: List<String>,
            val rootDomains: List<String>,
        )

        private val registrations: List<Registration> by lazy {
            extractors.map { extractor ->
                val all = buildList {
                    add(extractor.mainUrl)
                    addAll(extractor.aliasUrls)
                }.filter { it.isNotBlank() }
                Registration(
                    extractor = extractor,
                    hosts = all.map(::hostOf).filter { it.isNotBlank() },
                    // `filemoon.site` -> `filemoon` para tolerar dominios rotatorios
                    rootDomains = all.map { host -> hostOf(host).substringBefore('.') }
                        .filter { it.length > 3 },
                )
            }
        }

        /**
         * Tipo MIME si el enlace es un archivo directo **HLS o MP4**.
         *
         * La app sólo entrega estos dos formatos: cualquier otra cosa (MPD/DASH,
         * MKV, WEBM, FLV…) no se considera un stream válido y el extractor
         * intentará resolverla como player empaquetado.
         */
        private fun directMediaType(link: String): String? = formatOf(link)

        /** `MimeTypes.APPLICATION_M3U8` para HLS, `MimeTypes.VIDEO_MP4` para MP4, `null` si no es ninguno. */
        internal fun formatOf(link: String): String? {
            val path = link.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".m3u8") || path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mp4") || path.contains(".mp4") -> MimeTypes.VIDEO_MP4
                else -> null
            }
        }

        private fun findExtractor(link: String, server: Video.Server?): Extractor? {
            val host = hostOf(link)

            // 1) Coincidencia exacta de host (o sufijo de host: `a.filemoon.site`).
            registrations.firstOrNull { registration ->
                registration.hosts.any { expected -> host == expected || host.endsWith(".$expected") }
            }?.let { return it.extractor }

            // 2) Coincidencia por dominio raíz (`filemoon.to` -> `filemoon`).
            registrations.firstOrNull { registration ->
                registration.rootDomains.any { root -> host.substringBefore('.') == root || host.contains(root) }
            }?.let { return it.extractor }

            // 3) Dominios rotatorios declarados por el extractor.
            val compareUrl = link.lowercase()
            registrations.firstOrNull { registration ->
                registration.extractor.rotatingDomain.any { it.containsMatchIn(compareUrl) }
            }?.let { return it.extractor }

            // 4) Fallback por el nombre del servidor devuelto por el provider.
            val serverName = server?.name?.lowercase().orEmpty()
            if (serverName.isNotBlank()) {
                registrations.firstOrNull { registration ->
                    serverName.contains(registration.extractor.name.lowercase())
                }?.let { return it.extractor }
            }
            return null
        }

        /**
         * Nombre del extractor que atendería [link] (o `"direct"` si es un
         * archivo multimedia servido tal cual). Pensado para diagnóstico y tests:
         * permite comprobar que **cada** reproductor de un provider tiene resolver.
         */
        internal fun describe(link: String, server: Video.Server? = null): String? =
            findExtractor(link, server)?.name ?: directMediaType(link)?.let { "direct" }

        suspend fun extract(link: String, server: Video.Server? = null): Video {
            if (link.isBlank()) throw Exception("Empty video link")

            var finalLink = link

            // Bridge de los providers (mysync.mov) resuelto antes de buscar extractor.
            if (link.contains("mysync.mov/stream/")) {
                runCatching { resolveBridge(link) }
                    .onSuccess { resolved ->
                        if (!resolved.isNullOrBlank()) {
                            Log.d(TAG, "Universal Bridge resolved: $link -> $resolved")
                            finalLink = resolved
                        }
                    }
                    .onFailure { Log.w(TAG, "Universal Bridge error: ${it.message}") }
            }

            val foundExtractor = findExtractor(finalLink, server)
            if (foundExtractor != null) {
                Log.i(TAG, "[EXTRACTOR] -> Starting: ${foundExtractor.name} (URL: $finalLink)")
                // El servidor original se propaga: muchos extractores lo usan para
                // rellenar subtítulos/títulos y para conservar el nombre visible.
                val video = foundExtractor.extract(finalLink, server)
                Log.i(TAG, "[VIDEO] -> Extracted: ${video.source}")
                return video
            }

            // 5) Enlaces directos: sólo HLS (.m3u8) y MP4 se aceptan como stream.
            directMediaType(finalLink)?.let { mediaType ->
                Log.i(TAG, "[DIRECT] -> $finalLink")
                return Video(source = finalLink, type = mediaType)
            }

            // 6) Último recurso: tratar el host desconocido como fuente empaquetada.
            //    Evita el clásico "No extractors found" cuando el player es nuevo.
            if (finalLink.startsWith("http")) {
                Log.i(TAG, "[GENERIC] -> $finalLink")
                runCatching { FallbackPackedExtractor(finalLink).extract(finalLink, server) }
                    .onSuccess { return it }
                    .onFailure { Log.w(TAG, "Generic extraction failed: ${it.message}") }
            }

            throw Exception("No extractors found for URL: $finalLink")
        }
    }
}
