package app.iptvplayer.core.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import app.iptvplayer.core.model.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

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
    /** XMLTV addresses from the playlist header (SRC-008), "\n"-separated. */
    @ColumnInfo(defaultValue = "") val epgUrls: String = "",
)

/** Provider category; [position] = provider order (Xtream) or first appearance (M3U). */
@Entity(tableName = "category", indices = [Index("sourceId", "name", unique = true)])
data class CategoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val name: String,
    val position: Int,
    /** Stable key of user edits ([CategoryState]): `Source.userKey()` + "|" + name. */
    @ColumnInfo(defaultValue = "") val ckey: String,
)

@Entity(
    tableName = "channel",
    indices = [Index("sourceId", "position"), Index("sourceId", "categoryId", "position"), Index("sourceId", "ckey")],
)
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
    /** [Channel.key]: what user data is attached to; the row itself is recreated by every import. */
    @ColumnInfo(defaultValue = "") val ckey: String,
    val number: Int? = null,
    @ColumnInfo(defaultValue = "0") val archiveDays: Int = 0,
    /** The guide channel ([EpgChannel.id]) bound by [app.iptvplayer.core.epg.EpgIndex]; kept up to date by [EpgStore]. */
    val epgId: Long? = null,
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

/** A provider category: [name] is the provider's (what filters use), [title] the user's name for it, if renamed. */
data class CategoryCount(val name: String, val count: Int, val title: String = name, val hidden: Boolean = false)

@Dao
abstract class PlaylistDao {
    @Insert abstract fun insertSource(s: SourceRow): Long
    @Insert abstract fun insertCategory(c: CategoryRow): Long
    @Insert abstract fun insertChannels(list: List<ChannelRow>): List<Long>
    @Insert abstract fun insertFts(list: List<ChannelFts>)

    @Query("UPDATE source SET savedAt = :savedAt, epgUrls = :epgUrls WHERE id = :id")
    abstract fun complete(id: Long, savedAt: Long, epgUrls: String)

    @Query("SELECT epgUrls FROM source WHERE sourceKey = :key AND savedAt IS NOT NULL")
    abstract suspend fun epgUrls(key: String): String?

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

    /**
     * Categories of the saved source with counts of visible channels: the user's order (moved ones first), then the
     * provider's. Without [withHidden], hidden and (by hiding their channels) emptied ones are left out.
     */
    @Query(
        """SELECT cat.name AS name, COALESCE(cs.name, cat.name) AS title, COALESCE(cs.hidden, 0) AS hidden,
        SUM(COALESCE(st.hidden, 0) = 0) AS count
        FROM category cat
        JOIN channel ch ON ch.sourceId = cat.sourceId AND ch.categoryId = cat.id
        LEFT JOIN channel_state st ON st.ckey = ch.ckey
        LEFT JOIN category_state cs ON cs.ckey = cat.ckey
        WHERE cat.sourceId = (SELECT id FROM source WHERE sourceKey = :key AND savedAt IS NOT NULL)
        GROUP BY cat.id HAVING :withHidden OR (COALESCE(cs.hidden, 0) = 0 AND SUM(COALESCE(st.hidden, 0) = 0) > 0)
        ORDER BY cs.position IS NULL, cs.position, cat.position"""
    )
    abstract fun categories(key: String, withHidden: Boolean): Flow<List<CategoryCount>>

    /** Keyset pages over a source in id order, for passes over every channel (logo and guide matching). */
    @Query(
        """SELECT ch.id, ch.name, ch.url, cat.name AS `group`, ch.logo, ch.tvgId, ch.autoLogo, ch.ckey AS `key`,
        ch.number, ch.archiveDays
        FROM channel ch JOIN category cat ON cat.id = ch.categoryId
        WHERE ch.sourceId = :sourceId AND ch.id > :after ORDER BY ch.id LIMIT :limit"""
    )
    abstract fun channelsAfter(sourceId: Long, after: Long, limit: Int): List<Channel>

    @Query("UPDATE channel SET autoLogo = :url WHERE id = :id")
    abstract fun setAutoLogo(id: Long, url: String?)

    // Filtered lists are built by ChannelFilter.query() (PlaylistStore.kt).
    @RawQuery
    abstract fun items(q: SupportSQLiteQuery): List<ItemRow>

    @RawQuery
    abstract fun count(q: SupportSQLiteQuery): Int
}

/** A row of a channel list query; [shift] = the channel's or its guide source's time shift, null = the global one. */
data class ItemRow(
    @Embedded val channel: Channel,
    val displayNumber: Int,
    val favorite: Boolean,
    val hidden: Boolean,
    val epgId: Long?,
    val shift: Int?,
)

/**
 * Schema changes bump [version] and add a Migration; the schema of every version is exported to `core/schemas/`.
 * Data of P0 is a cache of the source, but later versions add user data to the same file, so no destructive fallback.
 */
@Database(
    entities = [
        SourceRow::class, CategoryRow::class, ChannelRow::class, ChannelFts::class,
        ChannelState::class, CategoryState::class, ChannelOrder::class, CollectionRow::class, CollectionChannel::class,
        Recent::class, EpgSource::class, EpgChannel::class, ProgrammeRow::class, StagedProgramme::class,
        LibraryRow::class, VodCategoryRow::class, VodRow::class, VodFts::class, EpisodeRow::class,
        VodState::class, VodCategoryState::class, VodOrder::class, VodCollection::class, VodCollectionItem::class, Watch::class,
    ],
    version = 3,
)
abstract class AppDb : RoomDatabase() {
    abstract fun playlist(): PlaylistDao
    abstract fun user(): UserDao
    abstract fun epg(): EpgDao
    abstract fun library(): LibraryDao
    abstract fun libraryUser(): LibraryUserDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "fervo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}

/** Emits once at once and then after every committed change of [tables] (several changes may come as one). */
internal fun AppDb.changes(vararg tables: String): Flow<Unit> = callbackFlow {
    val observer = object : InvalidationTracker.Observer(arrayOf(*tables)) {
        override fun onInvalidated(tables: Set<String>) {
            trySend(Unit)
        }
    }
    invalidationTracker.addObserver(observer)
    send(Unit)
    awaitClose { invalidationTracker.removeObserver(observer) }
}.conflate()

/**
 * v1 → v2 (P1): user data and the guide. Saved channels stay: their new columns get what can be known without the
 * source (key = URL; for Xtream the right key comes with the next refresh, and v1 had no user data to lose).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach(db::execSQL)
        db.execSQL("UPDATE category SET ckey = (SELECT sourceKey FROM source WHERE source.id = category.sourceId) || '|' || name")
        db.execSQL("UPDATE channel SET ckey = url")
    }
}

/** v2 → v3 (P2.1): the library and its user data, new tables only; channels, edits and guide stay as they are. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_2_3_SQL.forEach(db::execSQL)
}

/** Copied from the exported schema `core/schemas/…/3.json`; checked by MigrationTest. */
private val MIGRATION_2_3_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `library` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceKey` TEXT NOT NULL, `savedAt` INTEGER)",
    "CREATE INDEX IF NOT EXISTS `index_library_sourceKey` ON `library` (`sourceKey`)",
    "CREATE TABLE IF NOT EXISTS `vod_category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `libraryId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, `ckey` TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_vod_category_libraryId_kind_name` ON `vod_category` (`libraryId`, `kind`, `name`)",
    "CREATE TABLE IF NOT EXISTS `vod` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `libraryId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `title` TEXT NOT NULL, `sortName` TEXT NOT NULL, `year` INTEGER, `tags` TEXT NOT NULL, `url` TEXT, `poster` TEXT, `backdrop` TEXT, `rating` REAL, `added` INTEGER NOT NULL, `plot` TEXT, `genre` TEXT, `ckey` TEXT NOT NULL, `episodesAt` INTEGER)",
    "CREATE INDEX IF NOT EXISTS `index_vod_libraryId_kind_position` ON `vod` (`libraryId`, `kind`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_libraryId_categoryId_position` ON `vod` (`libraryId`, `categoryId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_libraryId_ckey` ON `vod` (`libraryId`, `ckey`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_libraryId_kind_sortName_position` ON `vod` (`libraryId`, `kind`, `sortName`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_libraryId_kind_added_position` ON `vod` (`libraryId`, `kind`, `added`, `position`)",
    "CREATE VIRTUAL TABLE IF NOT EXISTS `vod_fts` USING FTS4(`name` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `episode` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `libraryId` INTEGER NOT NULL, `seriesKey` TEXT NOT NULL, `season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `title` TEXT, `url` TEXT NOT NULL, `image` TEXT, `plot` TEXT, `duration` INTEGER, `released` TEXT, `ckey` TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_episode_libraryId_seriesKey_season_episode` ON `episode` (`libraryId`, `seriesKey`, `season`, `episode`)",
    "CREATE INDEX IF NOT EXISTS `index_episode_libraryId_ckey` ON `episode` (`libraryId`, `ckey`)",
    "CREATE TABLE IF NOT EXISTS `vod_state` (`vkey` TEXT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL DEFAULT 0, `favorite` INTEGER, PRIMARY KEY(`vkey`))",
    "CREATE TABLE IF NOT EXISTS `vod_category_state` (`ckey` TEXT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL DEFAULT 0, `position` INTEGER, PRIMARY KEY(`ckey`))",
    "CREATE TABLE IF NOT EXISTS `vod_order` (`list` TEXT NOT NULL, `vkey` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`list`, `vkey`))",
    "CREATE TABLE IF NOT EXISTS `vod_collection` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `vod_collection_item` (`collectionId` INTEGER NOT NULL, `vkey` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `vkey`))",
    "CREATE TABLE IF NOT EXISTS `watch` (`vkey` TEXT NOT NULL, `kind` TEXT NOT NULL, `seriesKey` TEXT, `season` INTEGER, `episode` INTEGER, `title` TEXT NOT NULL, `episodeTitle` TEXT, `image` TEXT, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `watched` INTEGER NOT NULL, `watchedAt` INTEGER NOT NULL, PRIMARY KEY(`vkey`))",
    "CREATE INDEX IF NOT EXISTS `index_watch_watchedAt` ON `watch` (`watchedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_watch_seriesKey` ON `watch` (`seriesKey`)",
)

/** Lowercase, ё→е, everything but letters and digits → space. Used for both the index and the query. */
fun searchText(s: String): String = buildString(s.length) {
    for (c in s.lowercase()) append(if (c == 'ё') 'е' else if (c.isLetterOrDigit()) c else ' ')
}

/** The same match as the channel search, for short in-memory lists (categories): every query word starts a word of [text]. */
fun matchesWords(text: String, query: String): Boolean {
    val words = searchText(text).split(' ').filter { it.isNotEmpty() }
    return searchText(query).split(' ').filter { it.isNotEmpty() }.all { q -> words.any { it.startsWith(q) } }
}
/** Copied from the exported schema `core/schemas/…/2.json`; checked by MigrationTest. */
private val MIGRATION_1_2_SQL = listOf(
    "ALTER TABLE `source` ADD COLUMN `epgUrls` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `category` ADD COLUMN `ckey` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `channel` ADD COLUMN `ckey` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `channel` ADD COLUMN `number` INTEGER",
    "ALTER TABLE `channel` ADD COLUMN `archiveDays` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `channel` ADD COLUMN `epgId` INTEGER",
    "CREATE INDEX IF NOT EXISTS `index_channel_sourceId_ckey` ON `channel` (`sourceId`, `ckey`)",
    "CREATE TABLE IF NOT EXISTS `channel_state` (`ckey` TEXT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL DEFAULT 0, `favorite` INTEGER, `epg` TEXT, `epgShift` INTEGER, `logo` TEXT, PRIMARY KEY(`ckey`))",
    "CREATE TABLE IF NOT EXISTS `category_state` (`ckey` TEXT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL DEFAULT 0, `position` INTEGER, PRIMARY KEY(`ckey`))",
    "CREATE TABLE IF NOT EXISTS `channel_order` (`list` TEXT NOT NULL, `ckey` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`list`, `ckey`))",
    "CREATE TABLE IF NOT EXISTS `collection` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `collection_channel` (`collectionId` INTEGER NOT NULL, `ckey` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `ckey`))",
    "CREATE TABLE IF NOT EXISTS `recent` (`ckey` TEXT NOT NULL, `watchedAt` INTEGER NOT NULL, PRIMARY KEY(`ckey`))",
    "CREATE INDEX IF NOT EXISTS `index_recent_watchedAt` ON `recent` (`watchedAt`)",
    "CREATE TABLE IF NOT EXISTS `epg_source` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `userAgent` TEXT, `refresh` TEXT NOT NULL, `refreshDays` INTEGER NOT NULL, `shift` INTEGER, `position` INTEGER NOT NULL, `savedAt` INTEGER, `attemptAt` INTEGER, `status` TEXT)",
    "CREATE TABLE IF NOT EXISTS `epg_channel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `xmltvId` TEXT NOT NULL, `names` TEXT NOT NULL, `icon` TEXT)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_channel_sourceId_xmltvId` ON `epg_channel` (`sourceId`, `xmltvId`)",
    "CREATE TABLE IF NOT EXISTS `programme` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` INTEGER NOT NULL, `start` INTEGER NOT NULL, `stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_programme_channelId_start` ON `programme` (`channelId`, `start`)",
    "CREATE TABLE IF NOT EXISTS `programme_stage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channel` TEXT NOT NULL, `start` INTEGER NOT NULL, `stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
)
