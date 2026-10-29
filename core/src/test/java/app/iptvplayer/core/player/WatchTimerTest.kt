package app.iptvplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LIVE-010: only a minute of uninterrupted watching puts a channel into "Недавние". */
class WatchTimerTest {
    private val min = 60_000L

    @Test
    fun aMinuteWithoutABreakCountsOnce() {
        val t = WatchTimer(min)
        assertNull(t.update("a", 0))
        assertNull(t.update("a", min - 1))
        assertEquals("a", t.update("a", min))
        assertNull(t.update("a", 5 * min)) // once per watch
    }

    @Test
    fun zappingDoesNotCount() {
        val t = WatchTimer(min)
        for (i in 0 until 30) assertNull(t.update("ch$i", i * 5_000L))
        assertNull(t.update("ch29", 29 * 5_000L + min - 1))
        assertEquals("ch29", t.update("ch29", 29 * 5_000L + min))
    }

    @Test
    fun aBreakStartsTheCountAgain() {
        val t = WatchTimer(min)
        t.update("a", 0)
        assertNull(t.update(null, 40_000)) // paused / in the background
        assertNull(t.update("a", 50_000))
        assertNull(t.update("a", 50_000 + min - 1))
        assertEquals("a", t.update("a", 50_000 + min))
        t.update("b", 200_000)
        assertEquals("a" to null, "a" to t.update("a", 200_001)) // back to a: counts from scratch
        assertEquals("a", t.update("a", 200_001 + min))
    }
}
