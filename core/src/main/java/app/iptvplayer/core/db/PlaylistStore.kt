package app.iptvplayer.core.db

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.map
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.epg.EpgIndex
import app.iptvplayer.core.epg.nonOverlapping
import app.iptvplayer.core.model.Airing
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.GuideRow
import app.iptvplayer.core.model.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Which list of the live section (LIVE-010): service lists, a user collection or a provider category. */
sealed interface ChannelGroup {
    data object All : ChannelGroup
    data object Favorites : ChannelGroup
    data object Recent : ChannelGroup
    data object Archive : ChannelGroup
    /** By the provider's name, not the user's one. */
    data class Category(val name: String) : ChannelGroup
    data class Collection(val id: Long) : ChannelGroup
}

/** As a string for saved state and settings; [decodeGroup] reads it back. */
fun ChannelGroup.encode(): String = when (this) {
    ChannelGroup.All -> "all"
    ChannelGroup.Favorites -> "favorites"
    ChannelGroup.Recent -> "recent"
    ChannelGroup.Archive -> "archive"
    is ChannelGroup.Category -> "category:$name"
    is ChannelGroup.Collection -> "collection:$id"
}

/** Unknown or missing → [ChannelGroup.All]. */
fun decodeGroup(v: String?): ChannelGroup = when {
    v == "favorites" -> ChannelGroup.Favorites
    v == "recent" -> ChannelGroup.Recent
    v == "archive" -> ChannelGroup.Archive
    v == null -> ChannelGroup.All
    v.startsWith("category:") -> ChannelGroup.Category(v.removePrefix("category:"))
    v.startsWith("collection:") -> v.removePrefix("collection:").toLongOrNull()?.let(ChannelGroup::Collection) ?: ChannelGroup.All
    else -> ChannelGroup.All
}

/** Hidden channels (LIVE-012) are left out of the screens; the management screen asks for them. */
enum class Visibility { VISIBLE, HIDDEN, ALL }

/**
 * What a channel list shows: the saved channels of a source, one of its lists, a search, an order.
 * Refers to the source by key and to the category by name, not by row ids: a background refresh replaces the rows,
 * and the same filter (and the Pager built on it) keeps working on the new ones.
 */
data class ChannelFilter(
    val sourceKey: String,
    val group: ChannelGroup = ChannelGroup.All,
    val search: String = "",
    val sort: ChannelSort = ChannelSort.PROVIDER,
    val visibility: Visibility = Visibility.VISIBLE,
    /** Only channels with a guide (EPG-010 "Показывать каналы без программы" off). */
    val guideOnly: Boolean = false,
)

/** A programme found in the grid (EPG-010) and the channel it is on. */
data class ProgrammeHit(val key: String, val channel: String, val programme: Programme)

/** A list with its count for the category column (LIVE-001, LIVE-010); [hidden] = a hidden category (LIVE-012). */
data class GroupCount(val group: ChannelGroup, val title: String, val count: Int, val hidden: Boolean = false)

/** The saved channels of playlist sources: streaming import, lists for the screens. */
class PlaylistStore(private val db: AppDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.playlist()
    private val epg = EpgStore(db)

    /**
     * Imports a source: [fill] hands categories and channels to [Import] as it reads them, they are written in
     * transactions of [Defaults.IMPORT_BATCH_SIZE]. Screens keep showing the previous channels until the end, when
     * the new ones replace them in one transaction. A failure (or cancellation) drops what was written, so an empty
     * or broken answer never replaces good data. One import at a time (the caller serializes them).
     * [userKey] = `Source.userKey()`, the prefix of category keys; [epgIndex] binds the channels to the guide as
     * they are written. Blocking; returns the number of channels.
     */
    fun import(
        sourceKey: String,
        savedAt: Long = System.currentTimeMillis(),
        userKey: String = sourceKey,
        epgIndex: EpgIndex = epg.index(),
        fill: (Import) -> Unit,
    ): Int {
        dao.unfinishedSources().forEach(dao::deleteSource) // left by a killed process
        val id = dao.insertSource(SourceRow(sourceKey = sourceKey, savedAt = null))
        try {
            val import = Import(id, userKey, epgIndex)
            fill(import)
            import.flush()
            // Almost always a broken/blocked response rather than a real empty playlist.
            if (import.count == 0) throw IOException("Плейлист пуст")
            db.runInTransaction {
                dao.complete(id, savedAt, import.headerEpgUrls.joinToString("\n"))
                dao.otherSources(id).forEach(dao::deleteSource)
            }
            return import.count
        } catch (e: Throwable) {
            dao.deleteSource(id)
            throw e
        }
    }

    inner class Import internal constructor(private val sourceId: Long, private val userKey: String, private val epgIndex: EpgIndex) {
        private val categories = HashMap<String, Long>()
        private val batch = ArrayList<ChannelRow>(Defaults.IMPORT_BATCH_SIZE)
        internal val headerEpgUrls = LinkedHashSet<String>()
        var count = 0
            private set

        /** Registers a category ahead of its channels, to keep the provider's order (Xtream). */
        fun category(name: String): Long = categories.getOrPut(name) {
            dao.insertCategory(CategoryRow(sourceId = sourceId, name = name, position = categories.size, ckey = "$userKey|$name"))
        }

        /** XMLTV addresses of the playlist header (SRC-008). */
        fun epgUrls(urls: List<String>) {
            headerEpgUrls += urls
        }

        fun channel(ch: Channel) {
            batch += ChannelRow(
                sourceId = sourceId, categoryId = category(ch.group), position = count++,
                name = ch.name, url = ch.url, logo = ch.logo, tvgId = ch.tvgId,
                ckey = ch.key, number = ch.number, archiveDays = ch.archiveDays, epgId = epgIndex.find(ch),
            )
            if (batch.size >= Defaults.IMPORT_BATCH_SIZE) flush()
        }

        internal fun flush() {
            if (batch.isEmpty()) return
            db.runInTransaction {
                val ids = dao.insertChannels(batch)
                dao.insertFts(ids.mapIndexed { i, rowId -> ChannelFts(rowId, searchText(batch[i].name)) })
            }
            batch.clear()
        }
    }

    /** When the saved channels of this source were downloaded; null = nothing saved. */
    suspend fun savedAt(sourceKey: String): Long? = dao.savedAt(sourceKey)

    /** XMLTV addresses from the saved playlist's header (EPG-001 "Взять из плейлиста"). */
    suspend fun epgUrls(sourceKey: String): List<String> = dao.epgUrls(sourceKey)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    /** Provider categories in the user's order with visible-channel counts; [withHidden] for the management screen. */
    fun categories(sourceKey: String, withHidden: Boolean = false): Flow<List<CategoryCount>> = dao.categories(sourceKey, withHidden)

    /**
     * The category column (LIVE-010): "Избранное", "Недавние", "С архивом", "Все каналы", the user's collections,
     * then the provider's categories. Empty service lists are left out, except "Избранное" (it shows a hint).
     * [withHidden] = hidden categories too (marked), to bring them back.
     */
    fun groups(sourceKey: String, withHidden: Boolean = false): Flow<List<GroupCount>> {
        val service = db.changes(*SERVICE_TABLES).map {
            withContext(Dispatchers.IO) {
                listOf(
                    GroupCount(ChannelGroup.Favorites, "Избранное", count(ChannelFilter(sourceKey, ChannelGroup.Favorites))),
                    GroupCount(ChannelGroup.Recent, "Недавние", count(ChannelFilter(sourceKey, ChannelGroup.Recent))),
                    GroupCount(ChannelGroup.Archive, "С архивом", count(ChannelFilter(sourceKey, ChannelGroup.Archive))),
                    GroupCount(ChannelGroup.All, "Все каналы", count(ChannelFilter(sourceKey))),
                ).filter { it.group == ChannelGroup.Favorites || it.count > 0 }
            }
        }
        val collections = combine(db.user().collections(), db.changes(*SERVICE_TABLES)) { list, _ ->
            withContext(Dispatchers.IO) {
                list.map { GroupCount(ChannelGroup.Collection(it.id), it.name, count(ChannelFilter(sourceKey, ChannelGroup.Collection(it.id)))) }
            }
        }
        return combine(service, collections, categories(sourceKey, withHidden)) { s, c, cats ->
            s + c + cats.map { GroupCount(ChannelGroup.Category(it.name), it.title, it.count, it.hidden) }
        }.distinctUntilChanged()
    }

    private fun count(f: ChannelFilter): Int = dao.count(f.query("COUNT(*)", order = false))

    /** Channels of the list (placeholders on: the count is known up front and the scroll position survives a refresh). */
    fun channels(f: ChannelFilter): Flow<PagingData<Channel>> = items(f, 0, emptyFlow()).map { data -> data.map { it.channel } }

    /**
     * Rows of the live list with what's on now and next (EPG-006): one query for the page's channels, one for the
     * page's programmes. [shift] = the global guide shift in minutes. A page is reloaded when the data changes and
     * when a programme it shows ends ([ticks], by default every minute), so "now" moves on by itself.
     */
    fun items(f: ChannelFilter, shift: Int, ticks: Flow<Long> = minuteTicks()): Flow<PagingData<ChannelItem>> =
        paged(ticks) { itemSource(f, shift) }

    internal fun itemSource(f: ChannelFilter, shift: Int) = listSource(f) { limit, offset, now -> page(rows(f, limit, offset), shift, now) }

    /**
     * Rows of the programme grid (EPG-008): channels of the list (paged like [items]) with their programmes
     * overlapping [from, to) in screen time, ordered and cut so they don't overlap: ready to draw one canvas per row.
     */
    fun guide(f: ChannelFilter, shift: Int, from: Long, to: Long, initialKey: Int? = null, ticks: Flow<Long> = minuteTicks()): Flow<PagingData<GuideRow>> =
        // Rows carry a day of programmes: far pages are dropped instead of kept for a 50 000-channel list, and a long
        // jump (CH± held, a found channel) loads where it lands instead of every page on the way.
        paged(ticks, initialKey, maxSize = Defaults.CHANNEL_PAGE_SIZE * 6, jump = true) { guideSource(f, shift, from, to) }

    internal fun guideSource(f: ChannelFilter, shift: Int, from: Long, to: Long) =
        listSource(f) { limit, offset, now -> guidePage(rows(f, limit, offset), shift, from, to, now) }

    /** The [index]-th channel of the filtered list (player: next/previous channel). */
    suspend fun channelAt(f: ChannelFilter, index: Int): Channel? = withContext(Dispatchers.IO) {
        rows(f, 1, index).firstOrNull()?.channel
    }

    /** The first [n] channels of the list, without their programmes (search results). */
    suspend fun first(f: ChannelFilter, n: Int): List<ChannelItem> = withContext(Dispatchers.IO) { rows(f, n, 0).map { it.item(null, null) } }

    /** Position of the channel [key] in the list (LIVE-017: focus where the user left), null if it isn't there. */
    suspend fun indexOf(f: ChannelFilter, key: String): Int? = withContext(Dispatchers.IO) {
        db.query(f.query("ch.ckey", order = true)).use { c ->
            while (c.moveToNext()) if (c.getString(0) == key) return@use c.position
            null
        }
    }

    /** LIVE-009: the visible channel with this number (tvg-chno / provider / position from 1). */
    suspend fun channelByNumber(sourceKey: String, number: Int): Channel? = withContext(Dispatchers.IO) {
        val f = ChannelFilter(sourceKey)
        dao.items(f.query(ITEM_COLUMNS, order = true, where = " AND COALESCE(ch.number, ch.position + 1) = ?", whereArgs = listOf(number), limit = " LIMIT 1"))
            .firstOrNull()?.channel
    }

    /** A saved channel of the source by key (the first one, if the playlist has it twice). */
    suspend fun channel(sourceKey: String, key: String): ChannelItem? = withContext(Dispatchers.IO) {
        val f = ChannelFilter(sourceKey, visibility = Visibility.ALL)
        dao.items(f.query(ITEM_COLUMNS, order = true, where = " AND ch.ckey = ?", whereArgs = listOf(key), limit = " LIMIT 1"))
            .firstOrNull()?.item(null, null)
    }

    /** Previous, current and next programme of a channel (player info bar), updated as programmes change. */
    fun airing(sourceKey: String, key: String, shift: Int, ticks: Flow<Long> = minuteTicks()): Flow<Airing> =
        combine(db.changes(*GUIDE_TABLES), ticks) { _, now -> now }.map { now ->
            withContext(Dispatchers.IO) {
                val b = epg.binding(sourceKey, key)
                val epgId = b?.epgId ?: return@withContext Airing(null, null, null)
                val shiftMs = (b.shift ?: shift) * 60_000L
                val t = now - shiftMs
                val list = epg.around(listOf(epgId to t), previous = true).map { it.shifted(shiftMs) }
                val current = list.lastOrNull { it.start <= now }?.takeIf { it.stop > now }
                Airing(
                    previous = list.lastOrNull { it.stop <= (current?.start ?: now) },
                    now = current,
                    next = list.firstOrNull { it.start > now },
                )
            }
        }.distinctUntilChanged()

    /** Programmes of a channel in [from, to) (screen time), e.g. its schedule (LIVE-006). */
    suspend fun schedule(sourceKey: String, key: String, shift: Int, from: Long, to: Long): List<Programme> = withContext(Dispatchers.IO) {
        val b = epg.binding(sourceKey, key)
        val epgId = b?.epgId ?: return@withContext emptyList()
        val shiftMs = (b.shift ?: shift) * 60_000L
        nonOverlapping(epg.programmes(listOf(epgId), from - shiftMs, to - shiftMs).map { it.shifted(shiftMs) })
    }

    /**
     * EPG-010 "Поиск" in the grid: programmes of the list's channels whose title matches [text] like the channel search
     * (every word starts a word of the title), not ended at [from], soonest first; times in screen time.
     */
    suspend fun findProgrammes(f: ChannelFilter, shift: Int, text: String, from: Long, limit: Int): List<ProgrammeHit> =
        withContext(Dispatchers.IO) {
            val (list, args) = f.sql(
                "ch.ckey AS ckey, ch.epgId AS epgId, COALESCE(st.epgShift, es.shift) AS shift, COALESCE(st.name, ch.name) AS name",
                order = false, where = " AND ch.epgId IS NOT NULL",
            )
            // Guide time: a shift can move a programme by up to ±14 h, so ask a bit wider and cut after shifting.
            val sql = "SELECT q.ckey, q.shift, p.start, p.stop, p.title, p.description, q.name FROM programme p JOIN ($list) q ON q.epgId = p.channelId" +
                " WHERE p.stop > ? ORDER BY p.start"
            val out = LinkedHashMap<Pair<String, Long>, ProgrammeHit>()
            // ponytail: titles are matched in Kotlin (SQLite folds only ASCII case), scanning the list's future guide once;
            // an FTS table over programme titles if this gets slow on big guides.
            db.query(SimpleSQLiteQuery(sql, (args + (from - SHIFT_MAX_MS)).toTypedArray())).use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val title = c.getString(4)
                    if (!matchesWords(title, text)) continue
                    val ms = (if (c.isNull(1)) shift else c.getInt(1)) * 60_000L
                    val p = Programme(c.getLong(2) + ms, c.getLong(3) + ms, title, c.getString(5))
                    if (p.stop > from) out.getOrPut(c.getString(0) to p.start) { ProgrammeHit(c.getString(0), c.getString(6), p) }
                }
            }
            out.values.sortedBy { it.programme.start }
        }

    private fun rows(f: ChannelFilter, limit: Int, offset: Int): List<ItemRow> =
        dao.items(f.query(ITEM_COLUMNS, order = true, limit = " LIMIT $limit OFFSET $offset"))

    /** Rows with their now/next: (items, the earliest moment one of their programmes changes). */
    private fun page(rows: List<ItemRow>, shift: Int, now: Long): Pair<List<ChannelItem>, Long> {
        val at = rows.mapNotNull { r -> r.epgId?.let { it to now - r.shiftMs(shift) } }.toSet()
        val byChannel = epg.around(at).groupBy { it.channelId }
        var validUntil = Long.MAX_VALUE
        val items = rows.map { row ->
            val list = row.epgId?.let(byChannel::get).orEmpty().map { it.shifted(row.shiftMs(shift)) }
            val current = list.lastOrNull { it.start <= now }?.takeIf { it.stop > now }
            val next = list.firstOrNull { it.start > now }
            (current?.stop ?: next?.start)?.let { validUntil = minOf(validUntil, it) }
            row.item(current, next)
        }
        return items to validUntil
    }

    private fun guidePage(rows: List<ItemRow>, shift: Int, from: Long, to: Long, now: Long): Pair<List<GuideRow>, Long> {
        val (items, validUntil) = page(rows, shift, now)
        val bound = rows.filter { it.epgId != null }
        if (bound.isEmpty()) return items.map { GuideRow(it, emptyList()) } to validUntil
        // One query for the page: the widest guide-time window any row's shift needs, cut per row below.
        val byChannel = epg.programmes(
            bound.map { it.epgId!! }.distinct(), from - bound.maxOf { it.shiftMs(shift) }, to - bound.minOf { it.shiftMs(shift) },
        ).groupBy { it.channelId }
        return rows.mapIndexed { i, r ->
            val list = r.epgId?.let(byChannel::get).orEmpty().map { it.shifted(r.shiftMs(shift)) }.filter { it.stop > from && it.start < to }
            GuideRow(items[i], nonOverlapping(list))
        } to validUntil
    }

    private fun <T : Any> listSource(f: ChannelFilter, load: (limit: Int, offset: Int, now: Long) -> Pair<List<T>, Long>) =
        ListSource(db, f.tables(), { dao.count(f.query("COUNT(*)", order = false)) }, clock, load)

    /**
     * A Pager over [source]; [ticks] reload the pages once a programme they show has ended. [initialKey] = the row the
     * first page starts at (a new time window of the grid opens where the user is), [maxSize] = rows kept loaded.
     */
    private fun <T : Any> paged(
        ticks: Flow<Long>, initialKey: Int? = null, maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED, jump: Boolean = false,
        source: () -> ListSource<T>,
    ) = channelFlow {
        val current = AtomicReference<ListSource<T>?>()
        launch { ticks.collect { t -> current.get()?.let { if (t >= it.validUntil) it.invalidate() } } }
        val config = PagingConfig(
            pageSize = Defaults.CHANNEL_PAGE_SIZE, enablePlaceholders = true, maxSize = maxSize,
            jumpThreshold = if (jump) Defaults.CHANNEL_PAGE_SIZE * 3 else PagingSource.LoadResult.Page.COUNT_UNDEFINED,
        )
        Pager(config, initialKey) { source().also(current::set) }
            .flow.collect { send(it) }
    }

    /**
     * Every saved channel of the source, read in pages (memory doesn't grow with the source).
     * Re-iterable; blocking, so iterate off the main thread.
     */
    fun allChannels(sourceKey: String): Sequence<Channel> = sequence {
        val sourceId = dao.activeSource(sourceKey) ?: return@sequence
        var after = 0L
        while (true) {
            val page = dao.channelsAfter(sourceId, after, Defaults.IMPORT_BATCH_SIZE)
            yieldAll(page)
            after = page.lastOrNull()?.id ?: break
        }
    }

    /**
     * Sets [Channel.autoLogo] of every channel of the source to [find]; writes only what changed.
     * Returns (channels with a logo found, all channels).
     */
    fun applyAutoLogos(sourceKey: String, find: (Channel) -> String?): Pair<Int, Int> {
        var found = 0
        var total = 0
        val changes = ArrayList<Pair<Long, String?>>()
        fun write() {
            if (changes.isEmpty()) return
            db.runInTransaction { changes.forEach { (id, url) -> dao.setAutoLogo(id, url) } }
            changes.clear()
        }
        for (ch in allChannels(sourceKey)) {
            val url = find(ch)
            total++
            if (url != null) found++
            if (url != ch.autoLogo) changes += ch.id to url
            if (changes.size >= Defaults.IMPORT_BATCH_SIZE) write()
        }
        write()
        return found to total
    }
}

private fun ItemRow.shiftMs(global: Int) = (shift ?: global) * 60_000L

private fun ItemRow.item(now: Programme?, next: Programme?) = ChannelItem(channel, displayNumber, favorite, hidden, now, next)

/** Emits the time now and then at the start of every minute (one shared "tick" for a list, 33-design 4.1). */
fun minuteTicks(): Flow<Long> = flow {
    while (true) {
        val now = System.currentTimeMillis()
        emit(now)
        delay(60_000 - now % 60_000)
    }
}

/**
 * LIMIT/OFFSET pages with placeholders, like Room's own paging source, but over a page loader that may run more
 * than one query (channels, then their programmes). Invalidated by changes of [tables] and, through
 * [validUntil], when a programme on the loaded pages ends.
 */
internal class ListSource<T : Any>(
    private val db: AppDb,
    tables: Array<String>,
    private val count: () -> Int,
    private val clock: () -> Long,
    private val load: (limit: Int, offset: Int, now: Long) -> Pair<List<T>, Long>,
) : PagingSource<Int, T>() {
    @Volatile var validUntil = Long.MAX_VALUE
        private set

    private val observer = object : InvalidationTracker.Observer(tables) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    init {
        // Not on the calling thread: Paging builds sources on the main thread, and the tracker's observer lock can be
        // held for seconds by its refresh waiting for a write transaction (an import, logo matching) — an ANR.
        // One thread for both keeps "add" before "remove".
        OBSERVERS.execute { db.invalidationTracker.addObserver(observer) }
        registerInvalidatedCallback { OBSERVERS.execute { db.invalidationTracker.removeObserver(observer) } }
    }

    override val jumpingSupported get() = true

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = withContext(Dispatchers.IO) {
        val result = db.runInTransaction<LoadResult<Int, T>> {
            val total = count()
            val key = params.key ?: 0
            val (offset, limit) = when (params) {
                is LoadParams.Prepend -> maxOf(0, key - params.loadSize) to minOf(params.loadSize, key)
                is LoadParams.Append -> key to params.loadSize
                is LoadParams.Refresh -> (if (key >= total) maxOf(0, total - params.loadSize) else key) to params.loadSize
            }
            val (data, until) = load(limit, offset, clock())
            validUntil = minOf(validUntil, until)
            LoadResult.Page(
                data,
                prevKey = offset.takeIf { it > 0 },
                nextKey = (offset + data.size).takeIf { it < total },
                itemsBefore = offset,
                itemsAfter = maxOf(0, total - offset - data.size),
            )
        }
        if (invalid) LoadResult.Invalid() else result
    }
}

/** Registers and removes the paging sources' table observers off the main thread, in order. */
private val OBSERVERS = Executors.newSingleThreadExecutor { r -> Thread(r, "paging-observers").apply { isDaemon = true } }

private val SERVICE_TABLES = arrayOf("source", "category", "channel", "channel_state", "category_state", "recent", "collection_channel")

private val GUIDE_TABLES = arrayOf("source", "channel", "channel_state", "epg_channel", "epg_source", "programme")

private const val ITEM_COLUMNS =
    "ch.id AS id, COALESCE(st.name, ch.name) AS name, ch.url AS url, cat.name AS `group`, COALESCE(st.logo, ch.logo) AS logo, " +
        "ch.tvgId AS tvgId, ch.autoLogo AS autoLogo, ch.ckey AS `key`, ch.number AS number, ch.archiveDays AS archiveDays, " +
        "COALESCE(ch.number, ch.position + 1) AS displayNumber, st.favorite IS NOT NULL AS favorite, " +
        "HIDDEN AS hidden, ch.epgId AS epgId, COALESCE(st.epgShift, es.shift) AS shift"

/** Tables a list depends on: its pages are reloaded when one of them changes. */
private fun ChannelFilter.tables(): Array<String> =
    arrayOf("source", "category", "channel", "channel_fts", "channel_state", "category_state", "epg_channel", "epg_source", "programme") +
        when (group) {
            ChannelGroup.Recent -> arrayOf("recent")
            is ChannelGroup.Collection -> arrayOf("collection_channel")
            is ChannelGroup.Category -> arrayOf("channel_order")
            else -> emptyArray()
        }

/**
 * The list as SQL. Source and category are uncorrelated subqueries: SQLite runs them once and walks the
 * (sourceId, categoryId, position) index. User edits come from LEFT JOINs on the stable keys. Lists of user-picked
 * channels (favorites, recent, collections) show a channel the playlist has twice once. Search = FTS prefix match of
 * every word ("пер кан" → "пер* кан*"). [select] may use HIDDEN for the list's hidden condition.
 */
private fun ChannelFilter.query(
    select: String, order: Boolean, where: String = "", whereArgs: List<Any> = emptyList(), limit: String = "",
): SimpleSQLiteQuery = sql(select, order, where, whereArgs, limit).let { (sql, args) -> SimpleSQLiteQuery(sql, args.toTypedArray()) }

/** [query] as SQL text and arguments, to be used inside a bigger query. */
private fun ChannelFilter.sql(
    select: String, order: Boolean, where: String = "", whereArgs: List<Any> = emptyList(), limit: String = "",
): Pair<String, List<Any>> {
    val args = ArrayList<Any>()
    // A hidden category hides its channels from "Все" and "С архивом", not from the lists the user made.
    val hidden = if (group == ChannelGroup.All || group == ChannelGroup.Archive) "(COALESCE(st.hidden, 0) OR COALESCE(cs.hidden, 0))"
    else "COALESCE(st.hidden, 0)"
    val sql = StringBuilder("SELECT ${select.replace("HIDDEN", hidden)} FROM channel ch JOIN category cat ON cat.id = ch.categoryId")
    sql.append(" LEFT JOIN channel_state st ON st.ckey = ch.ckey LEFT JOIN category_state cs ON cs.ckey = cat.ckey")
    if (select.contains("es.shift")) sql.append(" LEFT JOIN epg_channel e ON e.id = ch.epgId LEFT JOIN epg_source es ON es.id = e.sourceId")
    when (group) {
        ChannelGroup.Recent -> sql.append(" JOIN recent r ON r.ckey = ch.ckey")
        is ChannelGroup.Collection -> {
            sql.append(" JOIN collection_channel cc ON cc.collectionId = ? AND cc.ckey = ch.ckey")
            args += group.id
        }
        is ChannelGroup.Category -> if (order && sort == ChannelSort.PROVIDER) {
            sql.append(" LEFT JOIN channel_order o ON o.list = cat.ckey AND o.ckey = ch.ckey")
        }
        else -> {}
    }
    sql.append(" WHERE ch.sourceId = (SELECT id FROM source WHERE sourceKey = ? AND savedAt IS NOT NULL)")
    args += sourceKey
    when (group) {
        is ChannelGroup.Category -> {
            sql.append(
                " AND ch.categoryId = (SELECT c.id FROM category c JOIN source s ON s.id = c.sourceId" +
                    " WHERE s.sourceKey = ? AND s.savedAt IS NOT NULL AND c.name = ?)"
            )
            args += sourceKey
            args += group.name
        }
        ChannelGroup.Favorites -> sql.append(" AND st.favorite IS NOT NULL")
        ChannelGroup.Archive -> sql.append(" AND ch.archiveDays > 0")
        else -> {}
    }
    if (group == ChannelGroup.Favorites || group == ChannelGroup.Recent || group is ChannelGroup.Collection) {
        sql.append(" AND ch.id = (SELECT MIN(d.id) FROM channel d WHERE d.sourceId = ch.sourceId AND d.ckey = ch.ckey)")
    }
    when (visibility) {
        Visibility.VISIBLE -> sql.append(" AND NOT $hidden")
        Visibility.HIDDEN -> sql.append(" AND $hidden")
        Visibility.ALL -> {}
    }
    if (guideOnly) sql.append(" AND ch.epgId IS NOT NULL")
    val words = searchText(search).split(' ').filter { it.isNotEmpty() }
    if (words.isNotEmpty()) {
        sql.append(" AND ch.id IN (SELECT rowid FROM channel_fts WHERE channel_fts MATCH ?)")
        args += words.joinToString(" ") { "$it*" }
    }
    sql.append(where)
    args.addAll(whereArgs)
    if (order) sql.append(" ORDER BY ").append(orderBy())
    sql.append(limit)
    return sql.toString() to args
}

private const val SHIFT_MAX_MS = (Defaults.EPG_SHIFT_MAX + 60) * 60_000L

private fun ChannelFilter.orderBy(): String {
    val base = when (group) {
        ChannelGroup.Favorites -> "st.favorite"
        ChannelGroup.Recent -> "r.watchedAt DESC"
        is ChannelGroup.Collection -> "cc.position"
        is ChannelGroup.Category -> if (sort == ChannelSort.PROVIDER) "o.position IS NULL, o.position, ch.position" else "ch.position"
        else -> "ch.position"
    }
    return when (sort) {
        ChannelSort.PROVIDER -> base
        ChannelSort.NUMBER -> "COALESCE(ch.number, ch.position + 1), $base"
        // ponytail: NOCASE folds ASCII case only (ё is folded here); a sort key column if lowercase Cyrillic names show up.
        ChannelSort.NAME -> "REPLACE(REPLACE(COALESCE(st.name, ch.name), 'Ё', 'Е'), 'ё', 'е') COLLATE NOCASE, $base"
        ChannelSort.ARCHIVE_FIRST -> "ch.archiveDays = 0, $base"
        ChannelSort.FAVORITES_FIRST -> "st.favorite IS NULL, $base"
    }
}
