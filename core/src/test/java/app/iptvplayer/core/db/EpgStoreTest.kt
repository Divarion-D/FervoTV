package app.iptvplayer.core.db

import androidx.room.Room
import app.iptvplayer.core.SyntheticXmltv
import app.iptvplayer.core.epg.unpacked
import app.iptvplayer.core.gzipped
import app.iptvplayer.core.loadPage
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Programme
import app.iptvplayer.core.usedHeap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class EpgStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDb
    private lateinit var store: PlaylistStore
    private lateinit var epg: EpgStore

    private val now = time("2026-10-28T12:00")

    @Before
    fun open() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java, File(tmp.root, "t.db").path)
            .allowMainThreadQueries()
            .build()
        store = PlaylistStore(db) { now }
        epg = EpgStore(db)
    }

    @After
    fun close() = db.close()

    private val playlist = listOf(
        Channel("Первый", "http://a", "Эфир", tvgId = "A.RU"),
        Channel("Второй HD", "http://b", "Эфир"), // bound by name
        Channel("Третий", "http://c", "Эфир"), // not in the guide
    )

    private fun importPlaylist(channels: List<Channel> = playlist) =
        store.import("k", userKey = "u", epgIndex = epg.index()) { import -> channels.forEach(import::channel) }

    /** 15:00 +0300 = 12:00 UTC = [now]. */
    private val xmltv = """<?xml version="1.0" encoding="UTF-8"?><tv>
        <channel id="a.ru"><display-name>Первый канал</display-name><icon src="http://i/a.png"/></channel>
        <channel id="two"><display-name>Второй</display-name></channel>
        <channel id="x"><display-name>Лишний</display-name></channel>
        <programme start="20261027000000 +0300" stop="20261027010000 +0300" channel="a.ru"><title>Вчера</title></programme>
        <programme start="20261028130000 +0300" stop="20261028140000 +0300" channel="a.ru"><title>Утро</title></programme>
        <programme start="20261028140000 +0300" stop="20261028153000 +0300" channel="a.ru"><title>Новости</title><desc>Главное</desc></programme>
        <programme start="20261028153000 +0300" stop="20261028170000 +0300" channel="a.ru"><title>Кино</title></programme>
        <programme start="20261030120000 +0300" stop="20261030130000 +0300" channel="a.ru"><title>Послезавтра</title></programme>
        <programme start="20261028113000" stop="20261028121500" channel="two"><title>Второй сейчас</title></programme>
        <programme start="20261028121500" stop="20261028130000" channel="two"><title>Второй далее</title></programme>
        <programme start="20261028110000" stop="20261028130000" channel="x"><title>Лишнее</title></programme>
    </tv>"""

    private fun importGuide(doc: String = xmltv, tick: () -> Unit = {}): Pair<Long, EpgResult> {
        val id = epg.addSource("http://e/guide.xml").id
        return id to epg.import(id, doc.byteInputStream(), days = 1, now = now, tick = tick)
    }

    private fun items(shift: Int = 0, f: ChannelFilter = ChannelFilter("k")): List<ChannelItem> =
        runBlocking { loadPage { store.itemSource(f, shift) }.data }

    private fun rows(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    /** EPG-003/004/006: only wanted programmes in the window are kept; rows get now/next in one page load. */
    @Test
    fun importBindsChannelsAndShowsNowAndNext() {
        importPlaylist()
        val (id, result) = importGuide()
        assertEquals(EpgResult(channels = 3, programmes = 5, matched = 2), result)
        assertEquals(result.status(), epg.source(id)?.status)
        assertEquals(now, epg.source(id)?.savedAt)
        assertEquals(5, rows("programme")) // not «Вчера» (past), «Послезавтра» (beyond 1 day), «Лишнее» (no such channel)
        assertEquals(0, rows("programme_stage"))

        val (a, b, c) = items()
        assertEquals(Programme(time("2026-10-28T11:00"), time("2026-10-28T12:30"), "Новости", "Главное"), a.now)
        assertEquals("Кино", a.next?.title)
        assertEquals(0.6666667f, a.now!!.progress(now), 0.001f)
        assertEquals(30L, a.now!!.minutesLeft(now))
        assertEquals("Второй сейчас" to "Второй далее", b.now?.title to b.next?.title)
        assertNull(c.now)
        assertNull(c.next)
        assertEquals(listOf(1, 2, 3), items().map { it.number }) // no tvg-chno: position from 1
    }

    @Test
    fun guideWithNoMatchingChannelSaysSo() {
        importPlaylist()
        val (id, result) = importGuide("""<tv><channel id="z"><display-name>Zzz</display-name></channel></tv>""")
        assertEquals(0, result.matched)
        assertTrue(epg.source(id)!!.status!!.contains("Ни один канал плейлиста не найден"))
    }

    /** EPG-003: the old guide stays on screen while the new one is read, and is replaced at the end. */
    @Test
    fun oldGuideStaysUntilTheImportEnds() {
        importPlaylist()
        val (id, _) = importGuide()
        var seen = -1
        epg.import(id, xmltv.replace("Новости", "Новости 2").byteInputStream(), days = 1, now = now) {
            seen = rows("programme")
            assertEquals("Новости", items()[0].now?.title)
        }
        assertEquals(5, seen)
        assertEquals("Новости 2", items()[0].now?.title)
        assertEquals(5, rows("programme"))
        assertEquals(3, rows("epg_channel")) // channel rows kept (same ids), not duplicated
    }

    /** EPG-007: global shift, the source's wins over it, the channel's wins over both; times move with it. */
    @Test
    fun timeShift() {
        importPlaylist()
        val (id, _) = importGuide()
        // -60 min: at 12:00 the guide's 13:00 is on, «Кино» (12:30–14:00 UTC) shown as 11:30–13:00.
        val a = items(shift = -60)[0]
        assertEquals("Кино", a.now?.title)
        assertEquals(time("2026-10-28T11:30"), a.now?.start)

        epg.updateSource(epg.source(id)!!.copy(shift = 0))
        assertEquals("Новости", items(shift = -60)[0].now?.title)

        UserStore(db).setEpgShift("http://a", -60)
        assertEquals("Кино", items(shift = 0)[0].now?.title)
        assertEquals("Второй сейчас", items(shift = 0)[1].now?.title) // other channels keep the source's shift
    }

    /** EPG-004: the user's binding wins, survives a playlist refresh; missing programmes ask for a re-download. */
    @Test
    fun userBindingSurvivesRefresh() {
        importPlaylist()
        val (id, _) = importGuide()
        assertEquals(emptySet<Long>(), epg.bind(listOf(Triple("http://c", "two", null)), takeLogo = false))
        assertEquals("Второй сейчас", items()[2].now?.title)

        importPlaylist() // background refresh: all channel rows are new
        val after = items()
        assertEquals(listOf("Новости", "Второй сейчас", "Второй сейчас"), after.map { it.now?.title })

        // «x» has no stored programmes (nobody wanted it at import): its source must be downloaded again.
        assertEquals(setOf(id), epg.bind(listOf(Triple("http://c", "x", "http://i/x.png")), takeLogo = true))
        importGuide()
        assertEquals("Лишнее", items()[2].now?.title)
        assertEquals("http://i/x.png", items()[2].channel.logo)

        epg.bind(listOf(Triple("http://c", null, null)), takeLogo = false) // back to automatic
        assertNull(items()[2].now)
    }

    /** EPG-006 player info bar and the channel schedule; EPG-008 grid rows. */
    @Test
    fun airingScheduleAndGuideRows() {
        importPlaylist()
        importGuide()
        val airing = runBlocking { store.airing("k", "http://a", 0, flowOf(now)).first() }
        assertEquals(listOf("Утро", "Новости", "Кино"), listOf(airing.previous, airing.now, airing.next).map { it?.title })

        val day = runBlocking { store.schedule("k", "http://a", 0, time("2026-10-28T00:00"), time("2026-10-29T00:00")) }
        assertEquals(listOf("Утро", "Новости", "Кино"), day.map { it.title })

        val from = time("2026-10-28T11:45")
        val to = time("2026-10-28T13:15")
        val guide = runBlocking { loadPage { store.guideSource(ChannelFilter("k"), 0, from, to) }.data }
        assertEquals(
            listOf(listOf("Новости", "Кино"), listOf("Второй сейчас", "Второй далее"), emptyList()),
            guide.map { r -> r.programmes.map { it.title } },
        )
        assertEquals("Новости", guide[0].item.now?.title)
    }

    /** EPG-010: channels without a guide can be left out; "Поиск" finds programmes of the list by title. */
    @Test
    fun guideOnlyAndProgrammeSearch() {
        importPlaylist()
        importGuide()
        val f = ChannelFilter("k")
        assertEquals(listOf("Первый", "Второй HD"), items(f = f.copy(guideOnly = true)).map { it.channel.name })
        val found = runBlocking { store.findProgrammes(f, 0, "кин", now, 10) }
        assertEquals(listOf(Triple("http://a", "Первый", "Кино")), found.map { Triple(it.key, it.channel, it.programme.title) })
        assertEquals(time("2026-10-28T12:30"), found[0].programme.start)
        // Ended ones aren't found; every word has to match; the shift moves the times.
        assertEquals(emptyList<String>(), runBlocking { store.findProgrammes(f, 0, "утро", now, 10) }.map { it.programme.title })
        assertEquals(listOf("Второй далее"), runBlocking { store.findProgrammes(f, 0, "вто дал", now, 10) }.map { it.programme.title })
        assertEquals(time("2026-10-28T13:30"), runBlocking { store.findProgrammes(f, 60, "кино", now, 10) }.single().programme.start)
    }

    /** EPG-002: a stored short EPG binds an Xtream channel no XMLTV covers. */
    @Test
    fun shortEpg() {
        importPlaylist()
        assertTrue(epg.needsShortEpg("k", "http://c"))
        epg.storeShortEpg("http://c", listOf(Programme(now - 600_000, now + 600_000, "Коротко")), now)
        assertEquals("Коротко", items()[2].now?.title)
        assertTrue(epg.needsShortEpg("k", "http://c")) // still its own short EPG, not an XMLTV
        importGuide()
        assertTrue(!epg.needsShortEpg("k", "http://a"))
        assertEquals(emptyList<String>(), runBlocking { epg.sourceList() }.map { it.url }.filter { it.startsWith("xtream:") })
    }

    /**
     * EPG-005: "Сопоставить автоматически" for 400 channels against a 3 000-channel guide in ≤ 5 s
     * (sure pairs bound, the rest offered for review).
     */
    @Test
    fun autoMatch400Channels() {
        val rnd = Random(7)
        val letters = "абвгдежзиклмнопрстуфхцчшщэюя"
        fun word() = String(CharArray(7 + rnd.nextInt(4)) { letters[rnd.nextInt(letters.length)] })
        val words = List(3_000) { "${word()} ${word()}" }
        // The first 200 have a typo (one letter dropped), the next 200 an extra word.
        val channels = List(400) { i ->
            val w = words[i]
            val name = if (i < 200) w.removeRange(3, 4) else "$w Россия"
            Channel(name.replaceFirstChar { it.uppercase() }, "http://s/$i", "Все")
        }
        importPlaylist(channels)
        val doc = buildString {
            append("<tv>")
            words.forEachIndexed { i, w -> append("<channel id=\"e$i\"><display-name>${w.replaceFirstChar { it.uppercase() }}</display-name></channel>") }
            append("</tv>")
        }
        importGuide(doc)

        val start = System.nanoTime()
        val (result, refetch) = epg.autoMatch("k")
        val ms = (System.nanoTime() - start) / 1_000_000
        println("auto-match 400 channels × 3 000 guide channels: $ms ms, sure ${result.sure.size}, unsure ${result.unsure.size}")
        assertTrue("took $ms ms", ms <= 5_000)

        fun right(ch: Channel, e: app.iptvplayer.core.epg.EpgEntry) = e.xmltvId == "e" + ch.url.substringAfterLast('/')
        assertTrue(result.sure.size >= 200) // every typo, and the extra word when the name is long enough
        assertTrue(result.sure.all { (ch, e) -> right(ch, e) })
        assertTrue(result.unsure.all { (ch, s) -> right(ch, s.first().entry) }) // the right one is offered first
        // Bound pairs are the user's bindings now; their programmes come with the next download.
        assertEquals(result.sure.size, db.user().epgBindings().size)
        assertEquals(1, refetch.size)
    }

    /**
     * EPG-003: an XMLTV of 300 MB unpacked (gzip, generated while it's read: the input itself takes no memory) goes in
     * with the 256 MB test heap, and the live heap doesn't grow with it. Sampled every 20 000 programmes.
     */
    @Test
    fun threeHundredMegabyteXmltvWithoutMemoryGrowth() {
        importPlaylist(List(400) { Channel("Канал $it", "http://s/$it", "Все", tvgId = "ch$it") })
        val id = epg.addSource("http://e/big.xml.gz").id
        val xml = SyntheticXmltv(channels = 2_000, perChannel = 500, base = now - 2 * 24 * 3_600_000L)
        val samples = ArrayList<Long>()
        var n = 0
        val start = System.nanoTime()
        val result = epg.import(id, unpacked(gzipped(xml)), days = 7, now = now) {
            if (++n % 20_000 == 0) samples += usedHeap()
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val growth = samples.max() - samples.first()
        println("XMLTV ${xml.bytes / 1_048_576} MB (gzip): $ms ms, ${result.programmes} programmes kept, heap growth ${growth / 1024} KB")
        assertTrue(xml.bytes >= 300L * 1_048_576)
        assertTrue("heap grew by ${growth / 1024} KB", growth < 8L * 1024 * 1024)
        assertEquals(2_000, result.channels)
        assertEquals(400, result.matched)
        // 400 wanted channels × the programmes from 6 h ago to 7 days ahead (30 min each).
        assertEquals(400 * 348, result.programmes)
        assertEquals(result.programmes, rows("programme"))
        assertTrue(items().all { it.now != null && it.next != null })
    }

    private fun time(iso: String) = Instant.parse("$iso:00Z").toEpochMilli()
}
