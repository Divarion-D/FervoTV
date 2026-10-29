package app.iptvplayer.core.source.xtream

import android.util.JsonReader
import app.iptvplayer.core.CoreException
import app.iptvplayer.core.ErrorCode
import app.iptvplayer.core.FakeServer
import app.iptvplayer.core.model.EpisodeEntry
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.StringReader

/** SRC-011 (films, series, episodes of Xtream) and SRC-012 (Xtream inside an M3U link); synthetic data only. */
@RunWith(RobolectricTestRunner::class)
class XtreamLibraryTest {
    private val cats = mapOf("1" to "Боевики", "2" to "Комедии")
    private val userKey = "http://h|u"

    private fun movies(json: String) = ArrayList<VodEntry>().also { out ->
        readVodStreams(JsonReader(StringReader(json)), cats, "http://h/movie/u/p", userKey) { out += it }
    }

    private fun series(json: String) = ArrayList<VodEntry>().also { out ->
        readSeries(JsonReader(StringReader(json)), cats, userKey) { out += it }
    }

    private fun episodes(json: String) = readSeriesInfo(JsonReader(StringReader(json)), "http://h/series/u/p", "http://h|u|series|9", "Шоу")

    @Test
    fun filmsWithContainerDateRatingAndMalformedFields() {
        val json = """[
            {"num":1,"name":"|EN| Heat (1995)","stream_type":"movie","stream_id":11,"stream_icon":"http://i/11.jpg",
             "rating":"7.8","added":"1700000000","category_id":"1","container_extension":"mkv","extra":{"a":[1]}},
            {"name":"Без всего","stream_id":"12","stream_icon":"","rating":"N/A","added":"","category_id":"9","container_extension":null},
            {"name":"Год числом","stream_id":13,"rating":0,"added":0,"category_id":2,"container_extension":"../x","year":2001},
            null, 5, {"name":"Нет id"}, {"stream_id":14}, {"name":{"x":1},"stream_id":[15]}
        ]"""
        assertEquals(
            listOf(
                VodEntry(VodKind.MOVIE, "http://h|u|movie|11", "|EN| Heat (1995)", "Боевики", "http://h/movie/u/p/11.mkv",
                    poster = "http://i/11.jpg", rating = 7.8, added = 1_700_000_000_000),
                VodEntry(VodKind.MOVIE, "http://h|u|movie|12", "Без всего", NO_GROUP, "http://h/movie/u/p/12.mp4"),
                VodEntry(VodKind.MOVIE, "http://h|u|movie|13", "Год числом", "Комедии", "http://h/movie/u/p/13.mp4", year = 2001),
            ),
            movies(json),
        )
    }

    @Test
    fun seriesWithBackdropListAndDates() {
        val json = """[
            {"series_id":9,"name":"Шоу","cover":"http://i/9.jpg","plot":"Сюжет","genre":"Драма","releaseDate":"2019-05-01",
             "rating":"8","backdrop_path":["","http://b/9.jpg"],"last_modified":"1700000001","category_id":"2"},
            {"series_id":"10","name":"Без полей","backdrop_path":"http://b/10.jpg","release_date":"","rating":null}
        ]"""
        assertEquals(
            listOf(
                VodEntry(VodKind.SERIES, "http://h|u|series|9", "Шоу", "Комедии", poster = "http://i/9.jpg", backdrop = "http://b/9.jpg",
                    rating = 8.0, added = 1_700_000_001_000, plot = "Сюжет", genre = "Драма", year = 2019),
                VodEntry(VodKind.SERIES, "http://h|u|series|10", "Без полей", NO_GROUP, backdrop = "http://b/10.jpg"),
            ),
            series(json),
        )
    }

    @Test
    fun episodesBySeasonKeyedBySeasonAndNumber() {
        val json = """{"seasons":[{"season_number":1}],"info":{"name":"Шоу"},"episodes":{
            "1":[{"id":"101","episode_num":1,"title":"Шоу - S01E01 - Пилот","container_extension":"mkv","season":1,
                  "info":{"movie_image":"http://i/e1.jpg","plot":"Начало","duration_secs":2700,"releasedate":"2019-05-01"}},
                 {"id":"102","episode_num":"2","title":"Шоу - S01E02","info":[]}],
            "0":[{"id":"100","title":"Special","info":{"duration":"00:45:30"}}],
            "2":[{"title":"нет id"}, null]
        }}"""
        assertEquals(
            listOf(
                EpisodeEntry("http://h|u|series|9|1|1", "http://h|u|series|9", 1, 1, "Шоу - S01E01 - Пилот", "Пилот",
                    "http://h/series/u/p/101.mkv", "http://i/e1.jpg", "Начало", 2_700_000, "2019-05-01"),
                EpisodeEntry("http://h|u|series|9|1|2", "http://h|u|series|9", 1, 2, "Шоу - S01E02", null, "http://h/series/u/p/102.mp4"),
                EpisodeEntry("http://h|u|series|9|0|1", "http://h|u|series|9", 0, 1, "Special", "Special", "http://h/series/u/p/100.mp4",
                    duration = 2_730_000),
            ),
            episodes(json),
        )
    }

    @Test
    fun episodesAsListOfListsAndEmptyAnswers() {
        val json = """{"episodes":[[{"id":1,"episode_num":1,"season":2,"title":"A"}],[{"id":2,"season":3,"title":"B"}]]}"""
        assertEquals(listOf(2 to 1, 3 to 1), episodes(json).map { it.season to it.episode })
        assertEquals(emptyList<EpisodeEntry>(), episodes("""{"episodes":[]}"""))
        assertEquals(emptyList<EpisodeEntry>(), episodes("""{"info":{}}"""))
        assertEquals(ErrorCode.XTREAM_BAD_LIBRARY, assertThrows(CoreException::class.java) { episodes("[]") }.code)
        assertEquals(ErrorCode.XTREAM_BAD_LIBRARY, assertThrows(CoreException::class.java) { movies("""{"user_info":{}}""") }.code)
    }

    @Test
    fun xtreamInsideM3uLinks() {
        assertEquals(Source.Xtream("http://h.example:8080", "us er", "p&ss"),
            xtreamOfM3uUrl("http://h.example:8080/get.php?username=us%20er&password=p%26ss&type=m3u_plus&output=ts"))
        assertEquals(Source.Xtream("https://h.example/panel", "u", "p", hls = true),
            xtreamOfM3uUrl("https://h.example/panel/get.php?type=m3u_plus&username=u&password=p&output=m3u8"))
        for (other in listOf("http://h/list.m3u", "http://h/get.php?username=u", "http://h/get.php?password=p", "content://x/get.php?username=u&password=p", "not a url")) {
            assertNull(other, xtreamOfM3uUrl(other))
        }
    }

    /** SRC-012: the account counts only when the server says auth = 1; anything else (rejected, not Xtream, down) is "no". */
    @Test
    fun accountRecognition() {
        for ((answer, accepted) in listOf(
            (200 to """{"user_info":{"auth":1}}""") to true,
            (200 to """{"user_info":{"auth":"1"}}""") to true,
            (200 to """{"user_info":{"auth":0}}""") to false,
            (200 to "#EXTM3U") to false,
            (404 to "") to false,
        )) {
            FakeServer { answer }.use { s -> assertEquals(answer.toString(), accepted, xtreamAccepted(Source.Xtream(s.url, "u", "p"))) }
        }
        val down = FakeServer { 200 to "" }.also { it.close() }
        assertFalse(xtreamAccepted(Source.Xtream(down.url, "u", "p")))
    }

    @Test
    fun wholeLibraryInProviderOrder() {
        val server = FakeServer { path ->
            200 to when {
                "get_vod_categories" in path -> """[{"category_id":"1","category_name":"Боевики"}]"""
                "get_vod_streams" in path -> """[{"name":"A","stream_id":1,"category_id":"1"},{"name":"B","stream_id":2,"category_id":"7"}]"""
                "get_series_categories" in path -> "null" // some panels: no categories
                "get_series" in path -> """[{"name":"S","series_id":3}]"""
                else -> "?"
            }
        }
        server.use { srv ->
            val s = Source.Xtream(srv.url, "u", "p")
            val cats = ArrayList<Pair<VodKind, String>>()
            val items = ArrayList<VodEntry>()
            loadXtreamLibrary(s, { k, n -> cats += k to n }) { items += it }
            assertEquals(listOf(VodKind.MOVIE to "Боевики"), cats)
            assertEquals(listOf("A" to "Боевики", "B" to NO_GROUP, "S" to NO_GROUP), items.map { it.name to it.category })
            assertEquals("${srv.url}/movie/u/p/1.mp4", items[0].url)
            assertTrue(srv.requests.all { "username=u&password=p" in it })
        }
    }
}
