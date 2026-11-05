package com.streamflixreborn.streamflix.stream

import android.util.LruCache
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.ConcurrentHashMap

enum class SpanishAudioVariant(
    val badge: String,
    val langCode: String,
) {
    LATINO("Español (Latino)", "es-LA"),
    CASTELLANO("Español (España)", "es-ES"),
    SPANISH_GENERAL("Español", "es"),
}

data class ResolvedStreamCandidate(
    val id: String,
    val poolSlot: Int,
    val label: String,
    val audioVariant: SpanishAudioVariant,
    val video: Video,
    val resolvedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Temporary in-memory cache for resolved direct playback URLs and active session playback positions.
 * Avoids re-scraping when the user pauses, seeks, backs out briefly, or switches screens.
 */
object StreamUrlMemoryCache {

    private const val DEFAULT_TTL_MS = 25 * 60 * 1_000L // 25 minutes
    private const val MAX_MEDIA_ENTRIES = 64

    private data class CacheEntry(
        val candidates: List<ResolvedStreamCandidate>,
        val createdAtMs: Long,
    )

    private val streamCache = object : LruCache<String, CacheEntry>(MAX_MEDIA_ENTRIES) {}
    private val failedUrlsByMediaKey = ConcurrentHashMap<String, MutableSet<String>>()
    private val playbackPositionsMs = ConcurrentHashMap<String, Long>()

    fun buildMediaKey(videoType: Video.Type): String {
        return when (videoType) {
            is Video.Type.Movie -> {
                val canonical = videoType.imdbId?.takeIf { it.isNotBlank() } ?: videoType.id
                "movie:$canonical"
            }
            is Video.Type.Episode -> {
                val showCanonical = videoType.tvShow.id.ifBlank { videoType.id }
                "episode:$showCanonical:s${videoType.season.number}e${videoType.number}"
            }
        }
    }

    @Synchronized
    fun getValidCandidates(
        mediaKey: String,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): List<ResolvedStreamCandidate> {
        val entry = streamCache.get(mediaKey) ?: return emptyList()
        val now = System.currentTimeMillis()
        if (now - entry.createdAtMs > ttlMs) {
            streamCache.remove(mediaKey)
            return emptyList()
        }
        val failed = failedUrlsByMediaKey[mediaKey].orEmpty()
        return entry.candidates.filter { it.video.source !in failed }
    }

    @Synchronized
    fun putOrAppendCandidate(mediaKey: String, candidate: ResolvedStreamCandidate): List<ResolvedStreamCandidate> {
        val failed = failedUrlsByMediaKey[mediaKey].orEmpty()
        if (candidate.video.source in failed) {
            return getValidCandidates(mediaKey)
        }
        val current = getValidCandidates(mediaKey)
        val merged = (current + candidate)
            .distinctBy { it.video.source }
        streamCache.put(
            mediaKey,
            CacheEntry(
                candidates = merged,
                createdAtMs = System.currentTimeMillis(),
            )
        )
        return merged
    }

    @Synchronized
    fun putCandidates(mediaKey: String, candidates: List<ResolvedStreamCandidate>) {
        val failed = failedUrlsByMediaKey[mediaKey].orEmpty()
        val cleaned = candidates
            .filter { it.video.source.isNotBlank() && it.video.source !in failed }
            .distinctBy { it.video.source }
        if (cleaned.isEmpty()) return
        streamCache.put(
            mediaKey,
            CacheEntry(
                candidates = cleaned,
                createdAtMs = System.currentTimeMillis(),
            )
        )
    }

    @Synchronized
    fun markUrlFailed(mediaKey: String, failedSourceUrl: String) {
        val set = failedUrlsByMediaKey.getOrPut(mediaKey) { ConcurrentHashMap.newKeySet() }
        set.add(failedSourceUrl)
        val existing = streamCache.get(mediaKey) ?: return
        val remaining = existing.candidates.filterNot { it.video.source == failedSourceUrl }
        if (remaining.isEmpty()) {
            streamCache.remove(mediaKey)
        } else {
            streamCache.put(mediaKey, existing.copy(candidates = remaining))
        }
    }

    fun savePlaybackPosition(mediaKey: String, positionMs: Long) {
        if (positionMs > 2_000L) {
            playbackPositionsMs[mediaKey] = positionMs
        }
    }

    fun getSavedPlaybackPosition(mediaKey: String): Long {
        return playbackPositionsMs[mediaKey] ?: 0L
    }

    fun clearPlaybackPosition(mediaKey: String) {
        playbackPositionsMs.remove(mediaKey)
    }
}
