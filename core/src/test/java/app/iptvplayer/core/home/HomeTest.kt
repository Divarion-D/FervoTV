package app.iptvplayer.core.home

import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.Watch
import app.iptvplayer.core.home.HomeRow.CONTINUE
import app.iptvplayer.core.home.HomeRow.FAVORITE_CHANNELS
import app.iptvplayer.core.home.HomeRow.FAVORITE_MOVIES
import app.iptvplayer.core.home.HomeRow.FAVORITE_SERIES
import app.iptvplayer.core.home.HomeRow.RECENT_CHANNELS
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.VodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HOME-001…006, HOME-012, HOME-013: the pure rules of the home screen, quick continue, history and jumps. */
class HomeTest {
    private fun ch(n: Int) = ChannelItem(Channel("Канал $n", "http://s/$n", "Новости"), n, favorite = false, hidden = false)
    private fun film(key: String, at: Long, position: Long = 60_000, watched: Boolean = false) =
        Watch(key, VodKind.MOVIE, title = key, position = position, duration = 600_000, watched = watched, watchedAt = at)
    private fun episode(key: String, at: Long, e: Int, watched: Boolean = false) =
        Watch(key, VodKind.SERIES, "show", 1, e, "Шоу", position = if (watched) 0 else 60_000, duration = 600_000, watched = watched, watchedAt = at)

    @Test
    fun rowsByDefaultAndAsTheUserSetThem() {
        // Default order; the recent channels row is off while channels are part of «Продолжить» (HOME-004).
        assertEquals(
            listOf(CONTINUE to true, FAVORITE_CHANNELS to true, FAVORITE_MOVIES to true, FAVORITE_SERIES to true, RECENT_CHANNELS to false),
            homeRows("", channelsInContinue = true).map { it.row to it.on },
        )
        assertEquals(true, homeRows("", channelsInContinue = false).last().on)
        // The user's order and switches; unknown and repeated names are skipped, missing rows follow.
        val rows = homeRows("FAVORITE_MOVIES,-CONTINUE,NOPE,FAVORITE_MOVIES,RECENT_CHANNELS", channelsInContinue = true)
        assertEquals(listOf(FAVORITE_MOVIES, CONTINUE, RECENT_CHANNELS, FAVORITE_CHANNELS, FAVORITE_SERIES), rows.map { it.row })
        assertEquals(listOf(true, false, true, true, true), rows.map { it.on })
        assertEquals(rows, homeRows(encodeHomeRows(rows), channelsInContinue = true))
        // Moving stays inside the list.
        assertEquals(listOf(CONTINUE, FAVORITE_MOVIES), rows.moved(CONTINUE, -1).take(2).map { it.row })
        assertEquals(rows, rows.moved(FAVORITE_MOVIES, -1))
        assertEquals(FAVORITE_SERIES, rows.moved(FAVORITE_SERIES, 5).last().row)
    }

    @Test
    fun onlyRowsThatAreOnAndNotEmptyShow() {
        val rows = homeRows("-FAVORITE_SERIES", channelsInContinue = true)
        val sizes = mapOf(CONTINUE to 3, FAVORITE_CHANNELS to 0, FAVORITE_MOVIES to 2, FAVORITE_SERIES to 5, RECENT_CHANNELS to 4)
        assertEquals(listOf(CONTINUE, FAVORITE_MOVIES), visibleRows(rows, sizes))
        assertEquals(emptyList<HomeRow>(), visibleRows(rows, emptyMap()))
    }

    @Test
    fun continueRowMixesFilmsEpisodesAndChannelsNewestFirst() {
        val vod = listOf(film("m1", at = 300), episode("e2", at = 100, e = 2))
        val channels = listOf(ch(1) to 400L, ch(2) to 200L)
        assertEquals(listOf(400L, 300L, 200L, 100L), continueRow(vod, channels, withChannels = true, limit = 10).map { it.at })
        assertEquals(listOf("m1", "e2"), continueRow(vod, channels, withChannels = false, limit = 10).map { (it as VodContinue).watch.vkey })
        assertEquals(2, continueRow(vod, channels, withChannels = true, limit = 2).size)
        assertEquals("show", (continueRow(vod, emptyList(), false, 10)[1] as VodContinue).itemKey)
    }

    @Test
    fun quickContinueTakesTheNewest() {
        val next = Episode("e3", "show", 1, 3, "Третья", "Шоу S01E03", "http://s/e3", null, null, null, null, null)
        // A film left in the middle, newer than the channel.
        assertEquals("m1", ((quickContinue(film("m1", 500), null, null, ch(1) to 400L)) as VodContinue).watch.vkey)
        // The last channel is newer.
        assertEquals("http://s/1", (quickContinue(film("m1", 300), null, null, ch(1) to 400L) as ChannelContinue).channel.channel.key)
        // A finished episode goes on with the next one.
        val q = quickContinue(film("m1", 100), episode("e2", 600, 2, watched = true), next, ch(1) to 400L) as VodContinue
        assertEquals("e3", q.next?.key)
        assertEquals(VodKind.SERIES, q.kind)
        // The series is over: nothing from it.
        assertEquals("m1", (quickContinue(film("m1", 100), episode("e9", 600, 9, watched = true), null, null) as VodContinue).watch.vkey)
        assertNull(quickContinue(null, null, null, null))
    }

    @Test
    fun historyGroupsByDayAndKind() {
        val day = 86_400_000L
        val vod = listOf(film("m1", at = 2 * day + 10), episode("e1", at = day + 5, e = 1), film("m0", at = 10))
        val channels = listOf(ch(1) to 2 * day + 20)
        val days = historyDays(vod, channels, HistoryFilter.ALL) { it / day * day }
        assertEquals(listOf(2 * day, day, 0L), days.map { it.first })
        assertEquals(listOf(2 * day + 20, 2 * day + 10), days[0].second.map { it.at })
        assertEquals(listOf("e1"), historyDays(vod, channels, HistoryFilter.SERIES) { it / day * day }.flatMap { d -> d.second.map { (it as VodContinue).watch.vkey } })
        assertEquals(listOf("m1", "m0"), historyDays(vod, channels, HistoryFilter.MOVIES) { it / day * day }.flatMap { d -> d.second.map { (it as VodContinue).watch.vkey } })
        assertEquals(1, historyDays(vod, channels, HistoryFilter.CHANNELS) { it / day * day }.single().second.size)
        assertEquals(emptyList<Any>(), historyDays(emptyList(), emptyList(), HistoryFilter.ALL) { it })
    }

    @Test
    fun aFoundItemOpensInItsOwnListElseAll() {
        val listed = listOf(ChannelGroup.Favorites, ChannelGroup.All, ChannelGroup.Category("Новости"))
        val news = ChannelGroup.Category("Новости")
        val hidden = ChannelGroup.Category("Скрытая")
        assertEquals(listOf(news, ChannelGroup.All), jumpLists(news, ChannelGroup.Favorites, listed, ChannelGroup.All))
        assertEquals(listOf(ChannelGroup.All), jumpLists(hidden, ChannelGroup.Favorites, listed, ChannelGroup.All))
        // No list asked for («Продолжить» of a film): the list on screen, then «Все».
        assertEquals(listOf(ChannelGroup.Favorites, ChannelGroup.All), jumpLists(null, ChannelGroup.Favorites, listed, ChannelGroup.All))
        assertEquals(listOf(ChannelGroup.All), jumpLists(null, ChannelGroup.All, listed, ChannelGroup.All))
    }
}
