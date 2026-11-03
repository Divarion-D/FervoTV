package app.iptvplayer

import android.content.Context
import android.content.pm.PackageManager
import android.app.UiModeManager
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// MainActivity's package/mode fixtures use a separate Android sandbox from the component-only tests.
@Config(sdk = [34], qualifiers = "ru-port", instrumentedPackages = ["app.iptvplayer"])
@OptIn(ExperimentalTestApi::class)
class ChangelogUiTest {
    @get:Rule(order = 0) val fixture = object : TestWatcher() {
        override fun starting(description: Description) {
            setup(freshInstall = description.methodName.startsWith("freshInstall"))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scenario get() = compose.activityRule.scenario
    private val compositions = mutableListOf<ComposeView>()
    private lateinit var releasedVersion: String
    // The startup release check may fail or return an older release: neither outcome changes these assertions.
    private val version = "999.0.0"

    private fun setup(freshInstall: Boolean) {
        val mode = context.getSystemService(UiModeManager::class.java)
        shadowOf(mode).setCurrentModeType(context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        preferences.edit().clear().apply { if (!freshInstall) putString("seenVersion", "0.1.1") }.commit()
        context.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().clear().commit()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
        releasedVersion = info.versionName.orEmpty()
        info.versionName = version
        info.firstInstallTime = 1_000
        info.lastUpdateTime = if (freshInstall) info.firstInstallTime else 2_000
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun rememberComposition() = scenario.onActivity {
        compositions += it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ComposeView
    }

    private fun recreate() {
        rememberComposition()
        scenario.recreate()
    }

    @After fun disposeCompositions() {
        // Robolectric can retain the old decor view after recreation; do not leak its recomposer into other tests.
        rememberComposition()
        scenario.onActivity { compositions.forEach { it.disposeComposition() } }
    }

    private fun prompt() = compose.onNodeWithText(context.getString(R.string.updated_title))
    private fun click(id: Int) = compose.onNodeWithText(context.getString(id)).performTouchInput { click() }

    @Test fun packagedChangelogStartsWithCurrentVersionAndDescends() {
        val versions = context.assets.open("CHANGELOG.md").bufferedReader().useLines { lines ->
            lines.filter { it.startsWith("## ") }.map { it.removePrefix("## ").substringBefore(' ') }.toList()
        }
        assertTrue(versions.isNotEmpty())
        assertEquals(releasedVersion, versions.first())
        val numbers = versions.map { value -> value.split('.').map(String::toInt).also { assertEquals(3, it.size) } }
        assertEquals(numbers.sortedWith(compareByDescending<List<Int>> { it[0] }.thenByDescending { it[1] }.thenByDescending { it[2] }), numbers)
        assertEquals(versions.size, versions.toSet().size)
    }

    @Test fun offerSurvivesActivityRecreationUntilTheUserAnswers() {
        prompt().assertIsDisplayed()
        recreate()
        prompt().assertIsDisplayed()
        assertEquals("0.1.1", preferences.getString("seenVersion", null))
    }

    @Test fun laterAcknowledgesTheOfferAndItDoesNotReturn() {
        click(R.string.later)
        prompt().assertDoesNotExist()
        assertEquals(version, preferences.getString("seenVersion", null))
        recreate()
        prompt().assertDoesNotExist()
    }

    @Test fun readOpensThePackagedChangelogAndSurvivesRecreation() {
        click(R.string.whats_new)
        compose.onNodeWithText("$releasedVersion —", substring = true).assertIsDisplayed()
        recreate()
        compose.onNodeWithText("$releasedVersion —", substring = true).assertIsDisplayed()
        assertEquals(version, preferences.getString("seenVersion", null))
    }

    @Test fun freshInstallDoesNotShowAnUpdateOffer() {
        prompt().assertDoesNotExist()
        assertEquals(version, preferences.getString("seenVersion", null))
    }

    @Test
    @Config(sdk = [25], qualifiers = "en-land-television")
    fun tvOkOpensChangesAndBackDoesNotOfferAgain() {
        compose.onNodeWithText(context.getString(R.string.whats_new)).assertIsFocused()
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("$releasedVersion —", substring = true).assertIsDisplayed()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        prompt().assertDoesNotExist()
        assertEquals(version, preferences.getString("seenVersion", null))
    }
}
