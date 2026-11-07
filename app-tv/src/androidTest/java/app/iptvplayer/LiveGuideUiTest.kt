package app.iptvplayer

import android.content.Context
import android.view.KeyEvent
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LiveGuideUiTest {
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

    private fun category(text: String) =
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    @Test
    fun switchesBetweenLiveAndGuideWithTheDeviceInputMode() {
        val tv = context.isTv()
        InstrumentationRegistry.getArguments().getString("expectedMode")?.let { expected ->
            assertTrue("expectedMode must be phone or tv", expected in setOf("phone", "tv"))
            assertEquals("AVD reported the wrong UI mode", expected == "tv", tv)
        }
        if (tv) remoteSwitchesBetweenLiveAndGuide() else touchSwitchesBetweenLiveAndGuide()
    }

    private fun touchSwitchesBetweenLiveAndGuide() {
        val live = tab(context.getString(R.string.section_live))
        val guide = tab(context.getString(R.string.section_guide))

        compose.waitUntil(10_000) { compose.onAllNodes(live).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(guide).performTouchInput { click() }
        compose.onNode(guide).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.guide_none_title)).assertIsDisplayed()

        compose.onNode(live).performTouchInput { click() }
        compose.onNode(live).assertIsSelected()
        compose.onNodeWithText("Test MP4").assertIsDisplayed()
    }

    private fun remoteSwitchesBetweenLiveAndGuide() {
        val liveText = context.getString(R.string.section_live)
        val guideText = context.getString(R.string.section_guide)
        val live = tab(liveText)
        val guide = tab(guideText)
        val first = channel("Test MP4")

        compose.waitUntil(10_000) { compose.onAllNodes(first).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { runCatching { compose.onNode(first).assertIsFocused() }.isSuccess }
        pressBack()
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(category(context.getString(R.string.group_all))).assertIsFocused() }.isSuccess
        }
        pressBack()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription(liveText).assertIsFocused() }.isSuccess }
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.onNode(guide).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.guide_none_title)).assertIsDisplayed()

        pressBack()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription(guideText).assertIsFocused() }.isSuccess }
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionCenter)
        }
        compose.onNode(live).assertIsSelected()
        compose.onNodeWithText("Test MP4").assertIsDisplayed()
    }
}
