package app.iptvplayer.core

import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.EpgResult
import app.iptvplayer.core.db.EpgSource
import app.iptvplayer.core.db.EpgStatus
import app.iptvplayer.core.db.failedStatus
import app.iptvplayer.core.db.lastStatus
import app.iptvplayer.core.db.decodeGroup
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.db.matchName
import app.iptvplayer.core.db.matchesWords
import app.iptvplayer.core.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscTest {
    @Test
    fun categorySearchMatchesWordStarts() {
        assertTrue(matchesWords("Спорт | HD", "спо"))
        assertTrue(matchesWords("Детские / Мультфильмы", "мульт дет"))
        assertTrue(matchesWords("Новости Ёлки", "елк"))
        assertTrue(matchesWords("Кино", ""))
        assertFalse(matchesWords("Спорт", "порт"))
        assertFalse(matchesWords("Спорт", "спорт кино"))
    }

    /** LIVE-008: the name a favourite finds its channel by in another source. */
    @Test
    fun favoriteMatchNameIgnoresCaseYoSpacesAndPunctuation() {
        for ((name, expected) in listOf(
            "Первый HD" to "первыйhd",
            "ПЕРВЫЙ-HD" to "первыйhd",
            "  первый   hd " to "первыйhd",
            "Ёлки" to "елки",
            "ЁЛКИ!" to "елки",
            "Матч! Футбол 1" to "матчфутбол1",
            "Sky Sports (UK)" to "skysportsuk",
            "+++" to "",
        )) assertEquals(name, expected, matchName(name))
    }

    @Test
    fun changelogOfferedOncePerUpdate() {
        assertFalse(changelogDue(null, "0.2", updated = false)) // fresh install
        assertTrue(changelogDue(null, "0.2", updated = true)) // updated from 0.1, which recorded nothing
        assertTrue(changelogDue("0.1", "0.2", updated = true))
        assertFalse(changelogDue("0.2", "0.2", updated = true))
    }

    @Test
    fun qrHasFinderPatternsInThreeCorners() {
        val m = qrModules(Defaults.GITHUB_URL)
        val n = m.size
        assertTrue(n >= 21 && (n - 21) % 4 == 0) // 21 + 4 per version
        assertEquals(n, m[0].size)
        // 7×7 finder: dark ring, light ring, dark 3×3 centre — top-left, top-right, bottom-left; not bottom-right.
        fun finder(x0: Int, y0: Int) = (0 until 7).all { y ->
            (0 until 7).all { x ->
                val ring = x == 0 || y == 0 || x == 6 || y == 6
                val centre = x in 2..4 && y in 2..4
                m[y0 + y][x0 + x] == (ring || centre)
            }
        }
        assertTrue(finder(0, 0) && finder(n - 7, 0) && finder(0, n - 7))
        assertFalse(finder(n - 7, n - 7))
    }

    @Test
    fun programmeTimes() {
        val p = Programme(start = 0, stop = 90 * 60_000L, title = "Фильм")
        assertEquals(0.5f, p.progress(45 * 60_000L), 0.001f)
        assertEquals(0f, p.progress(-5), 0f)
        assertEquals(1f, p.progress(100 * 60_000L), 0f)
        assertEquals(42, p.minutesLeft(48 * 60_000L)) // 42 min left
        assertEquals(1, p.minutesLeft(90 * 60_000L - 1)) // never "ещё 0 мин" while it is on
    }

    @Test
    fun groupsSurviveAsStrings() {
        for (g in listOf(ChannelGroup.All, ChannelGroup.Favorites, ChannelGroup.Recent, ChannelGroup.Archive,
            ChannelGroup.Category("Кино: новинки"), ChannelGroup.Collection(7))) assertEquals(g, decodeGroup(g.encode()))
        assertEquals(ChannelGroup.All, decodeGroup(null))
        assertEquals(ChannelGroup.All, decodeGroup("collection:x"))
    }

    /** UI-022: a guide's last result is stored as data, not as words; core's own failures keep their code. */
    @Test
    fun epgStatusIsData() {
        fun status(s: String?) = EpgSource(url = "u", status = s).lastStatus()
        val ok = EpgResult(channels = 3, programmes = 40, matched = 0, damaged = true)
        assertEquals(EpgStatus.Loaded(ok), status(ok.status()))
        assertEquals(EpgStatus.Failed(ErrorCode.XTREAM_BAD_EPG, null), status(failedStatus(CoreException(ErrorCode.XTREAM_BAD_EPG))))
        assertEquals(EpgStatus.Failed(null, "HTTP 404"), status(failedStatus(java.io.IOException("HTTP 404"))))
        assertEquals(null, status("Ошибка: старая запись")) // written by 0.1/early 0.2: replaced by the next refresh
        assertEquals(null, status(null))
    }
}
