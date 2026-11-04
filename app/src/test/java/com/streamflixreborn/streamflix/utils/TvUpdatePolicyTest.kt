package com.streamflixreborn.streamflix.utils

import org.junit.Assert.*
import org.junit.Test

class TvUpdatePolicyTest {
    private val mime = TvUpdatePolicy.APK_MIME
    @Test fun acceptsOnlyTvApk() {
        assertTrue(TvUpdatePolicy.isTvUpdate("streamflix-v2.0.0-tv.apk", mime))
        assertFalse(TvUpdatePolicy.isTvUpdate("streamflix-v2.0.0-mobile.apk", mime))
        assertFalse(TvUpdatePolicy.isTvUpdate("streamflix-v2.0.0.apk", mime))
        assertFalse(TvUpdatePolicy.isTvUpdate("streamflix-v2.0.0-tv.apk", "application/zip"))
        assertFalse(TvUpdatePolicy.isTvUpdate("notes.txt", mime))
    }
    @Test fun numericVersionOrdering() {
        assertTrue(TvUpdatePolicy.compare("1.10.0", "1.9.99") > 0)
        assertTrue(TvUpdatePolicy.compare("2.0", "2.0.0") == 0)
        assertTrue(TvUpdatePolicy.compare("1.7.116", "1.7.117") < 0)
    }
}
