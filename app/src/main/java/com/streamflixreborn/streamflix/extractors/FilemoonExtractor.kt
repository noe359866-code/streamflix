package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.JvmSuppressWildcards
import retrofit2.http.POST
import retrofit2.http.Url
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Filemoon.
 *
 * La versión anterior pedía `/api/videos/{id}/embed/details` y decodificaba el
 * playback con las dos primeras `key_parts`. El player cambió: ahora el embed
 * exige un **challenge → attest** con una firma ECDSA P-256 de un nonce, y en
 * algunos videos un **proof-of-work** de captcha. Sin ese baile de tokens la
 * respuesta de playback no descifra y el stream sale vacío.
 *
 * Implementación:
 * 1. `details` (+`settings` para saber si hay captcha) sobre el dominio real del enlace;
 * 2. `access/challenge` → nonce;
 * 3. attest: se genera un par EC P-256 por petición, se firma el nonce
 *    (SHA256withECDSA) y se envía en DER convertido a raw r||s + la JWK pública;
 * 4. si el video lo pide, PoW de captcha con el mismo hash que usa la web;
 * 5. `embed/playback` → payload AES-GCM; la clave se arma con las `key_parts`
 *    que indica `version` (no siempre son las dos primeras).
 *
 * Sólo devuelve HLS; si algo falla lanza para que el reproductor pruebe el
 * siguiente servidor (ver StreamAutoSelector).
 */
open class FilemoonExtractor : Extractor() {

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.site"
    override val aliasUrls = listOf(
        "https://bf0skv.org",
        "https://bysejikuar.com",
        "https://moflix-stream.link",
        "https://bysezoxexe.com",
        "https://bysebuho.com",
        "https://filemoon.sx",
        "https://bysekoze.com",
        "https://bysesayeveum.com",
        "https://bysezejataos.com",
        "https://byselapuix.com",
    )

    private var deviceId = UUID.randomUUID().toString().replace("-", "")

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        // El enlace puede ser /e/{id} (embed) o /d/{id} (download).
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link)
            ?: throw Exception("No se pudo extraer el id de Filemoon")
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]

        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("No se pudo extraer el dominio de Filemoon")

        val apiPrefix = if (linkType == "e") "embed/" else ""
        val detailsUrl = "$currentDomain/api/videos/$videoId/${apiPrefix}details"
        val settingsUrl = "$currentDomain/api/videos/$videoId/${apiPrefix}settings"

        val initialHeaders = mapOf(
            "Referer" to link,
            "Origin" to currentDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
        )

        val details = service.getDetails(detailsUrl, initialHeaders)
        val embedFrameUrl = details.embed_frame_url
            ?: throw Exception("Filemoon: falta embed_frame_url")

        val playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
            ?: currentDomain

        val captchaRequired = runCatching {
            service.getSettings(settingsUrl, initialHeaders).captcha_required
        }.getOrNull() == true

        // 1. Challenge
        val challenge = service.getChallenge(
            "$playbackDomain/api/videos/access/challenge",
            mapOf(
                "Referer" to embedFrameUrl,
                "Origin" to playbackDomain,
                "User-Agent" to Service.DEFAULT_USER_AGENT,
                "Content-Length" to "0",
            ),
        )
        val challengeId = challenge.challenge_id ?: throw Exception("Filemoon: falta challenge_id")
        val nonce = challenge.nonce ?: throw Exception("Filemoon: falta nonce")
        var viewerId = UUID.randomUUID().toString().replace("-", "")

        // 2. Attest (firma EC del nonce)
        val attestation = generateAttestation(nonce)
        val attestPayload: Map<String, Any> = mapOf(
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "challenge_id" to challengeId,
            "nonce" to nonce,
            "signature" to attestation.signature,
            "public_key" to attestation.publicKey,
            "client" to mapOf(
                "user_agent" to Service.DEFAULT_USER_AGENT,
                "architecture" to "x86",
                "bitness" to "64",
                "platform" to "Windows",
                "platform_version" to "10.0.0",
                "pixel_ratio" to 1.0,
                "screen_width" to 1920,
                "screen_height" to 1080,
                "languages" to listOf("en-US"),
            ),
            "storage" to mapOf(
                "cookie" to viewerId,
                "local_storage" to viewerId,
                "indexed_db" to "$viewerId:$deviceId",
                "cache_storage" to "$viewerId:$deviceId",
            ),
            "attributes" to mapOf("entropy" to "high"),
        )

        val attestResponse = service.attest(
            "$playbackDomain/api/videos/access/attest",
            attestPayload,
            mapOf(
                "Referer" to embedFrameUrl,
                "Origin" to playbackDomain,
                "User-Agent" to Service.DEFAULT_USER_AGENT,
                "Content-Type" to "application/json",
            ),
        )
        val token = attestResponse.token ?: throw Exception("Filemoon: falta token de attest")
        viewerId = attestResponse.viewer_id ?: viewerId
        deviceId = attestResponse.device_id ?: deviceId
        val confidence = attestResponse.confidence ?: throw Exception("Filemoon: falta confidence")

        val fingerprint: Map<String, Any> = mapOf(
            "token" to token,
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "confidence" to confidence,
        )

        val playbackHeaders = mutableMapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "X-Embed-Parent" to link,
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Content-Type" to "application/json",
        )

        // 3. Captcha / proof of work (sólo si el video lo exige)
        if (captchaRequired) {
            val captchaResponse = service.getCaptcha(
                "$playbackDomain/api/videos/$videoId/embed/captcha",
                mapOf("fingerprint" to fingerprint),
                playbackHeaders,
            )
            val powNonce = captchaResponse.pow_nonce ?: throw Exception("Filemoon: falta pow_nonce")
            val powDifficulty = captchaResponse.pow_difficulty ?: 16
            val powToken = captchaResponse.pow_token ?: throw Exception("Filemoon: falta pow_token")

            val solution = solvePow(powNonce, powDifficulty)
            val verify = service.verifyCaptcha(
                "$playbackDomain/api/videos/$videoId/embed/captcha/verify",
                mapOf(
                    "pow_token" to powToken,
                    "solution" to solution,
                    "fingerprint" to fingerprint,
                ),
                playbackHeaders,
            )
            val captchaToken = verify.token ?: throw Exception("Filemoon: captcha sin token")
            playbackHeaders["X-Captcha-Token"] = captchaToken
        }

        // 4. Playback
        val playbackResponse = service.getPlayback(
            "$playbackDomain/api/videos/$videoId/embed/playback",
            mapOf("fingerprint" to fingerprint),
            playbackHeaders,
        )
        val playbackData = playbackResponse.playback ?: throw Exception("Filemoon: sin playback")

        val json = JSONObject(decryptPlayback(playbackData))
        val sources = json.optJSONArray("sources") ?: throw Exception("Filemoon: sin sources")
        val sourceUrl = sources.getJSONObject(0).getString("url")

        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to embedFrameUrl,
                "Origin" to playbackDomain,
                "User-Agent" to Service.DEFAULT_USER_AGENT,
            ),
        )
    }

    private fun generateAttestation(nonce: String): Attestation {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey

        val x = Base64.encodeToString(
            publicKey.w.affineX.toByteArray().stripLeadingZero(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val y = Base64.encodeToString(
            publicKey.w.affineY.toByteArray().stripLeadingZero(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(nonce.toByteArray())
        val derSignature = signer.sign()

        val encodedSignature = Base64.encodeToString(
            derToRawSignature(derSignature),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val jwk = mapOf(
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "kty" to "EC",
            "x" to x,
            "y" to y,
        )

        return Attestation(encodedSignature, jwk)
    }

    /** DER (SEQUENCE{r,s}) → r||s de 64 bytes, que es lo que espera el server. */
    private fun derToRawSignature(der: ByteArray): ByteArray {
        var offset = 2
        val rLength = der[offset + 1].toInt()
        val r = der.copyOfRange(offset + 2, offset + 2 + rLength).stripLeadingZero()
        offset += 2 + rLength
        val sLength = der[offset + 1].toInt()
        val s = der.copyOfRange(offset + 2, offset + 2 + sLength).stripLeadingZero()

        val raw = ByteArray(64)
        System.arraycopy(r, 0, raw, 32 - r.size, r.size)
        System.arraycopy(s, 0, raw, 64 - s.size, s.size)
        return raw
    }

    private fun ByteArray.stripLeadingZero(): ByteArray =
        if (isNotEmpty() && this[0] == 0.toByte()) copyOfRange(1, size) else this

    data class Attestation(val signature: String, val publicKey: Map<String, Any>)

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)

        val keyParts = data.key_parts
        val version = data.version?.toIntOrNull()
        val (first, second) = if (version != null && version in 1..20 && keyParts.size >= 30) {
            // La clave rota según la versión: una parte del principio y otra del final.
            val index1 = version - 1
            val index2 = (31 - version) - 1
            Pair(
                Base64.decode(keyParts[index1], Base64.URL_SAFE),
                Base64.decode(keyParts[index2], Base64.URL_SAFE),
            )
        } else if (keyParts.size >= 2) {
            Pair(
                Base64.decode(keyParts[0], Base64.URL_SAFE),
                Base64.decode(keyParts[1], Base64.URL_SAFE),
            )
        } else {
            throw Exception("Filemoon: faltan key_parts para descifrar")
        }

        val key = ByteArray(first.size + second.size)
        System.arraycopy(first, 0, key, 0, first.size)
        System.arraycopy(second, 0, key, first.size, second.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    open class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(@Url url: String, @HeaderMap headers: Map<String, String>): DetailsResponse

        @GET
        suspend fun getSettings(@Url url: String, @HeaderMap headers: Map<String, String>): SettingsResponse

        @POST
        suspend fun getChallenge(@Url url: String, @HeaderMap headers: Map<String, String>): ChallengeResponse

        @JvmSuppressWildcards
        @POST
        suspend fun attest(
            @Url url: String,
            @Body body: Map<String, Any>,
            @HeaderMap headers: Map<String, String>,
        ): AttestResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getCaptcha(
            @Url url: String,
            @Body body: Map<String, Any>,
            @HeaderMap headers: Map<String, String>,
        ): CaptchaResponse

        @JvmSuppressWildcards
        @POST
        suspend fun verifyCaptcha(
            @Url url: String,
            @Body body: Map<String, Any>,
            @HeaderMap headers: Map<String, String>,
        ): VerifyCaptchaResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getPlayback(
            @Url url: String,
            @Body body: Map<String, Any>,
            @HeaderMap headers: Map<String, String>,
        ): PlaybackResponse

        companion object {
            const val DEFAULT_USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

            fun build(baseUrl: String): Service {
                val cookieJar = object : okhttp3.CookieJar {
                    private val store = HashMap<String, List<okhttp3.Cookie>>()

                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        store[url.host] = cookies
                    }

                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                        store[url.host] ?: emptyList()
                }

                val client = OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class SettingsResponse(
        val captcha_required: Boolean? = false,
        val embed_domain_allowed: String? = null,
    )
    data class ChallengeResponse(val challenge_id: String?, val nonce: String?, val viewer_hint: String?)
    data class AttestResponse(
        val token: String?,
        val viewer_id: String?,
        val device_id: String?,
        val confidence: Double?,
    )
    data class CaptchaResponse(val pow_nonce: String?, val pow_difficulty: Int?, val pow_token: String?)
    data class VerifyCaptchaResponse(val status: String?, val token: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String> = emptyList(),
        val version: String? = null,
    )

    companion object {
        private const val BE = 512
        private const val LT = BE - 1
        private const val DR = 2
        private const val LR = -1640531535 // 2654435761.toInt()
        private const val HR = -2048144777 // 2246822519L.toInt()

        private fun rotl(t: Int, e: Int): Int = (t shl e) or (t ushr (32 - e))

        private fun ye(t: IntArray) {
            t[0] = t[0] + t[1]
            t[3] = rotl(t[3] xor t[0], 16)
            t[2] = t[2] + t[3]
            t[1] = rotl(t[1] xor t[2], 12)
            t[0] = t[0] + t[1]
            t[3] = rotl(t[3] xor t[0], 8)
            t[2] = t[2] + t[3]
            t[1] = rotl(t[1] xor t[2], 7)
        }

        /** Mismo hash que usa la web de Filemoon para el captcha. */
        private fun gr(t: ByteArray): IntArray {
            val e = intArrayOf(
                1779033703,
                -1150833019, // 3144134277
                1013904242,
                -1521486534, // 2773480762
            )
            for (i in t.indices) {
                val b = t[i].toInt() and 0xFF
                e[0] = e[0] + b
                e[0] = rotl(e[0], 7)
                ye(e)
            }
            for (i in 0 until 8) ye(e)

            val r = IntArray(BE)
            for (i in 0 until BE) {
                ye(e)
                r[i] = e[0] xor e[2]
            }
            for (i in 0 until DR) {
                for (s in 0 until BE) {
                    val a = r[s] and LT
                    var c = r[s] + r[a]
                    c = rotl(c, 13)
                    c = c xor (r[(s + 1) and LT] * LR)
                    r[s] = c
                    e[0] = e[0] xor c
                    ye(e)
                }
            }
            val n = IntArray(8)
            val o = BE / 8
            for (i in 0 until 8) {
                ye(e)
                var s = e[0]
                val a = i * o
                for (c in 0 until o) {
                    val d = r[a + c]
                    s += d
                    s = rotl(s, 5)
                    s = s xor (d * HR)
                }
                n[i] = s xor e[2]
            }
            return n
        }

        private fun wr(t: IntArray): Int {
            var e = 0
            for (value in t) {
                if (value == 0) {
                    e += 32
                    continue
                }
                return e + Integer.numberOfLeadingZeros(value)
            }
            return e
        }

        fun solvePow(nonce: String, difficulty: Int): String {
            if (difficulty <= 0) return "0"
            val prefix = "$nonce:"
            var counter = 0
            while (true) {
                val input = (prefix + counter).toByteArray(Charsets.ISO_8859_1)
                if (wr(gr(input)) >= difficulty) return counter.toString()
                counter++
            }
        }
    }
}
