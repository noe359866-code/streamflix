package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.*
import org.junit.Test

class SubtitleSelectionPolicyTest {
    private fun subtitles() = listOf(
        Video.Subtitle("English", "en.srt"),
        Video.Subtitle("Español", "es.srt"),
        Video.Subtitle("Español forzado", "es-forced.srt"),
    )
    private fun select(list: List<Video.Subtitle>, lang: String = "en", preferred: String? = "Español", server: Boolean = false, disabled: Boolean = false) =
        SubtitleSelectionPolicy.select(list, lang, preferred, server, disabled)

    @Test fun selectsFirstMatchingPreferredLanguage() = assertEquals(1, select(subtitles()))
    @Test fun preservesProviderForcedDefault() {
        val list = subtitles().apply { last().default = true }
        assertNull(select(list))
    }
    @Test fun spanishProviderDoesNotAutoSelectSubtitles() = assertNull(select(subtitles(), lang = "es"))
    @Test fun respectsServerSubtitleDisableSetting() = assertNull(select(subtitles(), server = true, disabled = true))
    @Test fun allowsPreferenceWhenServerSettingIsNotUsed() = assertEquals(1, select(subtitles(), server = false, disabled = true))
    @Test fun missingPreferenceDoesNotAccidentallyEnableFirstTrack() = assertNull(select(subtitles(), preferred = null))
    @Test fun noMatchAndEmptyListAreSafe() {
        assertNull(select(subtitles(), preferred = "Français"))
        assertNull(select(emptyList()))
    }
}
