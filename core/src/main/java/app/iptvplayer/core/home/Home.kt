package app.iptvplayer.core.home

import app.iptvplayer.core.db.Watch
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.VodKind

/*
 * Pure rules of the home screen, quick continue and history (HOME-001…006, HOME-013): the screens ask these.
 */

/** Rows of the home screen (HOME-001…004), in their default order. */
enum class HomeRow { CONTINUE, FAVORITE_CHANNELS, FAVORITE_MOVIES, FAVORITE_SERIES, RECENT_CHANNELS }

/** A row and whether the user has it on (HOME-005). */
data class HomeRowSetting(val row: HomeRow, val on: Boolean)

/**
 * HOME-005: the rows in the user's order with on/off, read from [code] ("CONTINUE,-RECENT_CHANNELS,…", "-" = off).
 * Rows the code doesn't name follow in the default order, on — except the recent channels, which are off by default
 * while channels are part of «Продолжить просмотр» ([channelsInContinue], HOME-004). Unknown names are skipped.
 */
fun homeRows(code: String, channelsInContinue: Boolean): List<HomeRowSetting> {
    val named = code.split(',').mapNotNull { part ->
        val off = part.startsWith("-")
        HomeRow.entries.find { it.name == part.removePrefix("-") }?.let { HomeRowSetting(it, !off) }
    }.distinctBy { it.row }
    val rest = HomeRow.entries.filter { r -> named.none { it.row == r } }
        .map { HomeRowSetting(it, it != HomeRow.RECENT_CHANNELS || !channelsInContinue) }
    return named + rest
}

fun encodeHomeRows(rows: List<HomeRowSetting>): String = rows.joinToString(",") { (if (it.on) "" else "-") + it.row.name }

/** [row] moved [by] places (−1 up, +1 down), kept inside the list. */
fun List<HomeRowSetting>.moved(row: HomeRow, by: Int): List<HomeRowSetting> {
    val i = indexOfFirst { it.row == row }
    val to = (i + by).coerceIn(0, size - 1)
    if (i < 0 || to == i) return this
    return toMutableList().apply { add(to, removeAt(i)) }
}

/** HOME-001: the rows shown, in order: on and not empty ([sizes] = how many items each has now). */
fun visibleRows(rows: List<HomeRowSetting>, sizes: Map<HomeRow, Int>): List<HomeRow> =
    rows.filter { it.on && (sizes[it.row] ?: 0) > 0 }.map { it.row }

/**
 * HOME-012: the lists to look for an item in, in order: its own list ([wanted]) when the section has it ([listed]);
 * with none asked for, the list on screen ([current]); «Все» ([all]) last (a hidden or gone category).
 */
fun <G> jumpLists(wanted: G?, current: G, listed: List<G>, all: G): List<G> =
    listOfNotNull(if (wanted == null) current else wanted.takeIf { it in listed }, all).distinct()

/** Something to go on with: a film or episode, or a channel. [at] = when it was watched last. */
sealed interface ContinueItem {
    val at: Long
}

/**
 * A film or an episode from the watching history; [next] = the episode to play instead (the one after [watch]
 * when that one was finished, HOME-006).
 */
data class VodContinue(val watch: Watch, val next: Episode? = null) : ContinueItem {
    override val at: Long get() = watch.watchedAt

    /** The key of the film or series to open. */
    val itemKey: String get() = watch.seriesKey ?: watch.vkey

    val kind: VodKind get() = watch.kind
}

data class ChannelContinue(val channel: ChannelItem, override val at: Long) : ContinueItem

/** HOME-002: started films and episodes and (if [withChannels]) recent channels, the latest first. */
fun continueRow(vod: List<Watch>, channels: List<Pair<ChannelItem, Long>>, withChannels: Boolean, limit: Int): List<ContinueItem> {
    val all: List<ContinueItem> = vod.map { VodContinue(it) } + if (withChannels) channels.map { ChannelContinue(it.first, it.second) } else emptyList()
    return all.sortedByDescending { it.at }.take(limit)
}

/**
 * HOME-006: what «Продолжить» starts — the newest of: the last unfinished film or episode ([unfinished]), the episode
 * after the last finished one ([finished] + its [next]; nothing when the series is over), the last channel.
 */
fun quickContinue(unfinished: Watch?, finished: Watch?, next: Episode?, channel: Pair<ChannelItem, Long>?): ContinueItem? =
    listOfNotNull(
        unfinished?.let { VodContinue(it) },
        if (finished != null && next != null) VodContinue(finished, next) else null,
        channel?.let { ChannelContinue(it.first, it.second) },
    ).maxByOrNull { it.at }

/** HOME-013: which kind of history is listed. */
enum class HistoryFilter { ALL, CHANNELS, MOVIES, SERIES }

/**
 * HOME-013: films, episodes and channels, the latest first, under one heading per day ([dayOf] = the start of the
 * day of a moment, in the user's time zone).
 */
fun historyDays(
    vod: List<Watch>, channels: List<Pair<ChannelItem, Long>>, filter: HistoryFilter, dayOf: (Long) -> Long,
): List<Pair<Long, List<ContinueItem>>> {
    val items: List<ContinueItem> = when (filter) {
        HistoryFilter.ALL -> vod.map { VodContinue(it) } + channels.map { ChannelContinue(it.first, it.second) }
        HistoryFilter.CHANNELS -> channels.map { ChannelContinue(it.first, it.second) }
        HistoryFilter.MOVIES -> vod.filter { it.kind == VodKind.MOVIE }.map { VodContinue(it) }
        HistoryFilter.SERIES -> vod.filter { it.kind == VodKind.SERIES }.map { VodContinue(it) }
    }
    return items.sortedByDescending { it.at }.groupBy { dayOf(it.at) }.toList()
}
