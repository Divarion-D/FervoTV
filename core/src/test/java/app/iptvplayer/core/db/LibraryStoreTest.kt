package app.iptvplayer.core.db

import android.content.Context
import androidx.room.Room
import app.iptvplayer.core.FakeServer
import app.iptvplayer.core.Repository
import app.iptvplayer.core.SyntheticVodM3u
import app.iptvplayer.core.loadPage
import app.iptvplayer.core.model.ResumeMode
import app.iptvplayer.core.model.SeriesView
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodInfo
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.model.VodKind.MOVIE
import app.iptvplayer.core.model.VodKind.SERIES
import app.iptvplayer.core.model.VodSort
import app.iptvplayer.core.net.HttpException
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.m3u.parseM3uEntries
import app.iptvplayer.core.source.m3uSection
import app.iptvplayer.core.usedHeap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** The library (SRC-010/011/012/016, VOD-007…012, HOME-002/013): import through the repository from a local server. */
@RunWith(RobolectricTestRunner::class)
class LibraryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDb
    private lateinit var repo: Repository
    private val servers = ArrayList<FakeServer>()

    @Before
    fun open() {
        listOf("iptv", "settings", "series").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        db = Room.databaseBuilder(ctx, AppDb::class.java, File(tmp.root, "t.db").path).allowMainThreadQueries().build()
        repo = Repository(ctx, db)
    }

    @After
    fun close() {
        servers.forEach(FakeServer::close)
        db.close()
    }

    private fun serve(answer: (String) -> Pair<Int, String>) = FakeServer(answer).also { servers += it }

    private var playlist = """#EXTM3U
#EXTINF:-1 group-title="Новости",Первый
http://h/live/1.ts
#EXTINF:-1 type="movie" tvg-logo="http://p/heat.jpg" group-title="Кино",|EN| Heat (1995) 4K
http://h/films/heat.mp4
#EXTINF:-1 group-title="Кино",Дюна (2021)
http://h/films/dune.mkv
#EXTINF:-1 type="series" tvg-logo="http://p/show.jpg" group-title="Сериалы",Шоу S01E02 Второй
http://h/s/2.mp4
#EXTINF:-1 group-title="Сериалы",Шоу S01E01 Первый
http://h/s/1.mp4
#EXTINF:-1 group-title="Сериалы",шоу s00e01 Спецвыпуск
http://h/s/0.mp4
#EXTINF:-1 type="series" group-title="Сериалы",Другое шоу
http://h/s/x1.ts
#EXTINF:-1 type="series" group-title="Сериалы",Другое шоу
http://h/s/x2.ts
"""

    private fun m3u(): Source.M3u = Source.M3u(serve { 200 to playlist }.url + "/list.m3u")

    private fun all(s: Source, kind: VodKind, sort: VodSort = VodSort.PROVIDER, group: VodGroup = VodGroup.All, search: String = "") =
        runBlocking { repo.firstVod(VodFilter(s.cacheKey(), kind, group, search, sort), 1000) }

    private fun state() = runBlocking { withTimeout(10_000) { repo.libraryState.first { it is LibraryState.Done || it is LibraryState.Failed } } }

    @Test
    fun m3uEntriesGoToLiveFilmsAndSeries() {
        val s = m3u()
        assertEquals(1, runBlocking { repo.load(s) }) // only the live channel stays in «Эфир»
        assertEquals(LibraryState.Done(LibraryResult(movies = 2, series = 2, added = 4, updated = 0, removed = 0)), repo.libraryState.value)

        val films = all(s, MOVIE)
        assertEquals(listOf("|EN| Heat (1995) 4K", "Дюна (2021)"), films.map { it.name }) // posters keep the provider's caption
        val heat = films[0]
        assertEquals(Triple("Heat", 1995, listOf("EN", "4K")), Triple(heat.title, heat.year, heat.tags))
        assertEquals("http://h/films/heat.mp4" to "http://h/films/heat.mp4", heat.key to heat.url)
        assertEquals("Кино", heat.category)

        val series = all(s, SERIES)
        assertEquals(listOf("Шоу", "Другое шоу"), series.map { it.name })
        assertNull(series[0].url)
        val episodes = runBlocking { repo.episodes(s, series[0]) }
        // Episodes of «Шоу» and «шоу» are one series; specials (season 0) last.
        assertEquals(listOf(Triple(1, 1, "Первый"), Triple(1, 2, "Второй"), Triple(0, 1, "Спецвыпуск")), episodes.map { Triple(it.season, it.episode, it.title) })
        assertEquals("http://h/s/1.mp4", episodes[0].key)
        // Without a marker: the show's next episode.
        assertEquals(listOf(1, 2), runBlocking { repo.episodes(s, series[1]) }.map { it.episode })

        assertEquals(listOf("Кино"), runBlocking { repo.vodCategories(s, MOVIE).first() }.map { it.name })
        assertEquals(listOf(VodGroup.Favorites to 0, VodGroup.All to 2, VodGroup.Category("Кино") to 2),
            runBlocking { repo.vodGroups(s, MOVIE).first() }.map { it.group to it.count })
    }

    @Test
    fun filmsOnlyPlaylistIsNotAnError() {
        playlist = "#EXTM3U\n#EXTINF:-1 type=\"movie\",A\nhttp://h/a.mp4\n"
        val s = m3u()
        assertEquals(0, runBlocking { repo.load(s) })
        assertEquals(listOf("A"), all(s, MOVIE).map { it.name })
    }

    /** User data hangs on stable keys: a refresh that renames, drops and adds titles keeps it all. */
    @Test
    fun userDataSurvivesRefresh() {
        val s = m3u()
        runBlocking { repo.load(s) }
        val heat = all(s, MOVIE)[0]
        val show = all(s, SERIES)[0]
        val firstSeen = heat.added
        runBlocking {
            repo.setVodFavorite(heat.key, true)
            repo.renameVod(show.key, "Моё шоу")
            repo.savePosition(heat, 60_000, 6_000_000)
            repo.savePosition(show, repo.episodes(s, show)[1], 30_000, 2_400_000)
            val c = repo.createVodCollection(MOVIE, "Вечер")
            repo.addToVodCollection(c, heat.key)
            repo.renameVodCategory(s, MOVIE, "Кино", "Фильмы")
        }

        playlist = playlist.replace("|EN| Heat (1995) 4K", "|EN| Heat (1995) FHD")
            .replace("#EXTINF:-1 group-title=\"Кино\",Дюна (2021)\nhttp://h/films/dune.mkv\n", "")
            .replace("#EXTM3U\n", "#EXTM3U\n#EXTINF:-1 type=\"movie\" group-title=\"Кино\",Новый\nhttp://h/films/new.mp4\n")
        Thread.sleep(5)
        runBlocking { repo.load(s) }
        assertEquals(LibraryState.Done(LibraryResult(movies = 2, series = 2, added = 1, updated = 1, removed = 1)), repo.libraryState.value)

        val films = all(s, MOVIE)
        val again = films.single { it.key == heat.key }
        assertEquals(firstSeen, again.added) // "date added" = first seen, not this refresh
        assertTrue(films.single { it.name == "Новый" }.added > firstSeen)
        assertEquals(listOf(heat.key), all(s, MOVIE, group = VodGroup.Favorites).map { it.key })
        assertEquals(60_000L, again.progress?.position)
        val c = runBlocking { repo.vodCollections(MOVIE).first() }.single()
        assertEquals(listOf(heat.key), all(s, MOVIE, group = VodGroup.Collection(c.id)).map { it.key })
        assertEquals("Фильмы", runBlocking { repo.vodCategories(s, MOVIE).first() }.single().title)
        val series = all(s, SERIES)[0]
        assertEquals("Моё шоу", series.name)
        assertEquals(30_000L, series.progress?.position) // a series shows its last started episode
        assertEquals(30_000L, runBlocking { repo.episodes(s, series) }[1].progress?.position)
    }

    /** SRC-011 + SRC-016: the account's library imports in the background after the channels; episodes on opening, cached. */
    @Test
    fun xtreamLibraryInBackgroundAndEpisodesOnDemand() {
        var infoStatus = 200
        val srv = serve { path ->
            when {
                "get_series_info" in path -> infoStatus to """{"episodes":{"1":[{"id":"5","episode_num":1,"title":"Шоу - S01E01 - Пилот","container_extension":"mkv"}]}}"""
                "get_live_categories" in path -> 200 to """[{"category_id":"1","category_name":"Новости"}]"""
                "get_live_streams" in path -> 200 to """[{"name":"Первый","stream_id":1,"category_id":"1"}]"""
                "get_vod_categories" in path -> 200 to """[{"category_id":"2","category_name":"Кино"}]"""
                "get_vod_streams" in path -> 200 to """[{"name":"Фильм (2001)","stream_id":7,"category_id":"2","container_extension":"mkv"}]"""
                "get_series_categories" in path -> 200 to "[]"
                "get_series" in path -> 200 to """[{"name":"Шоу","series_id":9}]"""
                else -> 200 to """{"user_info":{"auth":1}}"""
            }
        }
        val s = Source.Xtream(srv.url, "u", "p")
        assertEquals(1, runBlocking { repo.load(s) })
        assertEquals(LibraryResult(1, 1, 2, 0, 0), (state() as LibraryState.Done).result)
        val film = all(s, MOVIE).single()
        assertEquals("${srv.url}|u|movie|7" to "${srv.url}/movie/u/p/7.mkv", film.key to film.url)

        val show = all(s, SERIES).single()
        fun infoRequests() = srv.requests.count { "get_series_info" in it }
        val episodes = runBlocking { repo.episodes(s, show) }
        assertEquals(listOf("${srv.url}|u|series|9|1|1" to "Пилот"), episodes.map { it.key to it.title })
        runBlocking { repo.episodes(s, show) }
        assertEquals(1, infoRequests()) // cached

        // A refresh keeps the loaded episodes with their series; a failed reload keeps them too.
        val before = runBlocking { repo.librarySavedAt(s) }
        Thread.sleep(5)
        runBlocking { repo.load(s) }
        state()
        assertTrue(runBlocking { repo.librarySavedAt(s) }!! > before!!)
        infoStatus = 503
        assertEquals(episodes, runBlocking { repo.episodes(s, all(s, SERIES).single(), refresh = true) })
        assertEquals(episodes[0], runBlocking { repo.episode(s, episodes[0].key) })

        // Never loaded and the server fails: the code reaches the screen (SRC-011).
        db.openHelper.writableDatabase.execSQL("DELETE FROM episode")
        db.openHelper.writableDatabase.execSQL("UPDATE vod SET episodesAt = NULL")
        val e = assertThrows(HttpException::class.java) { runBlocking { repo.episodes(s, show) } }
        assertEquals(503, e.code)
    }

    /** VOD-001 / UI-007: a section shows only when the source has films or series in it; hidden ones still count. */
    @Test
    fun sectionsFollowWhatTheSourceHas() {
        fun kinds(s: Source) = runBlocking { repo.libraryKinds(s).first() }
        playlist = "#EXTM3U\n#EXTINF:-1,Первый\nhttp://h/live/1.ts\n"
        val live = m3u()
        runBlocking { repo.load(live) }
        assertEquals(emptySet<VodKind>(), kinds(live)) // the owner's case: live only, no «Фильмы» / «Сериалы»
        playlist = "#EXTM3U\n#EXTINF:-1 type=\"movie\",A\nhttp://h/a.mp4\n"
        val films = m3u()
        runBlocking { repo.load(films) }
        assertEquals(setOf(MOVIE), kinds(films))
        runBlocking { repo.setVodHidden(listOf("http://h/a.mp4"), true) }
        assertEquals(setOf(MOVIE), kinds(films))
        playlist += "#EXTINF:-1 type=\"series\",Шоу S01E01\nhttp://h/s1.mp4\n"
        val s = m3u() // another server: another source
        runBlocking { repo.load(s) }
        assertEquals(setOf(MOVIE, SERIES), kinds(s))
        assertEquals(emptySet<VodKind>(), kinds(Source.M3u("http://other/none.m3u"))) // nothing imported
    }

    /** VOD-004: the film's duration and the rest from get_vod_info, once per run; M3U has no such call. */
    @Test
    fun filmDetailsFromXtream() {
        val srv = serve { path ->
            when {
                "get_vod_info" in path -> 200 to """{"info":{"duration_secs":"6720","plot":"Сюжет","genre":"Драма","backdrop_path":["http://p/b.jpg"],"cast":"А, Б","director":"В","releasedate":"2001-05-01"},"movie_data":{"stream_id":7}}"""
                else -> 200 to "[]"
            }
        }
        val s = Source.Xtream(srv.url, "u", "p")
        val film = VodItem("${srv.url}|u|movie|7", MOVIE, "Ф", "Ф", null, emptyList(), "К", null, null, null, 0, null, null, "http://x/7.mp4", false, false, null)
        val info = runBlocking { repo.vodInfo(s, film) }!!
        assertEquals(VodInfo(6_720_000, "Сюжет", "Драма", "http://p/b.jpg", "В", "А, Б", "2001-05-01"), info)
        runBlocking { repo.vodInfo(s, film) }
        assertEquals(1, srv.requests.count { "get_vod_info" in it && "vod_id=7" in it })
        assertNull(runBlocking { repo.vodInfo(Source.M3u("http://h/list.m3u"), film) })
        // `info: []` (nothing known) is no error.
        val empty = serve { 200 to """{"info":[],"movie_data":{}}""" }
        assertEquals(VodInfo(), runBlocking { repo.vodInfo(Source.Xtream(empty.url, "u", "p"), film.copy(key = "x|u|movie|8")) })
    }

    /** VOD-006 per series; VOD-002/003/009/010 settings are remembered (and the sort / view per section). */
    @Test
    fun seriesViewAndLibrarySettingsAreKept() {
        assertEquals(SeriesView(), repo.seriesView("a"))
        repo.setSeriesView("a", SeriesView(newestFirst = true, hideWatched = true))
        repo.setSeriesView("b", SeriesView(hideWatched = true))
        val again = Repository(ctx, db)
        assertEquals(SeriesView(true, true), again.seriesView("a"))
        assertEquals(SeriesView(false, true), again.seriesView("b"))
        again.setSeriesView("a", SeriesView())
        assertEquals(SeriesView(), repo.seriesView("a"))

        assertEquals(Settings(), repo.settings)
        val changed = Settings(resume = ResumeMode.START, autoNext = false, movieSort = VodSort.ADDED, seriesSort = VodSort.NAME, movieList = true)
        repo.settings = changed
        assertEquals(changed, Repository(ctx, db).settings)
    }

    /** SRC-012 at the repository: a confirmed account is returned as Xtream, anything else stays M3U. */
    @Test
    fun recognizeXtreamInM3uLink() {
        var auth = 1
        val srv = serve { 200 to """{"user_info":{"auth":$auth}}""" }
        val link = Source.M3u("${srv.url}/get.php?username=u&password=p&type=m3u_plus&output=m3u8")
        assertEquals(Source.Xtream(srv.url, "u", "p", hls = true), runBlocking { repo.recognize(link) })
        auth = 0
        assertEquals(link, runBlocking { repo.recognize(link) })
        val plain = Source.M3u("${srv.url}/list.m3u")
        assertEquals(plain, runBlocking { repo.recognize(plain) })
    }

    private fun importDirect(key: String = "k", items: List<VodEntry>) =
        LibraryStore(db).import(key, "u") { lib -> items.forEach(lib::item) }

    @Test
    fun sortsSearchAndHiding() {
        val k = Source.M3u("k")
        importDirect(k.cacheKey(), listOf(
            VodEntry(MOVIE, "a", "Человек-паук (2002)", "Марвел", rating = 7.3, added = 3_000),
            VodEntry(MOVIE, "b", "Ёлки (2010)", "Комедии", rating = 6.0, added = 1_000),
            VodEntry(MOVIE, "c", "Аватар", "Фантастика", added = 2_000),
            VodEntry(MOVIE, "d", "Бэтмен (1989)", "Марвел", rating = 8.1, added = 4_000),
        ))
        fun names(sort: VodSort = VodSort.PROVIDER, search: String = "", group: VodGroup = VodGroup.All) = all(k, MOVIE, sort, group, search).map { it.title }
        assertEquals(listOf("Человек-паук", "Ёлки", "Аватар", "Бэтмен"), names())
        assertEquals(listOf("Аватар", "Бэтмен", "Ёлки", "Человек-паук"), names(VodSort.NAME))
        assertEquals(listOf("Бэтмен", "Человек-паук", "Аватар", "Ёлки"), names(VodSort.ADDED))
        assertEquals(listOf("Ёлки", "Человек-паук", "Бэтмен", "Аватар"), names(VodSort.YEAR))
        assertEquals(listOf("Бэтмен", "Человек-паук", "Ёлки", "Аватар"), names(VodSort.RATING))
        // HOME-010: word starts, hyphen splits, ё = е, case.
        assertEquals(listOf("Человек-паук"), names(search = "челов-пау"))
        assertEquals(listOf("Ёлки"), names(search = "ЕЛК"))
        assertEquals(listOf("Человек-паук", "Бэтмен"), names(group = VodGroup.Category("Марвел")))

        runBlocking {
            repo.setVodFavorite("a", true)
            repo.setVodCategoriesHidden(k, MOVIE, listOf("Марвел"), true)
            repo.setVodHidden(listOf("b"), true)
        }
        // A hidden category hides from «Все», not from favorites; a hidden item from everything.
        assertEquals(listOf("Аватар"), names())
        assertEquals(listOf("Человек-паук"), names(group = VodGroup.Favorites))
        assertEquals(listOf("Фантастика"), runBlocking { repo.vodCategories(k, MOVIE).first() }.map { it.name })
        val edits = runBlocking { repo.vodEdits(k, MOVIE, null) }
        assertEquals(listOf("Марвел", "Ёлки (2010)"), edits.hidden)
        runBlocking { repo.resetVodEdits(k, MOVIE, null) }
        assertEquals(4, names().size)
        assertTrue(runBlocking { repo.vodEdits(k, MOVIE, null) }.isEmpty)

        runBlocking { repo.reorderVod(k, MOVIE, VodGroup.Category("Марвел"), listOf("d", "a")) }
        assertEquals(listOf("Бэтмен", "Человек-паук"), names(group = VodGroup.Category("Марвел")))
        assertEquals(1, runBlocking { repo.vodIndexOf(VodFilter(k.cacheKey(), MOVIE), "b") })
    }

    /** VOD-008/010, HOME-002/013. */
    @Test
    fun watching() {
        val k = Source.M3u("k")
        importDirect(k.cacheKey(), listOf(
            VodEntry(MOVIE, "m1", "Один", "К", url = "http://m1"), VodEntry(MOVIE, "m2", "Два", "К", url = "http://m2"),
            VodEntry(SERIES, "s", "Шоу", "С"),
        ))
        LibraryStore(db).storeEpisodes(k.cacheKey(), "s", (1..3).map {
            app.iptvplayer.core.model.EpisodeEntry("s|1|$it", "s", 1, it, "E$it", null, "http://e$it")
        })
        val (m1, m2) = all(k, MOVIE)
        val show = all(k, SERIES).single()
        val eps = runBlocking { repo.episodes(k, show) }
        fun later(save: suspend () -> Unit) = runBlocking { Thread.sleep(3); save() }
        later { repo.savePosition(m1, 600_000, 6_000_000) }
        later { repo.savePosition(m2, 5_700_000, 6_000_000) } // 95%: watched
        later { repo.savePosition(show, eps[0], 100_000, 2_000_000) }
        later { repo.savePosition(show, eps[1], 1_995_000, 2_000_000) } // the last 10 s: watched
        later { repo.savePosition(show, eps[2], 50_000, 0) } // unknown duration: just the position
        val w2 = runBlocking { repo.watch("m2") }!!
        assertEquals(Triple(0L, true, 6_000_000L), Triple(w2.position, w2.watched, w2.duration))
        assertEquals(true, runBlocking { repo.watch("s|1|2") }?.watched)
        // Unfinished: started films and the last episode of each series, the latest first.
        assertEquals(listOf("s|1|3", "m1"), runBlocking { repo.unfinished(10).first() }.map { it.vkey })
        val ep = runBlocking { repo.watch("s|1|3") }!!
        assertEquals(listOf("s", "Шоу", null), listOf(ep.seriesKey, ep.title, ep.episodeTitle))
        runBlocking { repo.setVodHidden(listOf("s"), true) }
        assertEquals(listOf("m1"), runBlocking { repo.unfinished(10).first() }.map { it.vkey })

        val history = runBlocking { loadPage { historySource(null) }.data }.map { it.vkey }
        assertEquals(listOf("s|1|3", "s|1|2", "s|1|1", "m2", "m1"), history)
        assertEquals(listOf("m2", "m1"), runBlocking { loadPage { historySource(MOVIE) }.data }.map { it.vkey })

        runBlocking {
            repo.setWatched(show, eps, true) // the whole season
            repo.setWatched(m1, false)
        }
        assertEquals(listOf(true, true, true), eps.map { runBlocking { repo.watch(it.key) }?.watched })
        assertNull(runBlocking { repo.watch("m1") })
        runBlocking { repo.forget(listOf("m2")) }
        assertNull(runBlocking { repo.watch("m2") })
        runBlocking { repo.clearHistory() }
        assertNull(runBlocking { repo.watch("s|1|1") })
    }

    @Test
    fun continueUsesOnlyTheCurrentSourceWithoutErasingHistory() {
        val first = Source.M3u("first")
        val liveOnly = Source.M3u("live-only")
        importDirect(first.cacheKey(), listOf(VodEntry(MOVIE, "m", "Film", "Films", url = "http://m")))
        val movie = all(first, MOVIE).single()
        runBlocking { repo.savePosition(movie, 60_000, 600_000) }
        assertEquals(listOf("m"), runBlocking { repo.unfinished(20, first).first() }.map { it.vkey })
        runBlocking { repo.setVodCategoriesHidden(first, MOVIE, listOf("Films"), true) }
        assertTrue(runBlocking { repo.unfinished(20, first).first() }.isEmpty())
        assertNull(runBlocking { repo.quickContinue(first).first() })
        runBlocking { repo.setVodCategoriesHidden(first, MOVIE, listOf("Films"), false) }
        importDirect(liveOnly.cacheKey(), emptyList())
        assertTrue(runBlocking { repo.unfinished(20, liveOnly).first() }.isEmpty())
        assertNull(runBlocking { repo.quickContinue(liveOnly).first() })
        assertEquals(listOf("m"), runBlocking { repo.historyList().first() }.map { it.vkey })
        importDirect(first.cacheKey(), listOf(VodEntry(MOVIE, "m", "Film", "Films", url = "http://m")))
        assertEquals(listOf("m"), runBlocking { repo.unfinished(20, first).first() }.map { it.vkey })
    }

    /**
     * P2 acceptance: 150 000 films (generated while being read, through the M3U parser and the section rules) go into
     * the library with the 256 MB test heap without heap growth; the catalogue pages and searches in < 200 ms.
     */
    @Test
    fun hundredFiftyThousandFilms() {
        val store = LibraryStore(db)
        val samples = ArrayList<Long>()
        val start = System.nanoTime()
        val result = store.import("big", "u", onProgress = { if (it % 15_000 == 0) samples += usedHeap() }) { lib ->
            parseM3uEntries(SyntheticVodM3u(150_000), "http://h/list.m3u") { e -> lib.m3u(e.channel, m3uSection(e.type, e.channel.url, e.channel.name)!!) }
        }
        val importMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(150_000, result.movies)
        val growth = samples.max() - samples.first()
        println("150k films import: $importMs ms (incl. ${samples.size} forced GCs), heap growth ${growth / 1024} KB")
        assertTrue("heap grew by ${growth / 1024} KB", growth < 8L * 1024 * 1024)

        fun timed(label: String, block: () -> Any): Long {
            block() // warm the page cache like a second screen open
            val t = System.nanoTime()
            block()
            val ms = (System.nanoTime() - t) / 1_000_000
            println("$label: $ms ms")
            return ms
        }
        val all = VodFilter("big", MOVIE)
        val times = listOf(
            timed("first page") { runBlocking { loadPage(60) { store.source(all) } } },
            timed("page at 140 000") { runBlocking { loadPage(60, 140_000) { store.source(all) } } },
            timed("first page by name") { runBlocking { loadPage(60) { store.source(all.copy(sort = VodSort.NAME)) } } },
            timed("first page by date added") { runBlocking { loadPage(60) { store.source(all.copy(sort = VodSort.ADDED)) } } },
            timed("category page") { runBlocking { loadPage(60) { store.source(all.copy(group = VodGroup.Category("Жанр 7"))) } } },
            timed("search, 11 hits") { runBlocking { loadPage(60) { store.source(all.copy(search = "фильм 4242")) } } },
            timed("search, 7 500 hits") { runBlocking { loadPage(60) { store.source(all.copy(search = "приключ")) } } },
            timed("categories with counts") { runBlocking { store.categories("big", MOVIE).first() } },
        )
        val found = runBlocking { loadPage(60) { store.source(all.copy(search = "фильм 4242")) } }
        assertEquals(listOf("Фильм 4242 драма") + (0..9).map { "Фильм 4242$it ${SyntheticVodM3u.WORDS[it]}" }, found.data.map { it.title })
        assertEquals(7_500, found.let { runBlocking { loadPage(60) { store.source(all.copy(search = "приключ")) } } }.let { it.data.size + it.itemsAfter })
        assertEquals(40, runBlocking { store.categories("big", MOVIE).first() }.size)
        assertTrue("slowest ${times.max()} ms", times.all { it < 200 })
    }

    private fun historySource(kind: VodKind?) = LibraryUser(db).history(kind)
}
