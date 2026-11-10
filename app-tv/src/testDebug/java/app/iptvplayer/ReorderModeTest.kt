package app.iptvplayer

import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.ReorderEntry
import app.iptvplayer.ui.ReorderMode
import app.iptvplayer.ui.ReorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-land-television")
@OptIn(ExperimentalTestApi::class)
class ReorderModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val items = listOf("a", "b", "c", "d").map { ReorderEntry(it, it) }

    @Test
    fun oneDraftMovesSeveralItemsBeforeItIsSaved() {
        var state = ReorderState(items, cursor = 1, picked = true)
        state = state.step(2).toggle()
        assertFalse(state.picked)

        state = state.moveTo(1).toggle().step(-1).toggle()

        assertEquals(listOf("c", "a", "d", "b"), state.items.map { it.key })
        assertEquals(listOf("a", "b", "c", "d"), items.map { it.key })
        assertFalse(state.picked)
    }

    @Test
    fun pageAndEdgeMovesKeepThePickedItem() {
        var state = ReorderState(items, cursor = 2, picked = true)
        state = state.moveTo(0)
        assertEquals(listOf("c", "a", "b", "d"), state.items.map { it.key })
        assertTrue(state.picked)
        assertEquals(0, state.cursor)
    }

    @Test
    fun droppedTopItemCanReachSaveWithTheRemote() {
        var saved = emptyList<String>()
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        compose.setContent {
            AppTheme {
                ReorderMode("Order", items, "a", onSave = { saved = it }, onCancel = {})
            }
        }

        compose.onNodeWithText("a").assertIsFocused()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionCenter)
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithContentDescription("Cancel").assertIsFocused()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionCenter)
        }

        assertEquals(listOf("a", "b", "c", "d"), saved)
    }
}
