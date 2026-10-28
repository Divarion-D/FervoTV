package app.iptvplayer.core.epg

import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatchTest {
    private val entries = listOf(
        EpgEntry(1, "Perviy.ru", listOf("Первый канал"), null),
        EpgEntry(2, "rossia1", listOf("Россия 1", "Russia 1"), "http://i/r1.png"),
        EpgEntry(3, "rossia24", listOf("Россия 24"), null),
        EpgEntry(4, "perviy.ru", listOf("Первый (второй гид)"), null), // lower priority duplicate id
        EpgEntry(5, "src|u|9", emptyList(), null), // Xtream short EPG of a channel
    )

    private fun ch(name: String, tvgId: String? = null, key: String = "http://s/$name") = Channel(name, key, "g", tvgId = tvgId, key = key)

    /** EPG-004: the user's binding, tvg-id (any case), the normalized name, the channel's own short EPG. */
    @Test
    fun exactBindingOrder() {
        val index = EpgIndex(entries, manual = mapOf("http://s/Мой" to "ROSSIA24"))
        assertEquals(1L, index.find(ch("Что угодно", tvgId = "PERVIY.RU"))) // first source wins
        assertEquals(2L, index.find(ch("Россия 1 HD"))) // name: case, quality tag, space
        assertEquals(2L, index.find(ch("russia 1")))
        assertEquals(3L, index.find(ch("Мой", tvgId = "perviy.ru"))) // the user's binding wins
        assertEquals(5L, index.find(ch("Канал", key = "src|u|9")))
        assertNull(index.find(ch("Неизвестный")))
        // A binding to a guide channel that is gone falls back to the automatic one.
        assertEquals(1L, EpgIndex(entries, mapOf("http://s/X" to "gone")).find(ch("X", tvgId = "perviy.ru")))
    }

    /** EPG-005: similar names are bound or offered; numbers must agree. */
    @Test
    fun autoMatchBySimilarity() {
        val guide = listOf(
            EpgEntry(1, "a", listOf("Дом кино Премиум"), null),
            EpgEntry(2, "b", listOf("Россия 1"), null),
            EpgEntry(3, "c", listOf("Россия 24"), null),
            EpgEntry(4, "d", listOf("Карусель"), null),
            EpgEntry(5, "e", listOf("Карусель International"), null),
        )
        val r = autoMatch(listOf(ch("Дом Кино Премиум!"), ch("Россия-24 Москва"), ch("Каруселька"), ch("Россия 2")), guide)
        // «Каруселька» ~ «Карусель» 0.88, far ahead of «Карусель International».
        assertEquals(listOf("Дом Кино Премиум!" to 1L, "Каруселька" to 4L), r.sure.map { it.first.name to it.second.id })
        val unsure = r.unsure.associate { it.first.name to it.second.map { s -> s.entry.id } }
        assertEquals(listOf(3L), unsure["Россия-24 Москва"]) // 0.7, and never «Россия 1»
        assertTrue("Россия 2" !in unsure) // different number: no candidate at all
        assertEquals(listOf(4L, 5L), suggestEpg("карусель", guide).map { it.entry.id }.take(2))
    }

    /** EPG-008: overlapping programmes follow one another; a row is cells without gaps for one canvas. */
    @Test
    fun guideRowCells() {
        val a = Programme(100, 200, "A")
        val b = Programme(150, 300, "B") // overlaps A
        val c = Programme(160, 250, "C") // hidden by A and B
        val d = Programme(400, 500, "D")
        val row = nonOverlapping(listOf(a, b, c, d))
        assertEquals(listOf(a, Programme(200, 300, "B"), d), row)
        assertEquals(
            listOf(
                GuideCell(120, 200, a), GuideCell(200, 300, row[1]), GuideCell(300, 400, null), GuideCell(400, 450, d),
            ),
            guideCells(row, 120, 450),
        )
        assertEquals(listOf(GuideCell(0, 50, null)), guideCells(row, 0, 50))
    }
}
