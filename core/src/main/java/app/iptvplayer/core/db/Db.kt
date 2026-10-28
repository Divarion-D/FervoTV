package app.iptvplayer.core.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.iptvplayer.core.model.Channel
import kotlinx.coroutines.flow.Flow

/**
 * One row per import of a playlist source. [savedAt] is null while the import is still running: its channels are
 * written but not shown. Finishing an import sets [savedAt] and deletes every other row (one saved source, as before).
 */
@Entity(tableName = "source", indices = [Index("sourceKey")])
data class SourceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [app.iptvplayer.core.source.cacheKey]: another URL or account doesn't see these channels. */
    val sourceKey: String,
    val savedAt: Long?,
)

/** Provider category; [position] = provider order (Xtream) or first appearance (M3U). */
@Entity(tableName = "category", indices = [Index("sourceId", "name", unique = true)])
data class CategoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val name: String,
    val position: Int,
)

@Entity(tableName = "channel", indices = [Index("sourceId", "position"), Index("sourceId", "categoryId", "position")])
data class ChannelRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long,
    /** Order in the source. */
    val position: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val tvgId: String?,
    val autoLogo: String? = null,
)

/**
 * Full-text index of channel names, rowid = [ChannelRow.id]. Holds [searchText] of the name rather than the name:
 * SQLite's tokenizer only folds ASCII case, so text is normalized the same way here and in the query.
 * Filled by the import, not by triggers: updating a channel's logo doesn't touch the index.
 */
@Fts4
@Entity(tableName = "channel_fts")
data class ChannelFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val name: String,
)

data class CategoryCount(val name: String, val count: Int)

@Dao
abstract class PlaylistDao {
    @Insert abstract fun insertSource(s: SourceRow): Long
    @Insert abstract fun insertCategory(c: CategoryRow): Long
    @Insert abstract fun insertChannels(list: List<ChannelRow>): List<Long>
    @Insert abstract fun insertFts(list: List<ChannelFts>)

    @Query("UPDATE source SET savedAt = :savedAt WHERE id = :id")
    abstract fun complete(id: Long, savedAt: Long)

    @Query("SELECT savedAt FROM source WHERE sourceKey = :key AND savedAt IS NOT NULL")
    abstract suspend fun savedAt(key: String): Long?

    @Query("SELECT id FROM source WHERE sourceKey = :key AND savedAt IS NOT NULL")
    abstract fun activeSource(key: String): Long?

    @Query("SELECT id FROM source WHERE id != :keep")
    abstract fun otherSources(keep: Long): List<Long>

    @Query("SELECT id FROM source WHERE savedAt IS NULL")
    abstract fun unfinishedSources(): List<Long>

    @Query("DELETE FROM channel_fts WHERE rowid IN (SELECT id FROM channel WHERE sourceId = :id)")
    protected abstract fun deleteFts(id: Long)

    @Query("DELETE FROM channel WHERE sourceId = :id")
    protected abstract fun deleteChannels(id: Long)

    @Query("DELETE FROM category WHERE sourceId = :id")
    protected abstract fun deleteCategories(id: Long)

    @Query("DELETE FROM source WHERE id = :id")
    protected abstract fun deleteSourceRow(id: Long)

    @Transaction
    open fun deleteSource(id: Long) {
        deleteFts(id)
        deleteChannels(id)
        deleteCategories(id)
        deleteSourceRow(id)
    }

    /** Non-empty categories of the saved source in order, with channel counts. */
    @Query(
        """SELECT cat.name AS name, COUNT(*) AS count FROM category cat
        JOIN channel ch ON ch.sourceId = cat.sourceId AND ch.categoryId = cat.id
        WHERE cat.sourceId = (SELECT id FROM source WHERE sourceKey = :key AND savedAt IS NOT NULL)
        GROUP BY cat.id ORDER BY cat.position"""
    )
    abstract fun categories(key: String): Flow<List<CategoryCount>>

    /** Keyset pages over a source in id order, for passes over every channel (logo matching). */
    @Query(
        """SELECT ch.id, ch.name, ch.url, cat.name AS `group`, ch.logo, ch.tvgId, ch.autoLogo
        FROM channel ch JOIN category cat ON cat.id = ch.categoryId
        WHERE ch.sourceId = :sourceId AND ch.id > :after ORDER BY ch.id LIMIT :limit"""
    )
    abstract fun channelsAfter(sourceId: Long, after: Long, limit: Int): List<Channel>

    @Query("UPDATE channel SET autoLogo = :url WHERE id = :id")
    abstract fun setAutoLogo(id: Long, url: String?)

    // Filtered lists (category + search) are built by ChannelFilter.query().
    @RawQuery(observedEntities = [SourceRow::class, CategoryRow::class, ChannelRow::class])
    abstract fun pagingSource(q: SupportSQLiteQuery): PagingSource<Int, Channel>

    @RawQuery
    abstract suspend fun channel(q: SupportSQLiteQuery): Channel?
}

/**
 * Schema changes bump [version] and add a Migration; the schema of every version is exported to `core/schemas/`.
 * Data of P0 is a cache of the source, but later versions add user data to the same file, so no destructive fallback.
 */
@Database(entities = [SourceRow::class, CategoryRow::class, ChannelRow::class, ChannelFts::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun playlist(): PlaylistDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "fervo.db").build().also { instance = it }
        }
    }
}

/** Lowercase, ё→е, everything but letters and digits → space. Used for both the index and the query. */
fun searchText(s: String): String = buildString(s.length) {
    for (c in s.lowercase()) append(if (c == 'ё') 'е' else if (c.isLetterOrDigit()) c else ' ')
}

/** The same match as the channel search, for short in-memory lists (categories): every query word starts a word of [text]. */
fun matchesWords(text: String, query: String): Boolean {
    val words = searchText(text).split(' ').filter { it.isNotEmpty() }
    return searchText(query).split(' ').filter { it.isNotEmpty() }.all { q -> words.any { it.startsWith(q) } }
}
