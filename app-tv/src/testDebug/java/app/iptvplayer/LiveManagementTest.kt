package app.iptvplayer

import app.iptvplayer.ui.rangeBetween
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveManagementTest {
    @Test fun rangeIncludesBothRowsInEitherDirection() {
        assertEquals(2..5, rangeBetween(2, 5))
        assertEquals(2..5, rangeBetween(5, 2))
        assertEquals(5..5, rangeBetween(-1, 5))
    }
}
