package com.streamflixreborn.streamflix.scrapers

import android.util.Log
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/**
 * Utilidades HTTP compartidas por todos los scrapers.
 *
 * Problemas que resuelve respecto al código anterior:
 * 1. `okhttp3.Call.execute()` se llamaba directamente desde corrutinas: bloqueaba
 *    el hilo del llamador (y en algunos casos el hilo principal). Aquí todo el
 *    I/O se ejecuta siempre en [Dispatchers.IO].
 * 2. Las `Response` no se cerraban (`response.body?.string()` sin `use`), lo que
 *    filtraba conexiones y sockets.
 * 3. No había reintentos: un único timeout de red hacía fallar toda la pantalla.
 *    Ahora hay reintentos con backoff para errores transitorios (IOException,
 *    429 y 5xx).
 * 4. Cada provider reimplementaba la detección de Cloudflare con strings distintos.
 */
object HttpScraper {

    private const val TAG = "HttpScraper"

    /** Marcadores típicos de un challenge de Cloudflare / anti-bot. */
    private val CHALLENGE_MARKERS = listOf(
        "cf-browser-verification",
        "checking your browser",
        "just a moment...",
        "challenge-running",
        "cf_chl_opt",
        "enable javascript and cookies to continue",
    )

    /** ¿El HTML es en realidad una página de challenge en vez del contenido real? */
    fun looksLikeChallenge(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val head = html.take(4096).lowercase()
        return CHALLENGE_MARKERS.any { head.contains(it) }
    }

    data class ScrapedResponse(
        val code: Int,
        val body: String,
        val url: String,
        val headers: Headers,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
        val isChallenge: Boolean get() = looksLikeChallenge(body)

        /** Errores que merece la pena reintentar (rate limit / servidor caído). */
        val isRetryable: Boolean get() = code == 429 || code in 500..599
    }

    /** Construye una request GET con el referer y headers que exigen los sitios con anti-hotlink. */
    fun request(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val builder = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.get().build()
    }

    /**
     * Ejecuta una request fuera del hilo llamador cerrando siempre la respuesta.
     * Nunca lanza por códigos de estado: el llamador decide con [ScrapedResponse.code].
     */
    suspend fun execute(
        request: Request,
        client: OkHttpClient = NetworkClient.default,
    ): ScrapedResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            ScrapedResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                url = response.request.url.toString(),
                headers = response.headers,
            )
        }
    }

    /** Descarga texto con reintentos. Devuelve `null` si no se pudo obtener (sin lanzar). */
    suspend fun getText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = NetworkClient.default,
        attempts: Int = 2,
    ): String? = runCatching {
        withRetry(attempts) {
            val response = execute(request(url, referer, headers), client)
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            response.body
        }
    }.onFailure { Log.w(TAG, "getText failed for $url: ${it.message}") }
        .getOrNull()

    /**
     * Descarga una página y la parsea con Jsoup, dejando el `baseUri` configurado
     * para que `abs:href` / `abs:src` funcionen aunque la página use rutas relativas.
     */
    suspend fun getDocument(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        baseUri: String? = null,
        client: OkHttpClient = NetworkClient.default,
        attempts: Int = 2,
    ): Document? {
        val html = getText(url, referer, headers, client, attempts) ?: return null
        if (looksLikeChallenge(html)) {
            Log.w(TAG, "Cloudflare challenge detected for $url")
            return null
        }
        return Jsoup.parse(html, baseUri ?: url)
    }

    /** POST de formulario (`application/x-www-form-urlencoded`). */
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = NetworkClient.default,
        attempts: Int = 2,
    ): String? = runCatching {
        val body: RequestBody = FormBody.Builder()
            .apply { fields.forEach { (key, value) -> add(key, value) } }
            .build()
        withRetry(attempts) {
            val builder = Request.Builder().url(url).post(body)
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            headers.forEach { (key, value) -> builder.header(key, value) }
            val response = execute(builder.build(), client)
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            response.body
        }
    }.onFailure { Log.w(TAG, "postForm failed for $url: ${it.message}") }
        .getOrNull()

    /**
     * Reintenta con backoff lineal (250ms, 500ms, 750ms...). Reintenta ante
     * [IOException], [HttpStatusException] 429/5xx o cualquier excepción marcada como
     * transitoria; el resto se propaga de inmediato.
     */
    suspend fun <T> withRetry(
        attempts: Int = 2,
        initialDelayMs: Long = 250,
        block: suspend (attempt: Int) -> T,
    ): T {
        var lastError: Throwable? = null
        val safeAttempts = attempts.coerceAtLeast(1)
        for (index in 0 until safeAttempts) {
            val attempt = index + 1
            try {
                return block(attempt)
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
                lastError = e
                if (attempt < safeAttempts) delay(initialDelayMs * attempt)
            }
        }
        throw lastError ?: IllegalStateException("withRetry: no attempts executed")
    }

    /** Igual que [withRetry] pero devuelve `null` en lugar de lanzar. */
    suspend fun <T> attempt(
        attempts: Int = 2,
        initialDelayMs: Long = 250,
        block: suspend (attempt: Int) -> T,
    ): T? = runCatching { withRetry(attempts, initialDelayMs, block) }
        .onFailure { Log.w(TAG, "attempt failed: ${it.message}") }
        .getOrNull()

    private fun isTransient(e: Throwable): Boolean = when (e) {
        is HttpStatusException -> e.isRetryable
        is IOException -> true
        else -> false
    }

    /** Error HTTP con el código de estado, para poder decidir si se reintenta. */
    class HttpStatusException(val code: Int, val url: String) :
        IOException("HTTP $code for $url") {
        val isRetryable: Boolean get() = code == 429 || code in 500..599
    }

    /** Convierte una URL relativa (`//host/x`, `/x`, `x`) en absoluta a partir de una base. */
    fun absoluteUrl(baseUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> runCatching {
                baseUrl.toHttpUrl().newBuilder()
                    .encodedPath(trimmed.substringBefore('?'))
                    .query(trimmed.substringAfter('?', "").ifEmpty { null })
                    .fragment(null)
                    .build()
                    .toString()
            }.getOrNull() ?: trimmed
            else -> {
                val parent = baseUrl.substringBeforeLast('/', "")
                if (parent.isEmpty()) trimmed else "$parent/$trimmed"
            }
        }
    }

    /** Codifica un parámetro de query de forma segura. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Cierra silenciosamente un body que no vamos a leer (liberando la conexión). */
    fun ResponseBody?.closeQuietly() {
        runCatching { this?.close() }
    }
}
