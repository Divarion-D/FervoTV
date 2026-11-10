package app.iptvplayer

import app.iptvplayer.ui.rangeBetween
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveManagementTest {
    @Test fun rangeIncludesBothRowsInEitherDirection() {
        assertEquals(2..5, rangeBetween(2, 5))
        assertEquals(2..5, rangeBetween(5, 2))
        assertEquals(5..5, rangeBetween(null, 5))
    }

    @Test fun rangeFollowsStableKeyAfterRowsMove() {
        assertEquals(1..3, rangeBetween("first", listOf("new", "first", "middle", "last"), 3))
        assertEquals(3..3, rangeBetween("removed", listOf("new", "first", "middle", "last"), 3))
    }
}
