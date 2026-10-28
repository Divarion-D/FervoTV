package app.iptvplayer.core.db

import androidx.paging.PagingSource
import androidx.room.Room
import app.iptvplayer.core.SyntheticM3u
import app.iptvplayer.core.migrateLegacyCache
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.source.encodePlaylist
import app.iptvplayer.core.source.m3u.parseM3u
import app.iptvplayer.core.usedHeap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.IOException

/** Room on Robolectric's SQLite: the real schema, FTS and paging queries, on the JVM. */
@RunWith(RobolectricTestRunner::class)
class PlaylistStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDb
    private lateinit var store: PlaylistStore

    @Before
    fun open() {
        // A file, not an in-memory database: WAL and the page cache behave as on a device.
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java, File(tmp.root, "t.db").path)
            .allowMainThreadQueries()
            .build()
        store = PlaylistStore(db)
    }

    @After
    fun close() = db.close()

    private val sample = listOf(
        Channel("Первый", "http://a", "Новости", logo = "http://l/1.png", tvgId = "one.ru"),
        Channel("Ёлки ТВ", "http://b", "Кино"),
        Channel("Второй", "http://c", "Новости"),
        Channel("Матч! Футбол 1", "http://d", "Спорт"),
    )

    private fun importSample(key: String = "k", channels: List<Channel> = sample) =
        store.import(key, savedAt = 42) { import ->
            import.category("Новости")
            import.category("Пусто")
            channels.forEach(import::channel)
        }

    private fun page(f: ChannelFilter, size: Int = 100) = runBlocking {
        store.pagingSource(f).load(PagingSource.LoadParams.Refresh(null, size, false)) as PagingSource.LoadResult.Page
    }

    private fun names(f: ChannelFilter) = page(f).data.map { it.name }

    private fun rows(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun importThenReadCategoriesFilterAndSearch() {
        assertEquals(4, importSample())
        assertEquals(42L, runBlocking { store.savedAt("k") })

        // Provider order (pre-registered first), counts, empty categories hidden.
        assertEquals(
            listOf(CategoryCount("Новости", 2), CategoryCount("Кино", 1), CategoryCount("Спорт", 1)),
            runBlocking { store.categories("k").first() },
        )

        val all = ChannelFilter("k")
        assertEquals(sample.map { it.name }, names(all))
        assertEquals(sample[0], page(all).data[0].copy(id = 0))
        assertEquals(listOf("Первый", "Второй"), names(ChannelFilter("k", group = "Новости")))

        // FTS: case, ё/е, word prefixes, several words; inside the selected category only.
        assertEquals(listOf("Ёлки ТВ"), names(ChannelFilter("k", search = "елки")))
        assertEquals(listOf("Ёлки ТВ"), names(ChannelFilter("k", search = "ЁЛ")))
        assertEquals(listOf("Матч! Футбол 1"), names(ChannelFilter("k", search = "фут 1")))
        assertEquals(emptyList<String>(), names(ChannelFilter("k", group = "Новости", search = "матч")))
        assertEquals(emptyList<String>(), names(ChannelFilter("k", search = "торой"))) // prefix, not substring
        assertEquals(sample.map { it.name }, names(ChannelFilter("k", search = " !! "))) // nothing to search for

        // Player: channel by position in the filtered list.
        assertEquals("Второй", runBlocking { store.channelAt(ChannelFilter("k", group = "Новости"), 1) }?.name)
        assertNull(runBlocking { store.channelAt(all, 4) })

        // Another source key sees nothing.
        assertNull(runBlocking { store.savedAt("other") })
        assertEquals(emptyList<String>(), names(ChannelFilter("other")))
    }

    @Test
    fun emptyOrFailedImportKeepsSavedChannels() {
        importSample()
        assertTrue(runCatching { store.import("k") { } }.exceptionOrNull() is IOException)
        val failure = runCatching {
            store.import("k") { import ->
                repeat(2_500) { import.channel(Channel("x$it", "http://x/$it", "g")) } // two batches already written
                throw IOException("обрыв связи")
            }
        }
        assertEquals("обрыв связи", failure.exceptionOrNull()?.message)

        assertEquals(42L, runBlocking { store.savedAt("k") })
        assertEquals(sample.map { it.name }, names(ChannelFilter("k")))
        assertEquals(4, rows("channel")) // the partial import is gone
        assertEquals(4, rows("channel_fts"))
    }

    @Test
    fun savedChannelsStayVisibleUntilTheNewImportFinishes() {
        importSample()
        store.import("k") { import ->
            repeat(2_500) { import.channel(Channel("Новый $it", "http://n/$it", "g")) }
            assertEquals(sample.map { it.name }, names(ChannelFilter("k")))
        }
        assertEquals(2_500, page(ChannelFilter("k")).run { data.size + itemsAfter })
        assertEquals(listOf("Новый 2499"), names(ChannelFilter("k", search = "новый 2499")))
        assertEquals(2_500, rows("channel")) // the previous import is deleted
        assertEquals(2_500, rows("channel_fts"))
    }

    @Test
    fun newSourceReplacesTheOldOne() {
        importSample("a")
        importSample("b")
        assertNull(runBlocking { store.savedAt("a") })
        assertEquals(4, names(ChannelFilter("b")).size)
        assertEquals(4, rows("channel"))
    }

    @Test
    fun autoLogosAreStoredNextToChannels() {
        importSample()
        assertEquals(1 to 4, store.applyAutoLogos("k") { if (it.tvgId == "one.ru") "http://auto/1.png" else null })
        assertEquals("http://auto/1.png", runBlocking { store.channelAt(ChannelFilter("k"), 0) }?.autoLogo)
        assertEquals(0 to 4, store.applyAutoLogos("k") { null }) // logos off
        assertNull(runBlocking { store.channelAt(ChannelFilter("k"), 0) }?.autoLogo)
    }

    @Test
    fun legacyPlaylistTsvMovesIntoTheDatabase() {
        val dir = tmp.newFolder()
        File(dir, "playlist.tsv").writeText(encodePlaylist("k", 1234L, sample))
        File(dir, "playlist.tsv.tmp").writeText("half-written")

        migrateLegacyCache(dir, store)

        assertEquals(1234L, runBlocking { store.savedAt("k") }) // the refresh schedule carries on
        assertEquals(sample, page(ChannelFilter("k")).data.map { it.copy(id = 0) })
        assertEquals(listOf("Новости", "Кино", "Спорт"), runBlocking { store.categories("k").first() }.map { it.name })
        assertFalse(File(dir, "playlist.tsv").exists())
        assertFalse(File(dir, "playlist.tsv.tmp").exists())

        // Damaged file: dropped, the saved channels stay.
        File(dir, "playlist.tsv").writeText("garbage\nlines")
        migrateLegacyCache(dir, store)
        assertFalse(File(dir, "playlist.tsv").exists())
        assertEquals(1234L, runBlocking { store.savedAt("k") })
    }

    /**
     * SRC-006: 100 000 channels, generated while being read, go into the database with a 256 MB test heap
     * (core/build.gradle.kts). Live heap after GC is sampled every 10 000 channels: it must not grow with the
     * import (the channels themselves would take ~40 MB).
     */
    @Test
    fun hundredThousandChannelsWithoutMemoryGrowth() {
        val samples = ArrayList<Long>()
        val start = System.nanoTime()
        val count = store.import("big") { import ->
            parseM3u(SyntheticM3u(100_000), "http://h/list.m3u") { ch ->
                import.channel(ch)
                if (import.count % 10_000 == 0) samples += usedHeap()
            }
        }
        val importMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(100_000, count)
        val growth = samples.max() - samples.first()
        println("100k import: $importMs ms (incl. ${samples.size} forced GCs), heap growth ${growth / 1024} KB")
        assertTrue("heap grew by ${growth / 1024} KB", growth < 8L * 1024 * 1024)

        val cats = runBlocking { store.categories("big").first() }
        assertEquals(50, cats.size)
        assertTrue(cats.all { it.count == 2_000 })

        val t = System.nanoTime()
        val found = names(ChannelFilter("big", search = "канал 4242"))
        println("FTS search over 100k: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertEquals(listOf("Канал 4242 HD") + (0..9).map { "Канал 4242$it HD" }, found)
        assertEquals("Канал 99999 HD", runBlocking { store.channelAt(ChannelFilter("big"), 99_999) }?.name)
    }
}
