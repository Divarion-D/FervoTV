package app.iptvplayer.ui

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
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
import app.iptvplayer.core.home.ChannelContinue
import app.iptvplayer.core.home.ContinueItem
import app.iptvplayer.core.home.HomeRow
import app.iptvplayer.core.home.VodContinue
import app.iptvplayer.core.home.continueRow
import app.iptvplayer.core.home.homeRows
import app.iptvplayer.core.home.visibleRows
import app.iptvplayer.core.input.ExitGuard
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.playItem
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.findActivity
import app.iptvplayer.isTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * «Главная» (HOME-001…004, 33-design 6.3): rows of «Продолжить просмотр», favourite channels, films and series, and
 * (optionally) recent channels, in the user's order (HOME-005); an empty row isn't shown. ▲▼ between rows, ◀▶ along
 * a row, OK plays or opens in the item's own section ([onJump]); no menus here (the actions are in the sections).
 * Rows are lazy with stable keys; pictures are requested at the card's size.
 */
@Composable
fun HomeScreen(
    repo: Repository,
    source: Source,
    player: PlayerSession,
    settings: Settings,
    online: Boolean,
    task: BgTask?,
    library: Set<VodKind>,
    continueItem: ContinueItem?,
    onSection: (Section) -> Unit,
    onJump: (Section, Jump) -> Unit,
    onBack: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val wide = isWideLayout()
    val key = source.cacheKey()
    val now by remember { minuteTicks() }.collectAsState(System.currentTimeMillis())

    // --- Rows ---
    val vod by remember(source) { repo.unfinished(Defaults.HOME_ROW_LIMIT, source) }.collectAsState(initial = null)
    val recent by remember(source) { repo.recentFlow(source) }.collectAsState(initial = null)
    val favChannels = remember(key, settings.epgShift) { repo.items(ChannelFilter(key, ChannelGroup.Favorites)) }.collectAsLazyPagingItems()
    val favMovies = remember(key) { repo.vod(VodFilter(key, VodKind.MOVIE, VodGroup.Favorites)) }.collectAsLazyPagingItems()
    val favSeries = remember(key) { repo.vod(VodFilter(key, VodKind.SERIES, VodGroup.Favorites)) }.collectAsLazyPagingItems()
    val cont = remember(vod, recent, settings.homeChannels) {
        continueRow(vod.orEmpty(), recent.orEmpty(), settings.homeChannels, Defaults.HOME_ROW_LIMIT)
    }
    val recentRow = recent.orEmpty().take(Defaults.HOME_ROW_LIMIT).map { it.first }
    val rowSettings = remember(settings.homeRows, settings.homeChannels) { homeRows(settings.homeRows, settings.homeChannels) }
    val rows = visibleRows(
        rowSettings,
        mapOf(
            HomeRow.CONTINUE to cont.size,
            HomeRow.FAVORITE_CHANNELS to favChannels.itemCount,
            HomeRow.FAVORITE_MOVIES to if (VodKind.MOVIE in library) favMovies.itemCount else 0,
            HomeRow.FAVORITE_SERIES to if (VodKind.SERIES in library) favSeries.itemCount else 0,
            HomeRow.RECENT_CHANNELS to recentRow.size,
        ),
    )
    val loaded = vod != null && recent != null

    // --- Focus: the card the user left (UI-005), else the first one ---
    var focusRow by rememberSaveable { mutableStateOf<String?>(null) }
    var focusIndex by rememberSaveable { mutableIntStateOf(0) }
    val cardFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val railFocus = rememberRailFocus()
    fun target(row: HomeRow, i: Int) = (focusRow ?: rows.firstOrNull()?.name) == row.name && i == focusIndex
    fun focused(row: HomeRow, i: Int) { focusRow = row.name; focusIndex = i }
    fun cardModifier(row: HomeRow, i: Int) = (if (target(row, i)) Modifier.focusRequester(cardFocus) else Modifier)
        .onFocusChanged { if (it.isFocused) focused(row, i) }
    val scope = rememberCoroutineScope()
    fun focusContent() {
        if (rows.isEmpty()) { runCatching { emptyFocus.requestFocus() }; return }
        if (runCatching { cardFocus.requestFocus() }.isSuccess) return
        // The card left is out of its row's window (or gone): the first card instead.
        focusRow = null
        focusIndex = 0
        scope.launch {
            withFrameNanos {}
            runCatching { cardFocus.requestFocus() }
        }
    }
    LaunchedEffect(loaded) {
        if (!tv || !loaded) return@LaunchedEffect
        delay(100) // the paged rows' first pages
        if (focusRow != null && rows.none { it.name == focusRow }) { focusRow = null; focusIndex = 0 }
        withFrameNanos {}
        focusContent()
    }

    fun open(row: HomeRow, i: Int, s: Section, j: Jump) {
        focusRow = row.name
        focusIndex = i
        onJump(s, j)
    }

    var previewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusRow, focusIndex, cont, settings.homePreview) {
        val item = cont.getOrNull(focusIndex).takeIf { tv && settings.homePreview && focusRow == HomeRow.CONTINUE.name }
        if (item == null) {
            if (previewKey != null) player.close()
            previewKey = null
            return@LaunchedEffect
        }
        delay(3_000)
        val play = when (item) {
            is ChannelContinue -> item.channel.channel.playItem()
            is VodContinue -> {
                val url = if (item.kind == VodKind.MOVIE) repo.vodItem(source, VodKind.MOVIE, item.watch.vkey)?.url
                else repo.episode(source, item.watch.vkey)?.url
                url?.let { PlayItem(it, live = false, start = item.watch.position) }
            }
        } ?: return@LaunchedEffect
        player.setMuted(true)
        player.open(play)
        previewKey = rowKey(item)
    }
    DisposableEffect(player) { onDispose { if (previewKey != null) player.close() } }

    val exit = remember { ExitGuard() }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(Defaults.TOAST_MS); toast = null } }
    BackHandler {
        val back = onBack
        if (back != null) back()
        else if (exit.press(System.currentTimeMillis())) ctx.findActivity()?.finish()
        else toast = ctx.getString(if (tv) R.string.exit_again_tv else R.string.exit_again_touch)
    }

    // --- Sizes: TV 196×110 / 160 / 112 (33-design 6.3); a phone, the same rows smaller ---
    val wideW = if (wide) 196.dp else 160.dp
    val logoW = if (wide) 160.dp else 120.dp
    val posterW = if (wide) 112.dp else 96.dp

    Box {
        SectionFrame(
            Section.Home, library, online, task, stringResource(R.string.section_home), railFocus,
            homeVisible = settings.homeVisible,
            onSection = onSection, onCurrent = ::focusContent,
            continueItem = continueItem, onContinue = { item -> item.playJump().let { (s, j) -> onJump(s, j) } },
            tools = { h -> if (!wide) ToolButton(R.drawable.ic_search, stringResource(R.string.section_search), size = h) { onSection(Section.Search) } },
        ) { modifier ->
            if (loaded && rows.isEmpty()) {
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(R.drawable.ic_home, stringResource(R.string.home_empty), stringResource(R.string.home_empty_hint), Modifier)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onSection(Section.Live) }, Modifier.focusRequester(emptyFocus).focusRing(CircleShape)) {
                        IconLabel(R.drawable.ic_live_tv, stringResource(R.string.home_open_live))
                    }
                }
            } else LazyColumn(modifier, rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in rows) {
                    when (row) {
                        HomeRow.CONTINUE -> cardRow(row, R.string.home_continue, cont.size) {
                            itemsIndexed(cont, key = { _, it -> rowKey(it) }) { i, item ->
                                ContinueCard(item, now, tv, wideW, cardModifier(row, i), player, previewKey == rowKey(item)) {
                                    item.playJump().let { (s, j) -> open(row, i, s, j) }
                                }
                            }
                        }
                        HomeRow.FAVORITE_CHANNELS -> cardRow(row, R.string.home_favorite_channels, favChannels.itemCount) {
                            channelItems(favChannels, row, settings.homeGuide, now, tv, wide, logoW, ::cardModifier) { i, ch ->
                                open(row, i, Section.Live, Jump(ch.channel.key, ChannelGroup.Favorites.encode(), JumpMode.PLAY))
                            }
                        }
                        HomeRow.FAVORITE_MOVIES, HomeRow.FAVORITE_SERIES -> {
                            val movies = row == HomeRow.FAVORITE_MOVIES
                            val list = if (movies) favMovies else favSeries
                            cardRow(row, if (movies) R.string.home_favorite_movies else R.string.home_favorite_series, list.itemCount) {
                                posterItems(list, row, tv, posterW, ::cardModifier) { i, item ->
                                    open(row, i, item.kind.section(), Jump(item.key, VodGroup.Favorites.asChannelGroup().encode(), JumpMode.OPEN))
                                }
                            }
                        }
                        HomeRow.RECENT_CHANNELS -> cardRow(row, R.string.home_recent_channels, recentRow.size) {
                            itemsIndexed(recentRow, key = { _, it -> it.channel.key }) { i, ch ->
                                ChannelCard(ch, false, now, tv, Modifier.width(logoW).then(cardModifier(row, i)), {}, {
                                    open(row, i, Section.Live, Jump(ch.channel.key, ChannelGroup.Category(ch.channel.group).encode(), JumpMode.PLAY))
                                }, {})
                            }
                        }
                    }
                }
            }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 84.dp else 136.dp)) }
    }
}

internal fun rowKey(item: ContinueItem): String = when (item) {
    is ChannelContinue -> "c:" + item.channel.channel.key
    is VodContinue -> "v:" + item.watch.vkey
}

/** A titled row of cards; [count] 0 = nothing (the caller leaves such rows out). */
private fun LazyListScope.cardRow(row: HomeRow, title: Int, count: Int, cards: LazyListScope.() -> Unit) {
    if (count == 0) return
    item(key = row.name) {
        Column {
            Text(stringResource(title), style = TvType.titleSmall, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp))
            // Room for the focused card's scale and glow.
            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cards()
            }
        }
    }
}

private fun LazyListScope.channelItems(
    list: LazyPagingItems<ChannelItem>, row: HomeRow, guide: Boolean, now: Long, tv: Boolean, wide: Boolean, width: Dp,
    cardModifier: (HomeRow, Int) -> Modifier, onOpen: (Int, ChannelItem) -> Unit,
) {
    items(list.itemCount, key = list.itemKey { it.channel.key }) { i ->
        val ch = list[i]
        val m = cardModifier(row, i)
        val click = { if (ch != null) onOpen(i, ch) }
        // HOME-003: cards, or a mini-guide of what's on now (a channel row with its programme and progress).
        if (guide) ChannelRow(ch, false, now, !wide, m.width(if (wide) 320.dp else 280.dp), {}, click, {})
        else ChannelCard(ch, false, now, tv, m.width(width), {}, click, {})
    }
}

private fun LazyListScope.posterItems(
    list: LazyPagingItems<VodItem>, row: HomeRow, tv: Boolean, width: Dp,
    cardModifier: (HomeRow, Int) -> Modifier, onOpen: (Int, VodItem) -> Unit,
) {
    items(list.itemCount, key = list.itemKey { it.key }) { i ->
        val item = list[i]
        PosterCard(item, tv, Modifier.width(width).then(cardModifier(row, i)), {}, { if (item != null) onOpen(i, item) }, {})
    }
}

/**
 * HOME-002 card (16:9): the picture (the episode's frame or the poster; a channel's logo on its backdrop), the progress
 * as the ember line, the title and «S1 · E2 — 31 мин до конца» / «смотрели 8 мин назад». Focus as on posters.
 */
@Composable
fun ContinueCard(
    item: ContinueItem, now: Long, tv: Boolean, width: Dp, modifier: Modifier,
    player: PlayerSession? = null, previewing: Boolean = false, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && tv) 1.05f else 1f, tween(100), label = "cardScale")
    val title: String
    val subtitle: String
    var share: Float? = null
    when (item) {
        is ChannelContinue -> {
            title = item.channel.channel.name
            subtitle = stringResource(R.string.home_watched_ago, agoText(item.at, now))
        }
        is VodContinue -> {
            val w = item.watch
            title = w.title
            val left = ((w.duration - w.position).coerceAtLeast(0) + 59_999) / 60_000
            share = if (w.duration > 0 && !w.watched) (w.position.toFloat() / w.duration).coerceIn(0f, 1f) else null
            val code = if (w.kind == VodKind.SERIES && w.season != null && w.episode != null) episodeCode(ctx, w.season!!, w.episode!!) else null
            subtitle = when {
                w.duration <= 0 -> code.orEmpty()
                code != null -> stringResource(R.string.home_episode_left, code, left.toInt())
                else -> stringResource(R.string.home_left, left.toInt())
            }
        }
    }
    Column(
        modifier.width(width)
            .then(if (focused && tv) Modifier.zIndex(1f).graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .then(if (focused) Modifier.clip(shape) else Modifier)
            .clickable(interaction, if (tv) null else ripple(), onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(width * 9 / 16)
                .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = Ext.focus, spotColor = Ext.focus).border(3.dp, Ext.focus, shape) else Modifier),
        ) {
            if (previewing && player != null) {
                AndroidView(
                    factory = { SurfaceView(it).apply { keepScreenOn = true; isFocusable = false; player.setVideoView(this) } },
                    modifier = Modifier.matchParentSize(),
                    onRelease = { player.releaseVideoView(it) },
                )
            } else when (item) {
                is ChannelContinue -> ChannelLogo(item.channel.channel, Modifier.matchParentSize(), top = 12.dp)
                is VodContinue -> Poster(item.watch.image, title, Modifier.matchParentSize())
            }
            if (share != null) Spacer(Modifier.matchParentSize().padding(8.dp).progressLine(share, Ext.nowTrack, Ext.now, 3.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (focused) c.primary else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
