package app.iptvplayer.core

import app.iptvplayer.core.db.matchesWords
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
}
