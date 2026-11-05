package com.streamflixreborn.streamflix.scrapers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Ejecuta una llamada OkHttp sin bloquear el hilo de la corrutina.
 *
 * `Call.execute()` es bloqueante: llamarlo desde un `suspend fun` que corre en
 * Dispatchers.Main (o desde un dispatcher sin I/O) congela la UI. En este proyecto
 * los ViewModels lanzan `provider.getHome()` en `Dispatchers.IO`, pero no todos los
 * extractores ni los `getVideo()` lo hacen, así que centralizamos aquí el cambio
 * de dispatcher.
 *
 * Uso:
 * ```
 * client.await(request).use { response -> ... }
 * ```
 */
suspend fun OkHttpClient.await(request: Request): Response =
    withContext(Dispatchers.IO) { newCall(request).execute() }

/** Variante que devuelve el cuerpo como texto y cierra la respuesta. */
suspend fun OkHttpClient.awaitText(request: Request): String =
    await(request).use { it.body?.string().orEmpty() }

/** Variante que devuelve `(code, body)` y cierra la respuesta. */
suspend fun OkHttpClient.awaitCodeAndBody(request: Request): Pair<Int, String> =
    await(request).use { response -> response.code to response.body?.string().orEmpty() }
