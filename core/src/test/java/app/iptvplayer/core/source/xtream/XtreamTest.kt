package app.iptvplayer.core.source.xtream

import android.util.JsonReader
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.StringReader
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.source.userKey
import org.junit.Assert.assertThrows
import java.net.ServerSocket
import kotlin.concurrent.thread

// Robolectric: android.util.JsonReader is a framework class.
@RunWith(RobolectricTestRunner::class)
class XtreamTest {
    private val cats = mapOf("1" to "Новости", "2" to "")

    private fun read(json: String, ext: String = "ts") = ArrayList<Channel>().also { out ->
        readLiveStreams(JsonReader(StringReader(json)), cats, "http://h/live/u/p", ext, "http://h|u") { out += it }
    }

    @Test
    fun streamsInOrderWithCategoryLogoAndEpgId() {
        val json = """[
            {"num":1,"name":"Первый","stream_id":101,"stream_icon":"http://i/1.png","epg_channel_id":"one.ru","category_id":"1","extra":{"a":[1,2]}},
            {"name":"Без группы","stream_id":"102","stream_icon":null,"epg_channel_id":"","category_id":"9"},
            {"name":"Пустая категория","stream_id":103,"category_id":"2"}
        ]"""
        assertEquals(
            listOf(
                Channel("Первый", "http://h/live/u/p/101.ts", "Новости", "http://i/1.png", "one.ru", key = "http://h|u|101", number = 1),
                Channel("Без группы", "http://h/live/u/p/102.ts", NO_GROUP, key = "http://h|u|102"),
                Channel("Пустая категория", "http://h/live/u/p/103.ts", NO_GROUP, key = "http://h|u|103"),
            ),
            read(json),
        )
    }

    @Test
    fun hlsFlagChangesExtension() {
        assertEquals("http://h/live/u/p/7.m3u8", read("""[{"name":"A","stream_id":7}]""", ext = "m3u8").single().url)
    }

    @Test
    fun brokenEntriesSkipOnlyThemselves() {
        val json = """[null, 5, {"name":"Нет id"}, {"name":{"x":1},"stream_id":[1]}, {"name":"B","stream_id":8,"category_id":1}]"""
        assertEquals(listOf(Channel("B", "http://h/live/u/p/8.ts", "Новости", key = "http://h|u|8")), read(json))
    }

    @Test(expected = IOException::class)
    fun notAnArrayIsAnError() {
        read("""{"user_info":{"auth":0}}""")
    }

    /** A wrong login reads as "account rejected" however the panel says it: auth = 0, 401, 403. */
    @Test
    fun rejectedAccount() {
        for ((status, body) in listOf(200 to """{"user_info":{"auth":0}}""", 401 to "", 403 to "denied")) {
            // One-request HTTP server (the account check fails, so nothing else is asked).
            val srv = ServerSocket(0)
            thread {
                srv.accept().use { c ->
                    val r = c.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) {}
                    val b = body.toByteArray()
                    c.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray() + b)
                }
            }
            try {
                val e = assertThrows(IOException::class.java) {
                    loadXtream(Source.Xtream("http://127.0.0.1:${srv.localPort}", "u", "p"), {}, {})
                }
                assertEquals(ACCOUNT_REJECTED, e.message)
            } finally {
                srv.close()
            }
        }
    }

    @Test
    fun numberAndArchive() {
        val json = """[{"num":"12","name":"A","stream_id":7,"tv_archive":1,"tv_archive_duration":"5"},
            {"num":0,"name":"B","stream_id":8,"tv_archive":"0","tv_archive_duration":3}]"""
        val (a, b) = read(json)
        assertEquals(12 to 5, a.number to a.archiveDays)
        assertEquals(null to 0, b.number to b.archiveDays)
    }

    /** EPG-002: short EPG with base64 texts and unix times; entries without times skipped. */
    @Test
    fun shortEpg() {
        fun b64(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
        val json = """{"epg_listings":[
            {"id":"1","title":"${b64("Новости")}","description":"${b64("Главное за день")}","start":"2026-10-28 18:00:00",
             "start_timestamp":"1793199600","stop_timestamp":"1793203200"},
            {"title":"${b64("Без времени")}"},
            {"title":"${b64("Кино")}","description":"","start_timestamp":1793203200,"stop_timestamp":1793210400}
        ]}"""
        assertEquals(
            listOf(
                app.iptvplayer.core.model.Programme(1_793_199_600_000, 1_793_203_200_000, "Новости", "Главное за день"),
                app.iptvplayer.core.model.Programme(1_793_203_200_000, 1_793_210_400_000, "Кино", null),
            ),
            readShortEpg(JsonReader(StringReader(json))),
        )
        assertEquals(emptyList<Any>(), readShortEpg(JsonReader(StringReader("""{"epg_listings":[]}"""))))
    }

    @Test
    fun keysSurvivePasswordAndHlsChanges() {
        val a = Source.Xtream("http://h/", "u", "p1")
        val b = Source.Xtream("http://h", "u", "p2", hls = true)
        assertEquals(a.userKey(), b.userKey())
        assertEquals("http://h/xmltv.php?username=u&password=p1", xtreamXmltvUrl(a))
    }
}
