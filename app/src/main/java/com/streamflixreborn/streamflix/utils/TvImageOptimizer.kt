package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import java.io.File

/**
 * Strict image downsampling and bitmap memory management for Android TV D-Pad navigation.
 * Prevents full-resolution 2K/4K bitmap decoding spikes during fast horizontal/vertical scrolling.
 */
object TvImageOptimizer {

    fun createImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .okHttpClient { StremioAddonsClient.httpClient }
            .allowHardware(true)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(180)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "tv_coil_image_cache"))
                    .maxSizeBytes(96L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    fun posterRequest(
        context: Context,
        url: String?,
        widthPx: Int = 270,
        heightPx: Int = 405,
    ): ImageRequest {
        val optimizedUrl = downscaleRemotePosterUrl(url)
        return ImageRequest.Builder(context)
            .data(optimizedUrl)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(140)
            .memoryCacheKey(optimizedUrl?.let { "poster:${widthPx}x$heightPx:$it" })
            .build()
    }

    fun backdropRequest(
        context: Context,
        url: String?,
        widthPx: Int = 1280,
        heightPx: Int = 720,
    ): ImageRequest {
        val optimizedUrl = downscaleRemoteBackdropUrl(url)
        return ImageRequest.Builder(context)
            .data(optimizedUrl)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .allowRgb565(true)
            .crossfade(280)
            .memoryCacheKey(optimizedUrl?.let { "backdrop:${widthPx}x$heightPx:$it" })
            .build()
    }

    fun episodeThumbRequest(
        context: Context,
        url: String?,
        widthPx: Int = 440,
        heightPx: Int = 248,
    ): ImageRequest {
        val optimizedUrl = downscaleRemoteBackdropUrl(url, tmdbSize = "w500")
        return ImageRequest.Builder(context)
            .data(optimizedUrl)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(140)
            .memoryCacheKey(optimizedUrl?.let { "ep:${widthPx}x$heightPx:$it" })
            .build()
    }

    fun logoRequest(
        context: Context,
        url: String?,
        widthPx: Int = 560,
        heightPx: Int = 200,
    ): ImageRequest {
        return ImageRequest.Builder(context)
            .data(url)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .scale(Scale.FIT)
            .allowRgb565(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .crossfade(180)
            .build()
    }

    fun avatarRequest(
        context: Context,
        url: String?,
        sizePx: Int = 220,
    ): ImageRequest {
        val optimizedUrl = downscaleRemotePosterUrl(url)
        return ImageRequest.Builder(context)
            .data(optimizedUrl)
            .size(sizePx, sizePx)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(150)
            .memoryCacheKey(optimizedUrl?.let { "avatar:${sizePx}:$it" })
            .build()
    }

    private fun downscaleRemotePosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url
            .replace("/t/p/original/", "/t/p/w342/")
            .replace("/t/p/w780/", "/t/p/w342/")
            .replace("/t/p/w500/", "/t/p/w342/")
            .replace("/poster/large/", "/poster/medium/")
    }

    private fun downscaleRemoteBackdropUrl(url: String?, tmdbSize: String = "w1280"): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("/t/p/original/", "/t/p/$tmdbSize/")
    }
}
