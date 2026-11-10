package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.input.Action
import app.iptvplayer.isTv
import app.iptvplayer.remoteKeys
import kotlin.math.abs

internal data class ReorderEntry(val key: String, val title: String)

internal data class ReorderState(
    val items: List<ReorderEntry>,
    val cursor: Int,
    val picked: Boolean,
) {
    fun toggle(): ReorderState = copy(picked = !picked)

    fun moveTo(index: Int): ReorderState {
        val target = index.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        if (!picked || target == cursor) return copy(cursor = target)
        return copy(items = items.toMutableList().apply { add(target, removeAt(cursor)) }, cursor = target)
    }

    fun step(by: Int): ReorderState = moveTo(cursor + by)
}

/** LIVE-013: one draft reorder screen for every manually ordered list. Back discards the draft; Save persists it. */
@Composable
internal fun ReorderMode(
    title: String,
    initial: List<ReorderEntry>,
    startKey: String,
    onSave: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val tv = LocalContext.current.isTv()
    var state by remember(initial, startKey) {
        mutableStateOf(ReorderState(initial, initial.indexOfFirst { it.key == startKey }.coerceAtLeast(0), picked = true))
    }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.cursor) {
        if (state.items.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(state.cursor)
        if (tv) {
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
    }
    BackHandler(onBack = onCancel)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TvType.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolButton(R.drawable.ic_close, stringResource(R.string.cancel), onClick = onCancel)
            Spacer(Modifier.width(8.dp))
            ToolButton(R.drawable.ic_save, stringResource(R.string.save)) { onSave(state.items.map { it.key }) }
        }
        Text(stringResource(R.string.reorder_hint), style = TvType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            Modifier.fillMaxSize().remoteKeys { action, long ->
                state = when (action) {
                    Action.Up -> if (state.cursor == 0 && !state.picked) return@remoteKeys false else state.step(-1)
                    Action.Down -> state.step(1)
                    Action.ChannelUp -> state.moveTo(if (long) 0 else state.cursor - Defaults.LIST_PAGE)
                    Action.ChannelDown -> state.moveTo(if (long) state.items.lastIndex else state.cursor + Defaults.LIST_PAGE)
                    Action.Ok -> state.toggle()
                    else -> return@remoteKeys false
                }
                true
            },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
                val currentIndex by rememberUpdatedState(index)
                val dragThreshold = with(LocalDensity.current) { 28.dp.toPx() }
                val selected = index == state.cursor
                Row(
                    Modifier.fillMaxWidth().height(56.dp)
                        .then(if (selected) Modifier.focusRequester(focus) else Modifier)
                        .background(
                            if (selected) if (state.picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable {
                            state = if (state.cursor == index) state.toggle() else state.copy(cursor = index, picked = true)
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_swap_vert), stringResource(R.string.fav_move),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp).padding(12.dp).pointerInput(item.key) {
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dy = 0f
                                    state = state.copy(cursor = currentIndex, picked = true)
                                },
                                onDragEnd = { state = state.copy(picked = false) },
                                onDragCancel = { state = state.copy(picked = false) },
                            ) { change, amount ->
                                change.consume()
                                dy += amount
                                while (abs(dy) >= dragThreshold) {
                                    state = state.step(if (dy > 0) 1 else -1)
                                    dy += if (dy > 0) -dragThreshold else dragThreshold
                                }
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.title, style = TvType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (selected && state.picked) Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
