package com.streamflixreborn.streamflix.scrapers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Caché en memoria con TTL y "single flight".
 *
 * - Evita repetir la misma petición varias veces seguidas (típico al abrir una
 *   ficha y volver atrás, o al paginar sobre la misma URL base).
 * - `singleFlight`: si dos pantallas piden el mismo recurso a la vez, sólo se
 *   ejecuta una descarga y ambas reciben el resultado.
 * - `expireNow` permite invalidar (p. ej. cuando un provider cambia de dominio).
 * - Si el `block` falla, **no** se cachea el fallo.
 */
class ScraperCache<T : Any>(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = 128,
) {

    private data class Entry<T>(val value: T, val storedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry<T>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Valor cacheado sin ejecutar el bloque, o `null` si expiró / no existe. */
    fun peek(key: String): T? {
        val entry = entries[key] ?: return null
        if (isExpired(entry)) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    /** Guarda un valor ya calculado. */
    fun put(key: String, value: T) {
        trimIfNeeded()
        entries[key] = Entry(value, System.currentTimeMillis())
    }

    /** Descarta una entrada (o todas si `key` es `null`). */
    fun invalidate(key: String? = null) {
        if (key == null) entries.clear() else entries.remove(key)
    }

    /**
     * Devuelve el valor cacheado o ejecuta [block]. Con `forceRefresh` se salta la
     * caché pero se sigue usando el bloqueo por clave.
     */
    suspend fun getOrFetch(
        key: String,
        forceRefresh: Boolean = false,
        timeoutMs: Long = 0L,
        ttl: Long = ttlMs,
        block: suspend () -> T,
    ): T {
        if (!forceRefresh) peek(key)?.let { return it }

        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            // Otra corrutina pudo resolverlo mientras esperábamos el lock.
            if (!forceRefresh) peek(key)?.let { return@withLock it }

            val value = if (timeoutMs > 0) {
                withTimeoutOrNull(timeoutMs) { block() }
                    ?: throw java.io.IOException("ScraperCache timeout for $key")
            } else {
                block()
            }

            entries[key] = Entry(value, System.currentTimeMillis())
            trimIfNeeded()
            // limpieza de locks usados una sola vez para no crecer sin control
            if (locks.size > maxEntries * 2) locks.clear()
            value
        }
    }

    /** Igual que [getOrFetch] pero devuelve `null` en vez de propagar errores. */
    suspend fun getOrFetchOrNull(
        key: String,
        forceRefresh: Boolean = false,
        timeoutMs: Long = 0L,
        block: suspend () -> T,
    ): T? = runCatching { getOrFetch(key, forceRefresh, timeoutMs, block = block) }.getOrNull()

    private fun isExpired(entry: Entry<T>): Boolean {
        // ttl <= 0 => sin expiración (útil para catálogos estáticos)
        if (ttlMs <= 0) return false
        return System.currentTimeMillis() - entry.storedAt > ttlMs
    }

    private fun trimIfNeeded() {
        if (entries.size <= maxEntries) return
        val oldest = entries.entries
            .sortedBy { it.value.storedAt }
            .take((entries.size - maxEntries).coerceAtLeast(1))
            .map { it.key }
        oldest.forEach { entries.remove(it) }
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        /** Fábrica de cachés tipadas para los providers. */
        fun <T : Any> create(ttlMs: Long = DEFAULT_TTL_MS): ScraperCache<T> = ScraperCache(ttlMs)
    }
}
