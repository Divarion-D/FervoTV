package app.iptvplayer.core.source.m3u

import app.iptvplayer.core.SyntheticM3u
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.usedHeap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3uStreamTest {
    @Test
    fun readerGivesSameChannelsAsText() {
        val text = "﻿#EXTM3U\r\n#EXTINF:-1 tvg-logo=\"l/1.png\" group-title=\"A, B\",Один, HD\r\nhttp://a\r\n" +
            "#EXTINF:-1,Два\n#EXTGRP:Спорт\nhttp://b\nhttp://h/c.m3u8\n"
        val streamed = ArrayList<Channel>()
        parseM3u(StringReader(text), "http://h/x.m3u") { streamed += it }
        assertEquals(parseM3u(text, "http://h/x.m3u"), streamed)
        assertEquals(
            listOf(
                Channel("Один, HD", "http://a", "A, B", "http://h/l/1.png"),
                Channel("Два", "http://b", "Спорт"),
                Channel("c.m3u8", "http://h/c.m3u8", NO_GROUP),
            ),
            streamed,
        )
    }

    /** SRC-006: memory doesn't grow with the playlist (the whole list would take ~40 MB). */
    @Test
    fun hundredThousandEntriesInConstantMemory() {
        var n = 0
        val samples = ArrayList<Long>()
        parseM3u(SyntheticM3u(100_000), "http://h/list.m3u") {
            if (++n % 10_000 == 0) samples += usedHeap()
        }
        assertEquals(100_000, n)
        val growth = samples.max() - samples.first()
        assertTrue("heap grew by ${growth / 1024} KB", growth < 4L * 1024 * 1024)
    }
}
