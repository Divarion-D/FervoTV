package app.iptvplayer.core

import android.content.Context
import android.net.Uri
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.CategoryCount
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.logos.LogoIndex
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.net.httpStream
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.readCacheChannels
import app.iptvplayer.core.source.readCacheHeader
import app.iptvplayer.core.source.m3u.parseM3u
import app.iptvplayer.core.source.xtream.loadXtream
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Stores the source and settings, imports the playlist into the database and serves it. The one entry point for the apps. */
class Repository(private val ctx: Context, db: AppDb = AppDb.get(ctx)) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val settingsPrefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val store = PlaylistStore(db)

    /** Imports (download, legacy-cache migration) write one at a time. */
    private val importLock = Mutex()

    var source: Source?
        get() = when (prefs.getString("type", null)) {
            "m3u" -> Source.M3u(prefs.getString("url", "")!!)
            "xtream" -> Source.Xtream(
                prefs.getString("server", "")!!,
                prefs.getString("user", "")!!,
                prefs.getString("pass", "")!!,
                prefs.getBoolean("hls", false),
            )
            else -> null
        }
        set(v) {
            val e = prefs.edit().clear()
            when (v) {
                is Source.M3u -> e.putString("type", "m3u").putString("url", v.url)
                is Source.Xtream -> e.putString("type", "xtream").putString("server", v.server)
                    .putString("user", v.user).putString("pass", v.pass).putBoolean("hls", v.hls)
                null -> {}
            }
            e.apply()
        }

    var settings: Settings
        get() = Settings(
            autoLogos = settingsPrefs.getBoolean("autoLogos", Defaults.AUTO_LOGOS),
            logoSource = settingsPrefs.getString("logoSource", null) ?: Defaults.LOGO_SOURCE,
            playlistRefresh = settingsPrefs.getString("playlistRefresh", null)
                ?.let { name -> PlaylistRefresh.entries.find { it.name == name } }
                ?: Defaults.PLAYLIST_REFRESH,
            refreshDays = settingsPrefs.getInt("refreshDays", Defaults.PLAYLIST_REFRESH_DAYS),
        )
        set(v) {
            settingsPrefs.edit()
                .putBoolean("autoLogos", v.autoLogos)
                .putString("logoSource", v.logoSource)
                .putString("playlistRefresh", v.playlistRefresh.name)
                .putInt("refreshDays", v.refreshDays)
                .apply()
        }

    /**
     * Should "Что нового" be offered for [version]? Once per new version, and only after an update ([updated]:
     * the package was replaced, not freshly installed). Remembers [version] as seen.
     */
    fun offerChangelog(version: String, updated: Boolean): Boolean {
        val offer = changelogDue(settingsPrefs.getString("seenVersion", null), version, updated)
        settingsPrefs.edit().putString("seenVersion", version).apply()
        return offer
    }

    /**
     * When the saved channels of [s] were downloaded; null if nothing is saved for this source.
     * The first call after an update from v0.1 moves its `playlist.tsv` into the database.
     */
    suspend fun savedAt(s: Source): Long? = withContext(Dispatchers.IO) {
        importLock.withLock { migrateLegacyCache(ctx.filesDir, store) }
        store.savedAt(s.cacheKey())
    }

    /** Downloads (or reads) the playlist and streams it into the database, replacing the saved channels. Returns their number. */
    suspend fun load(s: Source): Int = withContext(Dispatchers.IO) {
        val job = coroutineContext
        importLock.withLock {
            store.import(s.cacheKey()) { import ->
                val add = { ch: Channel -> job.ensureActive(); import.channel(ch) }
                when (s) {
                    is Source.M3u -> if (s.url.startsWith("content://")) {
                        // Permission revoked, file moved or USB drive removed: the user has to pick it again.
                        val input = try {
                            ctx.contentResolver.openInputStream(Uri.parse(s.url))
                        } catch (e: SecurityException) {
                            null
                        } catch (e: FileNotFoundException) {
                            null
                        } ?: throw IOException(FILE_LOST)
                        input.reader().use { parseM3u(it, s.url, add) }
                    } else {
                        httpStream(s.url) { input -> input.reader().use { parseM3u(it, s.url, add) } }
                    }
                    is Source.Xtream -> loadXtream(s, { import.category(it) }, add)
                }
            }
        }
    }

    fun categories(s: Source): Flow<List<CategoryCount>> = store.categories(s.cacheKey())

    fun channels(f: ChannelFilter): Flow<PagingData<Channel>> = store.channels(f)

    suspend fun channelAt(f: ChannelFilter, index: Int): Channel? = store.channelAt(f, index)

    /**
     * Matches the saved channels of [s] against the logo database [logoSource] (or clears the matches when null)
     * and stores the result next to the channels. Returns (channels with a logo found, all channels).
     */
    suspend fun matchLogos(s: Source, logoSource: String?): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val key = s.cacheKey()
        val job = coroutineContext
        val index = logoSource?.let { LogoIndex.load(ctx, it, store.allChannels(key)) }
        store.applyAutoLogos(key) { ch -> job.ensureActive(); index?.find(ch) }
    }
}

internal const val FILE_LOST = "Нет доступа к файлу плейлиста (его переместили, удалили или отключили накопитель). " +
    "Выберите файл заново: «Источник» → «Выбрать файл»"

/** [seen] = the version the user last ran (null: none recorded, e.g. updated from 0.1, which didn't record it). */
internal fun changelogDue(seen: String?, current: String, updated: Boolean): Boolean =
    if (seen == null) updated else seen != current

/**
 * v0.1 kept the last playlist in `filesDir/playlist.tsv`. Moves it into the database (keeping its source key and
 * download time, so offline start and the refresh schedule carry on) and deletes the file. A damaged file is
 * deleted too: v0.1 would have ignored it as well.
 */
internal fun migrateLegacyCache(filesDir: File, store: PlaylistStore) {
    val file = File(filesDir, "playlist.tsv")
    if (file.exists()) {
        runCatching {
            file.bufferedReader().use { r ->
                val header = readCacheHeader(r) ?: return@use
                store.import(header.sourceKey, header.savedAt) { import -> readCacheChannels(r, import::channel) }
            }
        }
        file.delete()
    }
    File(filesDir, "playlist.tsv.tmp").delete()
}
