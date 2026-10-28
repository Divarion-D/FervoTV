package app.iptvplayer.core.epg

import app.iptvplayer.core.Defaults
import app.iptvplayer.core.logos.normName
import app.iptvplayer.core.model.Channel

/** A guide channel as matching sees it; [names] = its display-names. */
class EpgEntry(val id: Long, val xmltvId: String, val names: List<String>, val icon: String?) {
    val title: String get() = names.firstOrNull() ?: xmltvId
}

/**
 * Exact binding of playlist channels to guide channels (EPG-004), in this order: the user's binding (by XMLTV id),
 * tvg-id / Xtream epg_channel_id (case-insensitive), the normalized name ([normName], as for logos), and last the
 * channel's own Xtream short EPG (its guide channel is keyed by [Channel.key]).
 * [entries] go in priority order: when several guides have the channel, the first one wins.
 */
class EpgIndex(entries: List<EpgEntry>, private val manual: Map<String, String>) {
    private val byId = HashMap<String, Long>()
    private val byName = HashMap<String, Long>()

    init {
        for (e in entries) {
            byId.getOrPut(e.xmltvId.lowercase()) { e.id }
            for (n in e.names) normName(n).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { e.id } }
        }
    }

    /** A user binding whose guide channel is gone (source removed) falls back to the automatic one. */
    fun find(ch: Channel): Long? =
        manual[ch.key]?.let { byId[it.lowercase()] }
            ?: ch.tvgId?.let { byId[it.lowercase()] }
            ?: normName(ch.name).takeIf { it.isNotEmpty() }?.let(byName::get)
            ?: byId[ch.key.lowercase()]

    companion object {
        val EMPTY = EpgIndex(emptyList(), emptyMap())
    }
}

class Suggestion(val entry: EpgEntry, val score: Double)

/** EPG-005: [sure] pairs are applied right away, [unsure] ones go to the review list with the best candidates first. */
class AutoMatch(val sure: List<Pair<Channel, EpgEntry>>, val unsure: List<Pair<Channel, List<Suggestion>>>)

/** Guide channels for [channels] (those without a guide) by name similarity. */
fun autoMatch(channels: List<Channel>, entries: List<EpgEntry>): AutoMatch {
    val prepared = prepare(entries)
    val sure = ArrayList<Pair<Channel, EpgEntry>>()
    val unsure = ArrayList<Pair<Channel, List<Suggestion>>>()
    for (ch in channels) {
        val top = rank(Name(ch.name), prepared).take(Defaults.EPG_MATCH_SUGGESTIONS)
        val best = top.firstOrNull() ?: continue
        val second = top.getOrNull(1)?.score ?: 0.0
        when {
            best.score >= Defaults.EPG_MATCH_SURE && best.score - second >= Defaults.EPG_MATCH_MARGIN -> sure += ch to best.entry
            best.score >= Defaults.EPG_MATCH_MIN -> unsure += ch to top.filter { it.score >= Defaults.EPG_MATCH_MIN }
        }
    }
    return AutoMatch(sure, unsure)
}

/** "Привязать телегид": every guide channel that looks anything like [name], the most similar first. */
fun suggestEpg(name: String, entries: List<EpgEntry>): List<Suggestion> = rank(Name(name), prepare(entries))

private fun prepare(entries: List<EpgEntry>) = entries.map { e -> e to e.names.ifEmpty { listOf(e.xmltvId) }.map(::Name) }

private fun rank(name: Name, prepared: List<Pair<EpgEntry, List<Name>>>): List<Suggestion> =
    prepared.mapNotNull { (e, names) -> names.maxOf { name.similarity(it) }.takeIf { it > 0 }?.let { Suggestion(e, it) } }
        .sortedByDescending { it.score }

/**
 * A name prepared for comparison: [normName] and its letter pairs. Similarity is the Dice coefficient of the pairs,
 * but only between names with the same numbers: «Россия 1» and «Россия 24» are different channels however alike.
 */
private class Name(raw: String) {
    val norm = normName(raw)
    val numbers = norm.filter { it in '0'..'9' }
    val pairs: IntArray = IntArray((norm.length - 1).coerceAtLeast(0)) { (norm[it].code shl 16) or norm[it + 1].code }.apply { sort() }

    fun similarity(o: Name): Double {
        if (numbers != o.numbers || norm.isEmpty() || o.norm.isEmpty()) return 0.0
        if (norm == o.norm) return 1.0
        if (pairs.isEmpty() || o.pairs.isEmpty()) return 0.0
        var i = 0
        var j = 0
        var common = 0
        while (i < pairs.size && j < o.pairs.size) {
            val a = pairs[i]
            val b = o.pairs[j]
            when {
                a == b -> { common++; i++; j++ }
                a < b -> i++
                else -> j++
            }
        }
        return 2.0 * common / (pairs.size + o.pairs.size)
    }
}
