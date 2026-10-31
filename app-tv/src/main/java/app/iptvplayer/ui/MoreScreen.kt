package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.VodFilter
import app.iptvplayer.core.db.VodGroup
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.db.minuteTicks
import app.iptvplayer.core.epg.DAY_MS
import app.iptvplayer.core.epg.floorTo
import app.iptvplayer.core.home.ChannelContinue
import app.iptvplayer.core.home.ContinueItem
import app.iptvplayer.core.home.HistoryFilter
import app.iptvplayer.core.home.VodContinue
import app.iptvplayer.core.home.historyDays
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.ExitGuard
import app.iptvplayer.findActivity
import kotlinx.coroutines.delay
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.isTv
import app.iptvplayer.remoteKeys
import kotlinx.coroutines.launch

/** The pages of «Ещё» (UI-012) that exist so far. */
enum class MorePage { Favorites, History, Settings }

/**
 * «Ещё» (UI-012): the list of pages with their state (how many favourites, how much history, the source) and, on a
 * wide screen, the focused page next to it: ▲▼ — a page, ▶ or OK — into it, Back — back to the list. «Настройки»
 * open as their own screen ([onOpenSettings]). Phone: the list, a tap opens the page full screen.
 */
@Composable
fun MoreScreen(
    repo: Repository,
    source: Source,
    sourceLabel: String,
    online: Boolean,
    task: BgTask?,
    library: Set<VodKind>,
    homeVisible: Boolean,
    continueItem: ContinueItem?,
    onSection: (Section) -> Unit,
    onJump: (Section, Jump) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val wide = isWideLayout()
    val scope = rememberCoroutineScope()
    val key = source.cacheKey()
    var page by rememberSaveable { mutableStateOf(MorePage.Favorites) } // focused (wide) / last opened
    var inside by rememberSaveable { mutableStateOf(false) } // phone: the page is open; wide: the focus is in it
    val rowFocus = remember { MorePage.entries.associateWith { FocusRequester() } }
    val pageFocus = remember { FocusRequester() }
    val railFocus = rememberRailFocus()

    // States of the lines.
    val favChannels = remember(key) { repo.items(ChannelFilter(key, ChannelGroup.Favorites)) }.collectAsLazyPagingItems()
    val favMovies = remember(key) { repo.vod(VodFilter(key, VodKind.MOVIE, VodGroup.Favorites)) }.collectAsLazyPagingItems()
    val favSeries = remember(key) { repo.vod(VodFilter(key, VodKind.SERIES, VodGroup.Favorites)) }.collectAsLazyPagingItems()
    val history by remember { repo.historyList() }.collectAsState(initial = emptyList())
    val recent by remember(source) { repo.recentFlow(source) }.collectAsState(initial = emptyList())
    val favCount = favChannels.itemCount + favMovies.itemCount + favSeries.itemCount
    val historyCount = history.size + recent.size

    fun focusList() {
        scope.launch {
            withFrameNanos {}
            runCatching { rowFocus.getValue(page).requestFocus() }
        }
    }
    LaunchedEffect(Unit) { if (tv && !inside) focusList() else if (tv) { withFrameNanos {}; runCatching { pageFocus.requestFocus() } } }

    fun enter(p: MorePage) {
        page = p
        if (p == MorePage.Settings) { onOpenSettings(); return }
        inside = true
        if (wide) scope.launch { withFrameNanos {}; runCatching { pageFocus.requestFocus() } }
    }

    val exit = remember { ExitGuard() }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(Defaults.TOAST_MS); toast = null } }
    BackHandler(!inside) {
        val back = onBack
        if (back != null) back()
        else if (exit.press(System.currentTimeMillis())) ctx.findActivity()?.finish()
        else toast = ctx.getString(if (tv) R.string.exit_again_tv else R.string.exit_again_touch)
    }
    BackHandler(inside) {
        inside = false
        if (tv) focusList()
    }

    val pageContent: @Composable (Modifier) -> Unit = { m ->
        when (page) {
            MorePage.Favorites -> FavoritesPage(repo, source, favChannels, favMovies, favSeries, library, pageFocus, m, onJump)
            MorePage.History -> HistoryPage(repo, history, recent, pageFocus, m, onJump)
            MorePage.Settings -> Column(m.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(24.dp)) {
                Text(stringResource(R.string.section_settings), style = TvType.title)
                Text(stringResource(R.string.more_settings_hint), style = TvType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    val title = if (!wide && inside) stringResource(page.title()) else stringResource(R.string.section_more)
    Box(Modifier.fillMaxSize()) {
    SectionFrame(
        Section.More, library, online, task, title, railFocus,
        homeVisible = homeVisible,
        onSection = onSection, onCurrent = ::focusList,
        continueItem = continueItem,
        onContinue = { item -> item.playJump().let { (s, j) -> onJump(s, j) } },
        tools = { h -> if (!wide && inside) ToolButton(R.drawable.ic_arrow_back, stringResource(R.string.back), size = h) { inside = false } },
    ) { modifier ->
        val lines: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MorePage.entries.forEach { p ->
                    val state = when (p) {
                        MorePage.Favorites -> if (favCount > 0) pluralStringResource(R.plurals.items_count, favCount, favCount) else stringResource(R.string.more_empty)
                        MorePage.History -> if (historyCount > 0) pluralStringResource(R.plurals.items_count, historyCount, historyCount) else stringResource(R.string.more_empty)
                        MorePage.Settings -> sourceLabel
                    }
                    MoreLine(
                        p.icon(), stringResource(p.title()), state, wide && page == p,
                        Modifier.focusRequester(rowFocus.getValue(p)).onFocusChanged { if (it.isFocused) { page = p; inside = false } },
                    ) { enter(p) }
                }
            }
        }
        if (wide) Row(modifier) {
            lines(Modifier.width(300.dp).fillMaxHeight())
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { if (it.hasFocus) inside = true }) { pageContent(Modifier.fillMaxSize()) }
        } else if (inside) pageContent(modifier) else lines(modifier.padding(top = 8.dp))
    }
    toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 84.dp else 136.dp)) }
    }
}

private fun MorePage.title(): Int = when (this) {
    MorePage.Favorites -> R.string.more_favorites
    MorePage.History -> R.string.more_history
    MorePage.Settings -> R.string.section_settings
}

@DrawableRes
private fun MorePage.icon(): Int = when (this) {
    MorePage.Favorites -> R.drawable.ic_star_fill
    MorePage.History -> R.drawable.ic_history
    MorePage.Settings -> R.drawable.ic_settings
}

/** A line of «Ещё»: icon, name, its state; [selected] = its page is shown beside (ember line, as in the rail). */
@Composable
private fun MoreLine(@DrawableRes icon: Int, title: String, state: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val fg = if (focused) Ext.onFocus else if (selected) c.primary else c.onSurface
    Row(
        modifier.fillMaxWidth().height(64.dp)
            .background(if (focused) Ext.focus else if (selected) c.surfaceContainerHigh else c.surfaceContainerLow, shape)
            .clip(shape)
            .combinedClickableNoLong(interaction, onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = if (focused) Ext.onFocus else c.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TvType.titleSmall, color = fg, maxLines = 1)
            Text(state, style = TvType.bodySmall, color = if (focused) Ext.onFocus else c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoLong(interaction: MutableInteractionSource, onClick: () -> Unit): Modifier =
    combinedClickable(interaction, null, onClick = onClick)

// --- Favourites (HOME-014) ---

/** HOME-014 filter: everything, or one kind. */
private enum class FavFilter { ALL, CHANNELS, MOVIES, SERIES }

/**
 * HOME-014: all favourite channels (with what's on now), films and series on one page, filtered by kind. OK opens the
 * item in its section; its menu (long OK, Menu, long press) has «Убрать» and «Переместить»: in moving mode ▲▼ (or the
 * arrows on the line) move it, OK or «Готово» keeps the new order, Back puts it back as it was.
 */
@Composable
private fun FavoritesPage(
    repo: Repository, source: Source,
    channels: LazyPagingItems<ChannelItem>, movies: LazyPagingItems<VodItem>, series: LazyPagingItems<VodItem>,
    library: Set<VodKind>, first: FocusRequester, modifier: Modifier, onJump: (Section, Jump) -> Unit,
) {
    val ctx = LocalContext.current
    val wide = isWideLayout()
    val tv = remember { ctx.isTv() }
    val scope = rememberCoroutineScope()
    val now by remember { minuteTicks() }.collectAsState(System.currentTimeMillis())
    var filter by rememberSaveable { mutableStateOf(FavFilter.ALL) }
    var sheet by remember { mutableStateOf<Pair<String, List<SheetGroup>>?>(null) }
    // Moving mode: the kind's list as it will be saved, and which line moves.
    var moving by remember { mutableStateOf<Pair<FavFilter, List<Pair<String, String>>>?>(null) }
    var movingIndex by remember { mutableIntStateOf(0) }
    val movingFocus = remember { FocusRequester() }
    val movingState = rememberLazyListState()
    val key = source.cacheKey()
    LaunchedEffect(moving?.first, movingIndex) {
        if (moving == null) return@LaunchedEffect
        movingState.scrollToItem(movingIndex)
        if (tv) { withFrameNanos {}; runCatching { movingFocus.requestFocus() } }
    }

    fun startMoving(kind: FavFilter, itemKey: String) {
        scope.launch {
            val list = when (kind) {
                FavFilter.CHANNELS -> repo.firstChannels(ChannelFilter(key, ChannelGroup.Favorites), channels.itemCount).map { it.channel.key to it.channel.name }
                FavFilter.MOVIES -> repo.firstVod(VodFilter(key, VodKind.MOVIE, VodGroup.Favorites), movies.itemCount).map { it.key to it.title }
                else -> repo.firstVod(VodFilter(key, VodKind.SERIES, VodGroup.Favorites), series.itemCount).map { it.key to it.title }
            }
            val index = list.indexOfFirst { it.first == itemKey }
            if (index < 0) return@launch // removed or refreshed while the menu was open
            movingIndex = index
            moving = kind to list
        }
    }

    fun move(by: Int) {
        val (kind, list) = moving ?: return
        val to = (movingIndex + by).coerceIn(0, list.size - 1)
        if (to == movingIndex) return
        moving = kind to list.toMutableList().apply { add(to, removeAt(movingIndex)) }
        movingIndex = to
    }

    fun saveMoving() {
        val (kind, list) = moving ?: return
        moving = null
        scope.launch {
            val keys = list.map { it.first }
            when (kind) {
                FavFilter.CHANNELS -> repo.reorder(source, ChannelGroup.Favorites, keys)
                FavFilter.MOVIES -> repo.reorderVod(source, VodKind.MOVIE, VodGroup.Favorites, keys)
                else -> repo.reorderVod(source, VodKind.SERIES, VodGroup.Favorites, keys)
            }
        }
    }
    BackHandler(moving != null) { moving = null }

    fun menu(kind: FavFilter, itemKey: String, title: String) {
        sheet = title to listOf(
            null to listOf(
                SheetItem(R.drawable.ic_favorite_fill, ctx.getString(R.string.fav_remove), danger = true) {
                    scope.launch { if (kind == FavFilter.CHANNELS) repo.setFavorite(source, itemKey, false) else repo.setVodFavorite(itemKey, false) }
                },
                SheetItem(R.drawable.ic_swap_vert, ctx.getString(R.string.fav_move)) { startMoving(kind, itemKey) },
            ),
        )
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            val kinds = FavFilter.entries.filter {
                it == FavFilter.ALL || it == FavFilter.CHANNELS || (it == FavFilter.MOVIES && VodKind.MOVIE in library) || (it == FavFilter.SERIES && VodKind.SERIES in library)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(kinds) { i, f ->
                    val count = when (f) {
                        FavFilter.ALL -> channels.itemCount + movies.itemCount + series.itemCount
                        FavFilter.CHANNELS -> channels.itemCount
                        FavFilter.MOVIES -> movies.itemCount
                        FavFilter.SERIES -> series.itemCount
                    }
                    Box(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        FilterChip(stringResource(R.string.label_count, stringResource(f.title()), count), filter == f) { filter = f; moving = null }
                    }
                }
            }
            val m = moving
            if (m != null) {
                // Moving mode: the whole list of that kind, the moving line marked.
                Text(stringResource(R.string.fav_move_hint), style = TvType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().remoteKeys { a, _ ->
                        when (a) {
                            Action.Up -> { move(-1); true }
                            Action.Down -> { move(1); true }
                            Action.Ok -> { saveMoving(); true }
                            else -> false
                        }
                    },
                    movingState, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(m.second, key = { _, it -> it.first }) { i, (_, name) ->
                        MovingLine(name, i == movingIndex, if (i == movingIndex) Modifier.focusRequester(movingFocus) else Modifier, onUp = { move(-1) }, onDown = { move(1) }, onDone = ::saveMoving)
                    }
                }
            } else if (channels.itemCount + movies.itemCount + series.itemCount == 0) {
                EmptyState(
                    R.drawable.ic_star_fill, stringResource(R.string.favorites_empty),
                    stringResource(if (tv) R.string.fav_empty_hint_tv else R.string.fav_empty_hint_touch), Modifier.fillMaxWidth(),
                )
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                if (filter == FavFilter.ALL || filter == FavFilter.CHANNELS) {
                    if (filter == FavFilter.ALL && channels.itemCount > 0) item(key = "hc") { PageHeading(stringResource(R.string.search_kind_channels)) }
                    items(channels.itemCount, key = channels.itemKey { "c:" + it.channel.key }) { i ->
                        val ch = channels[i]
                        ChannelRow(
                            ch, false, now, !wide, Modifier.remoteKeysMenu({ ch?.let { open(it, onJump) } }) { ch?.let { menu(FavFilter.CHANNELS, it.channel.key, it.channel.name) } }, {},
                            { ch?.let { open(it, onJump) } }, { ch?.let { menu(FavFilter.CHANNELS, it.channel.key, it.channel.name) } },
                        )
                    }
                }
                for ((f, list) in listOf(FavFilter.MOVIES to movies, FavFilter.SERIES to series)) {
                    if (filter != FavFilter.ALL && filter != f) continue
                    if (filter == FavFilter.ALL && list.itemCount > 0) item(key = "h$f") { PageHeading(stringResource(f.title())) }
                    items(list.itemCount, key = list.itemKey { "v:" + it.key }) { i ->
                        val item = list[i]
                        VodRow(
                            item, !wide, Modifier.remoteKeysMenu({ item?.let { open(it, onJump) } }) { item?.let { menu(f, it.key, it.title) } }, {},
                            { item?.let { open(it, onJump) } }, { item?.let { menu(f, it.key, it.title) } },
                        )
                    }
                }
            }
        }
        sheet?.let { (title, groups) -> ActionSheet(title, null, groups, wide) { sheet = null } }
    }
}

private fun open(ch: ChannelItem, onJump: (Section, Jump) -> Unit) =
    onJump(Section.Live, Jump(ch.channel.key, ChannelGroup.Favorites.encode(), JumpMode.PLAY))

private fun open(item: VodItem, onJump: (Section, Jump) -> Unit) =
    onJump(item.kind.section(), Jump(item.key, VodGroup.Favorites.asChannelGroup().encode(), JumpMode.OPEN))

private fun FavFilter.title(): Int = when (this) {
    FavFilter.ALL -> R.string.filter_all
    FavFilter.CHANNELS -> R.string.search_kind_channels
    FavFilter.MOVIES -> R.string.section_movies
    FavFilter.SERIES -> R.string.section_series
}

@Composable
private fun PageHeading(text: String) =
    Text(text, style = TvType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))

/** A line in moving mode: the moving one is filled, with ▲▼ and «Готово» for touch. */
@Composable
private fun MovingLine(name: String, on: Boolean, modifier: Modifier, onUp: () -> Unit, onDown: () -> Unit, onDone: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .background(if (on) (if (focused) Ext.focus else c.surfaceContainerHigh) else Color.Transparent, RoundedCornerShape(12.dp))
            .combinedClickableNoLong(interaction) { if (on) onDone() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) Icon(painterResource(R.drawable.ic_swap_vert), null, tint = if (focused) Ext.onFocus else c.primary, modifier = Modifier.size(20.dp).padding(end = 4.dp))
        Text(name, style = TvType.body, color = if (on && focused) Ext.onFocus else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (on && !LocalContext.current.isTv()) {
            ToolButton(R.drawable.ic_arrow_upward, stringResource(R.string.move_up), size = 48.dp, onClick = onUp)
            Spacer(Modifier.width(8.dp))
            ToolButton(R.drawable.ic_arrow_downward, stringResource(R.string.move_down), size = 48.dp, onClick = onDown)
            Spacer(Modifier.width(8.dp))
            ToolButton(R.drawable.ic_check, stringResource(R.string.done), size = 48.dp, onClick = onDone)
        }
    }
}

// --- History (HOME-013) ---

/**
 * HOME-013: films, episodes and channels watched, the latest first, under day headings; filters by kind. A line has
 * the picture, the time it was watched and the progress; OK (or «Продолжить» in its menu) goes on from there,
 * «Удалить» forgets it; «Очистить историю» forgets everything.
 */
@Composable
private fun HistoryPage(
    repo: Repository, history: List<app.iptvplayer.core.db.Watch>, recent: List<Pair<ChannelItem, Long>>,
    first: FocusRequester, modifier: Modifier, onJump: (Section, Jump) -> Unit,
) {
    val ctx = LocalContext.current
    val wide = isWideLayout()
    val scope = rememberCoroutineScope()
    val now by remember { minuteTicks() }.collectAsState(System.currentTimeMillis())
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var sheet by remember { mutableStateOf<Pair<String, List<SheetGroup>>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val days = remember(history, recent, filter) { historyDays(history, recent, filter) { t -> floorTo(t, DAY_MS, zoneOffset(t)) } }
    val today = floorTo(now, DAY_MS, zoneOffset(now))

    fun forget(item: ContinueItem) {
        scope.launch {
            when (item) {
                is VodContinue -> repo.forget(listOf(item.watch.vkey))
                is ChannelContinue -> repo.forgetChannels(listOf(item.channel.channel.key))
            }
        }
    }

    fun menu(item: ContinueItem, title: String) {
        sheet = title to listOf(
            null to listOf(
                SheetItem(R.drawable.ic_play_arrow, ctx.getString(R.string.history_continue)) { item.playJump().let { (s, j) -> onJump(s, j) } },
                SheetItem(R.drawable.ic_delete, ctx.getString(R.string.history_remove), danger = true) { forget(item) },
            ),
        )
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(HistoryFilter.entries) { i, f ->
                        Box(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            FilterChip(stringResource(f.title()), filter == f) { filter = f }
                        }
                    }
                }
                if (history.isNotEmpty() || recent.isNotEmpty()) ToolChip(R.drawable.ic_delete, stringResource(R.string.history_clear)) { confirm = true }
            }
            if (days.isEmpty()) EmptyState(R.drawable.ic_history, stringResource(R.string.history_empty), stringResource(R.string.history_empty_hint), Modifier.fillMaxWidth())
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                for ((day, list) in days) {
                    item(key = "d$day") {
                        PageHeading(
                            when ((day - today + DAY_MS / 2).floorDiv(DAY_MS)) {
                                0L -> stringResource(R.string.day_today)
                                -1L -> stringResource(R.string.day_yesterday)
                                else -> dayText(day)
                            },
                        )
                    }
                    for (item in list) item(key = historyKey(item)) {
                        val title = historyTitle(item)
                        HistoryLine(item, title, !wide, Modifier.remoteKeysMenu({ item.playJump().let { (s, j) -> onJump(s, j) } }) { menu(item, title) }, {
                            item.playJump().let { (s, j) -> onJump(s, j) }
                        }) { menu(item, title) }
                    }
                }
            }
        }
        sheet?.let { (title, groups) -> ActionSheet(title, null, groups, wide) { sheet = null } }
        if (confirm) ConfirmDialog(
            stringResource(R.string.history_clear_title), stringResource(R.string.history_clear_text), stringResource(R.string.history_clear),
            { scope.launch { repo.clearHistory() } },
        ) { confirm = false }
    }
}

private fun HistoryFilter.title(): Int = when (this) {
    HistoryFilter.ALL -> R.string.filter_all
    HistoryFilter.CHANNELS -> R.string.search_kind_channels
    HistoryFilter.MOVIES -> R.string.section_movies
    HistoryFilter.SERIES -> R.string.section_series
}

private fun historyKey(item: ContinueItem) = when (item) {
    is VodContinue -> "v:" + item.watch.vkey
    is ChannelContinue -> "c:" + item.channel.channel.key
}

@Composable
private fun historyTitle(item: ContinueItem): String = when (item) {
    is VodContinue -> item.watch.title
    is ChannelContinue -> item.channel.channel.name
}

/** A history line: the picture, the title, «S1 · E2 · 21:40» / the time, the progress under the text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryLine(item: ContinueItem, title: String, tall: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val hm = rememberTimeFormat()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val fg = if (focused) Ext.onFocus else c.onSurface
    val dim = if (focused) Ext.onFocus else c.onSurfaceVariant
    var share: Float? = null
    val details = when (item) {
        is ChannelContinue -> hm(item.at)
        is VodContinue -> {
            val w = item.watch
            share = if (w.duration > 0 && !w.watched && w.position > 0) (w.position.toFloat() / w.duration).coerceIn(0f, 1f) else null
            listOfNotNull(
                if (w.season != null && w.episode != null) episodeCode(ctx, w.season!!, w.episode!!) else null,
                w.episodeTitle,
                if (w.watched) stringResource(R.string.vod_watched) else null,
                hm(w.watchedAt),
            ).joinToString(" · ")
        }
    }
    Row(
        modifier.fillMaxWidth().height(if (tall) 80.dp else 68.dp)
            .then(if (focused) Modifier.background(Ext.focus, shape).clip(shape) else Modifier)
            .combinedClickable(interaction, ripple(), onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item) {
            is ChannelContinue -> ChannelLogo(item.channel.channel, Modifier.fillMaxHeight().aspectRatio(16f / 10f))
            is VodContinue -> Poster(item.watch.image, title, Modifier.fillMaxHeight().aspectRatio(16f / 9f), radius = 6.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (share != null) Modifier.progressLine(share, if (focused) Ext.onFocusTrack else Ext.nowTrack, if (focused) Ext.onFocus else Ext.now, 2.dp) else Modifier),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = if (tall) MaterialTheme.typography.titleMedium else TvType.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(details, style = if (tall) MaterialTheme.typography.bodyMedium else TvType.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
