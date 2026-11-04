package com.streamflixreborn.streamflix

import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.streamflixreborn.streamflix.ui.PlayerTvView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real TV view and Android key events without a network stream. */
@RunWith(AndroidJUnit4::class)
class PlayerTvRemoteTest {
    private lateinit var player: TestPlayer
    private lateinit var view: PlayerTvView

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var positionMs = 60_000L
        var pauseCalls = 0
        private var playing = true
        private fun state() = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(MediaItemData.Builder("test")
                .setDurationUs(120_000_000L).build()))
            .setContentPositionMs(positionMs)
            .build()
        override fun getState(): State = state()
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            this.positionMs = positionMs
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            if (!playWhenReady) pauseCalls++
            invalidateState()
            return Futures.immediateVoidFuture()
        }
    }

    @Before fun setUp() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        player = TestPlayer()
        view = PlayerTvView(android.view.ContextThemeWrapper(context, R.style.AppTheme_Tv))
        view.player = player
        view.controller.hide()
    }
    @After fun tearDown() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        view.player = null
        player.release()
    }
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN) =
        view.dispatchKeyEvent(KeyEvent(action, code))

    @Test fun dpadSeeksOncePerPressAndClampsAtStart() {
        assertTrue(key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(50_000L, player.positionMs)
        assertTrue(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP))
        assertEquals(50_000L, player.positionMs)
        player.positionMs = 5_000L
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0L, player.positionMs)
    }
    @Test fun dpadForwardClampsAtDuration() {
        player.positionMs = 115_000L
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(120_000L, player.positionMs)
    }
    @Test fun visibleControllerReceivesDpadInsteadOfSeeking() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.controller.show() }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(60_000L, player.positionMs)
    }
    @Test fun mediaNextIsDeliveredOnlyOnActionDown() {
        var calls = 0
        view.onMediaNextClicked = { calls++; true }
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP))
        assertEquals(1, calls)
    }
    @Test fun manualZoomPausesAndConsumesDpadWithoutSeeking() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.enterManualZoomMode() }
        assertEquals(1, player.pauseCalls)
        assertTrue(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(60_000L, player.positionMs)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.exitManualZoomMode() }
        assertFalse(view.isManualZoomEnabled)
    }
}
