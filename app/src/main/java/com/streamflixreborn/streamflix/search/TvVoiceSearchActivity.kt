package com.streamflixreborn.streamflix.search

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

/**
 * Host `FragmentActivity` for Android TV Leanback Voice Search (`SearchSupportFragment`).
 * Responds to both `android.intent.action.SEARCH` and explicit in-app microphone launches.
 */
class TvVoiceSearchActivity : FragmentActivity() {

    companion object {
        const val EXTRA_AUTO_START_SPEECH = "extra_auto_start_speech"
    }

    private var searchFragment: TvLeanbackSearchFragment? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val containerId = View.generateViewId()
        val root = FrameLayout(this).apply {
            id = containerId
            setBackgroundColor(Color.parseColor("#08090D"))
        }
        setContentView(root)

        val fragment = (supportFragmentManager.findFragmentById(containerId) as? TvLeanbackSearchFragment)
            ?: TvLeanbackSearchFragment().also { created ->
                supportFragmentManager.beginTransaction()
                    .replace(containerId, created)
                    .commitNow()
            }
        searchFragment = fragment

        handleSearchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchIntent(intent)
    }

    private fun handleSearchIntent(incoming: Intent?) {
        val rawQuery = incoming?.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
        if (rawQuery.isNotEmpty()) {
            val normalized = TvVoiceQueryNormalizer.normalizeSpokenQuery(rawQuery)
            searchFragment?.setSearchQuery(normalized, true)
        } else if (incoming?.getBooleanExtra(EXTRA_AUTO_START_SPEECH, false) == true) {
            searchFragment?.triggerVoicePromptIfAvailable()
        }
    }
}
