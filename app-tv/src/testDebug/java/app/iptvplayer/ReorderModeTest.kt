package app.iptvplayer

import app.iptvplayer.ui.ReorderEntry
import app.iptvplayer.ui.ReorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderModeTest {
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
}
