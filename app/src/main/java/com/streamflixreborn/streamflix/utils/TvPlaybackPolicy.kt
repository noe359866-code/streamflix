package com.streamflixreborn.streamflix.utils

/** Pure playback decisions shared by the TV player and its regression tests. */
object TvPlaybackPolicy {
    const val SEEK_STEP_MS = 10_000L
    const val RESUME_REWIND_MS = 10_000L
    const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L

    fun resumePosition(savedPositionMs: Long?, durationMs: Long): Long {
        if (savedPositionMs == null || savedPositionMs <= 0) return 0L
        if (durationMs > 0 && savedPositionMs >= durationMs) return 0L
        return (savedPositionMs - RESUME_REWIND_MS).coerceAtLeast(0L)
    }

    fun hasStarted(positionMs: Long, durationMs: Long): Boolean =
        positionMs > 0 && (positionMs > 20_000 || durationMs > 0 && positionMs > durationMs * 0.005)

    fun hasFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs > durationMs * 0.90

    fun hasReallyFinished(positionMs: Long, durationMs: Long, autoplayBufferSeconds: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs - autoplayBufferSeconds.coerceAtLeast(0) * 1_000L

    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }

    fun showNextEpisodeOverlay(positionMs: Long, durationMs: Long, thresholdMs: Long): Boolean {
        if (durationMs <= 0 || thresholdMs < NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS) return false
        val remaining = durationMs - positionMs
        return remaining in 1..thresholdMs
    }
}
