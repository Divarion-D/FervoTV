package app.iptvplayer.core.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.iptvplayer.core.loadPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * A database of version 1, built from its exported schema (`core/schemas/…/1.json`) the way Room 2.6 created it on
 * devices, is opened by the current version. Room checks the migrated schema against the entities on open, so a
 * migration that misses a column or index fails here; the saved channels must stay usable.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A database file of [version] as Room created it from its exported schema, filled by [fill]. */
    private fun oldDatabase(version: Int, fill: (SQLiteDatabase) -> Unit): File {
        val file = File(tmp.root, "fervo.db")
        val schema = JSONObject(File("schemas/app.iptvplayer.core.db.AppDb/$version.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
            fill(db)
        }
        return file
    }

    private fun open(file: File) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java, file.path)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .allowMainThreadQueries()
        .build()

    @Test
    fun version1To2KeepsSavedChannels() {
        val file = oldDatabase(1) { db ->

            db.execSQL("INSERT INTO source(id, sourceKey, savedAt) VALUES (1, 'k', 42)")
            db.execSQL("INSERT INTO category(id, sourceId, name, position) VALUES (1, 1, 'Новости', 0), (2, 1, 'Кино', 1)")
            db.execSQL(
                "INSERT INTO channel(id, sourceId, categoryId, position, name, url, logo, tvgId, autoLogo) VALUES " +
                    "(1, 1, 1, 0, 'Первый', 'http://a', NULL, 'one.ru', 'http://auto/1.png'), (2, 1, 2, 1, 'Ёлки', 'http://b', NULL, NULL, NULL)"
            )
            db.execSQL("INSERT INTO channel_fts(rowid, name) VALUES (1, 'первый'), (2, 'елки')")
        }

        val db = open(file)
        try {
            val store = PlaylistStore(db)
            assertEquals(42L, runBlocking { store.savedAt("k") })
            assertEquals(listOf("Новости", "Кино"), runBlocking { store.categories("k").first() }.map { it.name })
            val items = runBlocking { loadPage { store.itemSource(ChannelFilter("k", search = "елк"), 0) }.data }
            assertEquals("Ёлки", items.single().channel.name)
            assertEquals("http://b", items.single().channel.key) // user data can be attached right away
            assertEquals(2, items.single().number)

            UserStore(db).setFavorite("k", "http://a", true)
            val favorites = runBlocking { loadPage { store.itemSource(ChannelFilter("k", ChannelGroup.Favorites), 0) }.data }
            assertEquals(listOf("http://auto/1.png"), favorites.map { it.channel.autoLogo })
        } finally {
            db.close()
        }
    }

    /** P2.1: v2 → v3 adds the library; the channels, every edit, the guide and «Недавние» stay as they were. */
    @Test
    fun version2To3KeepsUserDataAndGuide() {
        val file = oldDatabase(2) { db ->
            db.execSQL("INSERT INTO source(id, sourceKey, savedAt, epgUrls) VALUES (1, 'k', 42, 'http://g/epg.xml')")
            db.execSQL("INSERT INTO category(id, sourceId, name, position, ckey) VALUES (1, 1, 'Новости', 0, 'u|Новости')")
            db.execSQL(
                "INSERT INTO channel(id, sourceId, categoryId, position, name, url, logo, tvgId, autoLogo, ckey, number, archiveDays, epgId) " +
                    "VALUES (1, 1, 1, 0, 'Первый', 'http://a', NULL, 'one', NULL, 'http://a', 1, 3, 1)"
            )
            db.execSQL("INSERT INTO channel_fts(rowid, name) VALUES (1, 'первый')")
            db.execSQL("INSERT INTO channel_state(ckey, name, hidden, favorite, epg, epgShift, logo) VALUES ('http://a', 'Мой первый', 0, 0, 'one', 30, NULL)")
            db.execSQL("INSERT INTO category_state(ckey, name, hidden, position) VALUES ('u|Новости', 'Главное', 0, 0)")
            db.execSQL("INSERT INTO collection(id, name, position) VALUES (1, 'Вечер', 0)")
            db.execSQL("INSERT INTO collection_channel(collectionId, ckey, position) VALUES (1, 'http://a', 0)")
            db.execSQL("INSERT INTO recent(ckey, watchedAt) VALUES ('http://a', 1000)")
            db.execSQL("INSERT INTO epg_source(id, url, userAgent, refresh, refreshDays, shift, position, savedAt, attemptAt, status) VALUES (1, 'http://g/epg.xml', NULL, 'DAILY', 3, NULL, 0, 40, 40, NULL)")
            db.execSQL("INSERT INTO epg_channel(id, sourceId, xmltvId, names, icon) VALUES (1, 1, 'one', 'Первый', NULL)")
            db.execSQL("INSERT INTO programme(id, channelId, start, stop, title, description) VALUES (1, 1, 0, 9000000000000, 'Новости', NULL)")
        }
        val db = open(file)
        try {
            val store = PlaylistStore(db)
            val item = runBlocking { loadPage { store.itemSource(ChannelFilter("k", ChannelGroup.Favorites), 0) }.data }.single()
            assertEquals("Мой первый" to "Новости", item.channel.name to item.now?.title)
            assertEquals(listOf("Главное"), runBlocking { store.categories("k").first() }.map { it.title })
            assertEquals(listOf("http://a"), runBlocking { UserStore(db).recentKeys("k") })
            val collection = runBlocking { UserStore(db).collections().first() }.single()
            assertEquals("Вечер", collection.name)
            assertEquals(1, runBlocking { loadPage { store.itemSource(ChannelFilter("k", ChannelGroup.Collection(collection.id)), 0) }.data }.size)
            assertEquals(listOf("http://g/epg.xml"), runBlocking { EpgStore(db).sources().first() }.map { it.url })

            // The new tables work at once.
            LibraryStore(db).import("k", "u") { it.item(app.iptvplayer.core.model.VodEntry(app.iptvplayer.core.model.VodKind.MOVIE, "m", "Фильм", "Кино", "http://m")) }
            LibraryUser(db).setFavorite("m", true)
            assertEquals(listOf("Фильм"), runBlocking {
                LibraryStore(db).first(VodFilter("k", app.iptvplayer.core.model.VodKind.MOVIE, VodGroup.Favorites), 10)
            }.map { it.name })
        } finally {
            db.close()
        }
    }

    @Test
    fun version4To5IndexesSavedGuideAndKeepsTheIndexInSync() {
        val file = oldDatabase(4) { db ->
            db.execSQL("INSERT INTO programme(id, channelId, start, stop, title, description) VALUES (1, 1, 0, 100, 'Ёлки-палки', NULL)")
        }
        val db = open(file)
        try {
            fun titles(query: String): List<String> = db.query(
                "SELECT p.title FROM programme p JOIN programme_fts f ON f.rowid = p.id WHERE programme_fts MATCH ?", arrayOf(query),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(listOf("Ёлки-палки"), titles("елк* пал*"))
            db.epg().insertProgrammes(listOf(ProgrammeRow(channelId = 2, start = 0, stop = 100, title = "Человек-паук", description = null)))
            assertEquals(listOf("Человек-паук"), titles("челов* пау*"))
            db.epg().deleteChannelProgrammes(2)
            assertEquals(emptyList<String>(), titles("челов* пау*"))
        } finally {
            db.close()
        }
    }

    /**
     * LIVE-008: v3 → v4 names the favourites after their channels in the saved source; one whose channel is gone keeps
     * no name and still works by key. The saved channels can be found by name at once.
     */
    @Test
    fun version3To4NamesFavorites() {
        val file = oldDatabase(3) { db ->
            db.execSQL("INSERT INTO source(id, sourceKey, savedAt, epgUrls) VALUES (1, 'k', 42, '')")
            db.execSQL("INSERT INTO category(id, sourceId, name, position, ckey) VALUES (1, 1, 'Новости', 0, 'u|Новости')")
            db.execSQL(
                "INSERT INTO channel(id, sourceId, categoryId, position, name, url, logo, tvgId, autoLogo, ckey, number, archiveDays, epgId) " +
                    "VALUES (1, 1, 1, 0, 'Первый HD', 'http://a', NULL, NULL, NULL, 'http://a', NULL, 0, NULL), " +
                    "(2, 1, 1, 1, 'Ёлки', 'http://b', NULL, NULL, NULL, 'http://b', NULL, 0, NULL)"
            )
            db.execSQL("INSERT INTO channel_fts(rowid, name) VALUES (1, 'первый hd'), (2, 'елки')")
            db.execSQL(
                "INSERT INTO channel_state(ckey, name, hidden, favorite, epg, epgShift, logo) VALUES " +
                    "('http://a', 'Мой первый', 0, 1, NULL, NULL, NULL), ('http://b', NULL, 0, 0, NULL, NULL, NULL), " +
                    "('http://gone', NULL, 0, 2, NULL, NULL, NULL)"
            )
        }
        val db = open(file)
        try {
            val states = db.user().allChannelStates().associate { it.ckey to (it.favoriteName to it.favoriteMatch) }
            assertEquals(mapOf("http://a" to ("Первый HD" to "первыйhd"), "http://b" to ("Ёлки" to "елки"), "http://gone" to (null to null)), states)
            val store = PlaylistStore(db)
            val favorites = { key: String -> runBlocking { loadPage { store.itemSource(ChannelFilter(key, ChannelGroup.Favorites), 0) }.data }.map { it.channel.name } }
            assertEquals(listOf("Ёлки", "Мой первый"), favorites("k"))

            // Another playlist: the favourites are found there by name; the one known by key only comes back with its channel.
            store.import("k2") { i ->
                i.channel(app.iptvplayer.core.model.Channel("ПЕРВЫЙ-HD", "http://x/1", "Эфир"))
                i.channel(app.iptvplayer.core.model.Channel("Канал", "http://gone", "Эфир"))
            }
            assertEquals(listOf("ПЕРВЫЙ-HD", "Канал"), favorites("k2"))
        } finally {
            db.close()
        }
    }
}
