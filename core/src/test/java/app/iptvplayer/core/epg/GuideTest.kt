package app.iptvplayer.core.epg

import app.iptvplayer.core.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** EPG-008/009: grid time maths and the remote's moves in a row. Times in minutes for readability. */
class GuideTest {
    private val m = 60_000L
    private fun p(from: Int, to: Int, title: String = "$from") = Programme(from * m, to * m, title)

    @Test
    fun ticksEveryHalfHourOfLocalTime() {
        // A +5:45 zone: the scale still says :00 and :30 of the local clock.
        val offset = (5 * 60 + 45) * m
        val ticks = guideTicks(10 * m, 120 * m, offset)
        assertEquals(listOf(15, 45, 75, 105).map { it * m }, ticks)
        assertEquals(listOf(0L, 30 * m), guideTicks(0, 60 * m, 0)) // a label right at the start is kept
    }

    @Test
    fun opensWithNowAtAThird() {
        val span = 240 * m
        val view = openingView(1_000 * m, span)
        assertEquals(1f / 3, (1_000 * m - view).toFloat() / span, 0.001f)
    }

    @Test
    fun windowIsKeptUntilTheViewNearsItsEdge() {
        val span = 240 * m
        val w = guideWindow(600 * m, span, null)
        assertEquals(GuideWindow(240 * m, 1_680 * m), w) // view at 10:00 → 04:00…04:00 next day
        assertSame(w, guideWindow(300 * m, span, w))
        assertSame(w, guideWindow(1_380 * m, span, w))
        assertEquals(GuideWindow(-120 * m, 1_320 * m), guideWindow(299 * m, span, w)) // less than an hour left before it
        assertEquals(1_020 * m, guideWindow(1_381 * m, span, w).from) // and before it ends
        val wide = guideWindow(0, 900 * m, null)
        assertEquals(1_620 * m, wide.to - wide.from) // a very wide view still fits with room to spare
    }

    @Test
    fun gapsAreSteppedInHalfHours() {
        val cells = focusCells(guideCells(listOf(p(10, 20), p(95, 120)), 0, 150 * m), 0)
        assertEquals(
            listOf(0 to 10, 10 to 20, 20 to 30, 30 to 60, 60 to 90, 90 to 95, 95 to 120, 120 to 150),
            cells.map { (it.start / m).toInt() to (it.stop / m).toInt() },
        )
        assertEquals("10", cells[1].programme?.title)
        assertNull(cells[2].programme)
        assertEquals(cells[3], cellAt(cells, 45 * m))
    }

    @Test
    fun stepsToNeighboursAndScrollsJustEnough() {
        val range = -10_000 * m..10_000 * m
        val cells = focusCells(guideCells(listOf(p(0, 60), p(60, 90), p(90, 300), p(300, 330)), -600 * m, 900 * m), 0)
        val span = 120 * m
        // In view: the focus moves, the view stays.
        assertEquals(0L to 60 * m, guideStep(cells, 10 * m, 0, span, true, range))
        // The next one is long and runs past the view: it is shown from its start.
        assertEquals(90 * m to 90 * m, guideStep(cells, 70 * m, 0, span, true, range))
        // On a cell cut on the right: ▶ scrolls by half a view first, the focus stays on it.
        assertEquals(150 * m to 150 * m, guideStep(cells, 100 * m, 90 * m, span, true, range))
        // A short next one is shown with its end at the view's edge.
        assertEquals(210 * m to 300 * m, guideStep(cells, 200 * m, 180 * m, span, true, range))
        // ◀ on a cell cut on the left scrolls back first, then goes to the previous one.
        assertEquals(140 * m to 140 * m, guideStep(cells, 200 * m, 200 * m, span, false, range))
        assertEquals(60 * m to 60 * m, guideStep(cells, 100 * m, 90 * m, span, false, range))
        // At the edge of the guide: nowhere to go.
        val edge = 0L..0L
        assertNull(guideStep(cells, 10 * m, 0, span, false, edge)) // the previous one is before the earliest view
        assertEquals(0L to 60 * m, guideStep(cells, 10 * m, 0, span, true, edge))
        assertNull(guideStep(cells, 70 * m, 0, 80 * m, true, edge)) // the next one starts beyond the latest view
    }

    @Test
    fun viewRangeFromYesterdayToTheLastDay() {
        val day = 1_440 * m
        val r = viewRange(now = 3 * day + 600 * m, offset = 0, days = 7, span = 240 * m)
        assertEquals(2 * day, r.first)
        assertEquals(10 * day - 240 * m, r.last)
    }

    @Test
    fun watchedAgo() {
        val now = 3 * 1_440 * m + 600 * m // 10:00
        assertEquals(Ago.JustNow, ago(now - 30_000, now, 0))
        assertEquals(Ago.Minutes(5), ago(now - 5 * m, now, 0))
        assertEquals(Ago.Today, ago(now - 120 * m, now, 0))
        assertEquals(Ago.Yesterday, ago(now - 900 * m, now, 0))
        assertEquals(Ago.Earlier, ago(now - 3_000 * m, now, 0))
        assertEquals(Ago.Yesterday, ago(now - 180 * m, now, -480 * m)) // 02:00 local: three hours ago was yesterday there
    }
}
