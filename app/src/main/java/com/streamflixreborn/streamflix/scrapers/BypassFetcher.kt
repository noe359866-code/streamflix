package com.streamflixreborn.streamflix.scrapers

import android.util.Log
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Descarga de páginas con "cascada" de estrategias, para los sitios protegidos
 * por Cloudflare o con redirects raros.
 *
 * Antes cada provider hacía algo distinto (unos probaban OkHttp con 3s, otros con
 * 5s, otros usaban siempre WebView y tardaban 30-120s por página). Ahora:
 *
 * 1. **OkHttp rápido**: se pide la página con un cliente de timeout corto.
 *    Si llega HTML limpio, se devuelve sin tocar la WebView (mucho más rápido).
 * 2. **WebView**: sólo si hay challenge o error de red. Reutiliza el
 *    [WebViewResolver] compartido y serializa el acceso con un [Mutex] porque
 *    Android sólo permite una WebView "en serio" a la vez.
 * 3. **Caché**: el resultado (bueno o malo) se guarda con TTL para no pagar el
 *    coste del bypass dos veces seguidas. Los resultados de challenge no se cachean
 *    como éxito.
 *
 * También detecta cambios de dominio: si un 301 lleva a otro host, se avisa por
 * [onHostChanged] para que el provider actualice su baseUrl.
 */
class BypassFetcher(
    private val clientProvider: () -> okhttp3.OkHttpClient = { NetworkClient.default },
    private val cacheTtlMs: Long = 5 * 60 * 1000L,
    private val webViewTimeoutMs: Long = 45_000L,
    private val fastTimeoutMs: Long = 6_000L,
) {

    private companion object {
        const val TAG = "BypassFetcher"
    }

    private val webViewMutex = Mutex()
    private val cache = ScraperCache<String>(ttlMs = cacheTtlMs, maxEntries = 64)
    private var webViewResolver: WebViewResolver? = null

    /** Se invoca cuando un redirect revela un host nuevo. */
    var onHostChanged: ((oldHost: String, newHost: String) -> Unit)? = null

    fun attach(context: android.content.Context) {
        if (webViewResolver == null) webViewResolver = WebViewResolver(context.applicationContext)
    }

    private fun resolver(): WebViewResolver? = webViewResolver

    /**
     * Descarga `url` devolviendo el HTML final (nunca `null`: si todo falla
     * devuelve cadena vacía, igual que antes hacía el WebViewResolver).
     */
    suspend fun fetch(
        url: String,
        referer: String? = null,
        forceRefresh: Boolean = false,
        preferWebView: Boolean = false,
    ): String {
        val key = "html|$url"
        if (!forceRefresh) cache.peek(key)?.let { return it }

        if (!preferWebView) {
            val fast = fetchFast(url, referer)
            if (fast != null && !HttpScraper.looksLikeChallenge(fast)) {
                cache.put(key, fast)
                return fast
            }
        }

        val html = fetchWithWebView(url, referer)
        if (html.isNotBlank() && !HttpScraper.looksLikeChallenge(html)) {
            cache.put(key, html)
        }
        return html
    }

    /** Sólo petición rápida; `null` si hubo error, challenge o timeout. */
    private suspend fun fetchFast(url: String, referer: String?): String? {
        val fastClient = clientProvider().newBuilder()
            .connectTimeout(fastTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(fastTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(fastTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        return runCatching {
            val request = HttpScraper.request(url, referer)
            val response = HttpScraper.execute(request, fastClient)

            detectHostChange(url, response.url)

            if (!response.isSuccessful) {
                Log.d(TAG, "Fast fetch HTTP ${response.code} for $url")
                return null
            }
            response.body.takeIf { it.isNotBlank() }
        }.onFailure { Log.d(TAG, "Fast fetch failed for $url: ${it.message}") }
            .getOrNull()
    }

    private fun detectHostChange(requestedUrl: String, finalUrl: String) {
        val observer = onHostChanged ?: return
        val oldHost = runCatching { java.net.URL(requestedUrl).host }.getOrNull() ?: return
        val newHost = runCatching { java.net.URL(finalUrl).host }.getOrNull() ?: return
        if (oldHost.isNotBlank() && newHost.isNotBlank() && oldHost != newHost) {
            Log.i(TAG, "Host changed: $oldHost -> $newHost")
            observer(oldHost, newHost)
        }
    }

    private suspend fun fetchWithWebView(url: String, referer: String?): String {
        val resolver = resolver() ?: run {
            Log.w(TAG, "No context attached: cannot use WebView for $url")
            return ""
        }
        return webViewMutex.withLock {
            val result = withTimeoutOrNull(webViewTimeoutMs) {
                resolver.get(
                    url,
                    headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap(),
                )
            }
            if (result == null) Log.w(TAG, "WebView timeout for $url")
            result.orEmpty()
        }
    }

    /** Igual que [fetch] pero parseado como [org.jsoup.nodes.Document]. */
    suspend fun fetchDocument(
        url: String,
        referer: String? = null,
        forceRefresh: Boolean = false,
    ): org.jsoup.nodes.Document? {
        val html = fetch(url, referer, forceRefresh)
        if (html.isBlank()) return null
        return org.jsoup.Jsoup.parse(html, url)
    }
}
