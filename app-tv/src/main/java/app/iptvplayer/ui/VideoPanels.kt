package app.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.db.matchesWords
import app.iptvplayer.core.epg.Ago
import app.iptvplayer.core.epg.ago
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.keyAction
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.remoteKeys
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Panels over the full-screen video (LIVE-019, LIVE-020): translucent at the screen's edge, the stream goes on under
// them. ◀ / ▶ switch the panels ([onSide]), OK picks, Back closes (the live screen's BackHandler).

/** The panel's frame: a translucent column at the left or right edge; a tap beside it closes it. */
@Composable
private fun BoxScope.PanelFrame(right: Boolean, title: String, onSide: (Action) -> Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.matchParentSize().focusProperties { canFocus = false }.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    Column(
        Modifier
            .align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .widthIn(max = 380.dp)
            .fillMaxWidth(0.85f)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .onPreviewKeyEvent { e ->
                // Back closes the panel here: otherwise Compose spends the first Back on moving the focus out of the list.
                if (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (e.type == KeyEventType.KeyUp) onClose()
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val a = keyAction(e.nativeKeyEvent.keyCode)
                (a == Action.Left || a == Action.Right) && onSide(a)
            }
            .focusProperties { canFocus = false }
            .clickable(remember { MutableInteractionSource() }, indication = null) {}, // taps inside don't close it
    ) {
        Text(title, style = TvType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        content()
    }
}

/** Puts the focus on row [index] of [state] (scrolled to it) through [requester] sitting on that row. */
private suspend fun focusAt(state: LazyListState, requester: FocusRequester, index: Int) {
    state.scrollToItem(index.coerceAtLeast(0))
    withFrameNanos {}
    runCatching { requester.requestFocus() }
}

/** LIVE-019: the channels of a list, the playing one in focus; CH± page by 10 (LIVE-021). */
@Composable
fun BoxScope.ChannelsPanel(
    title: String, items: LazyPagingItems<ChannelItem>, start: Int?, playingKey: String?, now: Long,
    onPick: (ChannelItem) -> Unit, onSide: (Action) -> Boolean, onClose: () -> Unit,
) = PanelFrame(false, title, onSide, onClose) {
    val state = rememberLazyListState()
    val requester = remember { FocusRequester() }
    var target by remember { mutableIntStateOf(0) }
    val focused = remember { intArrayOf(0) }
    val scope = rememberCoroutineScope()
    fun go(i: Int) {
        target = i.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0))
        scope.launch { focusAt(state, requester, target) }
    }
    LaunchedEffect(start, items.itemCount > 0) {
        if (start != null && items.itemCount > 0) go(start)
    }
    LazyColumn(
        Modifier.fillMaxWidth().remoteKeys { a, long ->
            when (a) {
                Action.Ok -> { items.peek(focused[0])?.let(onPick); true }
                Action.ChannelUp, Action.ChannelDown -> {
                    val down = a == Action.ChannelDown
                    go(if (long) (if (down) items.itemCount - 1 else 0) else focused[0] + if (down) Defaults.LIST_PAGE else -Defaults.LIST_PAGE)
                    true
                }
                else -> false
            }
        },
        state,
    ) {
        items(items.itemCount, key = items.itemKey { it.channel.id }) { i ->
            val item = items[i]
            ChannelRow(
                item, item != null && item.channel.key == playingKey, now, false,
                if (i == target) Modifier.focusRequester(requester) else Modifier,
                onFocus = { focused[0] = i }, onClick = { if (item != null) onPick(item) }, onLongClick = {},
            )
        }
    }
}

/** LIVE-019: every list of the section (service lists, collections, categories) with a search when there are many. */
@Composable
fun BoxScope.CategoriesPanel(
    groups: List<GroupCount>, selected: ChannelGroup, onPick: (ChannelGroup) -> Unit, onSide: (Action) -> Boolean, onClose: () -> Unit,
) = PanelFrame(false, stringResource(R.string.hint_categories), onSide, onClose) {
    var query by remember { mutableStateOf("") }
    val searchable = groups.size >= Defaults.CATEGORY_SEARCH_MIN
    val shown = remember(groups, query) { groups.filter { !searchable || matchesWords(it.title, query) } }
    val state = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val start = shown.indexOfFirst { it.group == selected }.coerceAtLeast(0)
    if (searchable) SearchField(query, { query = it }, stringResource(R.string.search_categories), remember { FocusRequester() }, Modifier.fillMaxWidth().padding(bottom = 8.dp), height = 40.dp)
    LazyColumn(Modifier.fillMaxWidth(), state) {
        itemsIndexed(shown, key = { _, g -> g.group.encode() }) { i, g ->
            CategoryItem(
                g, g.group == selected, if (i == start) Modifier.focusRequester(requester) else Modifier,
                onFocus = {}, onClick = { onPick(g.group) }, onLongClick = {},
            )
        }
    }
    LaunchedEffect(Unit) { focusAt(state, requester, start) }
}

/** LIVE-020: the last watched channels with when each was watched ("5 min ago", "yesterday 21:40"). */
@Composable
fun BoxScope.RecentPanel(list: List<Pair<ChannelItem, Long>>?, playingKey: String?, now: Long, onPick: (ChannelItem) -> Unit, onSide: (Action) -> Boolean, onClose: () -> Unit) =
    PanelFrame(true, stringResource(R.string.panel_recent), onSide, onClose) {
        val state = rememberLazyListState()
        val requester = remember { FocusRequester() }
        if (list == null) return@PanelFrame
        if (list.isEmpty()) {
            Text(stringResource(R.string.panel_recent_empty), style = TvType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            return@PanelFrame
        }
        LazyColumn(Modifier.fillMaxWidth(), state, contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(list, key = { _, (item, _) -> item.channel.key }) { i, (item, at) ->
                RecentRow(item, item.channel.key == playingKey, agoText(at, now), if (i == 0) Modifier.focusRequester(requester) else Modifier) { onPick(item) }
            }
        }
        LaunchedEffect(Unit) { focusAt(state, requester, 0) }
    }

@Composable
private fun RecentRow(item: ChannelItem, playing: Boolean, whenText: String, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = if (focused) Ext.onFocus else if (playing) c.primary else c.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (focused) Ext.focus else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(item.channel, Modifier.size(56.dp, 35.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${item.number}  ${item.channel.name}", style = TvType.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText, style = TvType.bodySmall, color = if (focused) Ext.onFocus else c.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun agoText(at: Long, now: Long): String {
    val ctx = LocalContext.current
    val time = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(at))
    return when (val a = ago(at, now, zoneOffset(now))) {
        Ago.JustNow -> stringResource(R.string.ago_just_now)
        is Ago.Minutes -> stringResource(R.string.ago_minutes, a.n)
        Ago.Today -> stringResource(R.string.ago_today, time)
        Ago.Yesterday -> stringResource(R.string.ago_yesterday, time)
        Ago.Earlier -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at)) + ", " + time
    }
}
