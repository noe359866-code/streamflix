package com.streamflixreborn.streamflix.subtitles

import androidx.compose.runtime.Immutable

@Immutable
data class TimedSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Lightweight, zero-allocation-friendly parser for `.srt` (SubRip) and `.vtt` (WebVTT) subtitle files,
 * enabling instantaneous real-time millisecond sync-offset (`±ms`) adjustments during playback.
 */
object SubtitleCueParser {

    private val timingLineRegex = Regex(
        pattern = "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})"
    )
    private val htmlTagRegex = Regex("<[^>]+>")
    private val assTagRegex = Regex("\\{\\\\[^}]*\\}")

    fun parse(rawContent: String): List<TimedSubtitleCue> {
        if (rawContent.isBlank()) return emptyList()

        val cleaned = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val blocks = cleaned.split(Regex("\n\\s*\n"))
        val cues = ArrayList<TimedSubtitleCue>(blocks.size)

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT", ignoreCase = true) ||
                lines[0].startsWith("NOTE", ignoreCase = true) ||
                lines[0].startsWith("STYLE", ignoreCase = true)
            ) {
                continue
            }

            var timingLineIndex = -1
            var matchResult: MatchResult? = null
            for (idx in lines.indices) {
                val m = timingLineRegex.find(lines[idx])
                if (m != null) {
                    timingLineIndex = idx
                    matchResult = m
                    break
                }
            }
            val match = matchResult ?: continue
            if (timingLineIndex + 1 >= lines.size) continue

            val startMs = groupsToMillis(
                hoursStr = match.groupValues[1],
                minsStr = match.groupValues[2],
                secsStr = match.groupValues[3],
                millisStr = match.groupValues[4],
            )
            val endMs = groupsToMillis(
                hoursStr = match.groupValues[5],
                minsStr = match.groupValues[6],
                secsStr = match.groupValues[7],
                millisStr = match.groupValues[8],
            )
            if (endMs <= startMs) continue

            val rawText = lines.subList(timingLineIndex + 1, lines.size)
                .joinToString("\n")
                .replace(assTagRegex, "")
                .replace(htmlTagRegex, "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim()

            if (rawText.isNotEmpty()) {
                cues.add(
                    TimedSubtitleCue(
                        startMs = startMs,
                        endMs = endMs,
                        text = rawText,
                    )
                )
            }
        }

        cues.sortBy { it.startMs }
        return cues
    }

    /**
     * Binary-searches active cues at `effectiveTimeMs = videoPositionMs - syncOffsetMs`.
     */
    fun findActiveCueText(
        cues: List<TimedSubtitleCue>,
        videoPositionMs: Long,
        syncOffsetMs: Long,
    ): String? {
        if (cues.isEmpty()) return null
        val targetMs = videoPositionMs - syncOffsetMs
        if (targetMs < cues.first().startMs) return null

        // Binary search last cue with startMs <= targetMs
        var low = 0
        var high = cues.lastIndex
        var candidateIdx = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= targetMs) {
                candidateIdx = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidateIdx < 0) return null

        val matchedLines = ArrayList<String>(2)
        // Check a small backward window in case overlapping cues started slightly earlier
        val startScan = (candidateIdx - 3).coerceAtLeast(0)
        for (i in startScan..candidateIdx) {
            val cue = cues[i]
            if (targetMs in cue.startMs..cue.endMs) {
                matchedLines.add(cue.text)
            }
        }
        return if (matchedLines.isEmpty()) null else matchedLines.joinToString("\n")
    }

    private fun groupsToMillis(
        hoursStr: String,
        minsStr: String,
        secsStr: String,
        millisStr: String,
    ): Long {
        val hours = hoursStr.toLongOrNull() ?: 0L
        val mins = minsStr.toLongOrNull() ?: 0L
        val secs = secsStr.toLongOrNull() ?: 0L
        val millis = millisStr.toLongOrNull() ?: 0L
        return (((hours * 60L + mins) * 60L) + secs) * 1_000L + millis
    }
}
