package app.iptvplayer.core.model

import app.iptvplayer.core.db.finished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VOD-005/006/009/010: what continues, what comes next, how a season is listed. */
class WatchingTest {
    private fun ep(season: Int, n: Int, p: Progress? = null) =
        Episode("s|$season|$n", "s", season, n, "E$n", "E$n", "http://h/$season/$n.mp4", null, null, null, null, p)

    private fun started(at: Long, pos: Long = 60_000) = Progress(pos, 3_600_000, false, at)
    private fun watched(at: Long) = Progress(0, 3_600_000, true, at)

    @Test
    fun resumeByTheSetting() {
        val p = started(1, pos = 754_000)
        assertEquals(ResumeChoice(754_000, false), resumeChoice(p, ResumeMode.CONTINUE))
        assertEquals(ResumeChoice(754_000, true), resumeChoice(p, ResumeMode.ASK))
        assertEquals(ResumeChoice(0, false), resumeChoice(p, ResumeMode.START))
        // Never started or finished: from the start, nothing to ask.
        for (mode in ResumeMode.entries) {
            assertEquals(ResumeChoice(0, false), resumeChoice(null, mode))
            assertEquals(ResumeChoice(0, false), resumeChoice(watched(1), mode))
            assertEquals(ResumeChoice(0, false), resumeChoice(started(1, pos = 0), mode))
        }
    }

    @Test
    fun watchedThresholdAsTheDataLayerUsesIt() {
        val hour = 3_600_000L
        assertFalse(finished(hour / 2, hour))
        assertTrue(finished((hour * 0.95).toLong(), hour)) // 95 %
        assertTrue(finished(15 * 60_000L - 9_000, 15 * 60_000L)) // the last 10 s of a short one
        assertFalse(finished(10_000, 0)) // unknown length: never
    }

    @Test
    fun nextEpisodeGoesAcrossSeasonsThenSpecialsThenStops() {
        // Stored order is the provider's; viewing order is seasons up, specials last.
        val list = listOf(ep(2, 1), ep(0, 1), ep(1, 2), ep(1, 1), ep(0, 2))
        assertEquals("s|1|2", nextEpisode(list, ep(1, 1))?.key)
        assertEquals("s|2|1", nextEpisode(list, ep(1, 2))?.key) // into the next season
        assertEquals("s|0|1", nextEpisode(list, ep(2, 1))?.key) // after the last season: specials
        assertEquals("s|0|2", nextEpisode(list, ep(0, 1))?.key)
        assertNull(nextEpisode(list, ep(0, 2))) // then stop
        assertNull(nextEpisode(list, ep(5, 5))) // not in the list
        assertEquals("s|1|2", previousEpisode(list, ep(2, 1))?.key)
        assertNull(previousEpisode(list, ep(1, 1)))
    }

    @Test
    fun seriesContinuesWhereLeft() {
        assertNull(continueEpisode(emptyList()))
        // Not started: the first episode of the first season (specials don't come first).
        assertEquals("s|1|1", continueEpisode(listOf(ep(0, 1), ep(2, 1), ep(1, 1)))?.key)
        // The one watched last, unfinished: itself.
        assertEquals("s|1|2", continueEpisode(listOf(ep(1, 1, watched(1)), ep(1, 2, started(5)), ep(1, 3, started(2))))?.key)
        // Finished: the next one, across a season.
        assertEquals("s|2|1", continueEpisode(listOf(ep(1, 1, watched(1)), ep(1, 2, watched(9)), ep(2, 1)))?.key)
        // The very last one finished: it stays.
        assertEquals("s|1|2", continueEpisode(listOf(ep(1, 1, watched(1)), ep(1, 2, watched(9))))?.key)
    }

    @Test
    fun seasonTabsAndEpisodeOrder() {
        val list = listOf(ep(0, 1), ep(1, 1, watched(1)), ep(1, 2), ep(1, 3), ep(2, 1))
        assertEquals(listOf(1 to 3, 2 to 1, 0 to 1), seasons(list))
        assertEquals(4, episodeTotal(list)) // specials don't count
        assertEquals(listOf(2 to 1, 1 to 3, 0 to 1), seasonTabs(list, SeriesView(newestFirst = true)))
        assertEquals(listOf(1, 2, 3), seasonEpisodes(list, 1, SeriesView()).map { it.episode })
        assertEquals(listOf(3, 2, 1), seasonEpisodes(list, 1, SeriesView(newestFirst = true)).map { it.episode })
        assertEquals(listOf(2, 3), seasonEpisodes(list, 1, SeriesView(hideWatched = true)).map { it.episode })
    }
}
