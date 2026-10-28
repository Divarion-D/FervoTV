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

    @Test
    fun version1To2KeepsSavedChannels() {
        val file = File(tmp.root, "fervo.db")
        val schema = JSONObject(File("schemas/app.iptvplayer.core.db.AppDb/1.json").readText()).getJSONObject("database")
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
            db.version = 1

            db.execSQL("INSERT INTO source(id, sourceKey, savedAt) VALUES (1, 'k', 42)")
            db.execSQL("INSERT INTO category(id, sourceId, name, position) VALUES (1, 1, 'Новости', 0), (2, 1, 'Кино', 1)")
            db.execSQL(
                "INSERT INTO channel(id, sourceId, categoryId, position, name, url, logo, tvgId, autoLogo) VALUES " +
                    "(1, 1, 1, 0, 'Первый', 'http://a', NULL, 'one.ru', 'http://auto/1.png'), (2, 1, 2, 1, 'Ёлки', 'http://b', NULL, NULL, NULL)"
            )
            db.execSQL("INSERT INTO channel_fts(rowid, name) VALUES (1, 'первый'), (2, 'елки')")
        }

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java, file.path)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val store = PlaylistStore(db)
            assertEquals(42L, runBlocking { store.savedAt("k") })
            assertEquals(listOf("Новости", "Кино"), runBlocking { store.categories("k").first() }.map { it.name })
            val items = runBlocking { loadPage { store.itemSource(ChannelFilter("k", search = "елк"), 0) }.data }
            assertEquals("Ёлки", items.single().channel.name)
            assertEquals("http://b", items.single().channel.key) // user data can be attached right away
            assertEquals(2, items.single().number)

            UserStore(db).setFavorite("http://a", true)
            val favorites = runBlocking { loadPage { store.itemSource(ChannelFilter("k", ChannelGroup.Favorites), 0) }.data }
            assertEquals(listOf("http://auto/1.png"), favorites.map { it.channel.autoLogo })
        } finally {
            db.close()
        }
    }
}
