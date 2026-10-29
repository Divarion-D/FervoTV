package app.iptvplayer.core.db

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.EpisodeEntry
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Progress
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.model.VodSort
import app.iptvplayer.core.source.cleanTitle
import app.iptvplayer.core.source.episodeMark
import app.iptvplayer.core.source.showKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/*
 * The library (films and series, SRC-010/011): a cache of the source like the channels, in its own tables with its
 * own import row, so it is imported separately (an Xtream account's in the background, SRC-016) and a slow or failed
 * library import never holds up or spoils the live channels.
 */

/** One row per library import of a source; [savedAt] null while it runs. Finishing one deletes every other row. */
@Entity(tableName = "library", indices = [Index("sourceKey")])
data class LibraryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [app.iptvplayer.core.source.cacheKey] of the source. */
    val sourceKey: String,
    val savedAt: Long?,
)

/** A provider category of films or series; [ckey] = `Source.userKey()` + "|" + kind + "|" + name (user edits). */
@Entity(tableName = "vod_category", indices = [Index(value = ["libraryId", "kind", "name"], unique = true)])
data class VodCategoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryId: Long,
    val kind: VodKind,
    val name: String,
    /** Provider order (Xtream) or first appearance (M3U). */
    val position: Int,
    val ckey: String,
)

/** A film or a series. [name] = the provider's caption, [title] / [year] / [tags] = [cleanTitle] of it. */
@Entity(
    tableName = "vod",
    indices = [
        Index("libraryId", "kind", "position"), Index("libraryId", "categoryId", "position"), Index("libraryId", "ckey"),
        // VOD-003 by name and by date: a 150 000-film catalogue pages without sorting all of it.
        Index("libraryId", "kind", "sortName", "position"), Index("libraryId", "kind", "added", "position"),
    ],
)
data class VodRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryId: Long,
    val categoryId: Long,
    val kind: VodKind,
    /** Order in the source. */
    val position: Int,
    val name: String,
    val title: String,
    /** [searchText] of [title]: the order of [VodSort.NAME]. */
    val sortName: String,
    val year: Int?,
    /** Labels taken off the name, space-separated. */
    val tags: String,
    /** Film stream; null for a series. */
    val url: String?,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    /** The provider's date added, else when this key first appeared (carried over by imports), epoch ms. */
    val added: Long,
    val plot: String?,
    val genre: String?,
    /** [VodItem.key]: what user data is attached to. */
    val ckey: String,
    /** Series: when its episodes were stored (M3U: with the import; Xtream: when opened), null = not yet. */
    val episodesAt: Long? = null,
)

/** Full-text index of [VodRow.name] ([searchText]), rowid = [VodRow.id]; filled by the import like [ChannelFts]. */
@Fts4
@Entity(tableName = "vod_fts")
data class VodFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val name: String,
)

/**
 * An episode, by [seriesKey]. M3U episodes come with the import; Xtream ones when the series is opened, and are
 * carried over to the next import while their series is still there.
 */
@Entity(tableName = "episode", indices = [Index("libraryId", "seriesKey", "season", "episode"), Index("libraryId", "ckey")])
data class EpisodeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryId: Long,
    val seriesKey: String,
    val season: Int,
    val episode: Int,
    /** Order in the source, for equal numbers. */
    val position: Int,
    val name: String,
    val title: String?,
    val url: String,
    val image: String?,
    val plot: String?,
    val duration: Long?,
    val released: String?,
    val ckey: String,
)

/** Which list of a library section (VOD-001): service lists, a user collection or a provider category. */
sealed interface VodGroup {
    data object All : VodGroup
    data object Favorites : VodGroup

    /** Films watched, series with an episode watched; the latest first. */
    data object Recent : VodGroup

    /** By the provider's name. */
    data class Category(val name: String) : VodGroup
    data class Collection(val id: Long) : VodGroup
}

/** What a catalogue shows (VOD-001, VOD-003); by key and name, not row ids, so it survives a refresh like [ChannelFilter]. */
data class VodFilter(
    val sourceKey: String,
    val kind: VodKind,
    val group: VodGroup = VodGroup.All,
    val search: String = "",
    val sort: VodSort = VodSort.PROVIDER,
    val visibility: Visibility = Visibility.VISIBLE,
)

/** A list of the category column with its count; [title] empty for service lists (the apps name them). */
data class VodGroupCount(val group: VodGroup, val title: String, val count: Int, val hidden: Boolean = false)

/** SRC-016: what a library import did. [added] / [updated] / [removed] compare with the previous copy by key. */
data class LibraryResult(val movies: Int, val series: Int, val added: Int, val updated: Int, val removed: Int)

/** SRC-016: the library import of the source, for a progress indicator and the summary when it ends. */
sealed interface LibraryState {
    /** [count] films and series written so far. */
    data class Running(val count: Int) : LibraryState
    data class Done(val result: LibraryResult) : LibraryState
    data class Failed(val error: Exception) : LibraryState
}

/** A row of a catalogue query. */
data class VodListRow(
    val id: Long,
    val key: String,
    val kind: VodKind,
    val name: String,
    val title: String,
    val year: Int?,
    val tags: String,
    val category: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    val added: Long,
    val plot: String?,
    val genre: String?,
    val url: String?,
    val favorite: Boolean,
    val hidden: Boolean,
    @Embedded(prefix = "w_") val progress: Progress?,
) {
    fun item() = VodItem(
        key, kind, name, title, year, tags.split(' ').filter { it.isNotEmpty() }, category, poster, backdrop, rating, added,
        plot, genre, url, favorite, hidden, progress, id,
    )
}

data class EpisodeWithProgress(
    @Embedded val e: EpisodeRow,
    @Embedded(prefix = "w_") val progress: Progress?,
) {
    fun episode() = Episode(e.ckey, e.seriesKey, e.season, e.episode, e.title, e.name, e.url, e.image, e.plot, e.duration, e.released, progress)
}

data class Previous(val ckey: String, val added: Long, val name: String, val url: String?, val poster: String?)

private const val ACTIVE_LIBRARY = "(SELECT id FROM library WHERE sourceKey = :sourceKey AND savedAt IS NOT NULL)"

private const val EPISODE_COLUMNS =
    "e.*, w.position AS w_position, w.duration AS w_duration, w.watched AS w_watched, w.watchedAt AS w_at"

@Dao
abstract class LibraryDao {
    @Insert abstract fun insertLibrary(l: LibraryRow): Long
    @Insert abstract fun insertCategory(c: VodCategoryRow): Long
    @Insert abstract fun insertVod(list: List<VodRow>): List<Long>
    @Insert abstract fun insertFts(list: List<VodFts>)
    @Insert abstract fun insertEpisodes(list: List<EpisodeRow>)

    @Query("SELECT id FROM library WHERE sourceKey = :sourceKey AND savedAt IS NOT NULL")
    abstract fun active(sourceKey: String): Long?

    @Query("SELECT savedAt FROM library WHERE sourceKey = :sourceKey AND savedAt IS NOT NULL")
    abstract suspend fun savedAt(sourceKey: String): Long?

    @Query("SELECT id FROM library WHERE id != :keep")
    abstract fun others(keep: Long): List<Long>

    @Query("SELECT id FROM library WHERE savedAt IS NULL")
    abstract fun unfinished(): List<Long>

    @Query("UPDATE library SET savedAt = :savedAt WHERE id = :id")
    abstract fun complete(id: Long, savedAt: Long)

    /** What the previous copy had under these keys: first-seen dates, and what changed. */
    @Query("SELECT ckey, added, name, url, poster FROM vod WHERE libraryId = :id AND ckey IN (:keys)")
    abstract fun previous(id: Long, keys: List<String>): List<Previous>

    @Query("SELECT COUNT(*) FROM vod o WHERE o.libraryId = :old AND NOT EXISTS (SELECT 1 FROM vod n WHERE n.libraryId = :next AND n.ckey = o.ckey)")
    abstract fun removed(old: Long, next: Long): Int

    /** Xtream episodes, loaded on opening a series, stay with the series in the new copy. */
    @Query(
        """UPDATE episode SET libraryId = :next WHERE libraryId = :old
        AND seriesKey IN (SELECT ckey FROM vod WHERE libraryId = :next AND kind = 'SERIES' AND episodesAt IS NULL)"""
    )
    abstract fun carryEpisodes(old: Long, next: Long)

    @Query(
        """UPDATE vod SET episodesAt = (SELECT MAX(o.episodesAt) FROM vod o WHERE o.libraryId = :old AND o.ckey = vod.ckey)
        WHERE libraryId = :next AND kind = 'SERIES' AND episodesAt IS NULL"""
    )
    abstract fun carryEpisodesAt(old: Long, next: Long)

    @Query("DELETE FROM vod_fts WHERE rowid IN (SELECT id FROM vod WHERE libraryId = :id)")
    protected abstract fun deleteFts(id: Long)

    @Query("DELETE FROM vod WHERE libraryId = :id")
    protected abstract fun deleteVod(id: Long)

    @Query("DELETE FROM vod_category WHERE libraryId = :id")
    protected abstract fun deleteCategories(id: Long)

    @Query("DELETE FROM episode WHERE libraryId = :id")
    protected abstract fun deleteAllEpisodes(id: Long)

    @Query("DELETE FROM library WHERE id = :id")
    protected abstract fun deleteLibraryRow(id: Long)

    @Transaction
    open fun deleteLibrary(id: Long) {
        deleteFts(id)
        deleteVod(id)
        deleteCategories(id)
        deleteAllEpisodes(id)
        deleteLibraryRow(id)
    }

    /** Categories of a section with counts of visible items, the user's order first (as [PlaylistDao.categories]). */
    @Query(
        """SELECT cat.name AS name, COALESCE(cs.name, cat.name) AS title, COALESCE(cs.hidden, 0) AS hidden,
        SUM(COALESCE(st.hidden, 0) = 0) AS count
        FROM vod_category cat
        JOIN vod v ON v.libraryId = cat.libraryId AND v.categoryId = cat.id
        LEFT JOIN vod_state st ON st.vkey = v.ckey
        LEFT JOIN vod_category_state cs ON cs.ckey = cat.ckey
        WHERE cat.libraryId = $ACTIVE_LIBRARY AND cat.kind = :kind
        GROUP BY cat.id HAVING :withHidden OR (COALESCE(cs.hidden, 0) = 0 AND SUM(COALESCE(st.hidden, 0) = 0) > 0)
        ORDER BY cs.position IS NULL, cs.position, cat.position"""
    )
    abstract fun categories(sourceKey: String, kind: VodKind, withHidden: Boolean): Flow<List<CategoryCount>>

    @RawQuery
    abstract fun items(q: SupportSQLiteQuery): List<VodListRow>

    @RawQuery
    abstract fun count(q: SupportSQLiteQuery): Int

    @Query(
        """SELECT $EPISODE_COLUMNS FROM episode e LEFT JOIN watch w ON w.vkey = e.ckey
        WHERE e.libraryId = $ACTIVE_LIBRARY AND e.seriesKey = :series ORDER BY e.season = 0, e.season, e.episode, e.position"""
    )
    abstract suspend fun episodes(sourceKey: String, series: String): List<EpisodeWithProgress>

    @Query("SELECT $EPISODE_COLUMNS FROM episode e LEFT JOIN watch w ON w.vkey = e.ckey WHERE e.libraryId = $ACTIVE_LIBRARY AND e.ckey = :key LIMIT 1")
    abstract suspend fun episode(sourceKey: String, key: String): EpisodeWithProgress?

    @Query("SELECT episodesAt FROM vod WHERE libraryId = $ACTIVE_LIBRARY AND ckey = :series LIMIT 1")
    abstract suspend fun episodesAt(sourceKey: String, series: String): Long?

    @Query("DELETE FROM episode WHERE libraryId = :libraryId AND seriesKey = :series")
    abstract fun deleteEpisodes(libraryId: Long, series: String)

    @Query("UPDATE vod SET episodesAt = :at WHERE libraryId = :libraryId AND ckey = :series")
    abstract fun setEpisodesAt(libraryId: Long, series: String, at: Long)
}

/** The library of the saved source: import, catalogue lists, episodes. User data is [LibraryUser]. */
class LibraryStore(private val db: AppDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.library()

    /**
     * Imports a source's library like [PlaylistStore.import]: [fill] hands items to [Import], written in batches; the
     * previous copy stays visible until the end, when the new one replaces it in one transaction. A failure or
     * cancellation drops what was written. An empty library is a valid result (a source without films). Blocking.
     * [onProgress] gets the number of films and series written so far.
     */
    fun import(
        sourceKey: String, userKey: String, savedAt: Long = clock(), onProgress: (Int) -> Unit = {}, fill: (Import) -> Unit,
    ): LibraryResult {
        dao.unfinished().forEach(dao::deleteLibrary) // left by a killed process
        val old = dao.active(sourceKey)
        val id = dao.insertLibrary(LibraryRow(sourceKey = sourceKey, savedAt = null))
        try {
            val import = Import(id, userKey, old, savedAt, onProgress)
            fill(import)
            import.flush()
            return db.runInTransaction<LibraryResult> {
                val removed = if (old == null) 0 else {
                    dao.carryEpisodes(old, id)
                    dao.carryEpisodesAt(old, id)
                    dao.removed(old, id)
                }
                dao.complete(id, savedAt)
                dao.others(id).forEach(dao::deleteLibrary)
                LibraryResult(import.movies, import.series, import.added, import.updated, removed)
            }
        } catch (e: Throwable) {
            dao.deleteLibrary(id)
            throw e
        }
    }

    inner class Import internal constructor(
        private val libraryId: Long, private val userKey: String, private val old: Long?, private val now: Long,
        private val onProgress: (Int) -> Unit,
    ) {
        private val categories = HashMap<Pair<VodKind, String>, Long>()
        private val items = ArrayList<VodRow>(Defaults.IMPORT_BATCH_SIZE)
        private val episodes = ArrayList<EpisodeRow>(Defaults.IMPORT_BATCH_SIZE)

        /** M3U series met so far, by [showKey]: their key and number of episodes. */
        private val shows = HashMap<String, Show>()
        private var position = 0
        private var episodePosition = 0
        var movies = 0
            private set
        var series = 0
            private set
        internal var added = 0
        internal var updated = 0

        /** Registers a category ahead of its items, to keep the provider's order (Xtream). */
        fun category(kind: VodKind, name: String): Long = categories.getOrPut(kind to name) {
            dao.insertCategory(VodCategoryRow(libraryId = libraryId, kind = kind, name = name, position = categories.size, ckey = "$userKey|${kind.name.lowercase()}|$name"))
        }

        fun item(e: VodEntry) = add(e, null)

        private fun add(e: VodEntry, episodesAt: Long?) {
            val clean = cleanTitle(e.name)
            items += VodRow(
                libraryId = libraryId, categoryId = category(e.kind, e.category), kind = e.kind, position = position++,
                name = e.name, title = clean.title, sortName = searchText(clean.title).trim(), year = e.year ?: clean.year,
                tags = clean.tags.joinToString(" "), url = e.url, poster = e.poster, backdrop = e.backdrop, rating = e.rating,
                added = e.added ?: 0, plot = e.plot, genre = e.genre, ckey = e.key, episodesAt = episodesAt,
            )
            if (e.kind == VodKind.MOVIE) movies++ else series++
            if (items.size >= Defaults.IMPORT_BATCH_SIZE) flushItems()
        }

        /**
         * SRC-010: an M3U entry that is a film, or an episode of a series. Episodes group into series by the show name
         * before the episode marker; an entry without a marker is the show's next episode (of season 1).
         */
        fun m3u(ch: Channel, kind: VodKind) {
            if (kind == VodKind.MOVIE) return item(VodEntry(VodKind.MOVIE, ch.key, ch.name, ch.group, ch.url, poster = ch.logo))
            val mark = episodeMark(ch.name)
            // "S01E01 Pilot" names no show: the group does (a playlist with a category per show).
            val showName = when {
                mark == null -> ch.name
                mark.show.isNotEmpty() -> mark.show
                ch.group != NO_GROUP -> ch.group
                else -> ch.name
            }
            val show = shows.getOrPut(showKey(showName).ifEmpty { showName }) {
                Show("$userKey|series|${showKey(showName).ifEmpty { showName }}").also {
                    add(VodEntry(VodKind.SERIES, it.key, showName, ch.group, poster = ch.logo), episodesAt = now)
                }
            }
            show.count++
            episode(
                EpisodeEntry(
                    key = ch.key, seriesKey = show.key, season = mark?.season ?: 1, episode = mark?.episode ?: show.count,
                    name = ch.name, title = mark?.title?.ifEmpty { null }, url = ch.url, image = ch.logo,
                )
            )
        }

        internal fun episode(e: EpisodeEntry) {
            episodes += e.row(libraryId, episodePosition++)
            if (episodes.size >= Defaults.IMPORT_BATCH_SIZE) flushEpisodes()
        }

        internal fun flush() {
            flushItems()
            flushEpisodes()
        }

        private fun flushItems() {
            if (items.isEmpty()) return
            // ponytail: one indexed lookup of the previous copy per batch; first-seen dates and the result's counts come from it.
            val prev = if (old == null) emptyMap() else items.map { it.ckey }.distinct().chunked(SQL_VARS)
                .flatMap { dao.previous(old, it) }.associateBy { it.ckey }
            val rows = items.map { r ->
                val p = prev[r.ckey]
                if (p == null) added++ else if (p.name != r.name || p.url != r.url || p.poster != r.poster) updated++
                if (r.added > 0) r else r.copy(added = p?.added ?: now)
            }
            db.runInTransaction {
                val ids = dao.insertVod(rows)
                dao.insertFts(ids.mapIndexed { i, rowId -> VodFts(rowId, searchText(rows[i].name)) })
            }
            items.clear()
            onProgress(movies + series)
        }

        private fun flushEpisodes() {
            if (episodes.isEmpty()) return
            db.runInTransaction { dao.insertEpisodes(episodes) }
            episodes.clear()
        }
    }

    private class Show(val key: String) {
        var count = 0
    }

    /** When the saved library of the source was imported; null = none yet. */
    suspend fun savedAt(sourceKey: String): Long? = dao.savedAt(sourceKey)

    /** Provider categories of a section in the user's order with visible counts; [withHidden] for the management screen. */
    fun categories(sourceKey: String, kind: VodKind, withHidden: Boolean = false): Flow<List<CategoryCount>> =
        dao.categories(sourceKey, kind, withHidden)

    /**
     * The category column of a section (VOD-001): "Избранное" (always), "Недавние", "Все" (when not empty), the user's
     * collections of this section, then the provider's categories.
     */
    fun groups(sourceKey: String, kind: VodKind, withHidden: Boolean = false): Flow<List<VodGroupCount>> {
        val service = db.changes(*LIBRARY_TABLES).map {
            withContext(Dispatchers.IO) {
                listOf(
                    VodGroupCount(VodGroup.Favorites, "", count(VodFilter(sourceKey, kind, VodGroup.Favorites))),
                    VodGroupCount(VodGroup.Recent, "", count(VodFilter(sourceKey, kind, VodGroup.Recent))),
                    VodGroupCount(VodGroup.All, "", count(VodFilter(sourceKey, kind))),
                ).filter { it.group == VodGroup.Favorites || it.count > 0 }
            }
        }
        val collections = combine(db.libraryUser().collections(kind), db.changes(*LIBRARY_TABLES)) { list, _ ->
            withContext(Dispatchers.IO) {
                list.map { VodGroupCount(VodGroup.Collection(it.id), it.name, count(VodFilter(sourceKey, kind, VodGroup.Collection(it.id)))) }
            }
        }
        return combine(service, collections, categories(sourceKey, kind, withHidden)) { s, c, cats ->
            s + c + cats.map { VodGroupCount(VodGroup.Category(it.name), it.title, it.count, it.hidden) }
        }.distinctUntilChanged()
    }

    fun count(f: VodFilter): Int = dao.count(f.query("COUNT(*)", order = false))

    /** The catalogue, paged with placeholders (VOD-001: 150 000 films without loading them all). */
    fun items(f: VodFilter): Flow<PagingData<VodItem>> =
        Pager(PagingConfig(pageSize = Defaults.VOD_PAGE_SIZE, enablePlaceholders = true)) { source(f) }.flow

    internal fun source(f: VodFilter) = ListSource(db, f.tables(), { count(f) }, clock) { limit, offset, _ ->
        dao.items(f.query(ITEM_COLUMNS, order = true, limit = " LIMIT $limit OFFSET $offset", progress = true)).map { it.item() } to Long.MAX_VALUE
    }

    /** The first [n] items of the list (search results, home rows). */
    suspend fun first(f: VodFilter, n: Int): List<VodItem> = withContext(Dispatchers.IO) {
        dao.items(f.query(ITEM_COLUMNS, order = true, limit = " LIMIT $n", progress = true)).map { it.item() }
    }

    /** A film or series of the saved library by key, hidden or not. */
    suspend fun item(sourceKey: String, kind: VodKind, key: String): VodItem? = withContext(Dispatchers.IO) {
        val f = VodFilter(sourceKey, kind, visibility = Visibility.ALL)
        dao.items(f.query(ITEM_COLUMNS, order = true, where = " AND v.ckey = ?", whereArgs = listOf(key), limit = " LIMIT 1", progress = true))
            .firstOrNull()?.item()
    }

    /** Position of [key] in the list (focus where the user left, HOME-012), null if it isn't there. */
    suspend fun indexOf(f: VodFilter, key: String): Int? = withContext(Dispatchers.IO) {
        db.query(f.query("v.ckey", order = true)).use { c ->
            while (c.moveToNext()) if (c.getString(0) == key) return@use c.position
            null
        }
    }

    /** Episodes of a series of the saved library with their progress; specials (season 0) last. */
    suspend fun episodes(sourceKey: String, seriesKey: String): List<Episode> = dao.episodes(sourceKey, seriesKey).map { it.episode() }

    suspend fun episode(sourceKey: String, key: String): Episode? = dao.episode(sourceKey, key)?.episode()

    /** When the episodes of a series were stored; null = never (Xtream: not opened yet). */
    suspend fun episodesAt(sourceKey: String, seriesKey: String): Long? = dao.episodesAt(sourceKey, seriesKey)

    /** SRC-011: the episodes of an Xtream series, as just loaded, replace its stored ones. */
    fun storeEpisodes(sourceKey: String, seriesKey: String, list: List<EpisodeEntry>, at: Long = clock()) = db.runInTransaction {
        val id = dao.active(sourceKey) ?: return@runInTransaction
        dao.deleteEpisodes(id, seriesKey)
        dao.insertEpisodes(list.mapIndexed { i, e -> e.row(id, i) })
        dao.setEpisodesAt(id, seriesKey, at)
    }
}

private fun EpisodeEntry.row(libraryId: Long, position: Int) = EpisodeRow(
    libraryId = libraryId, seriesKey = seriesKey, season = season, episode = episode, position = position, name = name,
    title = title, url = url, image = image, plot = plot, duration = duration, released = released, ckey = key,
)

/** SQLite's oldest limit of `?` per statement is 999. */
private const val SQL_VARS = 500

internal val LIBRARY_TABLES = arrayOf("library", "vod_category", "vod", "vod_state", "vod_category_state", "watch", "vod_collection_item")

private const val ITEM_COLUMNS =
    "v.id AS id, v.ckey AS `key`, v.kind AS kind, COALESCE(st.name, v.name) AS name, COALESCE(st.name, v.title) AS title, " +
        "v.year AS year, v.tags AS tags, cat.name AS category, v.poster AS poster, v.backdrop AS backdrop, v.rating AS rating, " +
        "v.added AS added, v.plot AS plot, v.genre AS genre, v.url AS url, st.favorite IS NOT NULL AS favorite, HIDDEN AS hidden, " +
        "w.position AS w_position, w.duration AS w_duration, w.watched AS w_watched, w.watchedAt AS w_at"

private fun VodFilter.tables(): Array<String> = arrayOf(*LIBRARY_TABLES, "vod_fts") +
    when (group) {
        is VodGroup.Category -> arrayOf("vod_order")
        else -> emptyArray()
    }

private fun VodFilter.query(
    select: String, order: Boolean, where: String = "", whereArgs: List<Any> = emptyList(), limit: String = "", progress: Boolean = false,
): SimpleSQLiteQuery {
    val args = ArrayList<Any>()
    // As in the live lists: a hidden category hides its items from "Все", not from the lists the user made.
    val hidden = if (group == VodGroup.All) "(COALESCE(st.hidden, 0) OR COALESCE(cs.hidden, 0))" else "COALESCE(st.hidden, 0)"
    val sql = StringBuilder("SELECT ${select.replace("HIDDEN", hidden)} FROM vod v JOIN vod_category cat ON cat.id = v.categoryId")
    sql.append(" LEFT JOIN vod_state st ON st.vkey = v.ckey LEFT JOIN vod_category_state cs ON cs.ckey = cat.ckey")
    // VOD-008: a series shows the progress of its last episode started.
    if (progress) sql.append(
        " LEFT JOIN watch w ON w.vkey = CASE WHEN v.kind = 'MOVIE' THEN v.ckey" +
            " ELSE (SELECT x.vkey FROM watch x WHERE x.seriesKey = v.ckey ORDER BY x.watchedAt DESC LIMIT 1) END"
    )
    when (group) {
        VodGroup.Recent -> sql.append(" JOIN (SELECT COALESCE(seriesKey, vkey) AS k, MAX(watchedAt) AS at FROM watch GROUP BY k) r ON r.k = v.ckey")
        is VodGroup.Collection -> {
            sql.append(" JOIN vod_collection_item ci ON ci.collectionId = ? AND ci.vkey = v.ckey")
            args += group.id
        }
        is VodGroup.Category -> if (order && sort == VodSort.PROVIDER) sql.append(" LEFT JOIN vod_order o ON o.list = cat.ckey AND o.vkey = v.ckey")
        else -> {}
    }
    sql.append(" WHERE v.libraryId = (SELECT id FROM library WHERE sourceKey = ? AND savedAt IS NOT NULL) AND v.kind = ?")
    args += sourceKey
    args += kind.name
    when (group) {
        is VodGroup.Category -> {
            sql.append(
                " AND v.categoryId = (SELECT c.id FROM vod_category c JOIN library l ON l.id = c.libraryId" +
                    " WHERE l.sourceKey = ? AND l.savedAt IS NOT NULL AND c.kind = ? AND c.name = ?)"
            )
            args += sourceKey
            args += kind.name
            args += group.name
        }
        VodGroup.Favorites -> sql.append(" AND st.favorite IS NOT NULL")
        else -> {}
    }
    if (group == VodGroup.Favorites || group == VodGroup.Recent || group is VodGroup.Collection) {
        sql.append(" AND v.id = (SELECT MIN(d.id) FROM vod d WHERE d.libraryId = v.libraryId AND d.ckey = v.ckey)")
    }
    when (visibility) {
        Visibility.VISIBLE -> sql.append(" AND NOT $hidden")
        Visibility.HIDDEN -> sql.append(" AND $hidden")
        Visibility.ALL -> {}
    }
    val words = searchText(search).split(' ').filter { it.isNotEmpty() }
    if (words.isNotEmpty()) {
        sql.append(" AND v.id IN (SELECT rowid FROM vod_fts WHERE vod_fts MATCH ?)")
        args += words.joinToString(" ") { "$it*" }
    }
    sql.append(where)
    args.addAll(whereArgs)
    if (order) sql.append(" ORDER BY ").append(orderBy())
    sql.append(limit)
    return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
}

private fun VodFilter.orderBy(): String {
    val base = when (group) {
        VodGroup.Favorites -> "st.favorite, v.position"
        VodGroup.Recent -> "r.at DESC, v.position"
        is VodGroup.Collection -> "ci.position, v.position"
        is VodGroup.Category -> if (sort == VodSort.PROVIDER) "o.position IS NULL, o.position, v.position" else "v.position"
        VodGroup.All -> "v.position"
    }
    return when (sort) {
        VodSort.PROVIDER -> base
        // ponytail: by the provider's clean title; a user's name doesn't move the item.
        VodSort.NAME -> "v.sortName, $base"
        // Equal dates (a whole M3U seen at once): later in the list = newer.
        VodSort.ADDED -> "v.added DESC, v.position DESC"
        VodSort.YEAR -> "v.year IS NULL, v.year DESC, $base"
        VodSort.RATING -> "v.rating IS NULL, v.rating DESC, $base"
    }
}
