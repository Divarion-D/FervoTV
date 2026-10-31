package app.iptvplayer.core

import android.content.Context
import android.net.Uri
import app.iptvplayer.core.backup.Backup
import app.iptvplayer.core.backup.readBackup
import app.iptvplayer.core.backup.writeBackup
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.CategoryCount
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.decodeGroup
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.db.CollectionRow
import app.iptvplayer.core.db.Edits
import app.iptvplayer.core.db.EpgResult
import app.iptvplayer.core.db.EpgSource
import app.iptvplayer.core.db.EpgStore
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.db.ProgrammeHit
import app.iptvplayer.core.db.UserStore
import app.iptvplayer.core.db.failedStatus
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
import app.iptvplayer.core.source.xtream.loadXtream
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import app.iptvplayer.core.db.LibraryResult
import app.iptvplayer.core.db.LibraryState
import app.iptvplayer.core.db.LibraryStore
import app.iptvplayer.core.db.LibraryUser
import app.iptvplayer.core.db.VodCollection
import app.iptvplayer.core.db.VodFilter
import app.iptvplayer.core.db.VodGroup
import app.iptvplayer.core.db.VodGroupCount
import app.iptvplayer.core.db.Watch
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.SeriesView
import app.iptvplayer.core.model.VodInfo
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.source.m3u.parseM3uEntries
import app.iptvplayer.core.source.m3uSection
import app.iptvplayer.core.source.xtream.loadXtreamEpisodes
import app.iptvplayer.core.source.xtream.loadXtreamLibrary
import app.iptvplayer.core.source.xtream.loadXtreamVodInfo
import app.iptvplayer.core.source.xtream.xtreamAccepted
import app.iptvplayer.core.source.xtream.xtreamOfM3uUrl
import app.iptvplayer.core.db.changes
import app.iptvplayer.core.db.searchText
import app.iptvplayer.core.home.ContinueItem
import app.iptvplayer.core.home.quickContinue
import app.iptvplayer.core.model.nextEpisode
import app.iptvplayer.core.search.SearchKind
import app.iptvplayer.core.search.SearchResults
import app.iptvplayer.core.search.ranked
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Stores the source and settings, imports the playlist into the database and serves it. The one entry point for the apps. */
class Repository(private val ctx: Context, private val db: AppDb = AppDb.get(ctx)) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val settingsPrefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val seriesPrefs = ctx.getSharedPreferences("series", Context.MODE_PRIVATE)
    private val store = PlaylistStore(db)
    private val epg = EpgStore(db)
    private val user = UserStore(db)
    private val library = LibraryStore(db)
    private val libraryUser = LibraryUser(db)

    /** Library imports, one at a time; taken after [importLock] when both are needed (M3U). */
    private val libraryLock = Mutex()

    /** The Xtream library import runs here, after [load] has returned (SRC-016). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var libraryJob: Job? = null
    private val libraryProgress = MutableStateFlow<LibraryState?>(null)

    /** SRC-016: the library import of the source — running (with a count), done (with what changed) or failed; null = none yet. */
    val libraryState: StateFlow<LibraryState?> = libraryProgress.asStateFlow()

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
            preview = settingsPrefs.getBoolean("preview", Defaults.PREVIEW),
            previewSound = settingsPrefs.getBoolean("previewSound", Defaults.PREVIEW_SOUND),
            rememberLive = settingsPrefs.getBoolean("rememberLive", Defaults.REMEMBER_LIVE),
            liveTiles = if (settingsPrefs.contains("liveTiles")) settingsPrefs.getBoolean("liveTiles", false) else null,
            liveGuide = settingsPrefs.getBoolean("liveGuide", Defaults.LIVE_GUIDE),
            guideEmpty = settingsPrefs.getBoolean("guideEmpty", Defaults.GUIDE_EMPTY),
            language = settingsPrefs.getString("language", null) ?: Defaults.LANGUAGE,
            resume = enumPref("resume", Defaults.RESUME),
            autoNext = settingsPrefs.getBoolean("autoNext", Defaults.AUTO_NEXT),
            movieSort = enumPref("movieSort", Defaults.VOD_SORT),
            seriesSort = enumPref("seriesSort", Defaults.VOD_SORT),
            movieList = settingsPrefs.getBoolean("movieList", false),
            seriesList = settingsPrefs.getBoolean("seriesList", false),
            startScreen = enumPref("startScreen", Defaults.START_SCREEN),
            homeRows = settingsPrefs.getString("homeRows", null).orEmpty(),
            homeChannels = settingsPrefs.getBoolean("homeChannels", Defaults.HOME_CHANNELS),
            homeGuide = settingsPrefs.getBoolean("homeGuide", Defaults.HOME_GUIDE),
            homeVisible = settingsPrefs.getBoolean("homeVisible", Defaults.HOME_VISIBLE),
            homePreview = settingsPrefs.getBoolean("homePreview", Defaults.HOME_PREVIEW),
            playerGiveUpSeconds = settingsPrefs.getInt("playerGiveUpSeconds", Defaults.PLAYER_GIVE_UP_SECONDS).coerceIn(0, 300),
            playerReconnectAttempts = settingsPrefs.getInt("playerReconnectAttempts", Defaults.PLAYER_RECONNECT_ATTEMPTS).coerceIn(0, 20),
            playerAudioLanguages = settingsPrefs.getString("playerAudioLanguages", "").orEmpty(),
            playerSubtitleLanguages = settingsPrefs.getString("playerSubtitleLanguages", "").orEmpty(),
            playerMatchFrameRate = settingsPrefs.getBoolean("playerMatchFrameRate", false),
            playerAudioOnly = settingsPrefs.getBoolean("playerAudioOnly", false),
            playerVolume = settingsPrefs.getInt("playerVolume", Defaults.PLAYER_VOLUME).coerceIn(0, 150),
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
                .putBoolean("preview", v.preview)
                .putBoolean("previewSound", v.previewSound)
                .putBoolean("rememberLive", v.rememberLive)
                .putBoolean("liveGuide", v.liveGuide)
                .putBoolean("guideEmpty", v.guideEmpty)
                .putString("language", v.language)
                .putString("resume", v.resume.name)
                .putBoolean("autoNext", v.autoNext)
                .putString("movieSort", v.movieSort.name)
                .putString("seriesSort", v.seriesSort.name)
                .putBoolean("movieList", v.movieList)
                .putBoolean("seriesList", v.seriesList)
                .putString("startScreen", v.startScreen.name)
                .putString("homeRows", v.homeRows)
                .putBoolean("homeChannels", v.homeChannels)
                .putBoolean("homeGuide", v.homeGuide)
                .putBoolean("homeVisible", v.homeVisible)
                .putBoolean("homePreview", v.homePreview)
                .putInt("playerGiveUpSeconds", v.playerGiveUpSeconds)
                .putInt("playerReconnectAttempts", v.playerReconnectAttempts)
                .putString("playerAudioLanguages", v.playerAudioLanguages)
                .putString("playerSubtitleLanguages", v.playerSubtitleLanguages)
                .putBoolean("playerMatchFrameRate", v.playerMatchFrameRate)
                .putBoolean("playerAudioOnly", v.playerAudioOnly)
                .putInt("playerVolume", v.playerVolume)
                .apply { if (v.liveTiles == null) remove("liveTiles") else putBoolean("liveTiles", v.liveTiles) }
                .apply()
        }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        settingsPrefs.getString(key, null)?.let { name -> enumValues<E>().find { it.name == name } } ?: default

    /**
     * BAK-011: the source came from a settings file without its Xtream password. True until the user sets the source
     * (setting it clears the flag with the rest of the source); the app asks for the password instead of loading.
     */
    val passwordMissing: Boolean get() = prefs.getBoolean("passwordMissing", false)

    /** The list the live section was left on (LIVE-017); the channel is the most recent of [recentKeys]. */
    var lastGroup: ChannelGroup
        get() = decodeGroup(settingsPrefs.getString("lastGroup", null))
        set(g) = settingsPrefs.edit().putString("lastGroup", g.encode()).apply()

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

    /**
     * Downloads (or reads) the playlist and streams it into the database, replacing the saved channels. Returns their
     * number. M3U films and series (SRC-010) go to the library in the same pass; an Xtream account's library is
     * imported after it in the background ([libraryState], SRC-011, SRC-016), the channels are usable meanwhile.
     */
    suspend fun load(s: Source): Int = withContext(Dispatchers.IO) {
        val job = coroutineContext
        libraryJob?.cancel() // an older library import (maybe of another source) gives way
        val count = importLock.withLock {
            when (s) {
                is Source.M3u -> libraryLock.withLock {
                    var library = 0
                    store.import(s.cacheKey(), userKey = s.userKey(), epgIndex = epg.index(), emptyOk = { library > 0 }) { import ->
                        val r = importLibrary(s) { lib ->
                            readM3u(s) { reader ->
                                parseM3uEntries(reader, s.url, import::epgUrls) { e ->
                                    job.ensureActive()
                                    when (val kind = m3uSection(e.type, e.channel.url, e.channel.name)) {
                                        null -> import.channel(e.channel)
                                        else -> lib.m3u(e.channel, kind)
                                    }
                                }
                            }
                        }
                        library = r.movies + r.series
                    }
                }
                is Source.Xtream -> store.import(s.cacheKey(), userKey = s.userKey(), epgIndex = epg.index()) { import ->
                    loadXtream(s, { import.category(it) }) { ch -> job.ensureActive(); import.channel(ch) }
                }
            }
        }
        if (s is Source.Xtream) startLibrary(s)
        count
    }

    private fun readM3u(s: Source.M3u, read: (java.io.Reader) -> Unit) {
        if (s.url.startsWith("content://")) {
            // Permission revoked, file moved or USB drive removed: the user has to pick it again.
            val input = try {
                ctx.contentResolver.openInputStream(Uri.parse(s.url))
            } catch (e: SecurityException) {
                null
            } catch (e: FileNotFoundException) {
                null
            } ?: throw CoreException(ErrorCode.FILE_LOST)
            input.reader().use(read)
        } else {
            httpStream(s.url) { input -> input.reader().use(read) }
        }
    }

    /** A library import with its state published; the caller holds [libraryLock]. */
    private fun importLibrary(s: Source, fill: (LibraryStore.Import) -> Unit): LibraryResult {
        libraryProgress.value = LibraryState.Running(0)
        try {
            val r = library.import(s.cacheKey(), s.userKey(), onProgress = { libraryProgress.value = LibraryState.Running(it) }, fill = fill)
            libraryProgress.value = LibraryState.Done(r)
            return r
        } catch (e: CancellationException) {
            libraryProgress.value = null
            throw e
        } catch (e: Exception) {
            libraryProgress.value = LibraryState.Failed(e)
            throw e
        }
    }

    private fun startLibrary(s: Source.Xtream) {
        libraryProgress.value = LibraryState.Running(0) // waiting for its turn counts as running
        libraryJob = background.launch {
            val job = coroutineContext
            try {
                libraryLock.withLock {
                    importLibrary(s) { lib -> loadXtreamLibrary(s, lib::category) { job.ensureActive(); lib.item(it) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // In [libraryState]; the channels stay as they are.
            }
        }
    }

    /**
     * SRC-012: an M3U link of an Xtream panel (`…/get.php?username=…&password=…`) whose account the server accepts
     * becomes that Xtream source; anything else (another link, rejected, no network) stays as it is, without an error.
     */
    suspend fun recognize(s: Source): Source = withContext(Dispatchers.IO) {
        val x = (s as? Source.M3u)?.let { xtreamOfM3uUrl(it.url) }
        if (x != null && xtreamAccepted(x)) x else s
    }

    fun categories(s: Source, withHidden: Boolean = false): Flow<List<CategoryCount>> = store.categories(s.cacheKey(), withHidden)

    /** The category column: service lists, collections, categories (LIVE-010). */
    fun groups(s: Source, withHidden: Boolean = false): Flow<List<GroupCount>> = store.groups(s.cacheKey(), withHidden)

    fun channels(f: ChannelFilter): Flow<PagingData<Channel>> = store.channels(f)

    /** Live rows with now/next (EPG-006); the shift of [settings] is applied. */
    fun items(f: ChannelFilter): Flow<PagingData<ChannelItem>> = store.items(f, settings.epgShift)

    /** Programme grid rows for [from, to) (EPG-008); the first page starts at row [initialKey]. */
    fun guide(f: ChannelFilter, from: Long, to: Long, initialKey: Int? = null): Flow<PagingData<GuideRow>> =
        store.guide(f, settings.epgShift, from, to, initialKey)

    /** EPG-010 "Поиск" in the grid: programmes of the list by title, from [from] on, soonest first (channel key, programme). */
    suspend fun findProgrammes(f: ChannelFilter, text: String, from: Long): List<ProgrammeHit> =
        store.findProgrammes(f, settings.epgShift, text, from, Defaults.GUIDE_SEARCH_LIMIT)

    fun airing(s: Source, key: String): Flow<Airing> = store.airing(s.cacheKey(), key, settings.epgShift)

    suspend fun schedule(s: Source, key: String, from: Long, to: Long): List<Programme> =
        store.schedule(s.cacheKey(), key, settings.epgShift, from, to)

    suspend fun channelAt(f: ChannelFilter, index: Int): Channel? = store.channelAt(f, index)

    suspend fun firstChannels(f: ChannelFilter, n: Int): List<ChannelItem> = store.first(f, n)

    suspend fun channel(s: Source, key: String): ChannelItem? = store.channel(s.cacheKey(), key)

    suspend fun indexOf(f: ChannelFilter, key: String): Int? = store.indexOf(f, key)

    suspend fun channelByNumber(s: Source, number: Int): Channel? = store.channelByNumber(s.cacheKey(), number)

    // --- User data (LIVE-008, 012–018): blocking database writes, run off the main thread ---

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun setFavorite(s: Source, key: String, on: Boolean) = io { user.setFavorite(s.cacheKey(), key, on) }
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

    /** A channel was watched long enough for "Недавние" (LIVE-010, see [app.iptvplayer.core.player.WatchTimer]). */
    suspend fun watched(key: String) = io { user.watched(key) }

    /** Keys of the channels of [s] in "Недавние", most recent first. */
    suspend fun recentKeys(s: Source): List<String> = user.recentKeys(s.cacheKey())

    /**
     * The last two channels started, however briefly, most recent first: [0] the last channel (LIVE-017), [1] the
     * previous one (LIVE-018). Kept with the source: another source starts without them.
     */
    val zapped: List<String> get() = prefs.getString("zapped", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    /** A channel started playing in full screen. */
    fun zap(key: String) {
        val list = zapped
        if (list.firstOrNull() != key) prefs.edit().putString("zapped", (listOf(key) + list.take(1)).joinToString("\n")).apply()
    }

    /** LIVE-020: the last watched channels of [s] with the time each was watched, most recent first. */
    suspend fun recent(s: Source): List<Pair<ChannelItem, Long>> =
        user.recent(s.cacheKey()).mapNotNull { r -> store.channel(s.cacheKey(), r.ckey)?.let { it to r.watchedAt } }

    /** [recent] as it changes (watching, a refresh, edits, «Удалить»): the home rows and the history (HOME-002/004/013). */
    fun recentFlow(s: Source): Flow<List<Pair<ChannelItem, Long>>> =
        db.changes("recent", "source", "channel_state").map { recent(s) }

    /** Number of channels of a list (HOME-008, HOME-014 counts). */
    suspend fun channelCount(f: ChannelFilter): Int = store.countOf(f)

    /** HOME-013 «Удалить» on a channel: it leaves the history and «Недавние». */
    suspend fun forgetChannels(keys: List<String>) = io { user.forgetRecent(keys) }

    // --- Global search (HOME-007…011) ---

    /**
     * HOME-007: one query across the source — channels, programmes on now or within [Defaults.SEARCH_AHEAD_MS]
     * (HOME-009), films and series; up to [limit] of each, best matches first ([ranked]), with counts (HOME-008).
     * The same word rules as the sections' own search (HOME-010); the kinds are looked up in parallel.
     */
    suspend fun search(s: Source, query: String, now: Long = System.currentTimeMillis(), limit: Int = Defaults.SEARCH_LIMIT): SearchResults {
        val q = query.trim()
        if (searchText(q).isBlank()) return SearchResults(q)
        val key = s.cacheKey()
        val shift = settings.epgShift
        val ch = ChannelFilter(key, search = q)
        fun vod(kind: VodKind) = VodFilter(key, kind, search = q)
        return coroutineScope {
            val channels = async(Dispatchers.IO) { store.first(ch, limit) }
            val channelCount = async(Dispatchers.IO) { store.countOf(ch) }
            val programmes = async(Dispatchers.IO) {
                store.findProgrammes(ChannelFilter(key), shift, q, now, limit, now + Defaults.SEARCH_AHEAD_MS)
            }
            val movies = async(Dispatchers.IO) { library.first(vod(VodKind.MOVIE), limit) }
            val movieCount = async(Dispatchers.IO) { library.count(vod(VodKind.MOVIE)) }
            val series = async(Dispatchers.IO) { library.first(vod(VodKind.SERIES), limit) }
            val seriesCount = async(Dispatchers.IO) { library.count(vod(VodKind.SERIES)) }
            val p = programmes.await()
            SearchResults(
                q,
                channels.await().ranked(q) { it.channel.name },
                p,
                movies.await().ranked(q) { it.title },
                series.await().ranked(q) { it.title },
                mapOf(
                    SearchKind.CHANNELS to channelCount.await(), SearchKind.PROGRAMMES to p.size,
                    SearchKind.MOVIES to movieCount.await(), SearchKind.SERIES to seriesCount.await(),
                ),
            )
        }
    }

    /** HOME-011: recent queries, the latest first (kept with the settings, not in the settings file). */
    val searches: List<String>
        get() = settingsPrefs.getString("searches", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        putSearches(listOf(q) + searches.filter { !it.equals(q, ignoreCase = true) })
    }

    fun forgetSearch(query: String) = putSearches(searches - query)

    fun clearSearches() = putSearches(emptyList())

    private fun putSearches(list: List<String>) =
        settingsPrefs.edit().putString("searches", list.take(Defaults.SEARCH_RECENT).joinToString("\n")).apply()

    // --- Library: films and series (SRC-010, SRC-011, VOD-001…012) ---

    /** VOD-001, UI-007: the library sections [s] has (a section without films or series isn't shown). */
    fun libraryKinds(s: Source): Flow<Set<VodKind>> = library.kinds(s.cacheKey())

    private val vodInfos = ConcurrentHashMap<String, VodInfo>()

    /**
     * VOD-004: details the catalogue doesn't carry (duration, cast…) — Xtream asks get_vod_info once per run; M3U has
     * none (null). Errors throw; the card shows what it has.
     */
    suspend fun vodInfo(s: Source, movie: VodItem): VodInfo? = withContext(Dispatchers.IO) {
        if (s !is Source.Xtream || movie.kind != VodKind.MOVIE) return@withContext null
        vodInfos[movie.key] ?: loadXtreamVodInfo(s, movie.key).also { vodInfos[movie.key] = it }
    }

    /** VOD-006: episode order and hiding watched ones, per series (kept with the settings, not in the settings file). */
    fun seriesView(key: String): SeriesView {
        val v = seriesPrefs.getString(key, null) ?: return SeriesView()
        return SeriesView(newestFirst = 'n' in v, hideWatched = 'h' in v)
    }

    fun setSeriesView(key: String, v: SeriesView) {
        val code = (if (v.newestFirst) "n" else "") + (if (v.hideWatched) "h" else "")
        seriesPrefs.edit().apply { if (code.isEmpty()) remove(key) else putString(key, code) }.apply()
    }

    /** When the library of [s] was imported; null = not yet. */
    suspend fun librarySavedAt(s: Source): Long? = library.savedAt(s.cacheKey())

    /** The category column of a section: «Избранное», «Недавние», «Все», collections, categories (VOD-001). */
    fun vodGroups(s: Source, kind: VodKind, withHidden: Boolean = false): Flow<List<VodGroupCount>> = library.groups(s.cacheKey(), kind, withHidden)

    fun vodCategories(s: Source, kind: VodKind, withHidden: Boolean = false): Flow<List<CategoryCount>> =
        library.categories(s.cacheKey(), kind, withHidden)

    /** A catalogue, paged (VOD-001, VOD-003); search by word starts like the channels (HOME-010). */
    fun vod(f: VodFilter): Flow<PagingData<VodItem>> = library.items(f)

    /** Number of items of a list (HOME-008 counts). */
    suspend fun vodCount(f: VodFilter): Int = io { library.count(f) }

    /** The first [n] items of a list (HOME-007: up to 500 of each kind). */
    suspend fun firstVod(f: VodFilter, n: Int): List<VodItem> = library.first(f, n)

    suspend fun vodItem(s: Source, kind: VodKind, key: String): VodItem? = library.item(s.cacheKey(), kind, key)

    suspend fun vodIndexOf(f: VodFilter, key: String): Int? = library.indexOf(f, key)

    /**
     * Episodes of [series] with their progress, specials (season 0) last. Xtream loads them on first opening
     * (get_series_info) and again when older than [Defaults.EPISODES_MAX_AGE_MS] or [refresh]; a failed reload
     * keeps the stored ones, a failed first load throws (HttpException carries the server's code, SRC-011).
     */
    suspend fun episodes(s: Source, series: VodItem, refresh: Boolean = false): List<Episode> = withContext(Dispatchers.IO) {
        val key = s.cacheKey()
        if (s is Source.Xtream) {
            val at = library.episodesAt(key, series.key)
            if (refresh || at == null || System.currentTimeMillis() - at > Defaults.EPISODES_MAX_AGE_MS) {
                try {
                    library.storeEpisodes(key, series.key, loadXtreamEpisodes(s, series.key, series.title))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (at == null) throw e
                }
            }
        }
        library.episodes(key, series.key)
    }

    /** An episode by key (from history or the home row), if stored. */
    suspend fun episode(s: Source, key: String): Episode? = library.episode(s.cacheKey(), key)

    suspend fun setVodFavorite(key: String, on: Boolean) = io { libraryUser.setFavorite(key, on) }
    suspend fun setVodHidden(keys: List<String>, hidden: Boolean) = io { libraryUser.setHidden(keys, hidden) }
    suspend fun renameVod(key: String, name: String?) = io { libraryUser.rename(key, name) }
    suspend fun setVodCategoriesHidden(s: Source, kind: VodKind, names: List<String>, hidden: Boolean) =
        io { libraryUser.setCategoriesHidden(s.cacheKey(), kind, names, hidden) }
    suspend fun renameVodCategory(s: Source, kind: VodKind, name: String, title: String?) = io { libraryUser.renameCategory(s.cacheKey(), kind, name, title) }
    suspend fun reorderVodCategories(s: Source, kind: VodKind, names: List<String>) = io { libraryUser.reorderCategories(s.cacheKey(), kind, names) }
    suspend fun reorderVod(s: Source, kind: VodKind, group: VodGroup, keys: List<String>) = io { libraryUser.reorder(s.cacheKey(), kind, group, keys) }

    fun vodCollections(kind: VodKind): Flow<List<VodCollection>> = libraryUser.collections(kind)
    suspend fun createVodCollection(kind: VodKind, name: String): Long = io { libraryUser.createCollection(kind, name) }
    suspend fun renameVodCollection(id: Long, name: String) = io { libraryUser.renameCollection(id, name) }
    suspend fun reorderVodCollections(ids: List<Long>) = io { libraryUser.reorderCollections(ids) }
    suspend fun deleteVodCollection(id: Long) = io { libraryUser.deleteCollection(id) }
    suspend fun addToVodCollection(id: Long, key: String) = io { libraryUser.addToCollection(id, key) }
    suspend fun removeFromVodCollection(id: Long, key: String) = io { libraryUser.removeFromCollection(id, key) }

    suspend fun vodEdits(s: Source, kind: VodKind, category: String?): Edits = io { libraryUser.edits(s.cacheKey(), kind, category) }
    suspend fun resetVodEdits(s: Source, kind: VodKind, category: String?) = io { libraryUser.reset(s.cacheKey(), kind, category) }

    // --- Watching (VOD-008, VOD-010, HOME-002, HOME-013) ---

    /** VOD-010: where a film stopped; near the end it becomes watched instead (VOD-008). Call it periodically too. */
    suspend fun savePosition(movie: VodItem, position: Long, duration: Long) = io { libraryUser.savePosition(movie.watchRow(), position, duration) }

    suspend fun savePosition(series: VodItem, episode: Episode, position: Long, duration: Long) =
        io { libraryUser.savePosition(episode.watchRow(series), position, duration) }

    /** VOD-008: mark a film watched / not watched. */
    suspend fun setWatched(movie: VodItem, watched: Boolean) = io { libraryUser.setWatched(listOf(movie.watchRow()), watched) }

    /** VOD-008: mark episodes (one, or a season's) watched / not watched. */
    suspend fun setWatched(series: VodItem, episodes: List<Episode>, watched: Boolean) =
        io { libraryUser.setWatched(episodes.map { it.watchRow(series) }, watched) }

    suspend fun watch(key: String): Watch? = libraryUser.watch(key)

    /** HOME-013: everything watched, the latest first; [kind] null = all. */
    fun history(kind: VodKind? = null): Flow<PagingData<Watch>> =
        Pager(PagingConfig(pageSize = Defaults.VOD_PAGE_SIZE)) { libraryUser.history(kind) }.flow

    /** HOME-002: started films and the last episode of started series; [source] limits to playable, visible titles. */
    fun unfinished(limit: Int, source: Source? = null): Flow<List<Watch>> = libraryUser.unfinished(limit, source?.cacheKey())

    /** HOME-013 «Удалить» / «Очистить историю»: with their marks and positions; clearing forgets the recent channels too. */
    suspend fun forget(keys: List<String>) = io { libraryUser.forget(keys) }
    suspend fun clearHistory() = io {
        db.runInTransaction {
            libraryUser.clearHistory()
            user.clearRecent()
        }
    }

    /** HOME-013 screen: the latest films and episodes watched, as they change. */
    fun historyList(limit: Int = Defaults.HISTORY_LIMIT): Flow<List<Watch>> = libraryUser.latest(limit)

    /**
     * HOME-006: what «Продолжить» starts now ([quickContinue]); follows watching. The episode after a finished one is
     * looked up among the stored episodes only (no network).
     */
    fun quickContinue(s: Source): Flow<ContinueItem?> =
        combine(libraryUser.unfinished(1, s.cacheKey()), libraryUser.lastFinishedEpisode(s.cacheKey()), recentFlow(s)) { unfinished, finished, channels ->
            val next = finished?.seriesKey?.let { series ->
                val list = library.episodes(s.cacheKey(), series)
                list.find { it.key == finished.vkey }?.let { nextEpisode(list, it) }
            }
            quickContinue(unfinished.firstOrNull(), finished, next, channels.firstOrNull())
        }.distinctUntilChanged()

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
            val src = epg.source(id) ?: throw CoreException(ErrorCode.EPG_SOURCE_GONE)
            val now = System.currentTimeMillis()
            epgAttempts[id] = now
            try {
                httpStream(src.url, src.userAgent ?: Defaults.USER_AGENT) { input ->
                    epg.import(id, unpacked(input), settings.epgDays, now) { job.ensureActive() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                epg.attempted(id, now, failedStatus(e))
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

    // --- Settings file (BAK-011) ---

    /** What a settings file holds, as it is now: the source, settings, guide sources and every channel edit. */
    suspend fun backup(): Backup = io {
        Backup(
            source = source, settings = settings, guides = epg.sourceRows(), channels = user.dump(),
            library = libraryUser.dump(), watched = libraryUser.watched(),
        )
    }

    /** Writes the settings file to [out] (and closes it); the Xtream password only [withPassword]. */
    suspend fun saveSettingsFile(out: OutputStream, withPassword: Boolean, appVersion: String) {
        val b = backup()
        io { writeBackup(out, b, withPassword, appVersion, System.currentTimeMillis()) }
    }

    /** Reads a settings file; nothing changes yet. A foreign, damaged or newer file throws [CoreException] (BACKUP_…). */
    suspend fun readSettingsFile(input: InputStream): Backup = io { input.use(::readBackup) }

    /**
     * Replaces the current settings with those of [b] (parts it doesn't have stay). Guide sources are replaced (their
     * guides download again), channel edits replaced in one transaction. The caller then refreshes the source.
     */
    suspend fun restore(b: Backup) = withContext(Dispatchers.IO) {
        epgLock.withLock {
            db.runInTransaction {
                b.guides?.let(epg::replaceSources)
                b.channels?.let(user::replace)
                b.library?.let(libraryUser::replace)
                b.watched?.let(libraryUser::replaceWatched)
                epg.rebind()
            }
        }
        b.settings?.let { settings = it }
        if (b.channels != null) lastGroup = ChannelGroup.All // a collection id it named is gone
        b.source?.let {
            source = it
            if (b.passwordMissing) prefs.edit().putBoolean("passwordMissing", true).apply()
        }
    }

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

private fun VodItem.watchRow() = Watch(key, VodKind.MOVIE, title = title, image = poster, watchedAt = 0)

private fun Episode.watchRow(series: VodItem) =
    Watch(key, VodKind.SERIES, series.key, season, episode, series.title, title, image ?: series.poster, watchedAt = 0)

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
