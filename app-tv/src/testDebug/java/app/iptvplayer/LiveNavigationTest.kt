package app.iptvplayer

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.room.Room
import androidx.room.RoomDatabase
import app.iptvplayer.core.db.AppDb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
import app.iptvplayer.ui.SearchScreen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h720dp-land-television")
@OptIn(ExperimentalTestApi::class)
class LiveNavigationTest {
    @get:Rule(order = 2) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 0) val tmp = TemporaryFolder()
    private lateinit var repo: Repository
    private lateinit var source: Source
    private lateinit var playlist: java.io.File
    private var database: AppDb? = null
    // Compose must cancel its Room collectors before the database closes; the temporary directory is removed last.
    @get:Rule(order = 1) val databaseCloser = object : TestWatcher() {
        override fun finished(description: Description) { database?.close() }
    }
    private val engine = TestEngine()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val blockPage = AtomicBoolean(false)
    private val pageStarted = CountDownLatch(1)
    private val pageReleased = CountDownLatch(1)
    private val pageDelayMs = AtomicLong(0)

    @After fun close() {
        pageReleased.countDown()
        runBlocking { background.coroutineContext[Job]?.cancelAndJoin() }
        compose.runOnUiThread {
            val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as? ComposeView)?.disposeComposition()
        }
    }

    private fun start(tv: Boolean = true, channels: Int = 30, duplicate: Boolean = false, tiles: Boolean = false, favorite: Int? = null, search: Boolean = false) {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java)).setCurrentModeType(if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        val file = tmp.newFile("live.m3u")
        playlist = file
        file.writeText("#EXTM3U\n" + (1..channels).joinToString("") {
            "#EXTINF:-1 group-title=\"News\",Channel $it\nhttps://example.invalid/${if (duplicate && it == 4) 1 else it}\n"
        })
        source = Source.M3u("content://test/live.m3u")
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(Uri.parse("content://test/live.m3u")) { file.inputStream() }
        val db = Room.databaseBuilder(compose.activity, AppDb::class.java, tmp.newFile("live.db").path)
            .allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.contains("FROM channel ch")) {
                        if (blockPage.compareAndSet(true, false)) {
                            pageStarted.countDown()
                            check(pageReleased.await(10, TimeUnit.SECONDS))
                        }
                        if (sqlQuery.startsWith("SELECT ch.id AS id")) Thread.sleep(pageDelayMs.get())
                    }
                }
            }, Executor { it.run() }).build()
        database = db
        repo = Repository(compose.activity, db)
        runBlocking {
            repo.load(source)
            favorite?.let { repo.setFavorite(source, "https://example.invalid/$it", true) }
        }
        val player = PlayerSession { engine }
        compose.setContent {
            AppTheme {
                if (search) SearchScreen(
                    repo, source, player, Settings(preview = false), true, null, emptySet(), null, {}, { _, _ -> }, null,
                ) else LiveScreen(repo, source, player,
                    Settings(preview = false, rememberLive = false, liveTiles = tiles), true, null, {})
            }
        }
        if (!search) compose.waitUntil(10_000) { compose.onAllNodesWithText("Channel 1").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test fun favoriteTileShowsHeart() {
        start(channels = 1, tiles = true, favorite = 1)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.row_favorite)).assertIsDisplayed()
    }

    @Test fun searchResultMenuTogglesFavorite() {
        start(tv = false, channels = 3, search = true)
        compose.onNode(hasSetTextAction()).performTextInput("Channel 1")
        compose.mainClock.advanceTimeBy(500)
        val channel = hasText("Channel 1") and !hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.waitUntil(10_000) { compose.onAllNodes(channel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(channel).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.favorite_add))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(runBlocking { repo.channel(source, "https://example.invalid/1")?.favorite == true })
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.row_favorite)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun searchResultRemoteMenusToggleFavoriteAndRestoreFocus() {
        start(channels = 3, search = true)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("Channel 1")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(500)
        val channel = hasText("Channel 1") and !hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.waitUntil(10_000) { compose.onAllNodes(channel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(channel).performSemanticsAction(SemanticsActions.RequestFocus)

        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText(compose.activity.getString(R.string.favorite_add)).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(10_000) { runBlocking { repo.channel(source, "https://example.invalid/1")?.favorite == true } }
        compose.onNode(channel).assertIsFocused()

        compose.runOnUiThread {
            val down = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(AndroidKeyEvent(down, down, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0))
            compose.activity.dispatchKeyEvent(AndroidKeyEvent(down, down + 500, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 1, 0, -1, 0, AndroidKeyEvent.FLAG_LONG_PRESS))
            compose.activity.dispatchKeyEvent(AndroidKeyEvent(down, down + 501, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.favorite_remove)).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(10_000) { runBlocking { repo.channel(source, "https://example.invalid/1")?.favorite == false } }
        compose.onNode(channel).assertIsFocused()
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
        compose.runOnIdle { assertTrue(engine.view is SurfaceView) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertNull(engine.view) }
        val channel = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")
        compose.waitUntil(10_000) { runCatching { compose.onNode(channel).assertIsDisplayed() }.isSuccess }
        compose.onNode(channel).assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.search_channels)).assertCountEquals(0)
    }

    @Test fun refreshKeepsFocusedChannelAfterRowsAreRenamedAndReordered() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")).assertIsFocused()
        var title = "Channel 4"
        repeat(5) { refresh ->
            val updated = "Updated 4 - $refresh"
            playlist.writeText(playlist.readText().replace("$title\n", "$updated\n")
                .replace("#EXTM3U\n", "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Inserted $refresh\nhttps://example.invalid/new/$refresh\n"))
            runBlocking { repo.load(source) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(updated).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(updated) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText(updated)).assertIsFocused()
            title = updated
        }
    }

    @Test fun refreshDoesNotTakeFocusFromSearch() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        val hint = compose.activity.getString(R.string.search_channels)
        compose.onNodeWithText(hint).performTouchInput { click() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(hint).assertIsFocused()
        playlist.writeText(playlist.readText().replace("Channel 4\n", "Updated 4\n"))
        runBlocking { repo.load(source) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Updated 4").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(hint).assertIsFocused()
    }

    @Test fun refreshRestoresTheFocusedDuplicateOccurrence() {
        start(duplicate = true)
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 4")).assertIsFocused()
        playlist.writeText(playlist.readText().replace("Channel 4\n", "Updated 4\n")
            .replace("#EXTM3U\n", "#EXTM3U\n#EXTINF:-1,Inserted\nhttps://example.invalid/new\n"))
        runBlocking { repo.load(source) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Updated 4") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun refreshWhileLoading(action: () -> Unit) {
        blockPage.set(true)
        playlist.writeText(playlist.readText().replace("Channel 4\n", "Updated 4\n"))
        val refresh = background.async { repo.load(source) }
        compose.waitUntil(10_000) { pageStarted.count == 0L }
        try { action() } finally { pageReleased.countDown() }
        runBlocking { withTimeout(10_000) { refresh.await() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Updated 4").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun inputDuringRefreshKeepsTheNewlySelectedChannel() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        refreshWhileLoading {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 5")).assertIsFocused()
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 5")).assertIsFocused()
    }

    @Test fun inputDuringRefreshKeepsSearchEditing() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        refreshWhileLoading {
            compose.onNodeWithText(compose.activity.getString(R.string.search_channels))
                .performTouchInput { click() }
            compose.onNode(hasSetTextAction()).assertIsFocused()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun inputDuringRefreshKeepsThePlayerOpen() {
        start()
        compose.onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        refreshWhileLoading {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertTrue(engine.view is SurfaceView) }
        }
        compose.runOnIdle { assertTrue(engine.view is SurfaceView) }
    }

    @Test fun refreshWaitsForADistantPageToLoad() {
        start(channels = 1_000)
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Channel 1"))).performScrollToIndex(300)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Channel 301").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Channel 301"))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(500)
        playlist.writeText(playlist.readText().replace("#EXTM3U\n", "#EXTM3U\n" + (1..500).joinToString("") {
            "#EXTINF:-1 group-title=\"News\",Inserted $it\nhttps://example.invalid/new/$it\n"
        }).replace("Channel 301\n", "Updated 301\n"))
        pageDelayMs.set(300)
        runBlocking { repo.load(source) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Updated 301") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
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
        var view: View? = null
        override fun setVideoView(view: View?) { this.view = view }
        override fun release() = Unit
    }
}
