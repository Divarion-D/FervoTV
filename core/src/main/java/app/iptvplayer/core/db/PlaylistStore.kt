package app.iptvplayer.core.db

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.Channel
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * What the channel grid shows: the saved channels of a source, optionally one category and a search.
 * Refers to the source by key and to the category by name, not by row ids: a background refresh replaces the rows,
 * and the same filter (and the Pager built on it) keeps working on the new ones.
 */
data class ChannelFilter(val sourceKey: String, val group: String? = null, val search: String = "")

/** The saved channels of playlist sources: streaming import, lists for the screens. */
class PlaylistStore(private val db: AppDb) {
    private val dao = db.playlist()

    /**
     * Imports a source: [fill] hands categories and channels to [Import] as it reads them, they are written in
     * transactions of [Defaults.IMPORT_BATCH_SIZE]. Screens keep showing the previous channels until the end, when
     * the new ones replace them in one transaction. A failure (or cancellation) drops what was written, so an empty
     * or broken answer never replaces good data. One import at a time (the caller serializes them).
     * Blocking; returns the number of channels.
     */
    fun import(sourceKey: String, savedAt: Long = System.currentTimeMillis(), fill: (Import) -> Unit): Int {
        dao.unfinishedSources().forEach(dao::deleteSource) // left by a killed process
        val id = dao.insertSource(SourceRow(sourceKey = sourceKey, savedAt = null))
        try {
            val import = Import(id)
            fill(import)
            import.flush()
            // Almost always a broken/blocked response rather than a real empty playlist.
            if (import.count == 0) throw IOException("Плейлист пуст")
            db.runInTransaction {
                dao.complete(id, savedAt)
                dao.otherSources(id).forEach(dao::deleteSource)
            }
            return import.count
        } catch (e: Throwable) {
            dao.deleteSource(id)
            throw e
        }
    }

    inner class Import internal constructor(private val sourceId: Long) {
        private val categories = HashMap<String, Long>()
        private val batch = ArrayList<ChannelRow>(Defaults.IMPORT_BATCH_SIZE)
        var count = 0
            private set

        /** Registers a category ahead of its channels, to keep the provider's order (Xtream). */
        fun category(name: String): Long = categories.getOrPut(name) {
            dao.insertCategory(CategoryRow(sourceId = sourceId, name = name, position = categories.size))
        }

        fun channel(ch: Channel) {
            batch += ChannelRow(
                sourceId = sourceId, categoryId = category(ch.group), position = count++,
                name = ch.name, url = ch.url, logo = ch.logo, tvgId = ch.tvgId,
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

    fun categories(sourceKey: String): Flow<List<CategoryCount>> = dao.categories(sourceKey)

    /** Placeholders on: the item count is known up front and the scroll position survives a refresh. */
    fun channels(f: ChannelFilter): Flow<PagingData<Channel>> =
        Pager(PagingConfig(pageSize = Defaults.CHANNEL_PAGE_SIZE, enablePlaceholders = true)) { pagingSource(f) }.flow

    internal fun pagingSource(f: ChannelFilter) = dao.pagingSource(f.query(CHANNEL_COLUMNS, "ORDER BY ch.position"))

    /** The [index]-th channel of the filtered list (player: next/previous channel). */
    suspend fun channelAt(f: ChannelFilter, index: Int): Channel? =
        dao.channel(f.query(CHANNEL_COLUMNS, "ORDER BY ch.position LIMIT 1 OFFSET $index"))

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

private const val CHANNEL_COLUMNS =
    "ch.id AS id, ch.name AS name, ch.url AS url, cat.name AS `group`, ch.logo AS logo, ch.tvgId AS tvgId, ch.autoLogo AS autoLogo"

/**
 * Category and source are resolved by uncorrelated subqueries: SQLite runs them once and walks the
 * (sourceId, categoryId, position) index. Search = FTS prefix match of every word ("пер кан" → "пер* кан*").
 */
private fun ChannelFilter.query(columns: String, suffix: String = ""): SimpleSQLiteQuery {
    val args = ArrayList<Any>()
    val sql = StringBuilder("SELECT $columns FROM channel ch JOIN category cat ON cat.id = ch.categoryId")
    sql.append(" WHERE ch.sourceId = (SELECT id FROM source WHERE sourceKey = ? AND savedAt IS NOT NULL)")
    args += sourceKey
    if (group != null) {
        sql.append(
            " AND ch.categoryId = (SELECT c.id FROM category c JOIN source s ON s.id = c.sourceId" +
                " WHERE s.sourceKey = ? AND s.savedAt IS NOT NULL AND c.name = ?)"
        )
        args += sourceKey
        args += group
    }
    val words = searchText(search).split(' ').filter { it.isNotEmpty() }
    if (words.isNotEmpty()) {
        sql.append(" AND ch.id IN (SELECT rowid FROM channel_fts WHERE channel_fts MATCH ?)")
        args += words.joinToString(" ") { "$it*" }
    }
    sql.append(' ').append(suffix)
    return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
}
