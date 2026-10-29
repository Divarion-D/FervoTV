package app.iptvplayer.core.db

import androidx.room.Room
import app.iptvplayer.core.loadPage
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.ChannelSort
import kotlinx.coroutines.flow.first
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

/** LIVE-008, 010, 012–018: user data is keyed by stable keys and survives background refreshes of the source. */
@RunWith(RobolectricTestRunner::class)
class UserStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDb
    private lateinit var store: PlaylistStore
    private lateinit var user: UserStore

    @Before
    fun open() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java, File(tmp.root, "t.db").path)
            .allowMainThreadQueries()
            .build()
        store = PlaylistStore(db)
        user = UserStore(db)
    }

    @After
    fun close() = db.close()

    private val playlist = listOf(
        Channel("Первый", "http://a", "Новости", number = 5),
        Channel("Второй", "http://b", "Новости"),
        Channel("Ёлки", "http://c", "Кино", archiveDays = 3),
        Channel("Матч", "http://d", "Спорт", number = 1),
        Channel("Первый", "http://a", "Спорт"), // the same stream in two categories
    )

    /** Every import recreates all channel and category rows. */
    private fun refresh(channels: List<Channel> = playlist) =
        store.import("k", userKey = "u") { import -> channels.forEach(import::channel) }

    private fun list(group: ChannelGroup = ChannelGroup.All, sort: ChannelSort = ChannelSort.PROVIDER, visibility: Visibility = Visibility.VISIBLE): List<ChannelItem> =
        runBlocking { loadPage { store.itemSource(ChannelFilter("k", group, sort = sort, visibility = visibility), 0) }.data }

    private fun names(group: ChannelGroup = ChannelGroup.All, sort: ChannelSort = ChannelSort.PROVIDER) = list(group, sort).map { it.channel.name }

    private fun cat(name: String) = ChannelGroup.Category(name)

    @Test
    fun favoritesInUserOrderSurviveRefresh() {
        refresh()
        user.setFavorite("http://a", true)
        user.setFavorite("http://d", true)
        assertEquals(listOf("Первый", "Матч"), names(ChannelGroup.Favorites)) // the duplicate stream listed once
        user.reorder("k", ChannelGroup.Favorites, listOf("http://d", "http://a"))
        refresh()
        assertEquals(listOf("Матч", "Первый"), names(ChannelGroup.Favorites))
        assertEquals(listOf(true, false, false, true, true), list().map { it.favorite })
        user.setFavorite("http://d", false)
        assertEquals(listOf("Первый"), names(ChannelGroup.Favorites))
        assertEquals(0, rowsOf("channel_state", "ckey = 'http://d'")) // nothing left to keep
    }

    @Test
    fun hideAndRenameChannelsAndCategories() {
        refresh()
        user.setHidden(listOf("http://b"), true)
        user.rename("http://c", "Ёлочки")
        refresh()
        assertEquals(listOf("Первый", "Ёлочки", "Матч", "Первый"), names())
        assertEquals(listOf("Второй"), list(visibility = Visibility.HIDDEN).map { it.channel.name })
        assertEquals(5, list(visibility = Visibility.ALL).size)
        assertEquals(1, runBlocking { store.categories("k").first() }.first { it.name == "Новости" }.count)

        // A hidden category hides its channels from «Все», not from the user's own lists.
        user.setFavorite("http://c", true)
        user.setCategoriesHidden("k", listOf("Кино"), true)
        user.renameCategory("k", "Спорт", "Футбол")
        refresh()
        val cats = runBlocking { store.categories("k").first() }
        assertEquals(listOf("Новости" to "Новости", "Спорт" to "Футбол"), cats.map { it.name to it.title })
        assertEquals(listOf(false, true, false), runBlocking { store.categories("k", withHidden = true).first() }.map { it.hidden })
        assertEquals(listOf("Первый", "Матч", "Первый"), names())
        assertEquals(listOf("Ёлочки"), names(ChannelGroup.Favorites))
        assertEquals(listOf("Ёлочки"), names(cat("Кино"))) // opened directly (management screen)
        // The category column with hidden ones, to bring them back (LIVE-012).
        val all = runBlocking { store.groups("k", withHidden = true).first() }.filter { it.group is ChannelGroup.Category }
        assertEquals(listOf("Новости" to false, "Кино" to true, "Футбол" to false), all.map { it.title to it.hidden })
        assertEquals(2, runBlocking { store.groups("k").first() }.count { it.group is ChannelGroup.Category })
    }

    /** LIVE-013/015: manual order on top of the provider's; new channels go after; reset brings the playlist back. */
    @Test
    fun manualOrderAndReset() {
        refresh()
        user.reorder("k", cat("Новости"), listOf("http://b", "http://a"))
        user.reorderCategories("k", listOf("Спорт", "Новости", "Кино"))
        user.rename("http://a", "Мой первый")
        user.setFavorite("http://a", true)
        refresh(playlist + Channel("Третий", "http://e", "Новости"))
        assertEquals(listOf("Второй", "Мой первый", "Третий"), names(cat("Новости")))
        assertEquals(listOf("Спорт", "Новости", "Кино"), runBlocking { store.categories("k").first() }.map { it.name })

        val edits = user.edits("k", "Новости")
        assertEquals(listOf("Первый → Мой первый"), edits.renamed)
        assertEquals(listOf("Новости"), edits.reordered)
        user.reset("k", "Новости")
        assertEquals(listOf("Первый", "Второй", "Третий"), names(cat("Новости")))
        assertEquals(listOf("Первый"), names(ChannelGroup.Favorites)) // favorites stay
        assertEquals(listOf("Спорт", "Новости", "Кино"), runBlocking { store.categories("k").first() }.map { it.name })

        assertTrue(!user.edits("k", null).isEmpty) // the category order
        user.reset("k", null)
        assertTrue(user.edits("k", null).isEmpty)
        assertEquals(listOf("Новости", "Кино", "Спорт"), runBlocking { store.categories("k").first() }.map { it.name })
    }

    /** LIVE-014 and LIVE-010 lists. */
    @Test
    fun collectionsRecentAndServiceGroups() {
        refresh()
        val id = user.createCollection("Моя")
        user.addToCollection(id, "http://d")
        user.addToCollection(id, "http://a")
        user.removeFromCollection(id, "http://a")
        user.watched("http://a", at = 1)
        user.watched("http://d", at = 2)
        refresh()
        assertEquals(listOf("Матч"), names(ChannelGroup.Collection(id)))
        assertEquals(listOf("Матч", "Первый"), names(ChannelGroup.Recent))
        assertEquals(listOf("http://d", "http://a"), runBlocking { user.recentKeys("k") }) // last and previous channel
        assertEquals(listOf("http://d" to 2L, "http://a" to 1L), runBlocking { user.recent("k") }.map { it.ckey to it.watchedAt }) // LIVE-020
        assertEquals(listOf("Ёлки"), names(ChannelGroup.Archive))

        val groups = runBlocking { store.groups("k").first() }
        assertEquals(
            listOf("Избранное" to 0, "Недавние" to 2, "С архивом" to 1, "Все каналы" to 5, "Моя" to 1, "Новости" to 2, "Кино" to 1, "Спорт" to 2),
            groups.map { it.title to it.count },
        )
        user.deleteCollection(id)
        assertEquals(0, rowsOf("collection_channel", "1"))
    }

    /** LIVE-016 sorting, LIVE-009 numbers, LIVE-017 position of a channel. */
    @Test
    fun sortingNumbersAndPosition() {
        refresh()
        user.setFavorite("http://d", true)
        assertEquals(listOf("Второй", "Ёлки", "Матч", "Первый", "Первый"), names(sort = ChannelSort.NAME)) // ё = е
        assertEquals(listOf(5, 2, 3, 1, 5), list().map { it.number })
        assertEquals(listOf("Матч", "Второй", "Ёлки", "Первый", "Первый"), names(sort = ChannelSort.NUMBER))
        assertEquals(listOf("Матч", "Первый", "Второй", "Ёлки", "Первый"), names(sort = ChannelSort.FAVORITES_FIRST))
        assertEquals(listOf("Ёлки", "Первый", "Второй", "Матч", "Первый"), names(sort = ChannelSort.ARCHIVE_FIRST))
        assertEquals("Матч", runBlocking { store.channelByNumber("k", 1) }?.name)
        assertEquals(null, runBlocking { store.channelByNumber("k", 99) })
        assertEquals(3, runBlocking { store.indexOf(ChannelFilter("k"), "http://d") })
        assertEquals(null, runBlocking { store.indexOf(ChannelFilter("k", cat("Кино")), "http://d") })
    }

    private fun rowsOf(table: String, where: String) =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }
}
