package app.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogoIndexTest {
    @Test
    fun normalizesNames() {
        assertEquals("звезда", normName("Звезда HD"))
        assertEquals("vijuhistory", normName("viju+ History"))
        assertEquals("россия1", normName("Россия-1 FHD"))
        assertEquals("елки", normName("Ёлки 4K"))
        assertEquals("sdelano", normName("Sdelano")) // quality tags only as whole words
        assertEquals("", normName("HD"))
    }

    @Test
    fun findsByTvgIdThenName() {
        val index = LogoIndex(mapOf("id:zvezda.ru" to "u1", "звезда" to "u2"))
        assertEquals("u1", index.find(Channel("Что угодно", "x", "g", tvgId = "Zvezda.ru")))
        assertEquals("u2", index.find(Channel("Звезда HD", "x", "g")))
        assertEquals("u2", index.find(Channel("Звезда", "x", "g", tvgId = "unknown")))
        assertNull(index.find(Channel("Нет такого", "x", "g")))
        assertNull(index.find(Channel("HD", "x", "g"))) // empty normalized name never matches
    }
}
