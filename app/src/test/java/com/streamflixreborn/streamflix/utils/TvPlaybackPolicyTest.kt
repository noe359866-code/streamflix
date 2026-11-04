package com.streamflixreborn.streamflix.utils

import org.junit.Assert.*
import org.junit.Test

class TvPlaybackPolicyTest {
    @Test fun resumeRewindsTenSeconds() = assertEquals(50_000L, TvPlaybackPolicy.resumePosition(60_000, 120_000))
    @Test fun resumeNeverBecomesNegative() = assertEquals(0L, TvPlaybackPolicy.resumePosition(3_000, 120_000))
    @Test fun missingHistoryStartsAtBeginning() = assertEquals(0L, TvPlaybackPolicy.resumePosition(null, 120_000))
    @Test fun invalidHistoryStartsAtBeginning() {
        assertEquals(0L, TvPlaybackPolicy.resumePosition(0, 120_000))
        assertEquals(0L, TvPlaybackPolicy.resumePosition(-1, 120_000))
    }
    @Test fun completedHistoryDoesNotResumeAtEnd() {
        assertEquals(0L, TvPlaybackPolicy.resumePosition(120_000, 120_000))
        assertEquals(0L, TvPlaybackPolicy.resumePosition(130_000, 120_000))
    }
    @Test fun liveHistoryCanResumeWithoutKnownDuration() = assertEquals(50_000L, TvPlaybackPolicy.resumePosition(60_000, -1))
    @Test fun unknownDurationIsNeverConsideredFinished() {
        assertFalse(TvPlaybackPolicy.hasFinished(60_000, -1))
        assertFalse(TvPlaybackPolicy.hasReallyFinished(60_000, 0, 10))
    }
    @Test fun progressThresholds() {
        assertFalse(TvPlaybackPolicy.hasStarted(0, 120_000))
        assertTrue(TvPlaybackPolicy.hasStarted(21_000, 0))
        assertFalse(TvPlaybackPolicy.hasFinished(108_000, 120_000))
        assertTrue(TvPlaybackPolicy.hasFinished(108_001, 120_000))
    }
    @Test fun completionBuffer() {
        assertFalse(TvPlaybackPolicy.hasReallyFinished(109_999, 120_000, 10))
        assertTrue(TvPlaybackPolicy.hasReallyFinished(110_000, 120_000, 10))
        assertTrue(TvPlaybackPolicy.hasReallyFinished(120_000, 120_000, -5))
    }
    @Test fun seekIsClampedToMediaBounds() {
        assertEquals(0L, TvPlaybackPolicy.seekTarget(5_000, -10_000, 120_000))
        assertEquals(120_000L, TvPlaybackPolicy.seekTarget(115_000, 10_000, 120_000))
        assertEquals(70_000L, TvPlaybackPolicy.seekTarget(60_000, 10_000, 120_000))
    }
    @Test fun liveSeekAllowsForwardWithoutKnownDuration() = assertEquals(70_000L, TvPlaybackPolicy.seekTarget(60_000, 10_000, -1))
    @Test fun nextEpisodeOverlayOnlyNearEnd() {
        assertFalse(TvPlaybackPolicy.showNextEpisodeOverlay(60_000, 120_000, 30_000))
        assertTrue(TvPlaybackPolicy.showNextEpisodeOverlay(90_000, 120_000, 30_000))
        assertTrue(TvPlaybackPolicy.showNextEpisodeOverlay(119_000, 120_000, 30_000))
        assertFalse(TvPlaybackPolicy.showNextEpisodeOverlay(120_000, 120_000, 30_000))
    }
    @Test fun overlayRequiresKnownDurationAndMinimumThreshold() {
        assertFalse(TvPlaybackPolicy.showNextEpisodeOverlay(90_000, 0, 30_000))
        assertFalse(TvPlaybackPolicy.showNextEpisodeOverlay(119_000, 120_000, 1_000))
    }
}
