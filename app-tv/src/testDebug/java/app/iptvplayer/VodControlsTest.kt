package app.iptvplayer

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlaybackEngine
import app.iptvplayer.core.player.PlaybackError
import app.iptvplayer.core.player.PlaybackState
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.VodPlay
import app.iptvplayer.ui.VodPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-land")
@OptIn(ExperimentalTestApi::class)
class VodControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val engine = TestEngine()
    private var closed = 0

    private fun startPlayer() {
        compose.mainClock.autoAdvance = false
        val session = PlayerSession { engine }
        val film = VodItem(
            key = "film", kind = VodKind.MOVIE, name = "Film", title = "Film", year = null,
            tags = emptyList(), category = "Test", poster = null, backdrop = null, rating = null,
            added = 0, plot = null, genre = null, url = "https://example.invalid/film.mp4",
            favorite = false, hidden = false, progress = null,
        )
        compose.setContent {
            AppTheme {
                VodPlayer(session, VodPlay(film), "Film", null, false, false, null, null,
                    onSave = { _, _, _ -> }, onEpisode = {}, onClose = { closed++ })
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun showPausedAfterAutoHide() {
        startPlayer()
        compose.mainClock.advanceTimeBy(Defaults.PLAYER_INFO_HIDE_MS + 100)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause)).assertDoesNotExist()
        compose.runOnUiThread { engine.pause() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_play)).assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun arrowsOnThePausePanelDoNotSeek() {
        showPausedAfterAutoHide()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(emptyList<Long>(), engine.seeks)
        assertEquals(120_000L, engine.position)
    }

    @Test
    fun rightMovesFromPlayThroughTheControlsToMore() {
        showPausedAfterAutoHide()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_play))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { repeat(4) { pressKey(Key.DirectionRight) } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).assertIsFocused()
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    @Test
    fun downEntersThePanelFromTheVideoSurface() {
        showPausedAfterAutoHide()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            repeat(6) { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).assertIsFocused()
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    @Test
    @Config(qualifiers = "en-port")
    fun portraitDownEntersTheControlsThenMovesBetweenRows() {
        showPausedAfterAutoHide()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).assertIsFocused()
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    @Test
    @Config(qualifiers = "en-land-television")
    fun televisionPanelArrowsReachMoreWithoutSeeking() {
        showPausedAfterAutoHide()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            repeat(6) { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).assertIsFocused()
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    @Test
    fun backHidesThePausePanelBeforeClosingThePlayer() {
        showPausedAfterAutoHide()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, closed)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_play)).assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closed)
    }

    @Test
    @Config(qualifiers = "en-port")
    fun portraitArrowsMoveBetweenRowsWithoutSeeking() {
        showPausedAfterAutoHide()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_play))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).assertIsFocused()
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    @Test
    fun arrowsOnTheErrorPanelDoNotSeek() {
        startPlayer()
        compose.mainClock.advanceTimeBy(Defaults.PLAYER_INFO_HIDE_MS + 100)
        compose.runOnUiThread {
            engine.state.value = engine.state.value.copy(error = PlaybackError(PlaybackError.Kind.Network, "TEST_ERROR"))
        }
        compose.mainClock.advanceTimeBy(Defaults.PLAYER_INFO_HIDE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.retry)).assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    @Test
    fun autoHideReturnsKeyControlToPlaybackAfterAButtonWasFocused() {
        startPlayer()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(Defaults.PLAYER_INFO_HIDE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause)).assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(130_000L), engine.seeks)
    }

    @Test
    fun arrowActivityRestartsTheAutoHideDeadline() {
        startPlayer()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(Defaults.PLAYER_INFO_HIDE_MS - 500)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause)).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(Defaults.PLAYER_INFO_HIDE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause)).assertDoesNotExist()
        assertEquals(emptyList<Long>(), engine.seeks)
    }

    private class TestEngine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackState())
        override var position = 120_000L
        override val duration = 300_000L
        val seeks = mutableListOf<Long>()
        override fun play(item: PlayItem) { state.value = PlaybackState(paused = false) }
        override fun pause() { state.value = state.value.copy(paused = true) }
        override fun resume() { state.value = state.value.copy(paused = false) }
        override fun seekTo(ms: Long) { seeks += ms; position = ms }
        override fun retry() = Unit
        override fun stop() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideoView(view: View?) = Unit
        override fun release() = Unit
    }
}
