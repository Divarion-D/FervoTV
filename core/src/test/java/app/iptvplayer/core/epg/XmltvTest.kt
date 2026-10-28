package app.iptvplayer.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

// Robolectric: the XmlPullParser implementation (android.util.Xml) is a framework class.
@RunWith(RobolectricTestRunner::class)
class XmltvTest {
    private val doc = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="one.ru"><display-name lang="ru">Первый канал</display-name><display-name>Perviy</display-name>
            <icon src="http://i/1.png"/><url>http://x</url></channel>
          <channel id="two"><display-name>Второй &amp; Ко</display-name></channel>
          <programme start="20261028180000 +0300" stop="20261028190000 +0300" channel="one.ru">
            <title lang="ru">Новости</title><title lang="en">News</title><desc>Итоги <b>дня</b>&nbsp;</desc><category>news</category>
          </programme>
          <programme start="bad" stop="20261028200000 +0300" channel="one.ru"><title>Битая</title></programme>
          <programme start="20261028190000 +0300" channel="one.ru"><title>Без конца</title></programme>
          <programme start="20261028200000 +0300" stop="20261028210000 +0300" channel="one.ru"><title>Кино</title></programme>
          <programme start="20261028150000" stop="20261028160000" channel="other"><title>Чужой</title></programme>
          <programme start="20261028150000 -0500" stop="20261028153000 -0500" channel="two"><title>Утро</title></programme>
        </tv>"""

    private fun parse(bytes: ByteArray, wants: (String) -> Boolean = { it != "other" }): Pair<List<XmltvChannel>, List<XmltvProgramme>> {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<XmltvProgramme>()
        parseXmltv(unpacked(bytes.inputStream()), wants, channels::add, programmes::add)
        return channels to programmes
    }

    @Test
    fun channelsAndProgrammes() {
        val (channels, programmes) = parse(doc.toByteArray())
        assertEquals(listOf("one.ru", "two"), channels.map { it.id })
        assertEquals(listOf("Первый канал", "Perviy"), channels[0].names)
        assertEquals("http://i/1.png", channels[0].icon)
        assertEquals("Второй & Ко", channels[1].names.single())

        // Broken start skipped alone; "other" not wanted; the one without stop ends where the next one starts.
        assertEquals(listOf("Новости", "Без конца", "Кино", "Утро"), programmes.map { it.title })
        val news = programmes[0]
        assertEquals(time("2026-10-28T15:00Z"), news.start) // +0300
        assertEquals(time("2026-10-28T16:00Z"), news.stop)
        assertEquals("Итоги дня", news.description) // nested tag dropped, undeclared entity tolerated
        assertEquals(time("2026-10-28T17:00Z"), programmes[1].stop)
        assertEquals(time("2026-10-28T20:00Z"), programmes[3].start) // -0500
    }

    @Test
    fun gzipIsRecognizedByContent() {
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(doc.toByteArray()) } }.toByteArray()
        assertEquals(parse(doc.toByteArray()).second.map { it.title }, parse(gz).second.map { it.title })
    }

    /** A file cut off (or garbage in the middle) still hands out what came before the damage. */
    @Test
    fun damagedDocumentKeepsWhatCameBefore() {
        val at = doc.indexOf("<programme start=\"20261028200000")
        for (damaged in listOf(doc.substring(0, at) + "<programme <<< garbage", doc.substring(0, at + 20))) {
            val programmes = ArrayList<XmltvProgramme>()
            val error = runCatching { parseXmltv(damaged.byteInputStream(), { true }, {}, programmes::add) }.exceptionOrNull()
            assertTrue(error == null || error is XmlPullParserException)
            assertEquals("Новости", programmes.first().title)
        }
    }

    @Test
    fun timeFormats() {
        assertEquals(time("2026-10-28T15:00Z"), parseXmltvTime("20261028180000 +0300"))
        assertEquals(time("2026-10-28T15:00Z"), parseXmltvTime("20261028180000 +03:00"))
        assertEquals(time("2026-10-28T23:30Z"), parseXmltvTime("20261028180000 -0530"))
        assertEquals(time("2026-10-28T18:00Z"), parseXmltvTime("20261028180000")) // no offset = UTC
        assertEquals(time("2026-10-28T18:00Z"), parseXmltvTime("20261028180000 +0000"))
        assertEquals(time("2026-10-28T18:05Z"), parseXmltvTime("202610281805")) // no seconds
        assertEquals(time("2026-10-28T00:00Z"), parseXmltvTime("20261028"))
        assertEquals(time("2024-02-29T12:00Z") + 7_000, parseXmltvTime("20240229120007"))
        assertEquals(time("1999-12-31T21:00Z"), parseXmltvTime("20000101000000 +0300"))
        assertNull(parseXmltvTime("2026-10-28"))
        assertNull(parseXmltvTime("20261328180000"))
        assertNull(parseXmltvTime("20261028180000 +3"))
    }

    private fun time(iso: String) = java.time.Instant.parse(iso.replace("Z", ":00Z")).toEpochMilli()
}
