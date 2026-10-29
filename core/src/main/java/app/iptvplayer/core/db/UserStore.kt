package app.iptvplayer.core.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import app.iptvplayer.core.Defaults
import kotlinx.coroutines.flow.Flow

/*
 * User data. Everything is keyed by stable keys ([app.iptvplayer.core.model.Channel.key], [CategoryRow.ckey]),
 * never by row ids: an import recreates the channel and category rows, these tables are not touched by it.
 * ponytail: no profile column yet; profiles (P5) add one to each table.
 */

/** Edits of one channel; a row with nothing set is deleted ([UserDao.prune]). */
@Entity(tableName = "channel_state")
data class ChannelState(
    @PrimaryKey val ckey: String,
    /** The user's name (LIVE-012). */
    val name: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Position in "Избранное" (LIVE-008, LIVE-013); null = not a favorite. */
    val favorite: Int? = null,
    /** The user's guide binding (EPG-004/005): XMLTV channel id; wins over the automatic one. */
    val epg: String? = null,
    /** The channel's own guide shift in minutes (EPG-007); wins over the source's and the global one. */
    val epgShift: Int? = null,
    /** The user's logo (taken from the guide when binding, EPG-005). */
    val logo: String? = null,
)

/** Edits of one provider category, [ckey] = [CategoryRow.ckey]. */
@Entity(tableName = "category_state")
data class CategoryState(
    @PrimaryKey val ckey: String,
    val name: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** The user's category order (LIVE-013); categories without one follow in the provider's order. */
    val position: Int? = null,
)

/** Manual order of channels inside a provider category ([list] = [CategoryRow.ckey]); new channels go after these. */
@Entity(tableName = "channel_order", primaryKeys = ["list", "ckey"])
data class ChannelOrder(val list: String, val ckey: String, val position: Int)

/** A user collection (LIVE-014): channels of any categories and sources. */
@Entity(tableName = "collection")
data class CollectionRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val position: Int)

@Entity(tableName = "collection_channel", primaryKeys = ["collectionId", "ckey"])
data class CollectionChannel(val collectionId: Long, val ckey: String, val position: Int)

/** Watched channels (LIVE-010 "Недавние", LIVE-017 last channel, LIVE-018 previous channel). */
@Entity(tableName = "recent", indices = [Index("watchedAt")])
data class Recent(@PrimaryKey val ckey: String, val watchedAt: Long)

/** What "Вернуть как в плейлисте" (LIVE-015) would undo, as lines for the user; all empty = no edits. */
data class Edits(val renamed: List<String>, val hidden: List<String>, val reordered: List<String>) {
    val isEmpty get() = renamed.isEmpty() && hidden.isEmpty() && reordered.isEmpty()
}

data class NameKey(val name: String, val ckey: String)

private const val ACTIVE = "(SELECT id FROM source WHERE sourceKey = :sourceKey AND savedAt IS NOT NULL)"

/** Channels of the saved source, optionally of one category ([category] = null: all). */
private const val SOURCE_CHANNELS =
    "SELECT ch.ckey FROM channel ch JOIN category cat ON cat.id = ch.categoryId " +
        "WHERE ch.sourceId = $ACTIVE AND (:category IS NULL OR cat.name = :category)"

private const val SOURCE_CATEGORIES =
    "SELECT ckey FROM category WHERE sourceId = $ACTIVE AND (:category IS NULL OR name = :category)"

@Dao
abstract class UserDao {
    @Query("INSERT OR IGNORE INTO channel_state(ckey) VALUES (:key)")
    abstract fun touchChannel(key: String)

    @Query(
        """UPDATE channel_state SET favorite = CASE WHEN :on
        THEN COALESCE(favorite, (SELECT COALESCE(MAX(favorite), -1) + 1 FROM channel_state)) ELSE NULL END
        WHERE ckey = :key"""
    )
    abstract fun setFavorite(key: String, on: Boolean)

    @Query("UPDATE channel_state SET favorite = :position WHERE ckey = :key AND favorite IS NOT NULL")
    abstract fun setFavoritePosition(key: String, position: Int)

    @Query("UPDATE channel_state SET hidden = :hidden WHERE ckey IN (:keys)")
    abstract fun setHidden(keys: List<String>, hidden: Boolean)

    @Query("UPDATE channel_state SET name = :name WHERE ckey = :key")
    abstract fun rename(key: String, name: String?)

    @Query("UPDATE channel_state SET epg = :xmltvId WHERE ckey = :key")
    abstract fun bindEpg(key: String, xmltvId: String?)

    @Query("UPDATE channel_state SET logo = :logo WHERE ckey = :key")
    abstract fun setLogo(key: String, logo: String?)

    @Query("UPDATE channel_state SET epgShift = :minutes WHERE ckey = :key")
    abstract fun setEpgShift(key: String, minutes: Int?)

    @Query("SELECT * FROM channel_state WHERE ckey = :key")
    abstract suspend fun channelState(key: String): ChannelState?

    @Query("SELECT ckey, epg FROM channel_state WHERE epg IS NOT NULL")
    abstract fun epgBindings(): List<KeyEpg>

    @Query("DELETE FROM channel_state WHERE name IS NULL AND hidden = 0 AND favorite IS NULL AND epg IS NULL AND epgShift IS NULL AND logo IS NULL")
    abstract fun pruneChannels()

    @Query("SELECT name, ckey FROM category WHERE sourceId = $ACTIVE")
    abstract fun categoryKeys(sourceKey: String): List<NameKey>

    @Query("INSERT OR IGNORE INTO category_state(ckey) VALUES (:key)")
    abstract fun touchCategory(key: String)

    @Query("UPDATE category_state SET hidden = :hidden WHERE ckey IN (:keys)")
    abstract fun setCategoryHidden(keys: List<String>, hidden: Boolean)

    @Query("UPDATE category_state SET name = :name WHERE ckey = :key")
    abstract fun renameCategory(key: String, name: String?)

    @Query("UPDATE category_state SET position = :position WHERE ckey = :key")
    abstract fun setCategoryPosition(key: String, position: Int?)

    @Query("DELETE FROM category_state WHERE name IS NULL AND hidden = 0 AND position IS NULL")
    abstract fun pruneCategories()

    @Query("DELETE FROM channel_order WHERE list = :list")
    abstract fun clearOrder(list: String)

    @Insert
    abstract fun insertOrder(rows: List<ChannelOrder>)

    @Insert
    abstract fun insertCollection(c: CollectionRow): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM collection")
    abstract fun nextCollectionPosition(): Int

    @Query("UPDATE collection SET name = :name WHERE id = :id")
    abstract fun renameCollection(id: Long, name: String)

    @Query("UPDATE collection SET position = :position WHERE id = :id")
    abstract fun setCollectionPosition(id: Long, position: Int)

    @Query("DELETE FROM collection WHERE id = :id")
    abstract fun deleteCollectionRow(id: Long)

    @Query("DELETE FROM collection_channel WHERE collectionId = :id")
    abstract fun clearCollection(id: Long)

    @Query("SELECT * FROM collection ORDER BY position")
    abstract fun collections(): Flow<List<CollectionRow>>

    @Query(
        """INSERT OR IGNORE INTO collection_channel(collectionId, ckey, position)
        VALUES (:id, :key, (SELECT COALESCE(MAX(position), -1) + 1 FROM collection_channel WHERE collectionId = :id))"""
    )
    abstract fun addToCollection(id: Long, key: String)

    @Query("DELETE FROM collection_channel WHERE collectionId = :id AND ckey = :key")
    abstract fun removeFromCollection(id: Long, key: String)

    @Query("UPDATE collection_channel SET position = :position WHERE collectionId = :id AND ckey = :key")
    abstract fun setCollectionChannelPosition(id: Long, key: String, position: Int)

    @Query("INSERT OR REPLACE INTO recent(ckey, watchedAt) VALUES (:key, :at)")
    abstract fun watched(key: String, at: Long)

    @Query("DELETE FROM recent WHERE ckey NOT IN (SELECT ckey FROM recent ORDER BY watchedAt DESC LIMIT :keep)")
    abstract fun trimRecent(keep: Int)

    /** Most recent first, only channels of the saved source. */
    @Query(
        """SELECT r.ckey FROM recent r WHERE EXISTS (SELECT 1 FROM channel ch WHERE ch.sourceId = $ACTIVE AND ch.ckey = r.ckey)
        ORDER BY r.watchedAt DESC LIMIT :limit"""
    )
    abstract suspend fun recentKeys(sourceKey: String, limit: Int): List<String>

    @Query(
        """SELECT r.ckey, r.watchedAt FROM recent r WHERE EXISTS (SELECT 1 FROM channel ch WHERE ch.sourceId = $ACTIVE AND ch.ckey = r.ckey)
        ORDER BY r.watchedAt DESC LIMIT :limit"""
    )
    abstract suspend fun recent(sourceKey: String, limit: Int): List<Recent>

    // --- LIVE-015 ---
    @Query(
        """SELECT DISTINCT ch.name || ' → ' || st.name FROM channel ch JOIN category cat ON cat.id = ch.categoryId
        JOIN channel_state st ON st.ckey = ch.ckey
        WHERE ch.sourceId = $ACTIVE AND (:category IS NULL OR cat.name = :category) AND st.name IS NOT NULL"""
    )
    abstract fun renamedChannels(sourceKey: String, category: String?): List<String>

    @Query(
        """SELECT DISTINCT COALESCE(st.name, ch.name) FROM channel ch JOIN category cat ON cat.id = ch.categoryId
        JOIN channel_state st ON st.ckey = ch.ckey
        WHERE ch.sourceId = $ACTIVE AND (:category IS NULL OR cat.name = :category) AND st.hidden"""
    )
    abstract fun hiddenChannels(sourceKey: String, category: String?): List<String>

    @Query(
        """SELECT cat.name AS name, cs.name AS title, cs.hidden AS hidden, cs.position AS position
        FROM category cat JOIN category_state cs ON cs.ckey = cat.ckey
        WHERE cat.sourceId = $ACTIVE AND (:category IS NULL OR cat.name = :category)"""
    )
    abstract fun categoryEdits(sourceKey: String, category: String?): List<CategoryEdit>

    @Query(
        """SELECT cat.name FROM category cat WHERE cat.sourceId = $ACTIVE AND (:category IS NULL OR cat.name = :category)
        AND EXISTS (SELECT 1 FROM channel_order o WHERE o.list = cat.ckey)"""
    )
    abstract fun reorderedCategories(sourceKey: String, category: String?): List<String>

    @Query("UPDATE channel_state SET name = NULL, hidden = 0 WHERE ckey IN ($SOURCE_CHANNELS)")
    abstract fun resetChannels(sourceKey: String, category: String?)

    @Query("DELETE FROM channel_order WHERE list IN ($SOURCE_CATEGORIES)")
    abstract fun resetOrder(sourceKey: String, category: String?)

    /** The category order is reset only with all categories. */
    @Query(
        """UPDATE category_state SET name = NULL, hidden = 0,
        position = CASE WHEN :category IS NULL THEN NULL ELSE position END WHERE ckey IN ($SOURCE_CATEGORIES)"""
    )
    abstract fun resetCategories(sourceKey: String, category: String?)
}

data class KeyEpg(val ckey: String, val epg: String)

data class CategoryEdit(val name: String, val title: String?, val hidden: Boolean, val position: Int?)

/**
 * Favorites, hiding, renaming, manual order, collections and history of channels (LIVE-008, 010, 012–018).
 * Blocking writes, each in one transaction; screens see the changes through their database flows.
 */
class UserStore(private val db: AppDb) {
    private val dao = db.user()

    private fun channels(keys: List<String>, edit: () -> Unit) = db.runInTransaction {
        keys.forEach(dao::touchChannel)
        edit()
        dao.pruneChannels()
    }

    fun setFavorite(key: String, on: Boolean) = channels(listOf(key)) { dao.setFavorite(key, on) }

    /** LIVE-012, also for a range of rows at once. */
    fun setHidden(keys: List<String>, hidden: Boolean) = channels(keys) { dao.setHidden(keys, hidden) }

    /** null or blank = the provider's name back. */
    fun rename(key: String, name: String?) = channels(listOf(key)) { dao.rename(key, name?.trim()?.ifEmpty { null }) }

    fun setEpgShift(key: String, minutes: Int?) = channels(listOf(key)) { dao.setEpgShift(key, minutes) }

    /** The user's guide binding; [logo] = also take the guide's logo (EPG-005). Rebinding is done by [EpgStore]. */
    internal fun bindEpg(bindings: List<Triple<String, String?, String?>>, takeLogo: Boolean) =
        channels(bindings.map { it.first }) {
            for ((key, xmltvId, logo) in bindings) {
                dao.bindEpg(key, xmltvId)
                if (takeLogo && logo != null) dao.setLogo(key, logo)
            }
        }

    suspend fun channelState(key: String): ChannelState? = dao.channelState(key)

    private fun categories(sourceKey: String, names: List<String>, edit: (List<String>) -> Unit) = db.runInTransaction {
        val byName = dao.categoryKeys(sourceKey).associate { it.name to it.ckey }
        val keys = names.mapNotNull(byName::get)
        keys.forEach(dao::touchCategory)
        edit(keys)
        dao.pruneCategories()
    }

    fun setCategoriesHidden(sourceKey: String, names: List<String>, hidden: Boolean) =
        categories(sourceKey, names) { dao.setCategoryHidden(it, hidden) }

    fun renameCategory(sourceKey: String, name: String, title: String?) =
        categories(sourceKey, listOf(name)) { keys -> keys.forEach { dao.renameCategory(it, title?.trim()?.ifEmpty { null }) } }

    /** LIVE-013: [names] = provider names of the categories in the new order. */
    fun reorderCategories(sourceKey: String, names: List<String>) =
        categories(sourceKey, names) { keys -> keys.forEachIndexed { i, k -> dao.setCategoryPosition(k, i) } }

    /**
     * LIVE-013: the new order of a list, [keys] = channel keys as the user arranged them. For a provider category
     * channels added by the provider later go after these.
     */
    fun reorder(sourceKey: String, group: ChannelGroup, keys: List<String>) = db.runInTransaction {
        when (group) {
            ChannelGroup.Favorites -> keys.forEachIndexed { i, k -> dao.setFavoritePosition(k, i) }
            is ChannelGroup.Collection -> keys.forEachIndexed { i, k -> dao.setCollectionChannelPosition(group.id, k, i) }
            is ChannelGroup.Category -> {
                val list = dao.categoryKeys(sourceKey).find { it.name == group.name }?.ckey ?: return@runInTransaction
                dao.clearOrder(list)
                dao.insertOrder(keys.distinct().mapIndexed { i, k -> ChannelOrder(list, k, i) })
            }
            else -> throw IllegalArgumentException("$group has no manual order")
        }
    }

    fun collections(): Flow<List<CollectionRow>> = dao.collections()

    fun createCollection(name: String): Long =
        db.runInTransaction<Long> { dao.insertCollection(CollectionRow(name = name.trim(), position = dao.nextCollectionPosition())) }

    fun renameCollection(id: Long, name: String) = dao.renameCollection(id, name.trim())

    fun reorderCollections(ids: List<Long>) = db.runInTransaction { ids.forEachIndexed { i, id -> dao.setCollectionPosition(id, i) } }

    fun deleteCollection(id: Long) = db.runInTransaction {
        dao.clearCollection(id)
        dao.deleteCollectionRow(id)
    }

    fun addToCollection(id: Long, key: String) = dao.addToCollection(id, key)

    /** Removes the channel from this collection only; a refresh of the source doesn't bring it back (LIVE-014). */
    fun removeFromCollection(id: Long, key: String) = dao.removeFromCollection(id, key)

    fun watched(key: String, at: Long = System.currentTimeMillis()) = db.runInTransaction {
        dao.watched(key, at)
        dao.trimRecent(Defaults.RECENT_CHANNELS)
    }

    /** Keys of the last watched channels of the saved source, most recent first. */
    suspend fun recentKeys(sourceKey: String, limit: Int = Defaults.RECENT_CHANNELS): List<String> =
        dao.recentKeys(sourceKey, limit)

    /** The same with the time each was watched (LIVE-020). */
    suspend fun recent(sourceKey: String, limit: Int = Defaults.RECENT_CHANNELS): List<Recent> = dao.recent(sourceKey, limit)

    /** LIVE-015: what [reset] would undo for [category] (null = every category of the source). */
    fun edits(sourceKey: String, category: String?): Edits {
        val cats = dao.categoryEdits(sourceKey, category)
        return Edits(
            renamed = cats.filter { it.title != null }.map { "${it.name} → ${it.title}" } + dao.renamedChannels(sourceKey, category),
            hidden = cats.filter { it.hidden }.map { it.title ?: it.name } + dao.hiddenChannels(sourceKey, category),
            reordered = listOfNotNull("Порядок категорий".takeIf { category == null && cats.any { it.position != null } }) +
                dao.reorderedCategories(sourceKey, category),
        )
    }

    /**
     * LIVE-015: names, hiding and order of [category] (null = all categories, and their order) back as in the playlist.
     * Other sources, collections, favorites and guide bindings stay.
     */
    fun reset(sourceKey: String, category: String?) = db.runInTransaction {
        dao.resetChannels(sourceKey, category)
        dao.resetOrder(sourceKey, category)
        dao.resetCategories(sourceKey, category)
        dao.pruneChannels()
        dao.pruneCategories()
    }
}
