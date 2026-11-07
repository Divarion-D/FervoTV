package app.iptvplayer

import androidx.room.Room
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.LibraryStore
import app.iptvplayer.core.db.LibraryUser
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.db.VodFilter
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.source.cacheKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UiFixturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDb

    @Before
    fun open() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java, File(tmp.root, "ui.db").path)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun seedRejectsNonTestPackageBeforeChangingData() = runBlocking {
        PlaylistStore(db).import("sentinel", savedAt = 42) { it.channel(Channel("Existing", "http://existing", "Test")) }

        assertThrows(IllegalArgumentException::class.java) {
            UiFixtures.seed("io.github.divarion_d.fervotv.debug", db, "http://127.0.0.1:8765")
        }

        assertEquals(42L, PlaylistStore(db).savedAt("sentinel"))
    }

    @Test
    fun seedRejectsUnsafeBaseUrlBeforeChangingData() = runBlocking {
        PlaylistStore(db).import("sentinel", savedAt = 42) { it.channel(Channel("Existing", "http://existing", "Test")) }

        listOf("file:///tmp/media", "http://user:pass@127.0.0.1:8765", "http://127.0.0.1:8765?token=x").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { UiFixtures.seed(UI_TEST_PACKAGE, db, url) }
        }

        assertEquals(42L, PlaylistStore(db).savedAt("sentinel"))
    }

    @Test
    fun seedIsRepeatableAndProvidesLiveVodEpisodesAndProgress() {
        val source = UiFixtures.seed(UI_TEST_PACKAGE, db, "http://127.0.0.1:8765/")
        val first = snapshot(source)

        assertEquals(source, UiFixtures.seed(UI_TEST_PACKAGE, db, "http://127.0.0.1:8765"))
        assertEquals(first, snapshot(source))
        assertEquals(24, first.channelKeys.size)
        assertEquals(1, first.movieKeys.size)
        assertEquals(1, first.seriesKeys.size)
        assertEquals(4, first.episodeKeys.size)
        assertEquals(30_000L, first.moviePosition)
        assertEquals(15_000L, first.episodePosition)
    }

    private fun snapshot(source: Source.M3u): Snapshot = runBlocking {
        val key = source.cacheKey()
        val playlist = PlaylistStore(db)
        val library = LibraryStore(db)
        val movies = library.first(VodFilter(key, VodKind.MOVIE), 10)
        val series = library.first(VodFilter(key, VodKind.SERIES), 10)
        val episodes = library.episodes(key, series.single().key)
        val user = LibraryUser(db)
        Snapshot(
            channelKeys = playlist.first(ChannelFilter(key), 100).map { it.channel.key },
            movieKeys = movies.map { it.key },
            seriesKeys = series.map { it.key },
            episodeKeys = episodes.map { it.key },
            moviePosition = user.watch(movies.single().key)?.position,
            episodePosition = user.watch(episodes.first().key)?.position,
        )
    }

    private data class Snapshot(
        val channelKeys: List<String>,
        val movieKeys: List<String>,
        val seriesKeys: List<String>,
        val episodeKeys: List<String>,
        val moviePosition: Long?,
        val episodePosition: Long?,
    )
}
