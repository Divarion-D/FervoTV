package app.iptvplayer.ui

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.iptvplayer.isTv
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import app.iptvplayer.R
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.minuteTicks
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Programme
import app.iptvplayer.core.player.PlaybackState
import app.iptvplayer.core.player.PlayerSession
import java.util.Date
import kotlin.math.roundToInt

// Parts of the live section (33-design §4): every list cell draws each pixel once — no fill under a fill, no clip
// at rest, effects only on the focused element (ARCHITECTURE.md, «Бюджет производительности отрисовки»).

/** Fill of a row, item or chip by state: focus = orange fill, selected = a tone up; nothing at rest. */
private fun Modifier.stateFill(focused: Boolean, selected: Boolean, high: Color, shape: RoundedCornerShape) = when {
    focused -> background(Ext.focus, shape)
    selected -> background(high, shape)
    else -> this
}

/** The "ember line" of a selected element: 3 dp at the left edge, vertically centred, [length] long. */
private fun Modifier.emberBar(on: Boolean, color: Color, length: Dp, x: Dp = 0.dp) = if (!on) this else drawBehind {
    val h = length.toPx()
    drawRoundRect(color, Offset(x.toPx(), (size.height - h) / 2), Size(3.dp.toPx(), h), CornerRadius(2.dp.toPx()))
}

/** Programme progress: a track and a fill, two rectangles drawn under the element (no layout nodes). */
fun Modifier.progressLine(fraction: Float, track: Color, fill: Color, height: Dp) = drawBehind {
    val h = height.toPx()
    val y = size.height - h
    drawRect(track, Offset(0f, y), Size(size.width, h))
    drawRect(fill, Offset(0f, y), Size(size.width * fraction, h))
}

// --- Rail (UI-007) ---

class RailEntry(@DrawableRes val icon: Int, val label: String, val current: Boolean, val focus: FocusRequester, val onClick: () -> Unit)

/**
 * Section rail at the left edge: icons when collapsed (80 dp); with the focus inside it opens to 240 dp **over** the
 * content with a scrim, so nothing under it moves. The current section has the ember line and an orange icon.
 * [bottom] entries sit at the foot (Настройки).
 */
@Composable
fun Rail(top: List<RailEntry>, bottom: List<RailEntry>, modifier: Modifier, onFocusIn: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 240.dp else 80.dp, tween(if (expanded) 250 else 150), label = "rail")
    Box(modifier.fillMaxHeight()) {
        if (expanded) Box(Modifier.fillMaxHeight().width(4000.dp).background(Color.Black.copy(alpha = 0.5f)))
        Column(
            Modifier.fillMaxHeight().width(width).background(c.surfaceContainerLow)
                .onFocusChanged { expanded = it.hasFocus; onFocusIn(it.hasFocus) }
                .padding(vertical = 24.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            top.forEach { RailItem(it, expanded) }
            Spacer(Modifier.weight(1f))
            bottom.forEach { RailItem(it, expanded) }
        }
    }
}

@Composable
private fun RailItem(e: RailEntry, expanded: Boolean) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = when {
        focused -> Ext.onFocus
        e.current -> c.primary
        else -> c.onSurfaceVariant
    }
    Row(
        Modifier
            .height(40.dp)
            .then(if (expanded) Modifier.fillMaxWidth() else Modifier.width(56.dp))
            .emberBar(e.current, Ext.now, 24.dp, x = (-12).dp)
            .background(if (focused) Ext.focus else Color.Transparent, CircleShape)
            .focusRequester(e.focus)
            .clickable(interaction, indication = null, onClick = e.onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(e.icon), contentDescription = e.label, tint = fg, modifier = Modifier.size(24.dp))
        if (expanded) {
            Spacer(Modifier.width(16.dp))
            Text(e.label, style = TvType.label, color = fg, maxLines = 1)
        }
    }
}

// --- Phone: bottom navigation (UI-007, 33-design 4.5) ---

/** Sections of the phone's bottom bar; «Ещё» holds the settings for now. */
enum class Section { Live, Guide, More }

@Composable
fun PhoneNav(current: Section, onSelect: (Section) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        for ((s, icon, label) in listOf(
            Triple(Section.Live, if (current == Section.Live) R.drawable.ic_live_tv_fill else R.drawable.ic_live_tv, R.string.section_live),
            Triple(Section.Guide, R.drawable.ic_guide, R.string.section_guide),
            Triple(Section.More, R.drawable.ic_more, R.string.section_more),
        )) {
            NavigationBarItem(
                selected = s == current,
                onClick = { if (s != current) onSelect(s) },
                icon = { Icon(painterResource(icon), contentDescription = null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

// --- Top bar: tools, network, clock (UI-009, UI-016) ---

/** Round tool button of a bar: tonal at rest, orange fill in focus; 48 dp touch target on a phone. */
@Composable
fun ToolButton(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, size: Dp = 40.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .background(if (focused) Ext.focus else c.surfaceContainerHigh, CircleShape)
            .clip(CircleShape)
            .clickable(interaction, ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = if (focused) Ext.onFocus else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** "Нет сети" mark (UI-016): shown only while there is no network. */
@Composable
fun OfflineMark() = Icon(
    painterResource(R.drawable.ic_wifi_off), contentDescription = stringResource(R.string.offline), tint = Ext.warning, modifier = Modifier.size(24.dp),
)

/**
 * Clock and date (UI-009): 12/24 h as the system has it, updated at the minute boundary; only this text recomposes.
 */
@Composable
fun Clock(modifier: Modifier = Modifier, date: Boolean = true) {
    val ctx = LocalContext.current
    val now by remember { minuteTicks() }.collectAsState(System.currentTimeMillis())
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(ctx).format(Date(now)) }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(time, style = TvType.title)
        if (date) {
            Spacer(Modifier.width(8.dp))
            Text(
                remember(now, LocalConfiguration.current) { dayText(now) },
                style = TvType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/** "1 h 30 min" / "1 ч 30 мин" for a programme's length. */
@Composable
fun durationText(ms: Long): String {
    val min = ((ms + 30_000) / 60_000).toInt()
    return when {
        min < 60 -> stringResource(R.string.duration_m, min)
        min % 60 == 0 -> stringResource(R.string.duration_h, min / 60)
        else -> stringResource(R.string.duration_hm, min / 60, min % 60)
    }
}

/** Time of day as the system shows it (12/24 h). */
@Composable
fun rememberTimeFormat(): (Long) -> String {
    val fmt = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return remember(fmt) { { t: Long -> fmt.format(Date(t)) } }
}

// --- Search (UI-004) ---

/**
 * M3 search bar look. At rest it's a clickable pill (orange in focus); the text field and keyboard only open on
 * OK/tap. Otherwise on TV just moving focus over it pops the keyboard and traps the remote.
 */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, hint: String, pillFocus: FocusRequester, modifier: Modifier, height: Dp = 56.dp) {
    val c = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    BackHandler(editing) { editing = false }

    if (editing) {
        val fieldFocus = remember { FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = modifier
                .focusRing(CircleShape)
                .focusRequester(fieldFocus)
                .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) editing = false },
            placeholder = { Text(hint) },
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else {
                { IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear)) } }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { editing = false }),
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceContainerHigh,
                unfocusedContainerColor = c.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        LaunchedEffect(Unit) {
            wasEditing = true
            fieldFocus.requestFocus()
        }
    } else {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val fg = if (focused) Ext.onFocus else c.onSurfaceVariant
        Row(
            modifier
                .height(height)
                .background(if (focused) Ext.focus else c.surfaceContainerHigh, CircleShape)
                .focusRequester(pillFocus)
                .clip(CircleShape)
                .clickable(interaction, ripple()) { editing = true }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                query.ifEmpty { hint },
                Modifier.weight(1f),
                color = if (focused) Ext.onFocus else if (query.isEmpty()) c.onSurfaceVariant else c.onSurface,
                style = TvType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, Modifier.focusProperties { canFocus = false }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear), tint = fg)
                }
            }
        }
        // Back from the keyboard: keep the remote's focus on the search pill instead of losing it.
        LaunchedEffect(Unit) {
            if (wasEditing) runCatching { pillFocus.requestFocus() }
        }
    }
}

// --- Categories (LIVE-001, LIVE-010) ---

@DrawableRes
fun groupIcon(g: ChannelGroup): Int = when (g) {
    ChannelGroup.Favorites -> R.drawable.ic_star_fill
    ChannelGroup.Recent -> R.drawable.ic_history
    ChannelGroup.Archive -> R.drawable.ic_replay
    ChannelGroup.All -> R.drawable.ic_tv
    is ChannelGroup.Collection -> R.drawable.ic_playlist_play
    is ChannelGroup.Category -> R.drawable.ic_category
}

/** One line of the category column: icon, full name (scrolls while focused or selected), count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryItem(g: GroupCount, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val fg = when {
        focused -> Ext.onFocus
        selected -> c.primary
        else -> c.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(if (g.hidden) Modifier.alpha(0.38f) else Modifier)
            .stateFill(focused, selected, c.surfaceContainerHigh, shape)
            .emberBar(selected, if (focused) Ext.onFocus else Ext.now, 20.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .combinedClickable(interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(groupIcon(g.group)), contentDescription = null, tint = if (focused || selected) fg else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        val running = focused || selected
        Text(
            g.title, style = if (selected) TvType.label else TvType.bodySmall, color = fg, maxLines = 1,
            overflow = if (running) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).then(if (running) Modifier.basicMarquee() else Modifier),
        )
        Spacer(Modifier.width(6.dp))
        Text("${g.count}", style = TvType.badge, color = if (focused) Ext.onFocus else if (selected) c.primary else c.onSurfaceVariant)
    }
}

/** Phone: the lists as chips in a horizontal strip; the selected one gets ✓ and orange text, not a fill. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryChips(groups: List<GroupCount>, selected: ChannelGroup, search: (@Composable (Modifier) -> Unit)?, onSelect: (ChannelGroup) -> Unit, onLongClick: (GroupCount) -> Unit) {
    val c = MaterialTheme.colorScheme
    LazyRow(
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (search != null) item { search(Modifier.width(220.dp)) }
        itemsIndexed(groups, key = { _, g -> g.group.toString() }) { _, g ->
            val on = g.group == selected
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val fg = if (focused) Ext.onFocus else if (on) c.primary else c.onSurface
            Row(
                Modifier
                    .height(48.dp) // touch target; the chip itself is 32
                    .padding(vertical = 8.dp)
                    .then(if (g.hidden) Modifier.alpha(0.38f) else Modifier)
                    .background(if (focused) Ext.focus else c.surfaceContainerHigh, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(interaction, ripple(), onLongClick = { onLongClick(g) }) { onSelect(g.group) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(g.title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

// --- Channel row (LIVE-005) and card (LIVE-003) ---

/**
 * Number · logo · name over "programme · ещё N мин" · icons, and the programme's progress under the text.
 * Without a guide only number, logo and name, centred, at the same height. No fill at rest; [selected] = playing
 * in the preview window (ember line). [tall] = phone size. [now] is the list's one shared minute tick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(item: ChannelItem?, selected: Boolean, now: Long, tall: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (focused) Ext.onFocus else c.onSurface
    val dim = if (focused) Ext.onFocus else c.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .height(if (tall) 72.dp else 56.dp)
            .then(if (item?.hidden == true) Modifier.alpha(0.38f) else Modifier)
            .stateFill(focused, selected, c.surfaceContainerHigh, shape)
            .emberBar(selected, if (focused) Ext.onFocus else Ext.now, 32.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(if (pressed || focused) Modifier.clip(shape) else Modifier)
            .combinedClickable(interaction, ripple(), onLongClick = onLongClick, onClick = onClick)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item == null) return@Row // not loaded yet: an empty row of the same size
        val ch = item.channel
        Text(
            "${item.number}", Modifier.width(if (tall) 32.dp else 36.dp), style = TvType.body.copy(fontWeight = TvType.label.fontWeight),
            color = dim, textAlign = TextAlign.End, maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        ChannelLogo(ch, if (tall) Modifier.size(56.dp, 35.dp) else Modifier.size(64.dp, 40.dp))
        Spacer(Modifier.width(12.dp))
        val p = item.now?.takeIf { it.stop > now }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)
                .then(if (p != null) Modifier.progressLine(p.progress(now), if (focused) Ext.onFocusTrack else Ext.nowTrack, if (focused) Ext.onFocus else Ext.now, 2.dp) else Modifier),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                ch.name, style = if (tall) MaterialTheme.typography.titleMedium else TvType.titleSmall, color = fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (p != null) Row {
                val small = if (tall) MaterialTheme.typography.bodyMedium else TvType.bodySmall
                Text(p.title, style = small, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" " + stringResource(R.string.minutes_left, p.minutesLeft(now)), style = small, color = dim, maxLines = 1, softWrap = false)
            }
        }
        if (item.favorite) RowIcon(R.drawable.ic_favorite_fill, stringResource(R.string.row_favorite), if (focused) Ext.onFocus else c.primary)
        if (ch.archiveDays > 0) RowIcon(R.drawable.ic_history, stringResource(R.string.row_archive), if (focused) Ext.onFocus else c.tertiary)
    }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int, description: String, tint: Color) {
    Spacer(Modifier.width(6.dp))
    Icon(painterResource(icon), description, tint = tint, modifier = Modifier.size(16.dp))
}

/**
 * Channel card: logo (with the programme's progress line along its lower edge), name and "12 · programme" (or the
 * category without a guide). Focus: orange caption, slight scale-up and glow — on the focused card only, so the
 * rest carry no layers (33-design §3). Each half paints its own background; nothing is clipped at rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(item: ChannelItem?, selected: Boolean, now: Long, tv: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (focused && tv) 1.05f else 1f, tween(100), label = "cardScale")
    Column(
        modifier
            .then(
                if (focused && tv) Modifier
                    .zIndex(1f) // the scaled card draws over its neighbours
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(16.dp, shape, ambientColor = Ext.focus, spotColor = Ext.focus)
                else Modifier
            )
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(if (pressed || focused) Modifier.clip(shape) else Modifier)
            .combinedClickable(interaction, ripple(), onLongClick = onLongClick, onClick = onClick),
    ) {
        val p = item?.now?.takeIf { it.stop > now }
        Box {
            if (item != null) ChannelLogo(item.channel, Modifier.fillMaxWidth().aspectRatio(16f / 10f), top = 16.dp, bottom = 0.dp)
            else Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(c.surfaceContainerHighest, RoundedCornerShape(16.dp, 16.dp)))
            if (p != null || selected) Spacer(
                Modifier.matchParentSize().progressLine(if (selected) 1f else p!!.progress(now), Ext.nowTrack, Ext.now, if (selected) 3.dp else 2.dp)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (focused) Ext.focus else c.surfaceContainer, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                item?.channel?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = if (focused) Ext.onFocus else c.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (item == null) "" else "${item.number} · ${p?.title ?: groupName(LocalContext.current, item.channel.group)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) Ext.onFocus else c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- Preview window and programme details (LIVE-006, LIVE-007) ---

/**
 * The channel playing next to the list. [playing] = the session plays this channel now (otherwise its logo is
 * shown: preview off, or the pause before it starts). Video 16:9 on the darkest tone; "ЭФИР" and the actual
 * "1080p · 50" at the top, logo, number and name at the bottom; buffering is a thin line along the lower edge.
 * Not focusable on TV (OK on the row already opens full screen); a tap opens full screen.
 */
@Composable
fun VideoWindow(item: ChannelItem?, playing: Boolean, player: PlayerSession, offline: Boolean, reason: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tv = LocalContext.current.isTv()
    val shape = RoundedCornerShape(12.dp)
    val state: PlaybackState by player.state.collectAsState()
    val bg = c.surface
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.surfaceContainerLowest, shape)) {
        if (playing) {
            AndroidView(
                factory = {
                    SurfaceView(it).apply {
                        keepScreenOn = true
                        isFocusable = false
                        player.setVideoView(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { player.releaseVideoView(it) },
            )
        } else if (item != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelLogo(item.channel, Modifier.size(112.dp, 70.dp))
                if (reason != null) Text(reason, style = TvType.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        // Everything over the video is drawn by the window itself: corners, scrims, badges (a SurfaceView ignores clip).
        Box(
            Modifier.matchParentSize()
                .drawWithContent {
                    drawContent()
                    val r = 12.dp.toPx()
                    val corners = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
                    }
                    drawPath(corners, bg)
                }
                .focusProperties { canFocus = false }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            if (item == null) return@Box
            val error = state.error
            if (playing) {
                Text(stringResource(R.string.live_badge), style = TvType.badge, color = Ext.onLive, modifier = Modifier.padding(8.dp).background(Ext.live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                if (state.height > 0) Text(
                    "${state.height}p" + if (state.fps > 0) " · ${state.fps.roundToInt()}" else "",
                    style = TvType.badge, color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(item.channel, Modifier.size(40.dp, 25.dp), top = 4.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${item.number}  ${item.channel.name}", style = TvType.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (error != null) Column(
                    Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(R.drawable.ic_error), null, tint = c.error, modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(if (offline) R.string.no_connection else if (tv) R.string.preview_error_tv else R.string.preview_error_touch),
                        style = TvType.bodySmall, color = Color.White, textAlign = TextAlign.Center,
                    )
                }
                if (state.buffering && error == null) {
                    LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp), color = Ext.now, trackColor = Color.Transparent)
                }
            }
        }
    }
}

/** Current programme with times, progress and description, then the next two (LIVE-006). */
@Composable
fun DetailsPanel(item: ChannelItem?, schedule: List<Programme>, now: Long, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val hm = rememberTimeFormat()
    val current = schedule.firstOrNull { it.start <= now && it.stop > now } ?: item?.now?.takeIf { it.start <= now && it.stop > now }
    val next = schedule.filter { it.start >= (current?.stop ?: now) }.take(2).ifEmpty { listOfNotNull(item?.next) }
    // Cut at its own edge when the screen is low (phone in landscape): text never spills under the panel.
    Column(modifier.background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).clipToBounds().padding(16.dp)) {
        if (item == null) return@Column
        if (current == null) {
            Text(item.channel.name, style = TvType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.no_programme), style = TvType.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        Text(current.title, style = TvType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.programme_times, hm(current.start), hm(current.stop), durationText(current.stop - current.start), current.minutesLeft(now)),
            style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Spacer(Modifier.fillMaxWidth().height(4.dp).progressLine(current.progress(now), Ext.nowTrack, Ext.now, 4.dp))
        current.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
        if (next.isNotEmpty()) {
            Text(stringResource(R.string.next_up), style = TvType.badge, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            next.forEach { p ->
                Row {
                    Text(hm(p.start), style = TvType.label, color = c.onSurfaceVariant, modifier = Modifier.widthIn(min = 48.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(p.title, style = TvType.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The channel's schedule (LIVE-007): from the current programme on, rows of 48 dp with the focus fill; past ones
 * with archive get the archive mark. The focus starts on the current programme ([first]).
 */
@Composable
fun ScheduleList(schedule: List<Programme>, archive: Boolean, now: Long, first: FocusRequester, state: LazyListState, modifier: Modifier, onFocus: (Programme) -> Unit, onClick: (Programme) -> Unit) {
    val c = MaterialTheme.colorScheme
    val hm = rememberTimeFormat()
    val start = schedule.indexOfFirst { it.stop > now }.coerceAtLeast(0)
    LazyColumn(modifier.background(c.surfaceContainerLow, RoundedCornerShape(24.dp)), state, contentPadding = PaddingValues(8.dp)) {
        itemsIndexed(schedule) { i, p ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val on = p.start <= now && p.stop > now
            val fg = if (focused) Ext.onFocus else if (p.stop <= now) c.onSurfaceVariant else c.onSurface
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .stateFill(focused, on, c.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .then(if (on) Modifier.progressLine(p.progress(now), if (focused) Ext.onFocusTrack else Ext.nowTrack, if (focused) Ext.onFocus else Ext.now, 2.dp) else Modifier)
                    .then(if (i == start) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocus(p) }
                    .clickable(interaction, indication = null) { onClick(p) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hm(p.start), style = TvType.label, color = if (focused) Ext.onFocus else c.onSurfaceVariant, modifier = Modifier.widthIn(min = 48.dp))
                Spacer(Modifier.width(8.dp))
                if (archive && p.stop <= now) {
                    Icon(painterResource(R.drawable.ic_history), stringResource(R.string.row_archive), tint = if (focused) Ext.onFocus else c.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(p.title, style = TvType.bodySmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    LaunchedEffect(Unit) { if (start > 0) state.scrollToItem(start) }
}

// --- Bottom line: key hints (UI-010), background task (UI-015), toast ---

/** Up to four "key — action" pairs for the focused area; not focusable, TV only. */
@Composable
fun Hints(pairs: List<Pair<String, String>>, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        pairs.take(4).forEach { (key, action) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key, style = TvType.badge, color = c.onSurface,
                    modifier = Modifier
                        .background(Color.Transparent, RoundedCornerShape(6.dp))
                        .drawBehind {
                            drawRoundRect(c.outline, cornerRadius = CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(action, style = TvType.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

/** What runs in the background (UI-015): [BgTask.ok] null = still running, then the result for a few seconds. */
data class BgTask(val text: String, val ok: Boolean? = null)

@Composable
fun TaskPill(task: BgTask, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val (icon, tint) = when (task.ok) {
        null -> R.drawable.ic_refresh to c.onSurfaceVariant
        true -> R.drawable.ic_check to Ext.success
        false -> R.drawable.ic_error to c.error
    }
    Pill(modifier) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(task.text, style = TvType.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Short message (4.13): never takes the focus. */
@Composable
fun Toast(text: String, modifier: Modifier) = Pill(modifier) { Text(text, style = TvType.bodySmall) }

@Composable
private fun Pill(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = CircleShape
    Row(
        modifier.height(36.dp).shadow(6.dp, shape).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Empty list (4.14): what to do so it isn't empty. */
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, hint: String?, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).background(c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = c.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Text(title, style = TvType.titleSmall, modifier = Modifier.padding(top = 12.dp))
        if (hint != null) Text(hint, style = TvType.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
