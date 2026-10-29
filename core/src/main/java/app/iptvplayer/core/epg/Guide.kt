package app.iptvplayer.core.epg

import app.iptvplayer.core.model.Programme

/**
 * Programmes in start order, cut so they follow one another (EPG-008: overlapping ones are drawn one after another,
 * not on top): a programme starting before the previous one ends starts at its end; one hidden entirely is dropped.
 */
fun nonOverlapping(sorted: List<Programme>): List<Programme> {
    val out = ArrayList<Programme>(sorted.size)
    var end = Long.MIN_VALUE
    for (p in sorted) {
        if (p.stop <= end) continue
        out += if (p.start < end) p.copy(start = end) else p
        end = p.stop
    }
    return out
}

/** One cell of a guide row; [programme] = null is a gap ("нет данных"). */
data class GuideCell(val start: Long, val stop: Long, val programme: Programme?)

/**
 * A guide row as cells covering [from, to) without gaps or overlaps, for drawing a row on one canvas:
 * the cell's x is `(start - from) * scale`. [programmes] as in [app.iptvplayer.core.model.GuideRow] (sorted, non-overlapping).
 */
fun guideCells(programmes: List<Programme>, from: Long, to: Long): List<GuideCell> {
    val out = ArrayList<GuideCell>()
    var t = from
    for (p in programmes) {
        if (p.stop <= t || p.start >= to) continue
        if (p.start > t) out += GuideCell(t, p.start, null)
        out += GuideCell(maxOf(p.start, t), minOf(p.stop, to), p)
        t = minOf(p.stop, to)
    }
    if (t < to) out += GuideCell(t, to, null)
    return out
}

// --- Grid time maths and remote navigation (EPG-008, EPG-009); times are epoch ms, [offset] = the local zone's offset ---

const val GUIDE_STEP_MS = 30 * 60_000L
const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS

/** [t] rounded down to a [step] boundary of local time. */
fun floorTo(t: Long, step: Long, offset: Long = 0): Long = t - Math.floorMod(t + offset, step)

/** Times of the scale's labels within [from, from + span): every 30 minutes of local time. */
fun guideTicks(from: Long, span: Long, offset: Long): List<Long> {
    var t = floorTo(from, GUIDE_STEP_MS, offset)
    if (t < from) t += GUIDE_STEP_MS
    val out = ArrayList<Long>()
    while (t < from + span) {
        out += t
        t += GUIDE_STEP_MS
    }
    return out
}

/** The view a grid opens on: the "now" line at a third of the width, a bit of the past to its left. */
fun openingView(now: Long, span: Long): Long = now - span / 3

/** Time range the grid's rows are loaded for (rows carry their programmes in it). */
data class GuideWindow(val from: Long, val to: Long)

/**
 * The window for a view [viewStart, viewStart + span): [current] while the view stays inside it with an hour to spare,
 * else a new one from 6 h before the view to 18 h after it (a day of programmes per row, reloaded rarely).
 */
fun guideWindow(viewStart: Long, span: Long, current: GuideWindow?): GuideWindow {
    if (current != null && viewStart - HOUR_MS >= current.from && viewStart + span + HOUR_MS <= current.to) return current
    val base = floorTo(viewStart, HOUR_MS)
    return GuideWindow(base - 6 * HOUR_MS, base + maxOf(18 * HOUR_MS, span + 6 * HOUR_MS))
}

/** Cells the remote steps through: programmes as they are, gaps ("нет данных") cut at every local 30 minutes. */
fun focusCells(cells: List<GuideCell>, offset: Long): List<GuideCell> {
    val out = ArrayList<GuideCell>(cells.size)
    for (c in cells) {
        if (c.programme != null) {
            out += c
            continue
        }
        var t = c.start
        while (t < c.stop) {
            val end = minOf(c.stop, floorTo(t, GUIDE_STEP_MS, offset) + GUIDE_STEP_MS)
            out += GuideCell(t, end, null)
            t = end
        }
    }
    return out
}

/** The cell of [cells] at moment [at], or null. */
fun cellAt(cells: List<GuideCell>, at: Long): GuideCell? = cells.firstOrNull { at >= it.start && at < it.stop }

/**
 * Where the grid's view may start: from the start of yesterday to the end of the guide's last day ([days] from
 * today), so that [span] of it fits.
 */
fun viewRange(now: Long, offset: Long, days: Int, span: Long): LongRange {
    val min = floorTo(now, DAY_MS, offset) - DAY_MS
    val max = floorTo(now, DAY_MS, offset) + days * DAY_MS - span
    return min..maxOf(min, max)
}

/**
 * ◀ / ▶ in a row (EPG-009) with the focus on the cell at [at] of [cells] (from [focusCells]) and the view starting
 * at [view]: the new (view start, focus moment), or null when there is nowhere to go. A cell cut by the view's
 * edge first scrolls the time by up to half a view; otherwise the focus goes to the neighbour, scrolling just
 * enough to show it (as much of it as fits). The view stays within [range].
 */
fun guideStep(cells: List<GuideCell>, at: Long, view: Long, span: Long, forward: Boolean, range: LongRange): Pair<Long, Long>? {
    fun clamp(v: Long) = v.coerceIn(range.first, range.last)
    val i = cells.indexOfFirst { at >= it.start && at < it.stop }
    if (i < 0) return null
    val cur = cells[i]
    if (forward) {
        if (cur.stop > view + span && view < range.last) {
            val v = clamp(minOf(cur.stop - span, view + span / 2))
            return v to maxOf(cur.start, v)
        }
        val next = cells.getOrNull(i + 1) ?: return null
        val v = if (next.stop > view + span) clamp(maxOf(view, minOf(next.start, next.stop - span))) else view
        return if (next.start >= v + span) null else v to maxOf(next.start, v)
    } else {
        if (cur.start < view && view > range.first) {
            val v = clamp(maxOf(cur.start, view - span / 2))
            return v to maxOf(cur.start, v)
        }
        val prev = cells.getOrNull(i - 1) ?: return null
        val v = if (prev.start < view) clamp(minOf(view, maxOf(prev.start, prev.stop - span))) else view
        return if (prev.stop <= v) null else v to maxOf(prev.start, v)
    }
}

/** When a channel was watched, for the recent channels panel (LIVE-020): "5 мин назад", "вчера 21:40". */
sealed interface Ago {
    data object JustNow : Ago
    data class Minutes(val n: Int) : Ago
    data object Today : Ago
    data object Yesterday : Ago
    data object Earlier : Ago
}

fun ago(at: Long, now: Long, offset: Long): Ago {
    val today = floorTo(now, DAY_MS, offset)
    return when {
        now - at < 60_000 -> Ago.JustNow
        now - at < HOUR_MS -> Ago.Minutes(((now - at) / 60_000).toInt())
        at >= today -> Ago.Today
        at >= today - DAY_MS -> Ago.Yesterday
        else -> Ago.Earlier
    }
}
