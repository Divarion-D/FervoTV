package app.iptvplayer.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.iptvplayer.core.CoreException
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.ErrorCode
import app.iptvplayer.core.epg.AutoMatch
import app.iptvplayer.core.epg.EpgEntry
import app.iptvplayer.core.epg.EpgIndex
import app.iptvplayer.core.epg.XmltvChannel
import app.iptvplayer.core.epg.autoMatch
import app.iptvplayer.core.epg.parseXmltv
import app.iptvplayer.core.epg.suggestEpg
import app.iptvplayer.core.epg.Suggestion
import app.iptvplayer.core.logos.normName
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Programme
import kotlinx.coroutines.flow.Flow
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream

/** A guide source (EPG-001): an XMLTV address with its own User-Agent, schedule, time shift and last result. */
@Entity(tableName = "epg_source")
data class EpgSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    /** null = [Defaults.USER_AGENT]. */
    val userAgent: String? = null,
    val refresh: PlaylistRefresh = Defaults.EPG_REFRESH,
    val refreshDays: Int = Defaults.PLAYLIST_REFRESH_DAYS,
    /** Time shift of this guide in minutes (EPG-007); null = the global one. A channel's own shift wins over it. */
    val shift: Int? = null,
    /** Priority among guides: the first one that has a channel is used. */
    val position: Int = 0,
    /** Last successful import. */
    val savedAt: Long? = null,
    /** Last attempt, successful or not (for the schedule, as [PlaylistRefresh.waitMs] wants). */
    val attemptAt: Long? = null,
    /** Result of the last attempt for the user: what was loaded and matched, or the error. */
    val status: String? = null,
)

/** A channel of a guide; ids are kept across refreshes of the same source ([sourceId] + [xmltvId]). */
@Entity(tableName = "epg_channel", indices = [Index("sourceId", "xmltvId", unique = true)])
data class EpgChannel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val xmltvId: String,
    /** display-names, "\n"-separated. */
    val names: String,
    val icon: String?,
)

/** Times as in the guide (epoch ms UTC), the shift is applied when reading. */
@Entity(tableName = "programme", indices = [Index("channelId", "start")])
data class ProgrammeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Programmes of an import in progress. Screens observe [ProgrammeRow]; writing the import here and moving it over in
 * one transaction at the end makes them redraw once per update, not once per batch (EPG-003).
 */
@Entity(tableName = "programme_stage")
data class StagedProgramme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/** Programme as read for a list: [channelId] = [EpgChannel.id]. */
data class ProgrammeAt(val channelId: Long, val start: Long, val stop: Long, val title: String, val description: String?)

data class IdKey(val id: Long, val xmltvId: String)

/** [EpgSource] of the Xtream short EPG: one hidden source, its channels keyed by [Channel.key]. */
internal const val SHORT_EPG_URL = "xtream:short_epg"

@Dao
abstract class EpgDao {
    @Query("SELECT * FROM epg_source WHERE url != '$SHORT_EPG_URL' ORDER BY position")
    abstract fun sources(): Flow<List<EpgSource>>

    @Query("SELECT * FROM epg_source WHERE url != '$SHORT_EPG_URL' ORDER BY position")
    abstract suspend fun sourceList(): List<EpgSource>

    @Query("SELECT * FROM epg_source WHERE url != '$SHORT_EPG_URL' ORDER BY position")
    abstract fun sourceRows(): List<EpgSource>

    @Query("SELECT * FROM epg_source WHERE id = :id")
    abstract fun source(id: Long): EpgSource?

    @Query("SELECT * FROM epg_source WHERE url = :url")
    abstract fun sourceByUrl(url: String): EpgSource?

    @Insert abstract fun insertSource(s: EpgSource): Long
    @Update abstract fun updateSource(s: EpgSource)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM epg_source WHERE url != '$SHORT_EPG_URL'")
    abstract fun nextPosition(): Int

    @Query("UPDATE epg_source SET attemptAt = :at, status = :status WHERE id = :id")
    abstract fun attempted(id: Long, at: Long, status: String?)

    @Query("UPDATE epg_source SET savedAt = :at, attemptAt = :at, status = :status WHERE id = :id")
    abstract fun saved(id: Long, at: Long, status: String)

    @Query("DELETE FROM epg_source WHERE id = :id")
    abstract fun deleteSourceRow(id: Long)

    @Query("SELECT id, xmltvId FROM epg_channel WHERE sourceId = :sourceId")
    abstract fun channelKeys(sourceId: Long): List<IdKey>

    @Insert abstract fun insertChannel(c: EpgChannel): Long

    @Query("UPDATE epg_channel SET names = :names, icon = :icon WHERE id = :id")
    abstract fun updateChannel(id: Long, names: String, icon: String?)

    @Query("DELETE FROM epg_channel WHERE id IN (:ids)")
    abstract fun deleteChannels(ids: List<Long>)

    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId")
    abstract fun deleteSourceChannels(sourceId: Long)

    /** Every guide channel in priority order, for matching. */
    @Query("SELECT e.* FROM epg_channel e JOIN epg_source s ON s.id = e.sourceId ORDER BY s.position, e.id")
    abstract fun allChannels(): List<EpgChannel>

    @Query("SELECT sourceId FROM epg_channel WHERE id = :id")
    abstract fun channelSource(id: Long): Long?

    @Query("SELECT * FROM epg_channel WHERE sourceId = :sourceId AND xmltvId = :xmltvId")
    abstract fun channel(sourceId: Long, xmltvId: String): EpgChannel?

    @Insert abstract fun stage(list: List<StagedProgramme>)

    @Query("DELETE FROM programme_stage")
    abstract fun clearStage()

    @Query("DELETE FROM programme WHERE channelId IN (SELECT id FROM epg_channel WHERE sourceId = :sourceId)")
    abstract fun deleteSourceProgrammes(sourceId: Long)

    @Query("DELETE FROM programme WHERE channelId = :channelId")
    abstract fun deleteChannelProgrammes(channelId: Long)

    @Insert abstract fun insertProgrammes(list: List<ProgrammeRow>)

    @Query(
        """INSERT INTO programme(channelId, start, stop, title, description)
        SELECT e.id, s.start, s.stop, s.title, s.description FROM programme_stage s
        JOIN epg_channel e ON e.sourceId = :sourceId AND e.xmltvId = s.channel"""
    )
    abstract fun moveStage(sourceId: Long): Long

    @Query("DELETE FROM programme WHERE stop < :before")
    abstract fun deleteEndedBefore(before: Long)

    @Query("SELECT COUNT(*) FROM programme_stage")
    abstract fun stagedCount(): Int

    @Query("SELECT MAX(stop) FROM programme WHERE channelId = :channelId")
    abstract fun lastStop(channelId: Long): Long?

    @Query("SELECT COALESCE(MAX(archiveDays), 0) FROM channel WHERE sourceId IN (SELECT id FROM source WHERE savedAt IS NOT NULL)")
    abstract fun maxArchiveDays(): Int

    @Query("SELECT id FROM source WHERE savedAt IS NOT NULL")
    abstract fun savedSources(): List<Long>

    @Query("UPDATE channel SET epgId = :epgId WHERE id = :id")
    abstract fun setChannelEpg(id: Long, epgId: Long?)

    @Query("SELECT id, epgId FROM channel WHERE sourceId = :sourceId AND id > :after ORDER BY id LIMIT :limit")
    abstract fun channelEpgAfter(sourceId: Long, after: Long, limit: Int): List<IdEpg>

    /** Saved channels bound to a guide channel of [sourceId]. */
    @Query(
        """SELECT COUNT(*) FROM channel WHERE sourceId IN (SELECT id FROM source WHERE savedAt IS NOT NULL)
        AND epgId IN (SELECT id FROM epg_channel WHERE sourceId = :sourceId)"""
    )
    abstract fun matchedCount(sourceId: Long): Int

    @Query(
        """SELECT channelId, start, stop, title, description FROM programme
        WHERE channelId IN (:channels) AND start < :to AND stop > :from ORDER BY channelId, start"""
    )
    abstract fun programmes(channels: List<Long>, from: Long, to: Long): List<ProgrammeAt>

    @RawQuery
    abstract fun programmesAt(q: SupportSQLiteQuery): List<ProgrammeAt>

    /** Guide channel and shift of a saved channel. */
    @Query(
        """SELECT ch.epgId AS epgId, COALESCE(st.epgShift, es.shift) AS shift FROM channel ch
        LEFT JOIN channel_state st ON st.ckey = ch.ckey
        LEFT JOIN epg_channel e ON e.id = ch.epgId LEFT JOIN epg_source es ON es.id = e.sourceId
        WHERE ch.sourceId = (SELECT id FROM source WHERE sourceKey = :sourceKey AND savedAt IS NOT NULL) AND ch.ckey = :key
        LIMIT 1"""
    )
    abstract fun binding(sourceKey: String, key: String): EpgBinding?
}

data class IdEpg(val id: Long, val epgId: Long?)

data class EpgBinding(val epgId: Long?, val shift: Int?)

/**
 * Result of a guide import, also written as the source's [EpgSource.status]. [matched] = 0: no playlist channel is in
 * this guide; [damaged]: the file broke off, what came before the damage is loaded.
 */
data class EpgResult(val channels: Int, val programmes: Int, val matched: Int, val damaged: Boolean = false) {
    internal fun status(): String = "ok $channels $programmes $matched ${if (damaged) 1 else 0}"
}

/** [EpgSource.status] as data, for the apps to word in the interface language (UI-022). */
sealed interface EpgStatus {
    data class Loaded(val result: EpgResult) : EpgStatus

    /** [code] null = not one of core's own failures (network, HTTP); [detail] = its technical text. */
    data class Failed(val code: ErrorCode?, val detail: String?) : EpgStatus
}

/** The status of a failed refresh. */
internal fun failedStatus(e: Exception): String {
    val code = (e as? CoreException)?.code
    val detail = if (e is CoreException) e.detail else e.message ?: e.javaClass.simpleName
    return "error ${code?.name ?: "-"}" + (detail?.let { " $it" } ?: "")
}

/** Null: never refreshed, or a status written by an older version (it is replaced by the next refresh). */
fun EpgSource.lastStatus(): EpgStatus? {
    val parts = status?.split(' ', limit = 3) ?: return null
    return when (parts[0]) {
        "ok" -> parts.getOrNull(2)?.split(' ')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 3 }?.let { (p, m, d) ->
            parts[1].toIntOrNull()?.let { EpgStatus.Loaded(EpgResult(it, p, m, d == 1)) }
        }
        "error" -> EpgStatus.Failed(ErrorCode.entries.find { it.name == parts.getOrNull(1) }, parts.getOrNull(2))
        else -> null
    }
}

/**
 * The guide: sources (EPG-001), streaming XMLTV import (EPG-003), binding to channels (EPG-004/005),
 * programmes for lists, the player and the grid (EPG-006/008). Blocking unless suspend; one import at a time
 * (the caller serializes them).
 */
class EpgStore(private val db: AppDb) {
    private val dao = db.epg()
    private val user = db.user()

    fun sources(): Flow<List<EpgSource>> = dao.sources()

    suspend fun sourceList(): List<EpgSource> = dao.sourceList()

    fun source(id: Long): EpgSource? = dao.source(id)

    /** Adds an XMLTV address (the same one twice is the same source). Doesn't download it. */
    fun addSource(url: String): EpgSource = db.runInTransaction<EpgSource> {
        val u = url.trim()
        dao.sourceByUrl(u) ?: dao.insertSource(EpgSource(url = u, position = dao.nextPosition())).let { dao.source(it)!! }
    }

    fun updateSource(s: EpgSource) = db.runInTransaction {
        dao.updateSource(s)
        rebind()
    }

    fun removeSource(id: Long) = db.runInTransaction {
        dao.deleteSourceProgrammes(id)
        dao.deleteSourceChannels(id)
        dao.deleteSourceRow(id)
        rebind()
    }

    fun attempted(id: Long, at: Long, status: String?) = dao.attempted(id, at, status)

    /** BAK-011: guide sources in priority order (the Xtream short EPG is not one of them). */
    fun sourceRows(): List<EpgSource> = dao.sourceRows()

    /**
     * BAK-011: the guide sources become [list] (address, User-Agent, schedule, shift; in priority order). Their
     * guides are gone until downloaded again: new sources are due right away. The short EPG stays.
     */
    internal fun replaceSources(list: List<EpgSource>) = db.runInTransaction {
        for (s in dao.sourceRows()) {
            dao.deleteSourceProgrammes(s.id)
            dao.deleteSourceChannels(s.id)
            dao.deleteSourceRow(s.id)
        }
        list.distinctBy { it.url }.forEachIndexed { i, s ->
            dao.insertSource(s.copy(id = 0, position = i, savedAt = null, attemptAt = null, status = null))
        }
        rebind()
    }

    /** Guide channels in priority order plus the user's bindings: what playlist imports bind channels with. */
    fun index(): EpgIndex = EpgIndex(entries(), user.epgBindings().associate { it.ckey to it.epg })

    /** Guide channels in priority order; [withShort] = with the Xtream short EPG's per-channel entries. */
    private fun entries(withShort: Boolean = true): List<EpgEntry> {
        val short = if (withShort) null else dao.sourceByUrl(SHORT_EPG_URL)?.id
        return dao.allChannels().filter { it.sourceId != short }
            .map { EpgEntry(it.id, it.xmltvId, it.names.split('\n').filter(String::isNotEmpty), it.icon) }
    }

    /**
     * Imports an XMLTV document into source [sourceId] (EPG-003): streamed (memory doesn't grow with the file), only
     * programmes of channels the saved playlists have (by tvg-id, name or the user's binding) and only within
     * [days] ahead and the past that is kept ([Defaults.EPG_PAST_MS], or the archive depth). Screens keep the old
     * guide until the end, when the new one replaces it in one transaction. A file damaged in the middle keeps what
     * was read before the damage. [tick] is called per programme (cancellation).
     */
    fun import(sourceId: Long, input: InputStream, days: Int, now: Long = System.currentTimeMillis(), tick: () -> Unit = {}): EpgResult {
        val wanted = Wanted(this)
        val past = maxOf(Defaults.EPG_PAST_MS, dao.maxArchiveDays() * DAY_MS)
        val from = now - past
        val to = now + days * DAY_MS
        val channels = LinkedHashMap<String, XmltvChannel>()
        val batch = ArrayList<StagedProgramme>(Defaults.EPG_BATCH_SIZE)
        var programmes = 0
        fun flush() {
            if (batch.isEmpty()) return
            db.runInTransaction { dao.stage(batch) }
            batch.clear()
        }
        dao.clearStage() // left by a killed process
        var damaged = false
        try {
            parseXmltv(
                input,
                wants = wanted::wants,
                onChannel = { ch -> channels[ch.id] = ch; wanted.channel(ch) },
                onProgramme = { p ->
                    tick()
                    if (p.stop > from && p.start < to) {
                        batch += StagedProgramme(channel = p.channel, start = p.start, stop = p.stop, title = p.title, description = p.description)
                        programmes++
                        if (batch.size >= Defaults.EPG_BATCH_SIZE) flush()
                    }
                },
            )
        } catch (e: XmlPullParserException) {
            if (programmes == 0 && channels.isEmpty()) { dao.clearStage(); throw e }
            damaged = true
        } catch (e: Throwable) {
            dao.clearStage()
            throw e
        }
        flush()
        return db.runInTransaction<EpgResult> {
            val existing = dao.channelKeys(sourceId).associate { it.xmltvId to it.id }
            for (ch in channels.values) {
                val names = ch.names.joinToString("\n")
                val id = existing[ch.id]
                if (id == null) dao.insertChannel(EpgChannel(sourceId = sourceId, xmltvId = ch.id, names = names, icon = ch.icon))
                else dao.updateChannel(id, names, ch.icon)
            }
            existing.filterKeys { it !in channels }.values.chunked(MAX_ARGS).forEach(dao::deleteChannels)
            dao.deleteSourceProgrammes(sourceId)
            // Programmes of channels the file doesn't declare are dropped by this join.
            dao.moveStage(sourceId)
            dao.clearStage()
            dao.deleteEndedBefore(from)
            rebind()
            EpgResult(channels.size, programmes, dao.matchedCount(sourceId), damaged).also { dao.saved(sourceId, now, it.status()) }
        }
    }

    /** Which XMLTV channels the saved playlists need. */
    private class Wanted(store: EpgStore) {
        private val ids = HashSet<String>()
        private val names = HashSet<String>()
        private val decided = HashMap<String, Boolean>()

        init {
            store.user.epgBindings().forEach { ids += it.epg.lowercase() }
            for (sourceId in store.dao.savedSources()) for (ch in store.channelsOf(sourceId)) {
                ch.tvgId?.let { ids += it.lowercase() }
                normName(ch.name).takeIf { it.isNotEmpty() }?.let(names::add)
            }
        }

        fun channel(ch: XmltvChannel) {
            decided[ch.id] = ch.id.lowercase() in ids || ch.names.any { normName(it) in names }
        }

        fun wants(id: String): Boolean = decided.getOrPut(id) { id.lowercase() in ids }
    }

    /** Pages of a source's channels: never the whole list in memory. */
    private fun channelsOf(sourceId: Long): Sequence<Channel> = sequence {
        var after = 0L
        while (true) {
            val page = db.playlist().channelsAfter(sourceId, after, Defaults.IMPORT_BATCH_SIZE)
            yieldAll(page)
            after = page.lastOrNull()?.id ?: break
        }
    }

    /**
     * Binds every saved channel to its guide channel again ([EpgIndex]) and writes only what changed.
     * After a guide import, a source change or a user binding.
     */
    fun rebind() = db.runInTransaction {
        val index = index()
        for (sourceId in dao.savedSources()) forEachPage(sourceId) { page, current ->
            for (ch in page) index.find(ch).let { if (it != current[ch.id]) dao.setChannelEpg(ch.id, it) }
        }
    }

    /** Pages of a source's channels with their current guide channel. */
    private fun forEachPage(sourceId: Long, block: (List<Channel>, Map<Long, Long?>) -> Unit) {
        var after = 0L
        while (true) {
            val page = db.playlist().channelsAfter(sourceId, after, Defaults.IMPORT_BATCH_SIZE)
            if (page.isEmpty()) return
            block(page, dao.channelEpgAfter(sourceId, after, Defaults.IMPORT_BATCH_SIZE).associate { it.id to it.epgId })
            after = page.last().id
        }
    }

    /**
     * EPG-005 "Сопоставить автоматически" for the saved channels of [sourceKey] without a guide: sure pairs are bound
     * right away, the unsure ones are returned for review. Returns the guide sources whose programmes for the newly
     * bound channels aren't stored yet (they have to be downloaded again).
     */
    fun autoMatch(sourceKey: String): Pair<AutoMatch, Set<Long>> {
        val sourceId = db.playlist().activeSource(sourceKey) ?: return AutoMatch(emptyList(), emptyList()) to emptySet()
        val unbound = ArrayList<Channel>()
        forEachPage(sourceId) { page, current -> page.filterTo(unbound) { current[it.id] == null } }
        val result = autoMatch(unbound.distinctBy { it.key }, entries(withShort = false))
        val refetch = bind(result.sure.map { (ch, e) -> Triple(ch.key, e.xmltvId, e.icon) }, takeLogo = false)
        return result to refetch
    }

    /** EPG-005 "Привязать телегид": candidates for one channel, the most similar first. */
    fun suggestions(name: String): List<Suggestion> = suggestEpg(name, entries(withShort = false))

    /**
     * The user's bindings (key, XMLTV id or null to unbind, guide logo); see [bind]. Returns the guide sources that
     * have the newly bound channels but not their programmes (stored only for channels that were wanted at import).
     */
    fun bind(bindings: List<Triple<String, String?, String?>>, takeLogo: Boolean): Set<Long> = db.runInTransaction<Set<Long>> {
        UserStore(db).bindEpg(bindings, takeLogo) // nested transaction
        rebind()
        val ids = bindings.mapNotNull { it.second?.lowercase() }.toSet()
        dao.allChannels().filter { it.xmltvId.lowercase() in ids && dao.lastStop(it.id) == null }.map { it.sourceId }.toSet()
    }

    /** Stores an Xtream short EPG of one channel ([Channel.key]) and binds the channel to it if no XMLTV covers it. */
    fun storeShortEpg(key: String, programmes: List<Programme>, now: Long = System.currentTimeMillis()) = db.runInTransaction {
        val sourceId = dao.sourceByUrl(SHORT_EPG_URL)?.id
            ?: dao.insertSource(EpgSource(url = SHORT_EPG_URL, position = Int.MAX_VALUE))
        val channelId = dao.channel(sourceId, key)?.id ?: dao.insertChannel(EpgChannel(sourceId = sourceId, xmltvId = key, names = "", icon = null))
        dao.deleteChannelProgrammes(channelId)
        dao.insertProgrammes(programmes.map { ProgrammeRow(channelId = channelId, start = it.start, stop = it.stop, title = it.title, description = it.description) })
        dao.saved(sourceId, now, "")
        rebind()
    }

    /** Is the saved channel [key] without an XMLTV guide (unbound, or bound to its own short EPG)? */
    fun needsShortEpg(sourceKey: String, key: String): Boolean {
        val b = dao.binding(sourceKey, key) ?: return false
        val epgId = b.epgId ?: return true
        return dao.channelSource(epgId) == dao.sourceByUrl(SHORT_EPG_URL)?.id
    }

    fun binding(sourceKey: String, key: String): EpgBinding? = dao.binding(sourceKey, key)

    /**
     * Programmes around moments, one query for a whole page of channels (no query per channel): for every
     * (guide channel, moment) pair the programme on at that moment (or the last one before it), the next one and,
     * with [previous], the one before. Moments are in guide time (without shift).
     */
    internal fun around(at: Collection<Pair<Long, Long>>, previous: Boolean = false): List<ProgrammeAt> {
        if (at.isEmpty()) return emptyList()
        // Integers only, so they are written into the SQL: no limit on bound arguments, one statement.
        val values = at.joinToString(",") { (ch, t) -> "($ch,$t)" }
        // Each is an index seek on (channelId, start).
        fun pick(where: String) = "SELECT (SELECT id FROM programme WHERE channelId = v.column1 AND $where) FROM (VALUES $values) v"
        val sql = listOfNotNull(
            pick("start <= v.column2 ORDER BY start DESC LIMIT 1"),
            pick("start > v.column2 ORDER BY start LIMIT 1"),
            if (previous) pick("start <= v.column2 ORDER BY start DESC LIMIT 1 OFFSET 1") else null,
        ).joinToString(" UNION ALL ")
        return dao.programmesAt(
            SimpleSQLiteQuery("SELECT channelId, start, stop, title, description FROM programme WHERE id IN ($sql) ORDER BY channelId, start")
        )
    }

    /** Programmes of [channels] overlapping [from, to) (guide time), in start order per channel. */
    internal fun programmes(channels: List<Long>, from: Long, to: Long): List<ProgrammeAt> =
        if (channels.isEmpty()) emptyList() else channels.chunked(MAX_ARGS).flatMap { dao.programmes(it, from, to) }
}

/** The shift is applied: [Programme] times are what the user sees. */
internal fun ProgrammeAt.shifted(shiftMs: Long) = Programme(start + shiftMs, stop + shiftMs, title, description)

private const val DAY_MS = 24 * 3_600_000L

/** SQLite's limit of bound arguments on old Android is 999. */
private const val MAX_ARGS = 900
