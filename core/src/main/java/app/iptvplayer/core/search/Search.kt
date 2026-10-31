package app.iptvplayer.core.search

import app.iptvplayer.core.db.ProgrammeHit
import app.iptvplayer.core.db.searchText
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.VodItem

/** The kinds of results of the global search (HOME-007, HOME-008 filters). */
enum class SearchKind { CHANNELS, PROGRAMMES, MOVIES, SERIES }

/**
 * HOME-007: one query across the source — up to the limit of each kind, best matches first ([searchRank]), with the
 * number found of each kind ([counts]; programmes are counted up to the limit). Empty for an empty query.
 */
data class SearchResults(
    val query: String = "",
    val channels: List<ChannelItem> = emptyList(),
    val programmes: List<ProgrammeHit> = emptyList(),
    val movies: List<VodItem> = emptyList(),
    val series: List<VodItem> = emptyList(),
    val counts: Map<SearchKind, Int> = emptyMap(),
) {
    fun count(kind: SearchKind): Int = counts[kind] ?: 0

    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/**
 * HOME-010 ranking of a name that matches [query] (every query word starts a word of the name): 0 = the name is the
 * query, 1 = the name begins with it (its words from the first one), 2 = the words are found further in.
 */
fun searchRank(name: String, query: String): Int {
    val n = words(name)
    val q = words(query)
    if (q.isEmpty()) return 2
    if (n == q) return 0
    // Every query word is a prefix: «челов-пау» starts «человек-паук».  Requiring the
    // earlier words to be complete made that query rank as a loose match.
    val begins = q.size <= n.size && q.indices.all { i -> n[i].startsWith(q[i]) }
    return if (begins) 1 else 2
}

/** Best matches first; equal ones keep their order (the section's own order). */
fun <T> List<T>.ranked(query: String, name: (T) -> String): List<T> = sortedBy { searchRank(name(it), query) }

private fun words(s: String) = searchText(s).split(' ').filter { it.isNotEmpty() }
