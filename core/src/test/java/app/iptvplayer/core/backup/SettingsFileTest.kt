package app.iptvplayer.core.backup

import android.content.Context
import androidx.room.Room
import app.iptvplayer.core.CoreException
import app.iptvplayer.core.ErrorCode
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.LibraryStore
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.db.VodFilter
import app.iptvplayer.core.db.VodGroup
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.loadPage
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.ResumeMode
import app.iptvplayer.core.model.StartScreen
import app.iptvplayer.core.model.VodSort
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.userKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** BAK-011: save → load into a clean install gives the same state; a bad file changes nothing. */
@RunWith(RobolectricTestRunner::class)
class SettingsFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val dbs = ArrayList<AppDb>()

    // Synthetic account: no real server or credentials.
    private val account = Source.Xtream("http://iptv.example", "demo", "secret", hls = true)

    @Before
    fun clean() = wipePrefs()

    @After
    fun close() = dbs.forEach(AppDb::close)

    private fun wipePrefs() = listOf("iptv", "settings").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

    private fun newDb(): AppDb = Room.databaseBuilder(ctx, AppDb::class.java, File(tmp.root, "${dbs.size}.db").path)
        .allowMainThreadQueries().build().also { dbs += it }

    private val playlist = listOf(
        Channel("Первый", "http://iptv.example/live/demo/secret/1.ts", "Новости", key = "http://iptv.example|demo|1"),
        Channel("Второй", "http://iptv.example/live/demo/secret/2.ts", "Новости", key = "http://iptv.example|demo|2"),
        Channel("Кино", "http://iptv.example/live/demo/secret/3.ts", "Фильмы", key = "http://iptv.example|demo|3"),
    )

    private fun importPlaylist(db: AppDb) =
        PlaylistStore(db).import(account.cacheKey(), userKey = account.userKey()) { i -> playlist.forEach(i::channel) }

    /** An installation with every kind of edit. */
    private fun configured(): Repository = runBlocking {
        val db = newDb()
        importPlaylist(db)
        Repository(ctx, db).apply {
            this.source = account
            settings = settings.copy(
                playlistRefresh = PlaylistRefresh.EVERY_N_DAYS, refreshDays = 5, channelSort = ChannelSort.NAME,
                logoSource = "https://logos.example/api", liveTiles = true, epgShift = 60, language = "en",
                resume = ResumeMode.CONTINUE, autoNext = false, movieSort = VodSort.RATING, seriesList = true,
                startScreen = StartScreen.LIVE, homeRows = "FAVORITE_MOVIES,-CONTINUE", homeChannels = false, homeGuide = true,
            )
            addEpgSource("https://guide.example/a.xml.gz")
            updateEpgSource(addEpgSource("https://guide.example/b.xml").copy(userAgent = "UA", shift = -30, refresh = PlaylistRefresh.WEEKLY))
            setFavorite(account, playlist[2].key, true)
            setFavorite(account, playlist[0].key, true)
            rename(playlist[1].key, "Мой второй")
            setHidden(listOf(playlist[0].key), true)
            setEpgShift(playlist[2].key, 15)
            renameCategory(account, "Фильмы", "Кинозал")
            setCategoriesHidden(account, listOf("Новости"), true)
            reorderCategories(account, listOf("Фильмы", "Новости"))
            reorder(account, ChannelGroup.Category("Новости"), listOf(playlist[1].key, playlist[0].key))
            val id = createCollection("Вечер")
            addToCollection(id, playlist[2].key)
            addToCollection(id, playlist[1].key)
            createCollection("Пустая")

            // Films and series (P2.1): edits, a collection and watching go to the file too.
            importLibrary(db)
            val films = firstVod(VodFilter(account.cacheKey(), VodKind.MOVIE), 10)
            val show = firstVod(VodFilter(account.cacheKey(), VodKind.SERIES), 10).single()
            setVodFavorite(films[1].key, true)
            setVodHidden(listOf(films[0].key), true)
            renameVod(show.key, "Моё шоу")
            renameVodCategory(account, VodKind.MOVIE, "Кино", "Фильмы")
            reorderVod(account, VodKind.MOVIE, VodGroup.Category("Кино"), listOf(films[1].key, films[0].key))
            addToVodCollection(createVodCollection(VodKind.SERIES, "Смотреть"), show.key)
            savePosition(films[1], 120_000, 7_200_000)
            setWatched(films[0], true)
        }
    }

    private fun importLibrary(db: AppDb) = LibraryStore(db).import(account.cacheKey(), account.userKey()) { lib ->
        lib.item(VodEntry(VodKind.MOVIE, "${account.userKey()}|movie|1", "Первый фильм", "Кино", "http://iptv.example/movie/demo/secret/1.mp4"))
        lib.item(VodEntry(VodKind.MOVIE, "${account.userKey()}|movie|2", "Второй фильм", "Кино", "http://iptv.example/movie/demo/secret/2.mp4"))
        lib.item(VodEntry(VodKind.SERIES, "${account.userKey()}|series|3", "Шоу", "Сериалы"))
    }

    private fun save(repo: Repository, withPassword: Boolean): ByteArray =
        ByteArrayOutputStream().also { runBlocking { repo.saveSettingsFile(it, withPassword, "0.2") } }.toByteArray()

    private fun cleanInstall(): Repository {
        wipePrefs()
        return Repository(ctx, newDb())
    }

    private fun restore(repo: Repository, bytes: ByteArray) = runBlocking { repo.restore(repo.readSettingsFile(ByteArrayInputStream(bytes))) }

    private fun state(repo: Repository) = runBlocking { repo.backup() }.let { it.copy(guides = it.guides?.map { g -> g.copy(id = 0) }) }

    @Test
    fun roundTripGivesTheSameState() {
        val old = configured()
        val before = state(old)
        val bytes = save(old, withPassword = true)

        val fresh = cleanInstall()
        restore(fresh, bytes)
        assertEquals(before, state(fresh))
        assertFalse(fresh.passwordMissing)

        // The playlist downloads again: the edits land on its channels by their keys.
        importPlaylist(dbs.last())
        val favorites = runBlocking {
            loadPage { PlaylistStore(dbs.last()).itemSource(ChannelFilter(account.cacheKey(), ChannelGroup.Favorites), 0) }.data
        }
        assertEquals(listOf("Кино"), favorites.map { it.channel.name })
        importLibrary(dbs.last())
        val films = runBlocking { fresh.firstVod(VodFilter(account.cacheKey(), VodKind.MOVIE, VodGroup.Favorites), 10) }
        assertEquals(listOf("Второй фильм" to 120_000L), films.map { it.name to it.progress?.position })
    }

    /** LIVE-008: favourites carry their channel's name; a file written before that has none and works by key. */
    @Test
    fun favoriteNamesInTheFileAndOldFilesWithout() {
        val bytes = save(configured(), withPassword = true)
        val channels = JSONObject(String(entry(bytes, "channels.json"))).getJSONArray("channels")
        val names = (0 until channels.length()).map { channels.getJSONObject(it) }.filter { it.has("favorite") }.map { it.getString("favoriteName") }
        assertEquals(setOf("Первый", "Кино"), names.toSet())

        val old = rezip(bytes) { name, text ->
            if (name != "channels.json") text
            else JSONObject(text).apply {
                val list = getJSONArray("channels")
                for (i in 0 until list.length()) list.getJSONObject(i).remove("favoriteName")
            }.toString()
        }
        val fresh = cleanInstall()
        restore(fresh, old)
        importPlaylist(dbs.last())
        val favorites = runBlocking {
            loadPage { PlaylistStore(dbs.last()).itemSource(ChannelFilter(account.cacheKey(), ChannelGroup.Favorites), 0) }.data
        }
        assertEquals(listOf("Кино"), favorites.map { it.channel.name }) // «Первый» is hidden
        // The download gave them their names: from now on they follow the user to another source too.
        assertEquals(setOf("Первый", "Кино"), state(fresh).channels!!.channels.mapNotNull { it.favoriteName }.toSet())
    }

    @Test
    fun passwordIsLeftOutUnlessAsked() {
        val bytes = save(configured(), withPassword = false)
        assertFalse(String(entry(bytes, "source.json")).contains("secret"))

        val fresh = cleanInstall()
        restore(fresh, bytes)
        assertEquals(account.copy(pass = ""), fresh.source)
        assertTrue(fresh.passwordMissing)
        fresh.source = account // the user typed it: asked no more
        assertFalse(fresh.passwordMissing)
    }

    @Test
    fun manifestDescribesTheFile() {
        val m = JSONObject(String(entry(save(configured(), withPassword = false), "manifest.json")))
        assertEquals(FORMAT, m.getString("format"))
        assertEquals(FORMAT_VERSION, m.getInt("formatVersion"))
        assertEquals("0.2", m.getString("appVersion"))
        assertTrue(m.getString("createdAt").matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")))
        assertEquals("""["source","settings","guide","channels","library","watched"]""", m.getJSONArray("parts").toString())
    }

    @Test
    fun badFilesChangeNothing() {
        val good = save(configured(), withPassword = true)
        val target = configured()
        val before = state(target)
        val newer = rezip(good) { name, text ->
            if (name == "manifest.json") JSONObject(text).put("formatVersion", FORMAT_VERSION + 1).toString() else text
        }
        val noPart = rezip(good) { name, text -> if (name == "guide.json") null else text }
        val badJson = rezip(good) { name, text -> if (name == "channels.json") text.dropLast(5) else text }
        val foreignZip = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { it.putNextEntry(ZipEntry("readme.txt")); it.write("hello".toByteArray()) }
        }.toByteArray()
        val cases = listOf(
            good.copyOf(good.size / 2) to ErrorCode.BACKUP_CORRUPT, // cut off
            badJson to ErrorCode.BACKUP_CORRUPT,
            noPart to ErrorCode.BACKUP_CORRUPT, // listed in the manifest but missing
            newer to ErrorCode.BACKUP_NEWER,
            foreignZip to ErrorCode.BACKUP_FOREIGN,
            "#EXTM3U\n#EXTINF:-1,A\nhttp://a\n".toByteArray() to ErrorCode.BACKUP_FOREIGN, // not a ZIP at all
            ByteArray(0) to ErrorCode.BACKUP_FOREIGN,
        )
        for ((bytes, code) in cases) {
            val e = runCatching { restore(target, bytes) }.exceptionOrNull()
            assertEquals(code, (e as? CoreException)?.code)
            assertEquals(before, state(target))
        }
    }

    /** Parts a file doesn't have, and fields an older version didn't write, keep what is there / the defaults. */
    @Test
    fun missingPartsStayAsTheyAre() {
        val target = configured()
        val before = state(target)
        val onlySettings = rezip(save(configured(), withPassword = true)) { name, text ->
            when (name) {
                "manifest.json" -> JSONObject(text).put("parts", org.json.JSONArray(listOf("settings"))).toString()
                "settings.json" -> """{"channelSort": "NUMBER", "unknownFromP5": 1}"""
                else -> null
            }
        }
        restore(target, onlySettings)
        val after = state(target)
        assertEquals(ChannelSort.NUMBER, after.settings?.channelSort)
        assertEquals(PlaylistRefresh.ON_START, after.settings?.playlistRefresh) // default
        assertEquals(before.copy(settings = after.settings), after)
    }

    private fun entry(zip: ByteArray, name: String): ByteArray = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.first { it.name == name }
        z.readBytes()
    }

    /** The same archive with entries changed ([edit] returns null to leave one out). */
    private fun rezip(zip: ByteArray, edit: (String, String) -> String?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { o ->
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    val text = edit(e.name, String(z.readBytes())) ?: return@forEach
                    o.putNextEntry(ZipEntry(e.name))
                    o.write(text.toByteArray())
                }
            }
        }
        return out.toByteArray()
    }
}
