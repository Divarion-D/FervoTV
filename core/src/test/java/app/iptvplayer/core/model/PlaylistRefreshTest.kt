package app.iptvplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRefreshTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day

    @Test
    fun onStartOncePerRun() {
        assertEquals(0L, PlaylistRefresh.ON_START.waitMs(now, 0, now, 3))
        assertNull(PlaylistRefresh.ON_START.waitMs(now, now - hour, now, 3))
    }

    @Test
    fun manualNeverByTime() = assertNull(PlaylistRefresh.MANUAL.waitMs(0, 0, now, 3))

    @Test
    fun everyNDays() {
        assertEquals(day, PlaylistRefresh.EVERY_N_DAYS.waitMs(now - 2 * day, 0, now, 3))
        assertEquals(-day, PlaylistRefresh.EVERY_N_DAYS.waitMs(now - 6 * day, 0, now, 5))
    }

    @Test
    fun failedAttemptWaitsAFullIntervalToo() {
        // Saved a week ago, the refresh just failed: the next try is in 6 hours, not right away.
        assertEquals(6 * hour, PlaylistRefresh.HOURS_6.waitMs(now - 7 * day, now, now, 3))
        assertEquals(5 * hour, PlaylistRefresh.HOURS_6.waitMs(now - 7 * day, now - hour, now, 3))
    }

    @Test
    fun labels() {
        assertEquals("Каждые 2 дня", PlaylistRefresh.EVERY_N_DAYS.label(2))
        assertEquals("Каждые 5 дней", PlaylistRefresh.EVERY_N_DAYS.label(5))
        assertEquals("Раз в день", PlaylistRefresh.DAILY.label(5))
        assertEquals(listOf("день", "дня", "дней", "дней", "день", "дня"), listOf(1, 3, 11, 14, 21, 22).map { plural(it, "день", "дня", "дней") })
    }
}
