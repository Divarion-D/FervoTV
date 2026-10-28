package app.iptvplayer.core

import android.content.Context
import android.net.Uri
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.CategoryCount
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.CollectionRow
import app.iptvplayer.core.db.Edits
import app.iptvplayer.core.db.EpgResult
import app.iptvplayer.core.db.EpgSource
import app.iptvplayer.core.db.EpgStore
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.db.UserStore
import app.iptvplayer.core.epg.AutoMatch
import app.iptvplayer.core.epg.EpgEntry
import app.iptvplayer.core.epg.Suggestion
import app.iptvplayer.core.epg.unpacked
import app.iptvplayer.core.logos.LogoIndex
import app.iptvplayer.core.model.Airing
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.GuideRow
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Programme
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.net.httpStream
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.userKey
import app.iptvplayer.core.source.xtream.loadShortEpg
import app.iptvplayer.core.source.xtream.xtreamStreamId
import app.iptvplayer.core.source.xtream.xtreamXmltvUrl
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
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Stores the source and settings, imports the playlist into the database and serves it. The one entry point for the apps. */
class Repository(private val ctx: Context, db: AppDb = AppDb.get(ctx)) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val settingsPrefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val store = PlaylistStore(db)
    private val epg = EpgStore(db)
    private val user = UserStore(db)

    /** Imports (download, legacy-cache migration) write one at a time. */
    private val importLock = Mutex()

    /** Guide imports, one at a time (separately from playlists: a big XMLTV doesn't hold up a playlist refresh). */
    private val epgLock = Mutex()

    /** Guide refreshes tried in this run (source id → time), for the schedule as [PlaylistRefresh.waitMs] wants it. */
    private val epgAttempts = ConcurrentHashMap<Long, Long>()

    /** Short EPG asked in this run (channel key → time): a channel the server has no guide for isn't asked every time. */
    private val shortEpgAsked = ConcurrentHashMap<String, Long>()

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
            epgDays = settingsPrefs.getInt("epgDays", Defaults.EPG_DAYS).coerceIn(Defaults.EPG_DAYS_RANGE),
            epgShift = settingsPrefs.getInt("epgShift", Defaults.EPG_SHIFT),
            channelSort = settingsPrefs.getString("channelSort", null)
                ?.let { name -> ChannelSort.entries.find { it.name == name } }
                ?: Defaults.CHANNEL_SORT,
        )
        set(v) {
            settingsPrefs.edit()
                .putBoolean("autoLogos", v.autoLogos)
                .putString("logoSource", v.logoSource)
                .putString("playlistRefresh", v.playlistRefresh.name)
                .putInt("refreshDays", v.refreshDays)
                .putInt("epgDays", v.epgDays)
                .putInt("epgShift", v.epgShift)
                .putString("channelSort", v.channelSort.name)
                .apply()
        }

    /** The list the live section was left on (LIVE-017); the channel is the most recent of [recentKeys]. */
    var lastGroup: ChannelGroup
        get() {
            val v = settingsPrefs.getString("lastGroup", null).orEmpty()
            return when {
                v == "favorites" -> ChannelGroup.Favorites
                v == "recent" -> ChannelGroup.Recent
                v == "archive" -> ChannelGroup.Archive
                v.startsWith("category:") -> ChannelGroup.Category(v.removePrefix("category:"))
                v.startsWith("collection:") -> v.removePrefix("collection:").toLongOrNull()?.let(ChannelGroup::Collection) ?: ChannelGroup.All
                else -> ChannelGroup.All
            }
        }
        set(g) {
            val v = when (g) {
                ChannelGroup.All -> "all"
                ChannelGroup.Favorites -> "favorites"
                ChannelGroup.Recent -> "recent"
                ChannelGroup.Archive -> "archive"
                is ChannelGroup.Category -> "category:${g.name}"
                is ChannelGroup.Collection -> "collection:${g.id}"
            }
            settingsPrefs.edit().putString("lastGroup", v).apply()
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
            store.import(s.cacheKey(), userKey = s.userKey(), epgIndex = epg.index()) { import ->
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
                        input.reader().use { parseM3u(it, s.url, import::epgUrls, add) }
                    } else {
                        httpStream(s.url) { input -> input.reader().use { parseM3u(it, s.url, import::epgUrls, add) } }
                    }
                    is Source.Xtream -> loadXtream(s, { import.category(it) }, add)
                }
            }
        }
    }

    fun categories(s: Source, withHidden: Boolean = false): Flow<List<CategoryCount>> = store.categories(s.cacheKey(), withHidden)

    /** The category column: service lists, collections, categories (LIVE-010). */
    fun groups(s: Source): Flow<List<GroupCount>> = store.groups(s.cacheKey())

    fun channels(f: ChannelFilter): Flow<PagingData<Channel>> = store.channels(f)

    /** Live rows with now/next (EPG-006); the shift of [settings] is applied. */
    fun items(f: ChannelFilter): Flow<PagingData<ChannelItem>> = store.items(f, settings.epgShift)

    /** Programme grid rows for [from, to) (EPG-008). */
    fun guide(f: ChannelFilter, from: Long, to: Long): Flow<PagingData<GuideRow>> = store.guide(f, settings.epgShift, from, to)

    fun airing(s: Source, key: String): Flow<Airing> = store.airing(s.cacheKey(), key, settings.epgShift)

    suspend fun schedule(s: Source, key: String, from: Long, to: Long): List<Programme> =
        store.schedule(s.cacheKey(), key, settings.epgShift, from, to)

    suspend fun channelAt(f: ChannelFilter, index: Int): Channel? = store.channelAt(f, index)

    suspend fun channel(s: Source, key: String): ChannelItem? = store.channel(s.cacheKey(), key)

    suspend fun indexOf(f: ChannelFilter, key: String): Int? = store.indexOf(f, key)

    suspend fun channelByNumber(s: Source, number: Int): Channel? = store.channelByNumber(s.cacheKey(), number)

    // --- User data (LIVE-008, 012–018): blocking database writes, run off the main thread ---

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun setFavorite(key: String, on: Boolean) = io { user.setFavorite(key, on) }
    suspend fun setHidden(keys: List<String>, hidden: Boolean) = io { user.setHidden(keys, hidden) }
    suspend fun rename(key: String, name: String?) = io { user.rename(key, name) }
    suspend fun setCategoriesHidden(s: Source, names: List<String>, hidden: Boolean) = io { user.setCategoriesHidden(s.cacheKey(), names, hidden) }
    suspend fun renameCategory(s: Source, name: String, title: String?) = io { user.renameCategory(s.cacheKey(), name, title) }
    suspend fun reorderCategories(s: Source, names: List<String>) = io { user.reorderCategories(s.cacheKey(), names) }
    suspend fun reorder(s: Source, group: ChannelGroup, keys: List<String>) = io { user.reorder(s.cacheKey(), group, keys) }

    fun collections(): Flow<List<CollectionRow>> = user.collections()
    suspend fun createCollection(name: String): Long = io { user.createCollection(name) }
    suspend fun renameCollection(id: Long, name: String) = io { user.renameCollection(id, name) }
    suspend fun reorderCollections(ids: List<Long>) = io { user.reorderCollections(ids) }
    suspend fun deleteCollection(id: Long) = io { user.deleteCollection(id) }
    suspend fun addToCollection(id: Long, key: String) = io { user.addToCollection(id, key) }
    suspend fun removeFromCollection(id: Long, key: String) = io { user.removeFromCollection(id, key) }

    suspend fun edits(s: Source, category: String?): Edits = io { user.edits(s.cacheKey(), category) }
    suspend fun resetEdits(s: Source, category: String?) = io { user.reset(s.cacheKey(), category) }

    /** A channel started playing: "Недавние", the last channel (LIVE-017) and the previous one (LIVE-018). */
    suspend fun watched(key: String) = io { user.watched(key) }

    /** Keys of the last watched channels of [s], most recent first: [0] is the last channel, [1] the previous one. */
    suspend fun recentKeys(s: Source): List<String> = user.recentKeys(s.cacheKey())

    // --- Guide (EPG-001…007) ---

    fun epgSources(): Flow<List<EpgSource>> = epg.sources()

    /** "Взять из плейлиста" (EPG-001, SRC-008): the M3U header's addresses, the account's xmltv.php for Xtream. */
    suspend fun playlistEpgUrls(s: Source): List<String> = when (s) {
        is Source.M3u -> store.epgUrls(s.cacheKey())
        is Source.Xtream -> listOf(xtreamXmltvUrl(s))
    }

    /** Adds a guide source; it is downloaded by [refreshEpg] / [refreshDueEpg], not here (the user agrees first). */
    suspend fun addEpgSource(url: String): EpgSource = io { epg.addSource(url) }

    /** User-Agent, schedule, shift, priority of a source. */
    suspend fun updateEpgSource(s: EpgSource) = io { epg.updateSource(s) }

    suspend fun removeEpgSource(id: Long) = withContext(Dispatchers.IO) { epgLock.withLock { epg.removeSource(id) } }

    /**
     * Downloads and imports one guide source (EPG-003) in the background; screens keep the old guide until it ends.
     * The result (or the error) is also stored as the source's status.
     */
    suspend fun refreshEpg(id: Long): EpgResult = withContext(Dispatchers.IO) {
        val job = coroutineContext
        epgLock.withLock {
            val src = epg.source(id) ?: throw IOException("Источник телегида удалён")
            val now = System.currentTimeMillis()
            epgAttempts[id] = now
            try {
                httpStream(src.url, src.userAgent ?: Defaults.USER_AGENT) { input ->
                    epg.import(id, unpacked(input), settings.epgDays, now) { job.ensureActive() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                epg.attempted(id, now, "Ошибка: ${e.message ?: e}")
                throw e
            }
        }
    }

    /** How long until the next guide source is due (null = none by time), for a scheduling loop like the playlist's. */
    suspend fun epgWaitMs(): Long? {
        val now = System.currentTimeMillis()
        return epg.sourceList().mapNotNull { it.refresh.waitMs(it.savedAt ?: 0, epgAttempts[it.id] ?: 0, now, it.refreshDays) }.minOrNull()
    }

    /** Refreshes the guide sources that are due; a failing one doesn't stop the others. Returns the errors. */
    suspend fun refreshDueEpg(): List<Exception> {
        val now = System.currentTimeMillis()
        val errors = ArrayList<Exception>()
        for (src in epg.sourceList()) {
            val wait = src.refresh.waitMs(src.savedAt ?: 0, epgAttempts[src.id] ?: 0, now, src.refreshDays) ?: continue
            if (wait > 0) continue
            try {
                refreshEpg(src.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e
            }
        }
        return errors
    }

    /**
     * Xtream short EPG (EPG-002) of a channel no XMLTV covers: the current and next programmes from the server,
     * stored like any guide. Cached: asked again when less than an hour of it is left. Call it for the channel
     * the user is looking at (preview, player).
     */
    suspend fun loadShortEpg(s: Source, ch: Channel) = withContext(Dispatchers.IO) {
        if (s !is Source.Xtream || !epg.needsShortEpg(s.cacheKey(), ch.key)) return@withContext
        val now = System.currentTimeMillis()
        if (now - (shortEpgAsked[ch.key] ?: 0) < Defaults.SHORT_EPG_AHEAD_MS) return@withContext
        shortEpgAsked[ch.key] = now
        val list = loadShortEpg(s, xtreamStreamId(ch.key), Defaults.SHORT_EPG_LIMIT)
        if (list.isNotEmpty()) epgLock.withLock { epg.storeShortEpg(ch.key, list, now) }
    }

    /**
     * EPG-005 "Сопоставить автоматически" for the channels of [s] without a guide: sure pairs are bound, the rest is
     * returned for review. Guides that now have to provide programmes for more channels are downloaded again.
     */
    suspend fun autoMatchEpg(s: Source): AutoMatch {
        val (result, refetch) = withContext(Dispatchers.IO) { epgLock.withLock { epg.autoMatch(s.cacheKey()) } }
        refetch.forEach { refreshEpg(it) }
        return result
    }

    /** "Привязать телегид": all guide channels, the most similar to [name] first. */
    suspend fun epgSuggestions(name: String): List<Suggestion> = io { epg.suggestions(name) }

    /**
     * The user's guide bindings (EPG-004/005; null entry = back to automatic), [takeLogo] = the guide's logo too.
     * They win over automatic binding and survive refreshes. Guides missing the programmes are downloaded again.
     */
    suspend fun bindEpg(bindings: List<Pair<String, EpgEntry?>>, takeLogo: Boolean = false) {
        val refetch = withContext(Dispatchers.IO) {
            epgLock.withLock { epg.bind(bindings.map { (key, e) -> Triple(key, e?.xmltvId, e?.icon) }, takeLogo) }
        }
        refetch.forEach { refreshEpg(it) }
    }

    /** The channel's own guide shift in minutes (EPG-007), null = the source's / global one. */
    suspend fun setEpgShift(key: String, minutes: Int?) = io { user.setEpgShift(key, minutes) }

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
