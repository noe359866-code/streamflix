package com.streamflixreborn.streamflix.activities.main

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.providers.StremioUnifiedProvider
import com.streamflixreborn.streamflix.search.TvSearchDeepLinkTarget
import com.streamflixreborn.streamflix.search.TvVoiceQueryNormalizer
import com.streamflixreborn.streamflix.tv.AppleTvApp
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow

class MainTvActivity : FragmentActivity() {

    private val pendingVoiceDeepLink = MutableStateFlow<TvSearchDeepLinkTarget?>(null)
    private val pendingVoiceSearchQuery = MutableStateFlow<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)

        if (BuildConfig.APP_LAYOUT == "mobile" ||
            (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        // Ensure default Spanish Stremio catalog provider is active with zero manual setup
        if (UserPreferences.currentProvider == null) {
            UserPreferences.currentProvider = StremioUnifiedProvider
        }

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        parseIncomingVoiceOrDeepLinkIntent(intent)

        setContent {
            val deepLink by pendingVoiceDeepLink.collectAsState()
            val voiceQuery by pendingVoiceSearchQuery.collectAsState()

            AppleTvApp(
                onExitApp = { finish() },
                pendingVoiceDeepLink = deepLink,
                onConsumeVoiceDeepLink = { pendingVoiceDeepLink.value = null },
                pendingVoiceSearchQuery = voiceQuery,
                onConsumeVoiceSearchQuery = { pendingVoiceSearchQuery.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIncomingVoiceOrDeepLinkIntent(intent)
    }

    override fun onSearchRequested(): Boolean {
        startSearch(null, false, null, false)
        return true
    }

    private fun parseIncomingVoiceOrDeepLinkIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_VIEW -> {
                val parsed = TvVoiceQueryNormalizer.parseDeepLinkUri(incoming.data)
                if (parsed != null) {
                    pendingVoiceDeepLink.value = parsed
                }
            }
            Intent.ACTION_SEARCH -> {
                val raw = incoming.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    pendingVoiceSearchQuery.value = TvVoiceQueryNormalizer.normalizeSpokenQuery(raw)
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        // Retained for binary/source compatibility with legacy SettingsTvFragment references.
    }
}
