package app.iptvplayer.core.db

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.VodKind
import kotlinx.coroutines.flow.Flow

/*
 * User data of the library (VOD-008, 010, 011, 012, HOME-002, 013), keyed by the stable keys of films, series,
 * episodes and categories ([app.iptvplayer.core.model.VodItem.key], [VodCategoryRow.ckey]): imports don't touch it.
 * Separate from the channels' tables, so the live section's queries and its settings-file part stay as they were.
 * ponytail: no profile column yet; profiles (P5) add one to each table.
 */

/** Edits of one film or series; a row with nothing set is deleted. */
@Entity(tableName = "vod_state")
data class VodState(
    @PrimaryKey val vkey: String,
    /** The user's name (VOD-012). */
    val name: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Place in "Избранное" (VOD-011); null = not a favorite. */
    val favorite: Int? = null,
)

/** Edits of one film / series category, [ckey] = [VodCategoryRow.ckey]. */
@Entity(tableName = "vod_category_state")
data class VodCategoryState(
    @PrimaryKey val ckey: String,
    val name: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    val position: Int? = null,
)

/** Manual order of items inside a provider category ([list] = [VodCategoryRow.ckey]); new items go after these. */
@Entity(tableName = "vod_order", primaryKeys = ["list", "vkey"])
data class VodOrder(val list: String, val vkey: String, val position: Int)

/** A user collection of films or of series (VOD-012 as LIVE-014). */
@Entity(tableName = "vod_collection")
data class VodCollection(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: VodKind, val name: String, val position: Int)

@Entity(tableName = "vod_collection_item", primaryKeys = ["collectionId", "vkey"])
data class VodCollectionItem(val collectionId: Long, val vkey: String, val position: Int)

/**
 * What was watched and how far (VOD-008 marks, VOD-010 positions, HOME-002, HOME-013): one row per film or episode.
 * [title] (film or series), [episodeTitle] and [image] are kept with it, so history shows without the catalogue.
 * [position] is 0 once [watched]; [watchedAt] = the last time it played.
 */
@Entity(tableName = "watch", indices = [Index("watchedAt"), Index("seriesKey")])
data class Watch(
    @PrimaryKey val vkey: String,
    /** [VodKind.SERIES] = an episode of [seriesKey]. */
    val kind: VodKind,
    val seriesKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val episodeTitle: String? = null,
    val image: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val watched: Boolean = false,
    val watchedAt: Long,
)

private const val ACTIVE = "(SELECT id FROM library WHERE sourceKey = :sourceKey AND savedAt IS NOT NULL)"

private const val SOURCE_ITEMS =
    "SELECT v.ckey FROM vod v JOIN vod_category cat ON cat.id = v.categoryId " +
        "WHERE v.libraryId = $ACTIVE AND v.kind = :kind AND (:category IS NULL OR cat.name = :category)"

private const val SOURCE_CATEGORIES =
    "SELECT ckey FROM vod_category WHERE libraryId = $ACTIVE AND kind = :kind AND (:category IS NULL OR name = :category)"

@Dao
abstract class LibraryUserDao {
    @Query("INSERT OR IGNORE INTO vod_state(vkey) VALUES (:key)")
    abstract fun touch(key: String)

    @Query(
        """UPDATE vod_state SET favorite = CASE WHEN :on
        THEN COALESCE(favorite, (SELECT COALESCE(MAX(favorite), -1) + 1 FROM vod_state)) ELSE NULL END WHERE vkey = :key"""
    )
    abstract fun setFavorite(key: String, on: Boolean)

    @Query("UPDATE vod_state SET favorite = :position WHERE vkey = :key AND favorite IS NOT NULL")
    abstract fun setFavoritePosition(key: String, position: Int)

    @Query("UPDATE vod_state SET hidden = :hidden WHERE vkey IN (:keys)")
    abstract fun setHidden(keys: List<String>, hidden: Boolean)

    @Query("UPDATE vod_state SET name = :name WHERE vkey = :key")
    abstract fun rename(key: String, name: String?)

    @Query("DELETE FROM vod_state WHERE name IS NULL AND hidden = 0 AND favorite IS NULL")
    abstract fun prune()

    @Query("SELECT name, ckey FROM vod_category WHERE libraryId = $ACTIVE AND kind = :kind")
    abstract fun categoryKeys(sourceKey: String, kind: VodKind): List<NameKey>

    @Query("INSERT OR IGNORE INTO vod_category_state(ckey) VALUES (:key)")
    abstract fun touchCategory(key: String)

    @Query("UPDATE vod_category_state SET hidden = :hidden WHERE ckey IN (:keys)")
    abstract fun setCategoryHidden(keys: List<String>, hidden: Boolean)

    @Query("UPDATE vod_category_state SET name = :name WHERE ckey = :key")
    abstract fun renameCategory(key: String, name: String?)

    @Query("UPDATE vod_category_state SET position = :position WHERE ckey = :key")
    abstract fun setCategoryPosition(key: String, position: Int?)

    @Query("DELETE FROM vod_category_state WHERE name IS NULL AND hidden = 0 AND position IS NULL")
    abstract fun pruneCategories()

    @Query("DELETE FROM vod_order WHERE list = :list")
    abstract fun clearOrder(list: String)

    @Insert
    abstract fun insertOrder(rows: List<VodOrder>)

    @Query("SELECT * FROM vod_collection WHERE kind = :kind ORDER BY position")
    abstract fun collections(kind: VodKind): Flow<List<VodCollection>>

    @Insert
    abstract fun insertCollection(c: VodCollection): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM vod_collection WHERE kind = :kind")
    abstract fun nextCollectionPosition(kind: VodKind): Int

    @Query("UPDATE vod_collection SET name = :name WHERE id = :id")
    abstract fun renameCollection(id: Long, name: String)

    @Query("UPDATE vod_collection SET position = :position WHERE id = :id")
    abstract fun setCollectionPosition(id: Long, position: Int)

    @Query("DELETE FROM vod_collection WHERE id = :id")
    abstract fun deleteCollectionRow(id: Long)

    @Query("DELETE FROM vod_collection_item WHERE collectionId = :id")
    abstract fun clearCollection(id: Long)

    @Query(
        """INSERT OR IGNORE INTO vod_collection_item(collectionId, vkey, position)
        VALUES (:id, :key, (SELECT COALESCE(MAX(position), -1) + 1 FROM vod_collection_item WHERE collectionId = :id))"""
    )
    abstract fun addToCollection(id: Long, key: String)

    @Query("DELETE FROM vod_collection_item WHERE collectionId = :id AND vkey = :key")
    abstract fun removeFromCollection(id: Long, key: String)

    @Query("UPDATE vod_collection_item SET position = :position WHERE collectionId = :id AND vkey = :key")
    abstract fun setCollectionItemPosition(id: Long, key: String, position: Int)

    // --- "Вернуть как у провайдера" (VOD-012 as LIVE-015) ---
    @Query(
        """SELECT DISTINCT v.name || ' → ' || st.name FROM vod v JOIN vod_category cat ON cat.id = v.categoryId
        JOIN vod_state st ON st.vkey = v.ckey
        WHERE v.libraryId = $ACTIVE AND v.kind = :kind AND (:category IS NULL OR cat.name = :category) AND st.name IS NOT NULL"""
    )
    abstract fun renamedItems(sourceKey: String, kind: VodKind, category: String?): List<String>

    @Query(
        """SELECT DISTINCT COALESCE(st.name, v.name) FROM vod v JOIN vod_category cat ON cat.id = v.categoryId
        JOIN vod_state st ON st.vkey = v.ckey
        WHERE v.libraryId = $ACTIVE AND v.kind = :kind AND (:category IS NULL OR cat.name = :category) AND st.hidden"""
    )
    abstract fun hiddenItems(sourceKey: String, kind: VodKind, category: String?): List<String>

    @Query(
        """SELECT cat.name AS name, cs.name AS title, cs.hidden AS hidden, cs.position AS position
        FROM vod_category cat JOIN vod_category_state cs ON cs.ckey = cat.ckey
        WHERE cat.libraryId = $ACTIVE AND cat.kind = :kind AND (:category IS NULL OR cat.name = :category)"""
    )
    abstract fun categoryEdits(sourceKey: String, kind: VodKind, category: String?): List<CategoryEdit>

    @Query(
        """SELECT cat.name FROM vod_category cat WHERE cat.libraryId = $ACTIVE AND cat.kind = :kind
        AND (:category IS NULL OR cat.name = :category) AND EXISTS (SELECT 1 FROM vod_order o WHERE o.list = cat.ckey)"""
    )
    abstract fun reorderedCategories(sourceKey: String, kind: VodKind, category: String?): List<String>

    @Query("UPDATE vod_state SET name = NULL, hidden = 0 WHERE vkey IN ($SOURCE_ITEMS)")
    abstract fun resetItems(sourceKey: String, kind: VodKind, category: String?)

    @Query("DELETE FROM vod_order WHERE list IN ($SOURCE_CATEGORIES)")
    abstract fun resetOrder(sourceKey: String, kind: VodKind, category: String?)

    @Query(
        """UPDATE vod_category_state SET name = NULL, hidden = 0,
        position = CASE WHEN :category IS NULL THEN NULL ELSE position END WHERE ckey IN ($SOURCE_CATEGORIES)"""
    )
    abstract fun resetCategories(sourceKey: String, kind: VodKind, category: String?)

    // --- Watching ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putWatch(rows: List<Watch>)

    @Query("SELECT * FROM watch WHERE vkey = :key")
    abstract suspend fun watch(key: String): Watch?

    @Query("DELETE FROM watch WHERE vkey IN (:keys)")
    abstract fun deleteWatch(keys: List<String>)

    @Query("DELETE FROM watch")
    abstract fun clearWatch()

    /** HOME-013: everything watched, the latest first; [kind] null = films and episodes. */
    @Query("SELECT * FROM watch WHERE :kind IS NULL OR kind = :kind ORDER BY watchedAt DESC")
    abstract fun history(kind: VodKind?): PagingSource<Int, Watch>

    /** HOME-013 screen: the latest [limit] rows. */
    @Query("SELECT * FROM watch ORDER BY watchedAt DESC LIMIT :limit")
    abstract fun latest(limit: Int): Flow<List<Watch>>

    /** HOME-006: the episode finished last (the series may go on with the next one). */
    @Query(
        """SELECT w.* FROM watch w WHERE w.kind = 'SERIES' AND w.watched
        AND NOT EXISTS (SELECT 1 FROM vod_state s WHERE s.vkey = w.seriesKey AND s.hidden)
        AND EXISTS (SELECT 1 FROM vod v JOIN library l ON l.id = v.libraryId
            LEFT JOIN vod_category_state cs ON cs.ckey = (SELECT ckey FROM vod_category WHERE id = v.categoryId)
            WHERE l.sourceKey = :sourceKey AND l.savedAt IS NOT NULL AND v.ckey = w.seriesKey AND COALESCE(cs.hidden, 0) = 0)
        ORDER BY w.watchedAt DESC LIMIT 1"""
    )
    abstract fun lastFinishedEpisode(sourceKey: String): Flow<Watch?>

    /**
     * HOME-002: started and not finished, the latest first: films, and the last episode played of each series.
     * Hidden films and series are left out (VOD-011).
     */
    @Query(
        """SELECT w.* FROM watch w WHERE w.position > 0 AND NOT w.watched
        AND (w.seriesKey IS NULL OR w.watchedAt = (SELECT MAX(x.watchedAt) FROM watch x WHERE x.seriesKey = w.seriesKey))
        AND NOT EXISTS (SELECT 1 FROM vod_state s WHERE s.vkey = COALESCE(w.seriesKey, w.vkey) AND s.hidden)
        AND (:sourceKey IS NULL OR EXISTS (SELECT 1 FROM vod v JOIN library l ON l.id = v.libraryId
            LEFT JOIN vod_category_state cs ON cs.ckey = (SELECT ckey FROM vod_category WHERE id = v.categoryId)
            WHERE l.sourceKey = :sourceKey AND l.savedAt IS NOT NULL AND v.ckey = COALESCE(w.seriesKey, w.vkey) AND COALESCE(cs.hidden, 0) = 0))
        ORDER BY w.watchedAt DESC LIMIT :limit"""
    )
    abstract fun unfinished(limit: Int, sourceKey: String?): Flow<List<Watch>>

    // --- BAK-011: everything, as stored ---
    @Query("SELECT * FROM vod_state ORDER BY vkey") abstract fun allStates(): List<VodState>
    @Query("SELECT * FROM vod_category_state ORDER BY ckey") abstract fun allCategoryStates(): List<VodCategoryState>
    @Query("SELECT * FROM vod_order ORDER BY list, position") abstract fun allOrder(): List<VodOrder>
    @Query("SELECT * FROM vod_collection ORDER BY kind, position, id") abstract fun allCollections(): List<VodCollection>
    @Query("SELECT vkey FROM vod_collection_item WHERE collectionId = :id ORDER BY position") abstract fun collectionKeys(id: Long): List<String>
    @Query("SELECT * FROM watch ORDER BY watchedAt DESC, vkey") abstract fun allWatch(): List<Watch>
    @Query("DELETE FROM vod_state") abstract fun clearStates()
    @Query("DELETE FROM vod_category_state") abstract fun clearCategoryStates()
    @Query("DELETE FROM vod_order") abstract fun clearAllOrder()
    @Query("DELETE FROM vod_collection") abstract fun clearCollections()
    @Query("DELETE FROM vod_collection_item") abstract fun clearCollectionItems()
    @Insert abstract fun insertStates(rows: List<VodState>)
    @Insert abstract fun insertCategoryStates(rows: List<VodCategoryState>)
    @Insert abstract fun insertCollectionItems(rows: List<VodCollectionItem>)
}

/** Library edits for the settings file (BAK-011), keyed by stable keys; [collections] of both sections. */
data class LibraryData(
    val items: List<VodState> = emptyList(),
    val categories: List<VodCategoryState> = emptyList(),
    val order: Map<String, List<String>> = emptyMap(),
    val collections: List<SavedVodCollection> = emptyList(),
)

data class SavedVodCollection(val kind: VodKind, val name: String, val items: List<String>)

/**
 * Favorites, hiding, names, manual order, collections (VOD-011, VOD-012) and watching (VOD-008, 010, HOME-002, 013)
 * of films and series. Blocking writes, each in one transaction; screens see changes through their flows.
 */
class LibraryUser(private val db: AppDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.libraryUser()

    private fun items(keys: List<String>, edit: () -> Unit) = db.runInTransaction {
        keys.forEach(dao::touch)
        edit()
        dao.prune()
    }

    fun setFavorite(key: String, on: Boolean) = items(listOf(key)) { dao.setFavorite(key, on) }
    fun setHidden(keys: List<String>, hidden: Boolean) = items(keys) { dao.setHidden(keys, hidden) }

    /** null or blank = the provider's name back. */
    fun rename(key: String, name: String?) = items(listOf(key)) { dao.rename(key, name?.trim()?.ifEmpty { null }) }

    private fun categories(sourceKey: String, kind: VodKind, names: List<String>, edit: (List<String>) -> Unit) = db.runInTransaction {
        val byName = dao.categoryKeys(sourceKey, kind).associate { it.name to it.ckey }
        val keys = names.mapNotNull(byName::get)
        keys.forEach(dao::touchCategory)
        edit(keys)
        dao.pruneCategories()
    }

    fun setCategoriesHidden(sourceKey: String, kind: VodKind, names: List<String>, hidden: Boolean) =
        categories(sourceKey, kind, names) { dao.setCategoryHidden(it, hidden) }

    fun renameCategory(sourceKey: String, kind: VodKind, name: String, title: String?) =
        categories(sourceKey, kind, listOf(name)) { keys -> keys.forEach { dao.renameCategory(it, title?.trim()?.ifEmpty { null }) } }

    fun reorderCategories(sourceKey: String, kind: VodKind, names: List<String>) =
        categories(sourceKey, kind, names) { keys -> keys.forEachIndexed { i, k -> dao.setCategoryPosition(k, i) } }

    /** The new order of a list ([keys] as the user arranged them); in a category, items added later go after these. */
    fun reorder(sourceKey: String, kind: VodKind, group: VodGroup, keys: List<String>) = db.runInTransaction {
        when (group) {
            VodGroup.Favorites -> keys.forEachIndexed { i, k -> dao.setFavoritePosition(k, i) }
            is VodGroup.Collection -> keys.forEachIndexed { i, k -> dao.setCollectionItemPosition(group.id, k, i) }
            is VodGroup.Category -> {
                val list = dao.categoryKeys(sourceKey, kind).find { it.name == group.name }?.ckey ?: return@runInTransaction
                dao.clearOrder(list)
                dao.insertOrder(keys.distinct().mapIndexed { i, k -> VodOrder(list, k, i) })
            }
            else -> throw IllegalArgumentException("$group has no manual order")
        }
    }

    fun collections(kind: VodKind): Flow<List<VodCollection>> = dao.collections(kind)

    fun createCollection(kind: VodKind, name: String): Long = db.runInTransaction<Long> {
        dao.insertCollection(VodCollection(kind = kind, name = name.trim(), position = dao.nextCollectionPosition(kind)))
    }

    fun renameCollection(id: Long, name: String) = dao.renameCollection(id, name.trim())
    fun reorderCollections(ids: List<Long>) = db.runInTransaction { ids.forEachIndexed { i, id -> dao.setCollectionPosition(id, i) } }
    fun deleteCollection(id: Long) = db.runInTransaction {
        dao.clearCollection(id)
        dao.deleteCollectionRow(id)
    }

    fun addToCollection(id: Long, key: String) = dao.addToCollection(id, key)
    fun removeFromCollection(id: Long, key: String) = dao.removeFromCollection(id, key)

    /** What [reset] would undo in a section, for [category] (null = all its categories). */
    fun edits(sourceKey: String, kind: VodKind, category: String?): Edits {
        val cats = dao.categoryEdits(sourceKey, kind, category)
        return Edits(
            renamed = cats.filter { it.title != null }.map { "${it.name} → ${it.title}" } + dao.renamedItems(sourceKey, kind, category),
            hidden = cats.filter { it.hidden }.map { it.title ?: it.name } + dao.hiddenItems(sourceKey, kind, category),
            reordered = dao.reorderedCategories(sourceKey, kind, category),
            categoryOrder = category == null && cats.any { it.position != null },
        )
    }

    /** Names, hiding and order of a section's [category] (null = all, and the category order) back as the provider has them. */
    fun reset(sourceKey: String, kind: VodKind, category: String?) = db.runInTransaction {
        dao.resetItems(sourceKey, kind, category)
        dao.resetOrder(sourceKey, kind, category)
        dao.resetCategories(sourceKey, kind, category)
        dao.prune()
        dao.pruneCategories()
    }

    // --- Watching ---

    /**
     * VOD-010: the position of [w] at [position] of [duration] (ms). Near the end (from [Defaults.WATCHED_SHARE] of
     * it, or the last [Defaults.END_MARGIN_MS]) the item counts as watched (VOD-008) and no position is kept.
     */
    fun savePosition(w: Watch, position: Long, duration: Long) {
        val done = finished(position, duration)
        dao.putWatch(listOf(w.copy(position = if (done) 0 else position.coerceAtLeast(0), duration = duration.coerceAtLeast(0), watched = done, watchedAt = clock())))
    }

    /** VOD-008 "Отметить просмотренным / непросмотренным" for a film, an episode or a season's episodes. Unmarked = forgotten. */
    fun setWatched(list: List<Watch>, watched: Boolean) = db.runInTransaction {
        if (watched) dao.putWatch(list.map { it.copy(position = 0, watched = true, watchedAt = clock()) })
        else list.map { it.vkey }.chunked(500).forEach(dao::deleteWatch)
    }

    suspend fun watch(key: String): Watch? = dao.watch(key)

    /** HOME-013 "Удалить": the rows go with their marks and positions. */
    fun forget(keys: List<String>) = keys.chunked(500).forEach(dao::deleteWatch)
    fun clearHistory() = dao.clearWatch()
    fun history(kind: VodKind?): PagingSource<Int, Watch> = dao.history(kind)
    fun unfinished(limit: Int, sourceKey: String? = null): Flow<List<Watch>> = dao.unfinished(limit, sourceKey)
    fun latest(limit: Int): Flow<List<Watch>> = dao.latest(limit)
    fun lastFinishedEpisode(sourceKey: String): Flow<Watch?> = dao.lastFinishedEpisode(sourceKey)

    // --- BAK-011 ---

    fun dump(): LibraryData = db.runInTransaction<LibraryData> {
        LibraryData(
            items = dao.allStates(),
            categories = dao.allCategoryStates(),
            order = dao.allOrder().groupBy({ it.list }, { it.vkey }),
            collections = dao.allCollections().map { SavedVodCollection(it.kind, it.name, dao.collectionKeys(it.id)) },
        )
    }

    fun watched(): List<Watch> = dao.allWatch()

    internal fun replace(d: LibraryData) = db.runInTransaction {
        dao.clearStates()
        dao.clearCategoryStates()
        dao.clearAllOrder()
        dao.clearCollectionItems()
        dao.clearCollections()
        dao.insertStates(d.items.distinctBy { it.vkey })
        dao.insertCategoryStates(d.categories.distinctBy { it.ckey })
        for ((list, keys) in d.order) dao.insertOrder(keys.distinct().mapIndexed { i, k -> VodOrder(list, k, i) })
        d.collections.forEachIndexed { i, c ->
            val id = dao.insertCollection(VodCollection(kind = c.kind, name = c.name, position = i))
            dao.insertCollectionItems(c.items.distinct().mapIndexed { p, k -> VodCollectionItem(id, k, p) })
        }
        dao.prune()
        dao.pruneCategories()
    }

    internal fun replaceWatched(list: List<Watch>) = db.runInTransaction {
        dao.clearWatch()
        list.distinctBy { it.vkey }.chunked(500).forEach(dao::putWatch)
    }
}

/** VOD-008/010: at [position] of [duration] the item is watched (the end is near); an unknown duration never is. */
fun finished(position: Long, duration: Long): Boolean =
    duration > 0 && (position >= duration * Defaults.WATCHED_SHARE || duration - position <= Defaults.END_MARGIN_MS)
