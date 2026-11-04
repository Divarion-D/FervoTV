package app.iptvplayer

import android.app.UiModeManager
import android.content.res.Configuration
import android.view.View
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import app.iptvplayer.core.Repository
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.player.PlaybackEngine
import app.iptvplayer.core.player.PlaybackState
import app.iptvplayer.core.player.PlayItem
import kotlinx.coroutines.flow.MutableStateFlow
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.LiveScreen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h720dp-land-television")
@OptIn(ExperimentalTestApi::class)
class LiveNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var repo: Repository
    private lateinit var source: Source
    private lateinit var playlist: java.io.File

    private fun start(tv: Boolean = true) {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java)).setCurrentModeType(if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        val file = tmp.newFile("live.m3u")
        playlist = file
        file.writeText("#EXTM3U\n" + (1..30).joinToString("") {
            "#EXTINF:-1 group-title=\"News\",Channel $it\nhttps://example.invalid/$it\n"
        })
        source = Source.M3u("content://test/live.m3u")
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(Uri.parse("content://test/live.m3u")) { file.inputStream() }
        repo = Repository(compose.activity)
        runBlocking { repo.load(source) }
        val player = PlayerSession { TestEngine() }
        compose.setContent {
            AppTheme {
                LiveScreen(repo, source, player,
                    Settings(preview = false, rememberLive = false, liveTiles = false), true, null, {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Channel 1").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test fun opensOnFirstChannelAndRemoteMovesToNext() {
        start()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 1")).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 2")).assertIsFocused()
    }

    @Test fun searchOpensOnlyOnOkAndBackClearsQueryToTheField() {
        start()
        val hint = compose.activity.getString(R.string.search_channels)
        compose.onNodeWithText(hint).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNode(hasSetTextAction()).performTextInput("Channel 4")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(hint).assertIsFocused()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Channel 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(hint).assertIsFocused()
    }

    @Test
    @Config(qualifiers = "en-w400dp-h800dp-port")
    fun touchSearchSurvivesOpeningAndClosingThePlayer() {
        start(tv = false)
        compose.onNodeWithText(compose.activity.getString(R.string.search_channels)).performTouchInput { click() }
        compose.onNode(hasSetTextAction()).performTextInput("Channel 4")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4"))
            .performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")).assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.search_channels)).assertCountEquals(0)
    }

    @Test fun refreshKeepsFocusedChannelAfterRowsAreRenamedAndReordered() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")).assertIsFocused()
        playlist.writeText(playlist.readText().replace("Channel 4\n", "Updated 4\n")
            .replace("#EXTM3U\n", "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Inserted\nhttps://example.invalid/new\n"))
        runBlocking { repo.load(source) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Updated 4").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Updated 4") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Updated 4")).assertIsFocused()
    }

    @Test fun refreshDoesNotTakeFocusFromSearch() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        val hint = compose.activity.getString(R.string.search_channels)
        compose.onNodeWithText(hint).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(hint).assertIsFocused()
        playlist.writeText(playlist.readText().replace("Channel 4\n", "Updated 4\n"))
        runBlocking { repo.load(source) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Updated 4").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(hint).assertIsFocused()
    }

    private class TestEngine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackState())
        override val position = 0L
        override val duration = 0L
        override fun play(item: PlayItem) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun retry() = Unit
        override fun stop() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideoView(view: View?) = Unit
        override fun release() = Unit
    }
}
