package app.iptvplayer.core.model

/*
 * Pure rules of watching films and series (VOD-005, VOD-006, VOD-009, VOD-010): the screens and the player ask these.
 */

/** Where to start and whether to ask first: [at] = 0 starts from the beginning. */
data class ResumeChoice(val at: Long, val ask: Boolean)

/**
 * VOD-010: a started, unfinished item continues (at once or after asking, by [mode]); a finished one or one never
 * started begins from the start.
 */
fun resumeChoice(p: Progress?, mode: ResumeMode): ResumeChoice {
    val at = p?.takeIf { !it.watched && it.position > 0 }?.position ?: return ResumeChoice(0, false)
    return when (mode) {
        ResumeMode.CONTINUE -> ResumeChoice(at, false)
        ResumeMode.ASK -> ResumeChoice(at, true)
        ResumeMode.START -> ResumeChoice(0, false)
    }
}

/** Viewing order: seasons from the first, specials (season 0) after the last one; episodes by number. */
fun List<Episode>.inViewingOrder(): List<Episode> = sortedWith(compareBy({ it.season == 0 }, { it.season }, { it.episode }))

/** VOD-009: the episode after [current] in viewing order, across seasons, then the specials; null after the last one. */
fun nextEpisode(episodes: List<Episode>, current: Episode): Episode? {
    val order = episodes.inViewingOrder()
    val i = order.indexOfFirst { it.key == current.key }
    return if (i < 0) null else order.getOrNull(i + 1)
}

fun previousEpisode(episodes: List<Episode>, current: Episode): Episode? {
    val order = episodes.inViewingOrder()
    val i = order.indexOfFirst { it.key == current.key }
    return if (i <= 0) null else order[i - 1]
}

/**
 * VOD-005: the episode a series continues with: the one watched last, or the next one if that was finished (the
 * last one again when nothing follows); not started = the first episode of the first season. Null = no episodes.
 */
fun continueEpisode(episodes: List<Episode>): Episode? {
    val last = episodes.filter { it.progress != null }.maxByOrNull { it.progress!!.at }
        ?: return episodes.inViewingOrder().firstOrNull()
    if (last.progress?.watched != true) return last
    return nextEpisode(episodes, last) ?: last
}

/** VOD-005: the season tabs (number, episodes) with specials last; the total leaves the specials out. */
fun seasons(episodes: List<Episode>): List<Pair<Int, Int>> =
    episodes.groupingBy { it.season }.eachCount().toList().sortedWith(compareBy({ it.first == 0 }, { it.first }))

fun episodeTotal(episodes: List<Episode>): Int = episodes.count { it.season != 0 }

/** VOD-006: the episodes of [season] as the series is set to show them. */
fun seasonEpisodes(episodes: List<Episode>, season: Int, view: SeriesView): List<Episode> =
    episodes.filter { it.season == season && !(view.hideWatched && it.progress?.watched == true) }
        .sortedBy { it.episode }
        .let { if (view.newestFirst) it.reversed() else it }

/** VOD-006: the season tabs in the series' order (newest first reverses the numbered seasons, specials stay last). */
fun seasonTabs(episodes: List<Episode>, view: SeriesView): List<Pair<Int, Int>> {
    val all = seasons(episodes)
    if (!view.newestFirst) return all
    val (specials, numbered) = all.partition { it.first == 0 }
    return numbered.reversed() + specials
}
