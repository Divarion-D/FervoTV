package app.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCacheTest {
    private val channels = listOf(
        Channel("Звезда HD", "http://cdn/zvezda.m3u8", "Документальное", "https://i/1.png", "Zvezda.ru"),
        Channel("Tab\there", "http://a", "Line\nbreak"),
    )

    @Test
    fun roundTrip() {
        val cached = decodePlaylist("k1", encodePlaylist("k1", 1234L, channels))!!
        assertEquals(1234L, cached.savedAt)
        assertEquals(channels[0], cached.channels[0])
        // Tabs/newlines inside values can't break the format.
        assertEquals(Channel("Tab here", "http://a", "Line break"), cached.channels[1])
    }

    @Test
    fun otherSourceOrDamagedIsIgnored() {
        assertNull(decodePlaylist("other", encodePlaylist("k1", 1L, channels)))
        assertNull(decodePlaylist("k1", ""))
        assertNull(decodePlaylist("k1", "garbage\nlines"))
        assertNull(decodePlaylist("k1", "#k1\tnot-a-number\n"))
    }

    @Test
    fun sourceKeyIsStableAndDistinct() {
        assertEquals(Source.M3u("http://x").cacheKey(), Source.M3u("http://x").cacheKey())
        assertFalse(Source.M3u("http://x").cacheKey() == Source.M3u("http://y").cacheKey())
    }

    @Test
    fun refreshPolicy() {
        val hour = 3_600_000L
        val now = 100 * 24 * hour
        assertTrue(PlaylistRefresh.ON_START.isDue(now, now))
        assertFalse(PlaylistRefresh.MANUAL.isDue(0, now))
        assertFalse(PlaylistRefresh.DAILY.isDue(now - 23 * hour, now))
        assertTrue(PlaylistRefresh.DAILY.isDue(now - 24 * hour, now))
        assertTrue(PlaylistRefresh.HOURS_6.isDue(now - 7 * hour, now))
        assertFalse(PlaylistRefresh.WEEKLY.isDue(now - 6 * 24 * hour, now))
    }
}
