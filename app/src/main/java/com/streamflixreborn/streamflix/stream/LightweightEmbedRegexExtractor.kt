package com.streamflixreborn.streamflix.stream

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Lightweight regex-based embed parser & Dean-Edwards (`eval(function(p,a,c,k,e,d)...)`) unpacker
 * capable of extracting direct `.m3u8` / `.mp4` playback URLs from Streamwish, Voe, Filemoon,
 * VidHide, Luluvdo, and generic JWPlayer/Clappr embeds when rotating domains bypass host matchers.
 */
object LightweightEmbedRegexExtractor {

    private val DIRECT_MEDIA_REGEX = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val JW_FILE_REGEX = Regex(
        """(?:file|hls|src|source)\s*[:=]\s*["'](https?://[^"']+?\.(?:m3u8|mp4)[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val ATOB_HLS_REGEX = Regex(
        """(?:hls|source|file)\w*\s*[:=]\s*atob\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    private val PACKED_JS_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(2_500, TimeUnit.MILLISECONDS)
            .readTimeout(2_800, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun extractDirectStream(embedUrl: String, refererHint: String? = null): Video? =
        withContext(Dispatchers.IO) {
            val cleanUrl = embedUrl.trim()
            if (!cleanUrl.startsWith("http")) return@withContext null

            // Already a direct HLS or MP4 link
            if (cleanUrl.contains(".m3u8", ignoreCase = true) ||
                cleanUrl.contains(".mp4", ignoreCase = true)
            ) {
                return@withContext buildVideo(cleanUrl, refererHint ?: cleanUrl)
            }

            val parsedHost = cleanUrl.toHttpUrlOrNull() ?: return@withContext null
            val origin = "${parsedHost.scheme}://${parsedHost.host}"
            val referer = refererHint ?: "$origin/"

            val html = runCatching {
                val req = Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use ""
                    resp.body?.string().orEmpty()
                }
            }.getOrDefault("")

            if (html.isBlank()) return@withContext null

            // Unpack any Dean Edwards packed JS blocks (common on StreamWish, Filemoon, VidHide)
            val unpackedBuilder = StringBuilder(html)
            PACKED_JS_REGEX.findAll(html).forEach { match ->
                val payload = match.groupValues[1].replace("\\'", "'")
                val radix = match.groupValues[2].toIntOrNull() ?: 36
                val count = match.groupValues[3].toIntOrNull() ?: 0
                val symtab = match.groupValues[4].split("|")
                val unpacked = unpackDeanEdwards(payload, radix, count, symtab)
                if (unpacked.isNotBlank()) {
                    unpackedBuilder.append("\n").append(unpacked)
                }
            }

            val fullCorpus = unpackedBuilder.toString().replace("\\/", "/")

            // 1. Check base64-encoded HLS URLs (common on Voe variants)
            ATOB_HLS_REGEX.findAll(fullCorpus).forEach { m ->
                val decoded = runCatching {
                    String(Base64.decode(m.groupValues[1], Base64.DEFAULT)).trim()
                }.getOrNull()
                if (decoded != null && decoded.startsWith("http")) {
                    return@withContext buildVideo(decoded, "$origin/")
                }
            }

            // 2. Check explicit player `file:` / `hls:` fields first
            JW_FILE_REGEX.find(fullCorpus)?.groupValues?.getOrNull(1)?.let { mediaUrl ->
                if (!isIgnoredSampleUrl(mediaUrl)) {
                    return@withContext buildVideo(mediaUrl, "$origin/")
                }
            }

            // 3. Fallback to any direct .m3u8 / .mp4 URL in the page/unpacked JS
            val candidateUrl = DIRECT_MEDIA_REGEX.findAll(fullCorpus)
                .map { it.value }
                .firstOrNull { !isIgnoredSampleUrl(it) }
                ?: return@withContext null

            buildVideo(candidateUrl, "$origin/")
        }

    private fun isIgnoredSampleUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("preroll") ||
            lower.contains("blank.mp4") ||
            lower.contains("trailer") ||
            lower.endsWith(".vtt") ||
            lower.endsWith(".srt")
    }

    private fun buildVideo(mediaUrl: String, referer: String): Video {
        val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)
        val origin = referer.trimEnd('/')
        return Video(
            source = mediaUrl,
            type = if (isM3u8) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Referer" to referer,
                "Origin" to origin,
            ),
        )
    }

    private fun unpackDeanEdwards(
        payload: String,
        radix: Int,
        count: Int,
        symtab: List<String>,
    ): String {
        if (radix < 2 || radix > 62 || count <= 0 || symtab.isEmpty()) return ""
        return Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { match ->
            val token = match.value
            val index = decodeBaseN(token, radix)
            if (index in 0 until minOf(count, symtab.size)) {
                symtab[index].ifEmpty { token }
            } else {
                token
            }
        }
    }

    private fun decodeBaseN(token: String, radix: Int): Int {
        var acc = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            acc = acc * radix + digit
        }
        return acc
    }
}
