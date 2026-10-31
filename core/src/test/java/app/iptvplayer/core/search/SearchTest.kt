package app.iptvplayer.core.search

import android.content.Context
import androidx.room.Room
import app.iptvplayer.core.Repository
import app.iptvplayer.core.SyntheticM3u
import app.iptvplayer.core.SyntheticVodM3u
import app.iptvplayer.core.SyntheticXmltv
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.EpgStore
import app.iptvplayer.core.db.LibraryStore
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.db.VodFilter
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.m3u.parseM3u
import app.iptvplayer.core.source.m3u.parseM3uEntries
import app.iptvplayer.core.source.m3uSection
import app.iptvplayer.core.source.userKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Instant

/** HOME-007…011: the global search — fan-out, ranking, the word rules, the 12-hour guide window and its speed. */
@RunWith(RobolectricTestRunner::class)
class SearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDb
    private lateinit var repo: Repository
    private val source = Source.M3u("http://h/list.m3u")
    private val key = source.cacheKey()
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()

    @Before
    fun open() {
        listOf("iptv", "settings").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        db = Room.databaseBuilder(ctx, AppDb::class.java, File(tmp.root, "t.db").path).allowMainThreadQueries().build()
        repo = Repository(ctx, db)
    }

    @After
    fun close() = db.close()

    @Test
    fun rankingPutsTheWholeNameFirstThenItsBeginning() {
        assertEquals(0, searchRank("Человек-паук", "человек паук"))
        assertEquals(1, searchRank("Человек-паук: Возвращение домой", "челов-пау"))
        assertEquals(2, searchRank("Новый Человек-паук", "челов-пау"))
        assertEquals(1, searchRank("Ёлки 2", "елки"))
        val names = listOf("Новый Человек-паук", "Человек-паук 2", "Человек-паук", "Паук")
        assertEquals(listOf("Человек-паук", "Человек-паук 2", "Новый Человек-паук", "Паук"), names.ranked("человек-паук") { it })
        // Equal ranks keep the section's order.
        assertEquals(listOf("Б фильм", "А фильм"), listOf("Б фильм", "А фильм").ranked("фильм") { it })
    }

    @Test
    fun oneQueryFindsEveryKindByTheSameRules() {
        PlaylistStore(db).import(key, userKey = source.userKey(), epgIndex = EpgStore(db).index()) { i ->
            i.channel(Channel("Новый Человек-паук ТВ", "http://s/1", "Кино", tvgId = "spider"))
            i.channel(Channel("Ёлки ТВ", "http://s/2", "Кино"))
            i.channel(Channel("Новости", "http://s/3", "Новости", tvgId = "news"))
        }
        LibraryStore(db).import(key, source.userKey()) { lib ->
            lib.item(VodEntry(VodKind.MOVIE, "m1", "|EN| Новый Человек-паук (2012)", "Фантастика", "http://s/m1.mkv"))
            lib.item(VodEntry(VodKind.MOVIE, "m2", "Человек-паук (2002)", "Фантастика", "http://s/m2.mkv"))
            lib.item(VodEntry(VodKind.MOVIE, "m3", "Паук", "Ужасы", "http://s/m3.mkv"))
            lib.item(VodEntry(VodKind.SERIES, "s1", "Ёлки-палки", "Комедии"))
        }
        val epg = EpgStore(db)
        val id = epg.addSource("http://e/guide.xml").id
        val guide = """<tv><channel id="spider"><display-name>Паук</display-name></channel><channel id="news"><display-name>Новости</display-name></channel>
            <programme start="20261028113000 +0000" stop="20261028123000 +0000" channel="spider"><title>Человек-паук: мультсериал</title></programme>
            <programme start="20261028230000 +0000" stop="20261028233000 +0000" channel="spider"><title>Человек-паук ночью</title></programme>
            <programme start="20261029010000 +0000" stop="20261029020000 +0000" channel="spider"><title>Человек-паук завтра</title></programme>
            <programme start="20261028150000 +0000" stop="20261028160000 +0000" channel="spider"><title>Ёлки-палки</title></programme>
            <programme start="20261028120000 +0000" stop="20261028130000 +0000" channel="news"><title>Новости</title></programme></tv>"""
        epg.import(id, guide.byteInputStream(), days = 2, now = now)

        val r = runBlocking { repo.search(source, "челов-пау", now) }
        assertEquals(listOf("Новый Человек-паук ТВ"), r.channels.map { it.channel.name })
        // HOME-009: on now and within 12 hours, soonest first; tomorrow's 01:00 is 13 h away.
        assertEquals(listOf("Человек-паук: мультсериал", "Человек-паук ночью"), r.programmes.map { it.programme.title })
        assertEquals("Новый Человек-паук ТВ", r.programmes.first().channel)
        // Best match first: the title that begins with the query before the one that has it further in.
        assertEquals(listOf("m2", "m1"), r.movies.map { it.key })
        assertEquals(emptyList<String>(), r.series.map { it.key })
        assertEquals(mapOf(SearchKind.CHANNELS to 1, SearchKind.PROGRAMMES to 2, SearchKind.MOVIES to 2, SearchKind.SERIES to 0), r.counts)

        // ё = е, case and punctuation don't matter.
        val e = runBlocking { repo.search(source, "ЕЛКИ", now) }
        assertEquals(listOf("Ёлки ТВ"), e.channels.map { it.channel.name })
        assertEquals(listOf("s1"), e.series.map { it.key })
        assertEquals(listOf("Ёлки-палки"), e.programmes.map { it.programme.title })
        assertTrue(runBlocking { repo.search(source, " - ", now) }.isEmpty)
        // The limit of each kind; the count is of everything found.
        val l = runBlocking { repo.search(source, "н", now, limit = 1) }
        assertEquals(1, l.channels.size)
        assertEquals(2, l.count(SearchKind.CHANNELS))
    }

    @Test
    fun recentQueriesAreKeptLatestFirst() {
        repo.rememberSearch("Матч")
        repo.rememberSearch(" новости ")
        repo.rememberSearch("матч")
        assertEquals(listOf("матч", "новости"), repo.searches)
        repo.forgetSearch("новости")
        assertEquals(listOf("матч"), repo.searches)
        repeat(15) { repo.rememberSearch("q$it") }
        assertEquals(10, repo.searches.size)
        repo.clearSearches()
        assertEquals(emptyList<String>(), repo.searches)
    }

    /**
     * P2 acceptance: 150 000 films, 20 000 series, 50 000 channels and a guide of 2 000 channels (≈ 50 000 programmes in
     * the 12-hour window): each kind of the global search answers in < 200 ms, the whole fan-out too.
     */
    @Test
    fun searchOnABigCatalogue() {
        val epg = EpgStore(db)
        PlaylistStore(db).import(key, userKey = source.userKey(), epgIndex = epg.index()) { i -> parseM3u(SyntheticM3u(50_000), "http://h/list.m3u") { ch -> i.channel(ch) } }
        val lib = LibraryStore(db)
        lib.import(key, source.userKey()) { l ->
            parseM3uEntries(SyntheticVodM3u(150_000), "http://h/list.m3u") { e -> l.m3u(e.channel, m3uSection(e.type, e.channel.url, e.channel.name)!!) }
            for (i in 0 until 20_000) l.item(VodEntry(VodKind.SERIES, "s$i", "Сериал $i ${SyntheticVodM3u.WORDS[i % 20]}", "Сериалы ${i % 20}"))
        }
        val id = epg.addSource("http://e/big.xml").id
        epg.import(id, SyntheticXmltv(channels = 2_000, perChannel = 32, base = now - 2 * 3_600_000L), days = 2, now = now)
        val store = PlaylistStore(db)

        fun timed(label: String, block: () -> Any): Long {
            block() // warm the page cache like a second search
            val t = System.nanoTime()
            block()
            val ms = (System.nanoTime() - t) / 1_000_000
            println("$label: $ms ms")
            return ms
        }
        val times = ArrayList<Long>()
        // Rare and common words, and a query nothing has (the guide window is then read to its end).
        for (q in listOf("канал 4242", "фильм 4242", "приключ", "передача 12", "к", "нет такого")) {
            times += timed("«$q» channels") { runBlocking { store.first(ChannelFilter(key, search = q), 500) to store.countOf(ChannelFilter(key, search = q)) } }
            times += timed("«$q» programmes") { runBlocking { store.findProgrammes(ChannelFilter(key), 0, q, now, 500, now + 12 * 3_600_000L) } }
            times += timed("«$q» films") { runBlocking { lib.first(VodFilter(key, VodKind.MOVIE, search = q), 500) to lib.count(VodFilter(key, VodKind.MOVIE, search = q)) } }
            times += timed("«$q» series") { runBlocking { lib.first(VodFilter(key, VodKind.SERIES, search = q), 500) to lib.count(VodFilter(key, VodKind.SERIES, search = q)) } }
            times += timed("«$q» all kinds") { runBlocking { repo.search(source, q, now) } }
        }
        val r = runBlocking { repo.search(source, "приключ", now) }
        assertEquals(500, r.movies.size)
        assertEquals(7_500, r.count(SearchKind.MOVIES))
        assertEquals(1_000, r.count(SearchKind.SERIES))
        assertEquals("Канал 4242 HD", runBlocking { repo.search(source, "канал 4242", now) }.channels.first().channel.name)
        assertTrue(runBlocking { repo.search(source, "передача 12", now) }.programmes.isNotEmpty())
        assertTrue("slowest ${times.max()} ms", times.all { it < 200 })
    }
}
