package app.iptvplayer

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.AppDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibrarySearchHistoryUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0)
    val fixture = object : TestWatcher() {
        override fun starting(description: Description) {
            UiFixtures.seed(context, "http://127.0.0.1:8765")
            if (!context.isTv()) {
                Repository(context, AppDb.get(context)).apply { settings = settings.copy(homeVisible = true) }
            }
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            check(context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("seenVersion", version).commit())
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun action(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

    private fun category(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    @Test
    fun opensLibrarySearchAndHistoryWithTheDeviceInputMode() {
        val tv = context.isTv()
        InstrumentationRegistry.getArguments().getString("expectedMode")?.let { expected ->
            assertTrue("expectedMode must be phone or tv", expected in setOf("phone", "tv"))
            assertEquals("AVD reported the wrong UI mode", expected == "tv", tv)
        }
        if (tv) remoteOpensLibrarySearchAndHistory() else touchOpensLibrarySearchAndHistory()
    }

    private fun touchOpensLibrarySearchAndHistory() {
        val movies = tab(context.getString(R.string.section_movies))
        val series = tab(context.getString(R.string.section_series))
        val home = tab(context.getString(R.string.section_home))
        val more = tab(context.getString(R.string.section_more))

        compose.waitUntil(10_000) { compose.onAllNodes(movies).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(movies).performTouchInput { click() }
        compose.onNode(movies).assertIsSelected()
        compose.onNodeWithText("Test multi audio subtitles", substring = true).assertIsDisplayed()

        compose.onNode(series).performTouchInput { click() }
        compose.onNode(series).assertIsSelected()
        compose.onNodeWithText("Test Series").assertIsDisplayed()

        compose.onNode(home).performTouchInput { click() }
        compose.onNodeWithContentDescription(context.getString(R.string.section_search)).performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.search_everything)).assertIsDisplayed()

        compose.onNode(more).performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.more_history)).performTouchInput { click() }
        compose.onNodeWithText("Test multi audio subtitles").assertIsDisplayed()
        compose.onNodeWithText("Test Series").assertIsDisplayed()
    }

    private fun remoteOpensLibrarySearchAndHistory() {
        val live = context.getString(R.string.section_live)
        val movies = context.getString(R.string.section_movies)
        val search = context.getString(R.string.section_search)

        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test MP4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(category(context.getString(R.string.group_all))).assertIsFocused() }.isSuccess
        }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription(live).assertIsFocused() }.isSuccess }
        compose.onRoot().performKeyInput {
            repeat(2) { pressKey(Key.DirectionDown) }
            pressKey(Key.DirectionCenter)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test multi audio subtitles", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(category(context.getString(R.string.group_all_movies))).assertIsFocused() }.isSuccess
        }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription(movies).assertIsFocused() }.isSuccess }
        compose.onRoot().performKeyInput {
            repeat(3) { pressKey(Key.DirectionUp) }
            pressKey(Key.DirectionCenter)
        }
        compose.onNodeWithText(context.getString(R.string.search_everything)).assertIsDisplayed()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription(search).assertIsFocused() }.isSuccess }
        compose.onRoot().performKeyInput {
            repeat(5) { pressKey(Key.DirectionDown) }
            pressKey(Key.DirectionCenter)
        }
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(action(context.getString(R.string.more_favorites))).assertIsFocused() }.isSuccess
        }
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.onNodeWithText("Test multi audio subtitles").assertIsDisplayed()
        compose.onNodeWithText("Test Series").assertIsDisplayed()
    }
}
