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
