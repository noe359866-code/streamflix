package com.streamflixreborn.streamflix

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun disableNetworkUpdateAndProviderSelection() {
        // Application.onCreate has already initialized UserPreferences. Write the
        // backing preferences directly: changing currentProvider schedules work.
        context.getSharedPreferences("${BuildConfig.APPLICATION_ID}.preferences", 0)
            .edit().putBoolean("UPDATE_CHECK_ENABLED", false)
            .remove("CURRENT_PROVIDER").remove("PROVIDER_LANGUAGE").commit()
    }

    @Test fun manifestIsLeanbackOnlyAndDoesNotExposeMobileLauncher() {
        val pm = context.packageManager
        assertTrue(pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        assertEquals("tv", BuildConfig.APP_LAYOUT)
        val leanback = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(context.packageName), 0)
        assertEquals(1, leanback.size)
        assertEquals(MainTvActivity::class.java.name, leanback.single().activityInfo.name)
        val mobile = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertTrue(mobile.isEmpty())
        val requiredFeatures = pm.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures
        assertTrue(requiredFeatures.orEmpty().any { it.name == PackageManager.FEATURE_LEANBACK })
    }

    @Test fun startsOnTvProviderScreenAndDpadMovesFocus() {
        ActivityScenario.launch(MainTvActivity::class.java).use { scenario ->
            await("TV provider list") {
                var ready = false
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.rv_providers)
                    ready = activity.findViewById<View>(R.id.iv_splash_overlay).visibility == View.GONE &&
                        list != null && list.childCount > 1 && list.hasFocus()
                }
                ready
            }
            var before: String? = null
            scenario.onActivity { before = focusedProviderName(it) }
            assertNotNull(before)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad moves to the next provider") {
                var moved = false
                scenario.onActivity { moved = focusedProviderName(it) != before }
                moved
            }
            scenario.onActivity { activity ->
                assertTrue(activity is MainTvActivity)
                assertFalse(activity.isFinishing)
                assertNotEquals(before, focusedProviderName(activity))
            }
        }
    }

    private fun focusedProviderName(activity: MainTvActivity): String? =
        activity.findViewById<RecyclerView>(R.id.rv_providers)?.findFocus()
            ?.findViewById<TextView>(R.id.tv_provider_name)?.text?.toString()

    private fun await(reason: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $reason")
    }
}
