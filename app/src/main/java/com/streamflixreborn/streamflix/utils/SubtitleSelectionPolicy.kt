package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Video

/** Selects at most one subtitle without changing provider-supplied forced defaults. */
object SubtitleSelectionPolicy {
    fun select(
        subtitles: List<Video.Subtitle>,
        providerLanguage: String,
        preferredLabel: String?,
        useServerSetting: Boolean,
        serverAutoSubtitlesDisabled: Boolean,
    ): Int? {
        if (subtitles.any { it.default }) return null
        if (providerLanguage.equals("es", ignoreCase = true)) return null
        if (useServerSetting && serverAutoSubtitlesDisabled) return null
        return subtitles.indexOfFirst { it.label.startsWith(preferredLabel.orEmpty()) }
            .takeIf { it >= 0 }
    }
}
