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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class NavigationUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0)
    val fixture = object : TestWatcher() {
        override fun starting(description: Description) {
            UiFixtures.seed(context, "http://127.0.0.1:8765")
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            check(context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("seenVersion", version).commit())
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(text: String) =
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun channel(text: String) =
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    @Test
    fun touchMovesFromLiveToMoreAndBack() {
        assumeFalse(context.isTv())
        val configuration = context.resources.configuration
        assumeFalse(configuration.screenWidthDp >= 600 && configuration.screenWidthDp > configuration.screenHeightDp)
        val live = tab(context.getString(R.string.section_live))
        val more = tab(context.getString(R.string.section_more))

        compose.waitUntil(10_000) { compose.onAllNodes(live).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(live).assertIsSelected()
        compose.onNode(more).performTouchInput { click() }
        compose.onNode(more).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.more_favorites)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.more_history)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.section_settings)).assertIsDisplayed()

        compose.onNode(live).performTouchInput { click() }
        compose.onNode(live).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.search_channels)).assertIsDisplayed()
    }

    @Test
    fun remoteMovesFocusBetweenLiveChannels() {
        assumeTrue(context.isTv())
        val first = channel("Test MP4")
        val second = channel("Test MPEG-TS")

        compose.waitUntil(10_000) { compose.onAllNodes(first).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { runCatching { compose.onNode(first).assertIsFocused() }.isSuccess }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(second).assertIsFocused()
    }
}
