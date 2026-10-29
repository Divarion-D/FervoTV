package app.iptvplayer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.epg.GuideCell
import app.iptvplayer.core.epg.GuideWindow
import app.iptvplayer.core.epg.HOUR_MS
import app.iptvplayer.core.epg.cellAt
import app.iptvplayer.core.epg.focusCells
import app.iptvplayer.core.epg.guideCells
import app.iptvplayer.core.epg.guideStep
import app.iptvplayer.core.epg.guideTicks
import app.iptvplayer.core.epg.openingView
import app.iptvplayer.core.epg.viewRange
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.keyAction
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.GuideRow
import app.iptvplayer.core.model.Programme
import app.iptvplayer.remoteKeys
import java.util.TimeZone

// The programme grid (EPG-008/009, 33-design 4.9): a channel column and, per row, one canvas with that row's cells;
// the time scale and the "now" line are drawn once above all rows. Horizontal scrolling only moves the canvases'
// drawing (no recomposition); the focus is a fill drawn over the focused cell, not a focusable per cell.

fun zoneOffset(t: Long): Long = TimeZone.getDefault().getOffset(t).toLong()

/**
 * Where the grid looks and what it has in focus: the view's start, the focused row, programme mode ([inRow]: the
 * remote moves through programmes; off: through channels) and the focused moment [at] (the cell containing it).
 * [span] = visible time, set by the grid from its width.
 */
@Stable
class GuideCursor(viewStart: Long, row: Int = 0, inRow: Boolean = false, at: Long = viewStart, placed: Boolean = false) {
    var viewStart by mutableLongStateOf(viewStart)
    var row by mutableIntStateOf(row)
    var inRow by mutableStateOf(inRow)
    var at by mutableLongStateOf(at)
    var span by mutableLongStateOf(4 * HOUR_MS)

    /** The view was set for a measured width (or by the user), not the first guess. */
    var placed = placed

    fun range(now: Long, days: Int): LongRange = viewRange(now, zoneOffset(now), days, span)

    /** The view on [t] with [t] at a third of the width (opening, "Сейчас", a found programme). */
    fun show(t: Long, now: Long, days: Int) {
        viewStart = openingView(t, span).coerceIn(range(now, days))
        at = t.coerceIn(viewStart, viewStart + span - 1)
    }

    /** The focused moment, kept inside the view. */
    fun focusAt(): Long = at.coerceIn(viewStart, viewStart + span - 1)

    companion object {
        val Saver = listSaver<GuideCursor, Long>(
            { listOf(it.viewStart, it.row.toLong(), if (it.inRow) 1 else 0, it.at, if (it.placed) 1 else 0) },
            { GuideCursor(it[0], it[1].toInt(), it[2] == 1L, it[3], it[4] == 1L) },
        )
    }
}

/**
 * The grid itself. [rows] are the list's channels with their programmes in [window]; [days] = how far the guide
 * goes. OK and long OK (tap and long press) on a channel go to [onChannel], on a programme to [onProgramme]
 * (programme null = a gap). ▲ on the first row and ◀ in the channel column leave the grid (top bar, rail).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideGrid(
    rows: LazyPagingItems<GuideRow>,
    window: GuideWindow,
    cursor: GuideCursor,
    days: Int,
    now: Long,
    playingKey: String?,
    tall: Boolean,
    focus: FocusRequester,
    modifier: Modifier,
    onFocusIn: () -> Unit,
    onChannel: (Int, ChannelItem, Boolean) -> Unit,
    onProgramme: (Int, ChannelItem, Programme?, Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pxPerMs = with(density) { Defaults.GUIDE_DP_PER_HOUR.dp.toPx() } / HOUR_MS
    val channelWidth = if (tall) 128.dp else 200.dp
    val rowHeight = if (tall) 52.dp else 40.dp
    val gap = 4.dp
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    val nowState by rememberUpdatedState(now)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val noData = stringResource(R.string.guide_no_data)
    val hm = rememberTimeFormat()

    fun range() = cursor.range(nowState, days)
    fun rowAt(i: Int): GuideRow? = if (i in 0 until rows.itemCount) rows.peek(i) else null
    fun cells(i: Int): List<GuideCell> =
        focusCells(guideCells(rowAt(i)?.programmes.orEmpty(), window.from, window.to), zoneOffset(cursor.viewStart))

    // A new time window comes with a new pager, empty until its first page: the list would jump to the top.
    // The last position with rows is kept and put back once they are there.
    val rowsNow by rememberUpdatedState(rows)
    val top = remember { intArrayOf(0, 0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (i, o) ->
            if (rowsNow.itemCount > 0) { top[0] = i; top[1] = o }
        }
    }
    // The focused row stays in sight.
    LaunchedEffect(rows) {
        snapshotFlow { rows.itemCount > 0 }.first { it }
        if (listState.firstVisibleItemIndex != top[0]) listState.scrollToItem(top[0], top[1])
        snapshotFlow { cursor.row }.collect { r ->
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@collect listState.scrollToItem(r)
            val full = visible.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
            val first = full.firstOrNull()?.index ?: visible.first().index
            val last = full.lastOrNull()?.index ?: first
            if (r < first) listState.scrollToItem(r)
            else if (r > last) listState.scrollToItem((r - (last - first)).coerceAtLeast(0))
        }
    }

    fun moveRow(to: Int) {
        val n = rows.itemCount
        if (n == 0) return
        cursor.row = to.coerceIn(0, n - 1)
    }

    fun step(forward: Boolean): Boolean {
        val r = guideStep(cells(cursor.row), cursor.focusAt(), cursor.viewStart, cursor.span, forward, range())
        if (r == null) {
            if (!forward) cursor.inRow = false // nothing before: back to the channel
            return true
        }
        cursor.placed = true
        cursor.viewStart = r.first
        cursor.at = r.second
        return true
    }

    fun arrows(a: Action): Boolean = when (a) {
        Action.Up -> if (cursor.row == 0) false else { moveRow(cursor.row - 1); true }
        Action.Down -> { moveRow(cursor.row + 1); true }
        Action.Left -> if (!cursor.inRow) false else step(false)
        Action.Right -> {
            if (!cursor.inRow) {
                cursor.inRow = true
                cursor.at = if (nowState in cursor.viewStart until cursor.viewStart + cursor.span) nowState else cursor.viewStart
            } else step(true)
            true
        }
        else -> false
    }

    fun keys(a: Action, long: Boolean): Boolean {
        val i = cursor.row
        return when (a) {
            Action.Ok, Action.Menu -> {
                val item = rowAt(i)?.item ?: return true
                val menu = long || a == Action.Menu
                if (!cursor.inRow) onChannel(i, item, menu)
                else onProgramme(i, item, cellAt(cells(i), cursor.focusAt())?.programme, menu)
                true
            }
            Action.ChannelUp, Action.ChannelDown -> {
                val down = a == Action.ChannelDown
                moveRow(if (long) (if (down) rows.itemCount - 1 else 0) else i + if (down) Defaults.LIST_PAGE else -Defaults.LIST_PAGE)
                true
            }
            else -> false
        }
    }

    val rangeNow by rememberUpdatedState(range())
    Column(
        modifier
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocusIn() }
            // Arrows with their repeats: holding ▼ runs down the channels.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val a = keyAction(e.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
                a in ARROWS && arrows(a)
            }
            .remoteKeys(::keys)
            .focusable()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dx ->
                    cursor.placed = true
                    cursor.viewStart = (cursor.viewStart - (dx / pxPerMs).toLong()).coerceIn(rangeNow)
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val areaPx = with(density) { (maxWidth - channelWidth - gap).toPx() }.coerceAtLeast(1f)
            val span = (areaPx / pxPerMs).toLong()
            LaunchedEffect(span) {
                cursor.span = span
                if (!cursor.placed) {
                    cursor.placed = true
                    cursor.show(nowState, nowState, days)
                }
            }
            Column(Modifier.fillMaxSize()) {
                // Time scale with the current time over the "now" line.
                val labelStyle = TvType.bodySmall
                val pillStyle = TvType.badge
                Spacer(
                    Modifier.fillMaxWidth().height(24.dp).drawBehind {
                        val x0 = (channelWidth + gap).toPx()
                        val v = cursor.viewStart
                        clipRect(left = x0) {
                            // The current time's pill first: labels it would cover, or cut by the left edge, are skipped.
                            val nx = x0 + (nowState - v) * pxPerMs
                            val pill = measurer.measure(hm(nowState), pillStyle)
                            val pw = pill.size.width + 12.dp.toPx()
                            val showPill = nx >= x0 && nx <= size.width
                            for (t in guideTicks(v, cursor.span, zoneOffset(v))) {
                                val x = x0 + (t - v) * pxPerMs
                                drawRect(c.outlineVariant, Offset(x, size.height - 6.dp.toPx()), Size(1.dp.toPx(), 6.dp.toPx()))
                                val label = measurer.measure(hm(t), labelStyle)
                                val lx = x + 4.dp.toPx()
                                val covered = showPill && lx < nx + pw / 2 && lx + label.size.width > nx - pw / 2
                                if (!covered && lx + label.size.width <= size.width) drawText(label, c.onSurfaceVariant, Offset(lx, 0f))
                            }
                            if (showPill) {
                                val h = size.height - 2.dp.toPx()
                                drawRoundRect(Ext.focus, Offset(nx - pw / 2, 0f), Size(pw, h), CornerRadius(h / 2))
                                drawText(pill, Ext.onFocus, Offset(nx - pill.size.width / 2, (h - pill.size.height) / 2))
                            }
                        }
                    },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize(), listState) {
                        items(rows.itemCount, key = rows.itemKey { it.item.channel.id }) { i ->
                            val row = rows[i]
                            GuideRowView(
                                row, i, window, cursor, { focused }, now, playingKey, tall, channelWidth, rowHeight, gap, pxPerMs,
                                measurer, noData, onChannel, onProgramme,
                            )
                        }
                    }
                    // One "now" line over all rows.
                    Spacer(
                        Modifier.matchParentSize().drawBehind {
                            val x = (channelWidth + gap).toPx() + (nowState - cursor.viewStart) * pxPerMs
                            if (x >= (channelWidth + gap).toPx() && x <= size.width) drawRect(Ext.now, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                        },
                    )
                }
            }
        }
    }
}

private val ARROWS = setOf(Action.Up, Action.Down, Action.Left, Action.Right)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRowView(
    row: GuideRow?,
    index: Int,
    window: GuideWindow,
    cursor: GuideCursor,
    gridFocused: () -> Boolean,
    now: Long,
    playingKey: String?,
    tall: Boolean,
    channelWidth: Dp,
    rowHeight: Dp,
    gap: Dp,
    pxPerMs: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    noData: String,
    onChannel: (Int, ChannelItem, Boolean) -> Unit,
    onProgramme: (Int, ChannelItem, Programme?, Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val item = row?.item
    val channelFocused by remember(index) { derivedStateOf { gridFocused() && !cursor.inRow && cursor.row == index } }
    val playing = item != null && item.channel.key == playingKey
    Row(Modifier.fillMaxWidth().height(rowHeight + gap).padding(bottom = gap)) {
        // Channel: number, logo, name, archive mark; the one playing in the window has the ember line.
        val shape = RoundedCornerShape(8.dp)
        val fg = if (channelFocused) Ext.onFocus else c.onSurface
        Row(
            Modifier
                .width(channelWidth)
                .fillMaxHeight()
                .background(if (channelFocused) Ext.focus else c.surfaceContainerLow, shape)
                .then(
                    if (playing) Modifier.drawBehind {
                        val h = 20.dp.toPx()
                        drawRoundRect(if (channelFocused) Ext.onFocus else Ext.now, Offset(0f, (size.height - h) / 2), Size(3.dp.toPx(), h), CornerRadius(2.dp.toPx()))
                    } else Modifier
                )
                .focusProperties { canFocus = false }
                .combinedClickable(
                    remember { MutableInteractionSource() }, null,
                    onLongClick = { if (item != null) onChannel(index, item, true) },
                ) { if (item != null) onChannel(index, item, false) }
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item == null) return@Row
            if (!tall) Text(
                "${item.number}", Modifier.width(28.dp), style = TvType.badge, color = if (channelFocused) Ext.onFocus else c.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            ChannelLogo(item.channel, Modifier.size(if (tall) 40.dp else 48.dp, if (tall) 25.dp else 30.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                item.channel.name, Modifier.weight(1f), style = TvType.bodySmall.copy(fontWeight = FontWeight.Medium), color = fg,
                maxLines = if (tall) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            if (item.channel.archiveDays > 0) Icon(
                painterResource(R.drawable.ic_history), stringResource(R.string.row_archive),
                tint = if (channelFocused) Ext.onFocus else c.tertiary, modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(gap))
        // Programmes: one canvas; cells and their titles are prepared once per row and window.
        val cells = remember(row, window) { guideCells(row?.programmes.orEmpty(), window.from, window.to) }
        val layouts = remember(cells) { arrayOfNulls<TextLayoutResult>(cells.size) }
        val archive = (item?.channel?.archiveDays ?: 0) > 0
        val archiveIcon = painterResource(R.drawable.ic_history)
        val latestCells by rememberUpdatedState(cells)
        val latestItem by rememberUpdatedState(item)
        val style = TvType.bodySmall.copy(fontWeight = FontWeight.Medium)
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(index) {
                    fun hit(x: Float): Programme? {
                        val t = cursor.viewStart + (x / pxPerMs).toLong()
                        return cellAt(latestCells, t)?.programme
                    }
                    // Touch (EPG-009): a programme opens its menu, an empty stretch acts like the channel.
                    fun touch(x: Float, long: Boolean) {
                        val item = latestItem ?: return
                        val p = hit(x)
                        if (p == null) onChannel(index, item, long) else onProgramme(index, item, p, true)
                    }
                    detectTapGestures(onTap = { touch(it.x, false) }, onLongPress = { touch(it.x, true) })
                }
                .drawBehind {
                    val v = cursor.viewStart
                    val end = v + (size.width / pxPerMs).toLong()
                    val r = CornerRadius(8.dp.toPx())
                    val hGap = 2.dp.toPx()
                    val pad = 8.dp.toPx()
                    val icon = 16.dp.toPx()
                    fun x(t: Long) = (t - v) * pxPerMs
                    fun title(i: Int, cell: GuideCell): TextLayoutResult = layouts[i] ?: measurer.measure(
                        cell.programme?.title ?: noData, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = ((cell.stop - cell.start) * pxPerMs - 2 * pad - hGap).toInt().coerceAtLeast(1)),
                    ).also { layouts[i] = it }

                    fun cell(i: Int, cell: GuideCell, fill: androidx.compose.ui.graphics.Color?, text: androidx.compose.ui.graphics.Color, outline: Boolean, mark: Boolean, layout: TextLayoutResult) {
                        val left = x(cell.start).coerceAtLeast(-r.x)
                        val right = (x(cell.stop) - hGap).coerceAtMost(size.width + r.x)
                        if (right <= left) return
                        val w = right - left
                        if (fill != null) drawRoundRect(fill, Offset(left, 0f), Size(w, size.height), r)
                        if (outline) drawRoundRect(c.outlineVariant, Offset(left, 0.5.dp.toPx()), Size(w, size.height - 1.dp.toPx()), r, style = Stroke(1.dp.toPx()))
                        var tx = maxOf(left, 0f) + pad
                        val content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                            if (mark) {
                                translate(tx, (size.height - icon) / 2) {
                                    with(archiveIcon) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(if (text == Ext.onFocus) Ext.onFocus else c.tertiary)) }
                                }
                                tx += icon + 4.dp.toPx()
                            }
                            drawText(layout, text, Offset(tx, (size.height - layout.size.height) / 2))
                        }
                        // The title is measured to fit its cell: a clip only where it is pushed right (cut cell, archive mark).
                        if (left < 0f || mark || right > size.width) clipRect(left = maxOf(left, 0f), right = minOf(right, size.width), block = content)
                        else content()
                    }

                    for ((i, cl) in cells.withIndex()) {
                        if (cl.stop <= v || cl.start >= end) continue
                        val p = cl.programme
                        if (p == null) {
                            cell(i, cl, null, c.onSurfaceVariant, outline = true, mark = false, title(i, cl))
                            continue
                        }
                        val past = p.stop <= now
                        val current = p.start <= now && now < p.stop
                        val fill = when {
                            past -> c.surfaceContainerLow
                            current -> c.surfaceContainerHigh
                            else -> c.surfaceContainer
                        }
                        cell(i, cl, fill, if (past) c.onSurfaceVariant else c.onSurface, outline = false, mark = past && archive, title(i, cl))
                        if (current) {
                            val left = maxOf(x(cl.start), 0f)
                            val right = minOf(x(now), size.width)
                            if (right > left) drawRect(Ext.now, Offset(left, size.height - 2.dp.toPx()), Size(right - left, 2.dp.toPx()))
                        }
                    }
                    // The focus: a fill over the focused cell (a 30-minute piece of a gap).
                    if (gridFocused() && cursor.inRow && cursor.row == index) {
                        val f = cellAt(focusCells(cells, zoneOffset(v)), cursor.focusAt())
                        if (f != null) {
                            val layout = if (f.programme != null) title(cells.indexOfFirst { it.programme == f.programme }.coerceAtLeast(0), f)
                            else measurer.measure(noData, style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                constraints = Constraints(maxWidth = ((f.stop - f.start) * pxPerMs - 2 * pad).toInt().coerceAtLeast(1)))
                            cell(-1, f, Ext.focus, Ext.onFocus, outline = false, mark = f.programme != null && f.programme!!.stop <= now && archive, layout)
                        }
                    }
                },
        )
    }
}

/** A tool of the guide's top bar with a text (day, category): tonal pill, orange fill in focus. */
@Composable
fun ToolChip(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier, height: Dp = 40.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    val fg = if (focused) Ext.onFocus else c.onSurface
    Row(
        modifier
            .height(height)
            .widthIn(max = 220.dp)
            .background(if (focused) Ext.focus else c.surfaceContainerHigh, CircleShape)
            .clip(CircleShape)
            .clickable(interaction, ripple(), onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), null, tint = if (focused) Ext.onFocus else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = TvType.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The focused programme over the grid (EPG-008): title, "20:30–21:30 · 1 h · channel", archive mark, description.
 * In the channel column: what the channel has on now. Reads the cursor itself, so moving it redraws only this.
 */
@Composable
fun GuideDetails(rows: LazyPagingItems<GuideRow>, window: GuideWindow, cursor: GuideCursor, now: Long, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val hm = rememberTimeFormat()
    val row = if (cursor.row < rows.itemCount) rows.peek(cursor.row) else null
    val item = row?.item
    val p = when {
        row == null -> null
        cursor.inRow -> cellAt(guideCells(row.programmes, window.from, window.to), cursor.focusAt())?.programme
        else -> row.programmes.firstOrNull { it.start <= now && now < it.stop } ?: item?.now?.takeIf { it.stop > now }
    }
    Column(modifier.padding(vertical = 2.dp)) {
        if (item == null) return@Column
        if (p == null) {
            Text(item.channel.name, style = TvType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.no_programme), style = TvType.bodySmall, color = c.onSurfaceVariant)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, style = TvType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (p.stop <= now && item.channel.archiveDays > 0) {
                Spacer(Modifier.width(12.dp))
                Icon(painterResource(R.drawable.ic_history), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.row_archive), style = TvType.bodySmall, color = c.tertiary)
            }
        }
        Text(
            stringResource(R.string.guide_programme_times, hm(p.start), hm(p.stop), durationText(p.stop - p.start), item.channel.name),
            style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        p.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** No guide connected: what to do, and the playlist's own guide when it names one (EPG-001 "Взять из плейлиста"). */
@Composable
fun GuideMissing(canLoad: Boolean, modifier: Modifier, onLoad: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.guide_none_title), style = TvType.titleSmall)
        Text(
            stringResource(if (canLoad) R.string.guide_none_hint_url else R.string.guide_none_hint),
            style = TvType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (canLoad) ToolChip(R.drawable.ic_refresh, stringResource(R.string.guide_take_from_playlist), Modifier.padding(top = 4.dp), onClick = onLoad)
    }
}
