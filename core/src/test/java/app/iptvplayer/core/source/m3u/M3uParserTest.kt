package app.iptvplayer.core.source.m3u

import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {
    @Test
    fun attributesAndNameWithComma() {
        val ch = parseM3u(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="one.ru" tvg-logo="http://l/1.png" group-title="Новости",Первый, HD
            http://s/1.ts
            """.trimIndent()
        ).single()
        assertEquals(Channel("Первый, HD", "http://s/1.ts", "Новости", "http://l/1.png", "one.ru"), ch)
    }

    @Test
    fun commaInsideQuotedAttribute() {
        val ch = parseM3u("#EXTINF:-1 group-title=\"A, B\",Name\nhttp://x").single()
        assertEquals("Name", ch.name)
        assertEquals("A, B", ch.group)
    }

    @Test
    fun extGrpFallbackAndMissingGroup() {
        val list = parseM3u("#EXTINF:-1,A\n#EXTGRP:Sport\nhttp://a\n#EXTINF:-1,B\nhttp://b")
        assertEquals(listOf("Sport", NO_GROUP), list.map { it.group })
    }

    @Test
    fun crlfBomAndUnknownTags() {
        val list = parseM3u("\uFEFF#EXTM3U\r\n#EXTINF:-1,A\r\n#EXTVLCOPT:http-user-agent=x\r\nhttp://a\r\n\r\n")
        assertEquals(listOf(Channel("A", "http://a", NO_GROUP)), list)
    }

    @Test
    fun bareUrlList() {
        val ch = parseM3u("http://h/live/7.m3u8?t=1\n").single()
        assertEquals("7.m3u8", ch.name)
        assertEquals("http://h/live/7.m3u8?t=1", ch.url)
    }

    @Test
    fun logoAttributesAndRelativePaths() {
        val text = """
            #EXTINF:-1 logo="/misc/1.png",A
            http://a
            #EXTINF:-1 tvg-logo="logos/2.png",B
            http://b
            #EXTINF:-1 tvg-logo="https://cdn/3.png" logo="/ignored.png",C
            http://c
            #EXTINF:-1 tvg-logo="",D
            http://d
        """.trimIndent()
        assertEquals(
            listOf("http://host/misc/1.png", "http://host/lists/logos/2.png", "https://cdn/3.png", null),
            parseM3u(text, "http://host/lists/tv.m3u").map { it.logo },
        )
        // Local file: relative paths can't be resolved, keep as is.
        assertEquals("/misc/1.png", parseM3u(text, "content://x/doc").first().logo)
    }

    @Test
    fun nameFallsBackToTvgName() {
        assertEquals("Zoo", parseM3u("#EXTINF:-1 tvg-name=\"Zoo\",\nhttp://z").single().name)
    }
}
