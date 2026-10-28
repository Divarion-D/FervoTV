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

// Robolectric: android.util.JsonReader is a framework class.
@RunWith(RobolectricTestRunner::class)
class XtreamTest {
    private val cats = mapOf("1" to "Новости", "2" to "")

    private fun read(json: String, ext: String = "ts") = ArrayList<Channel>().also { out ->
        readLiveStreams(JsonReader(StringReader(json)), cats, "http://h/live/u/p", ext) { out += it }
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
                Channel("Первый", "http://h/live/u/p/101.ts", "Новости", "http://i/1.png", "one.ru"),
                Channel("Без группы", "http://h/live/u/p/102.ts", NO_GROUP),
                Channel("Пустая категория", "http://h/live/u/p/103.ts", NO_GROUP),
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
        assertEquals(listOf(Channel("B", "http://h/live/u/p/8.ts", "Новости")), read(json))
    }

    @Test(expected = IOException::class)
    fun notAnArrayIsAnError() {
        read("""{"user_info":{"auth":0}}""")
    }
}
