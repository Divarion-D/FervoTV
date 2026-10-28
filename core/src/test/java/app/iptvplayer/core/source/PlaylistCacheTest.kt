package app.iptvplayer.core.source

import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Source
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
        fun due(r: PlaylistRefresh, savedAt: Long) = r.waitMs(savedAt, 0, now, 3)?.let { it <= 0 } ?: false
        assertTrue(due(PlaylistRefresh.ON_START, now))
        assertFalse(due(PlaylistRefresh.MANUAL, 0))
        assertFalse(due(PlaylistRefresh.DAILY, now - 23 * hour))
        assertTrue(due(PlaylistRefresh.DAILY, now - 24 * hour))
        assertTrue(due(PlaylistRefresh.HOURS_6, now - 7 * hour))
        assertFalse(due(PlaylistRefresh.WEEKLY, now - 6 * 24 * hour))
    }
}
