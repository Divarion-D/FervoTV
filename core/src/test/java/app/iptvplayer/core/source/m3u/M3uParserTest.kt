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

    /** SRC-008: guide addresses from the header, channel numbers, archive depth; unknown tags still skipped. */
    @Test
    fun headerEpgUrlsNumbersAndArchive() {
        val text = """
            #EXTM3U url-tvg="http://e/a.xml.gz, http://e/b.xml" x-tvg-url="http://e/b.xml" tvg-shift="0"
            #EXTINF:-1 tvg-chno="7" catchup="default" catchup-days="3",Семь
            #EXTVLCOPT:http-user-agent=X
            #KODIPROP:inputstream=x
            http://s/7
            #EXTINF:-1 tvg-chno="x" tvg-rec="2",Без номера
            http://s/0
            #EXTM3U x-tvg-url="http://e/c.xml"
            #EXTINF:-1,Обычный
            http://s/1
        """.trimIndent()
        val urls = ArrayList<List<String>>()
        val channels = ArrayList<Channel>()
        parseM3u(java.io.StringReader(text), null, urls::add) { channels += it }
        assertEquals(listOf(listOf("http://e/a.xml.gz", "http://e/b.xml"), listOf("http://e/c.xml")), urls)
        assertEquals(listOf(7, null, null), channels.map { it.number })
        assertEquals(listOf(3, 2, 0), channels.map { it.archiveDays })
        assertEquals(listOf("http://s/7", "http://s/0", "http://s/1"), channels.map { it.key }) // M3U key = stream URL
    }

    /** SRC-010: the entry's `type` (or `tvg-type`) comes with it; no attribute = null. */
    @Test
    fun entryType() {
        val text = "#EXTM3U\n#EXTINF:-1 type=\"movie\",A\nhttp://s/a.mp4\n#EXTINF:-1 tvg-type=\"series\",B\nhttp://s/b\n#EXTINF:-1 type=\"\",C\nhttp://s/c\n"
        val types = ArrayList<Pair<String, String?>>()
        parseM3uEntries(java.io.StringReader(text)) { types += it.channel.name to it.type }
        assertEquals(listOf("A" to "movie", "B" to "series", "C" to null), types)
    }
}
