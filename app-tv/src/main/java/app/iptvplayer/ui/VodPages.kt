package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.Repository
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.SeriesView
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodInfo
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.continueEpisode
import app.iptvplayer.core.model.episodeTotal
import app.iptvplayer.core.model.seasonEpisodes
import app.iptvplayer.core.model.seasonTabs
import app.iptvplayer.core.net.HttpException
import app.iptvplayer.core.input.Action
import app.iptvplayer.isTv
import app.iptvplayer.remoteKeys
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// The film card (VOD-004) and the series page (VOD-005, VOD-006): one page over the catalogue; a page of its own
// with scrolling on a phone. Titles are the clean ones (VOD-007); posters keep the provider's caption.

/** A page button (33-design 4.15): tonal at rest, the main one marked by an orange icon and text; focus = fill. */
@Composable
fun PageButton(@DrawableRes icon: Int, label: String, main: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val bg = when {
        focused -> Ext.focus
        main && !tv -> c.primary
        else -> c.surfaceContainerHigh
    }
    val fg = when {
        focused -> Ext.onFocus
        main && !tv -> c.onPrimary
        main -> c.primary
        else -> c.onSurface
    }
    Row(
        modifier.height(if (tv) 40.dp else 48.dp).background(bg, CircleShape).clip(CircleShape)
            .clickable(interaction, ripple(), onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = TvType.label, color = fg, maxLines = 1)
    }
}

/** The backdrop of a page: the picture dimmed towards the background, under the text on a wide screen (one per page). */
@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    if (url == null) return
    val bg = MaterialTheme.colorScheme.surface
    Box(modifier) {
        AsyncImage(url, null, Modifier.fillMaxSize().alpha(0.45f), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.6f), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, bg))))
    }
}

/** "1080p · EN" style labels taken off the title (VOD-007): small outlined chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(tags: List<String>) {
    if (tags.isEmpty()) return
    val c = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        tags.forEach { Text(it, style = TvType.badge, color = c.onSurfaceVariant, modifier = Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) }
    }
}

/**
 * VOD-004: poster, title, year · genre · duration · rating, description; «Смотреть» or «Продолжить с N:NN» and
 * «С начала», «В избранное», the menu. [info] = what Xtream adds (duration, cast), null until it comes.
 */
@Composable
fun FilmPage(item: VodItem, info: VodInfo?, wide: Boolean, onWatch: (Long) -> Unit, onFavorite: () -> Unit, onMenu: () -> Unit, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    BackHandler(onBack = onBack)
    val p = item.progress
    val resumeAt = p?.takeIf { !it.watched && it.position > 0 }?.position
    val duration = info?.duration ?: p?.duration?.takeIf { it > 0 }
    val plot = item.plot?.takeIf { it.isNotBlank() } ?: info?.plot
    val buttons: @Composable (Modifier) -> Unit = { m ->
        val first = remember { FocusRequester() }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(m, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (resumeAt != null) {
                PageButton(R.drawable.ic_play_arrow, stringResource(R.string.vod_continue_at, clockText(resumeAt)), true, Modifier.focusRequester(first)) { onWatch(resumeAt) }
                PageButton(R.drawable.ic_replay, stringResource(R.string.vod_from_start), false) { onWatch(0) }
            } else PageButton(R.drawable.ic_play_arrow, stringResource(R.string.watch), true, Modifier.focusRequester(first)) { onWatch(0) }
            PageButton(
                if (item.favorite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite,
                stringResource(if (item.favorite) R.string.favorite_remove else R.string.favorite_add), false, onClick = onFavorite,
            )
            PageButton(R.drawable.ic_more, stringResource(R.string.vod_menu), false, onClick = onMenu)
        }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            if (tv) runCatching { first.requestFocus() }
        }
    }
    val text: @Composable () -> Unit = {
        Text(item.title, style = if (wide) TvType.headline else MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val facts = vodFacts(item, duration, item.genre ?: info?.genre)
        if (facts.isNotEmpty()) Text(facts, style = TvType.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Tags(item.tags)
        if (p?.watched == true) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_check_circle), null, tint = Ext.success, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.vod_watched), style = TvType.bodySmall, color = c.onSurfaceVariant)
        } else if (p != null && resumeAt != null && p.duration > 0) {
            Spacer(Modifier.padding(top = 10.dp).width(240.dp).height(4.dp).progressLine(resumeAt.toFloat() / p.duration, Ext.nowTrack, Ext.now, 4.dp))
        }
    }
    val details: @Composable () -> Unit = {
        if (plot != null) Text(plot, style = TvType.body, color = c.onSurface, modifier = Modifier.padding(top = 16.dp))
        info?.director?.let { Text(stringResource(R.string.vod_director, it), style = TvType.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
        info?.cast?.let { Text(stringResource(R.string.vod_cast, it), style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
    }
    if (wide) Box(Modifier.fillMaxSize()) {
        Backdrop(item.backdrop ?: info?.backdrop, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 64.dp, end = 48.dp, top = 40.dp, bottom = 24.dp)) {
            Poster(item.poster, item.title, Modifier.width(200.dp).aspectRatio(2f / 3f))
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                text()
                buttons(Modifier.padding(top = 20.dp))
                details()
            }
        }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        BackButton(onBack)
        Row(Modifier.padding(horizontal = 16.dp)) {
            Poster(item.poster, item.title, Modifier.width(120.dp).aspectRatio(2f / 3f))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { text() }
        }
        Column(Modifier.padding(16.dp)) {
            buttons(Modifier)
            details()
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
    }
}

private sealed interface Episodes {
    data object Loading : Episodes
    data class Loaded(val list: List<Episode>) : Episodes
    data class Failed(val error: Exception) : Episodes
}

/**
 * VOD-005: the series' backdrop and description, «Продолжить S1·E2» / «Смотреть», «В избранное», its menu; season tabs
 * with counts (specials last, outside the total); episodes with picture, number, title, date, length, description and
 * progress. Opens on the episode to continue with ([focusKey]: back from the player — on that one). [itemMenu] = the
 * catalogue's lines for the series (favourite, collection, rename, hide); the page adds its own (VOD-006, VOD-008).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesPage(
    repo: Repository,
    source: Source,
    series: VodItem,
    wide: Boolean,
    focusKey: String?,
    itemMenu: () -> List<SheetGroup>,
    onPlay: (Episode, List<Episode>, Long?) -> Unit,
    onFavorite: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val tv = remember { ctx.isTv() }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var reload by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<Episodes>(Episodes.Loading) }
    var view by remember(series.key) { mutableStateOf(repo.seriesView(series.key)) }
    var season by remember(series.key) { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, List<SheetGroup>>?>(null) }
    LaunchedEffect(series.key, reload) {
        episodes = try {
            Episodes.Loaded(repo.episodes(source, series, refresh))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Episodes.Failed(e)
        }
        refresh = false
    }
    val list = (episodes as? Episodes.Loaded)?.list.orEmpty()
    val next = remember(list) { continueEpisode(list) }
    val back = remember(list, focusKey) { list.find { it.key == focusKey } }
    LaunchedEffect(list.isNotEmpty()) { if (season == null) season = (back ?: next)?.season }
    val tabs = remember(list, view) { seasonTabs(list, view) }
    val shown = remember(list, season, view) { season?.let { seasonEpisodes(list, it, view) }.orEmpty() }

    fun setView(v: SeriesView) {
        view = v
        repo.setSeriesView(series.key, v)
    }

    fun mark(eps: List<Episode>, watched: Boolean) {
        scope.launch {
            repo.setWatched(series, eps, watched)
            reload++
        }
    }

    fun episodeMenu(e: Episode) {
        val watched = e.progress?.watched == true
        sheet = "${episodeCode(ctx, e)} · ${episodeName(ctx, e)}" to listOf(
            null to listOfNotNull(
                SheetItem(R.drawable.ic_play_arrow, ctx.getString(R.string.watch)) { onPlay(e, list, null) },
                e.progress?.takeIf { !it.watched && it.position > 0 }?.let { SheetItem(R.drawable.ic_replay, ctx.getString(R.string.vod_from_start)) { onPlay(e, list, 0) } },
                SheetItem(if (watched) R.drawable.ic_visibility_off else R.drawable.ic_check_circle, ctx.getString(if (watched) R.string.vod_mark_unwatched else R.string.vod_mark_watched)) { mark(listOf(e), !watched) },
            ),
        )
    }

    fun seriesMenu() {
        val s = season
        val inSeason = s?.let { n -> list.filter { it.season == n } }.orEmpty()
        val allWatched = inSeason.isNotEmpty() && inSeason.all { it.progress?.watched == true }
        sheet = series.title to (
            listOf(
                ctx.getString(R.string.menu_episodes) to listOfNotNull(
                    s?.let {
                        SheetItem(
                            if (allWatched) R.drawable.ic_visibility_off else R.drawable.ic_check_circle,
                            ctx.getString(if (allWatched) R.string.vod_mark_season_unwatched else R.string.vod_mark_season_watched, seasonName(ctx, it)),
                        ) { mark(inSeason, !allWatched) }
                    },
                    SheetItem(R.drawable.ic_sort, ctx.getString(R.string.vod_newest_first), checked = view.newestFirst) { setView(view.copy(newestFirst = !view.newestFirst)) },
                    SheetItem(R.drawable.ic_visibility_off, ctx.getString(R.string.vod_hide_watched), checked = view.hideWatched) { setView(view.copy(hideWatched = !view.hideWatched)) },
                    SheetItem(R.drawable.ic_refresh, ctx.getString(R.string.vod_reload_episodes)) { refresh = true; reload++ },
                ),
            ) + itemMenu()
        )
    }

    val playFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // TV: the focus starts on «Продолжить», or back on the episode just watched.
    LaunchedEffect(episodes is Episodes.Loading, shown.isNotEmpty()) {
        if (!tv || episodes is Episodes.Loading) return@LaunchedEffect
        withFrameNanos {}
        val i = back?.let { b -> shown.indexOfFirst { it.key == b.key } } ?: -1
        if (i >= 0) {
            listState.scrollToItem(i + if (wide) 0 else 1)
            withFrameNanos {}
            runCatching { rowFocus.requestFocus() }
        } else runCatching { playFocus.requestFocus() }
    }

    val focused = remember { arrayOfNulls<Episode>(1) } // the episode under the remote's focus
    val header: @Composable () -> Unit = {
        Text(series.title, style = if (wide) TvType.headline else MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val total = episodeTotal(list)
        val facts = listOf(vodFacts(series, null), if (total > 0) ctx.resources.getQuantityString(R.plurals.episodes, total, total) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        if (facts.isNotEmpty()) Text(facts, style = TvType.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Tags(series.tags)
        series.plot?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TvType.bodySmall, color = c.onSurface, maxLines = if (wide) 8 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        }
    }
    val buttons: @Composable (Modifier) -> Unit = { m ->
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(m, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val label = when {
                next == null -> stringResource(R.string.watch)
                next.progress == null && list.none { it.progress != null } -> stringResource(R.string.watch)
                else -> stringResource(R.string.vod_continue_episode, episodeCode(ctx, next))
            }
            PageButton(R.drawable.ic_play_arrow, label, true, Modifier.focusRequester(playFocus)) {
                next?.let { onPlay(it, list, it.progress?.takeIf { p -> !p.watched }?.position ?: 0) }
            }
            PageButton(
                if (series.favorite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite,
                stringResource(if (series.favorite) R.string.favorite_remove else R.string.favorite_add), false, onClick = onFavorite,
            )
            PageButton(R.drawable.ic_more, stringResource(R.string.vod_menu), false, onClick = ::seriesMenu)
        }
    }
    val tabRow: @Composable () -> Unit = {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(tabs, key = { it.first }) { (n, count) ->
                SeasonTab(stringResource(R.string.season_tab, seasonName(ctx, n), count), n == season) { season = n }
            }
        }
    }
    val episodeItems: LazyListScope.() -> Unit = {
        when (val e = episodes) {
            Episodes.Loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            is Episodes.Failed -> item { EpisodesError(e.error) { refresh = true; reload++ } }
            is Episodes.Loaded -> if (shown.isEmpty()) item {
                EmptyState(R.drawable.ic_series, stringResource(R.string.vod_no_episodes), null, Modifier.fillMaxWidth())
            } else items(shown, key = { it.key }) { ep ->
                EpisodeRow(
                    ep, wide, if (ep.key == back?.key) Modifier.focusRequester(rowFocus) else Modifier,
                    onFocus = { focused[0] = ep }, onClick = { onPlay(ep, list, null) }, onLongClick = { episodeMenu(ep) },
                )
            }
        }
    }
    // The remote: OK plays the focused episode, long OK / Menu opens its menu (rows take touch themselves).
    val keys = Modifier.remoteKeys { a, long ->
        val e = focused[0]
        if ((a == Action.Ok || a == Action.Menu) && e != null) {
            if (long || a == Action.Menu) episodeMenu(e) else onPlay(e, list, null)
            true
        } else false
    }

    if (wide) Box(Modifier.fillMaxSize()) {
        Backdrop(series.backdrop ?: series.poster, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 64.dp, end = 48.dp, top = 32.dp, bottom = 24.dp)) {
            Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                header()
                buttons(Modifier.padding(top = 20.dp))
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                tabRow()
                LazyColumn(Modifier.weight(1f).then(if (shown.isNotEmpty()) keys else Modifier), listState, verticalArrangement = Arrangement.spacedBy(4.dp)) { episodeItems() }
            }
        }
    } else LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column {
                BackButton(onBack)
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    Poster(series.backdrop ?: series.poster, series.title, Modifier.matchParentSize())
                }
                Spacer(Modifier.height(12.dp))
                header()
                buttons(Modifier.padding(vertical = 12.dp))
                tabRow()
            }
        }
        episodeItems()
    }
    sheet?.let { (title, groups) -> ActionSheet(title, null, groups, wide) { sheet = null } }
}

@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = if (focused) Ext.onFocus else if (selected) c.primary else c.onSurface
    Row(
        Modifier.height(if (tv) 36.dp else 48.dp)
            .background(if (focused) Ext.focus else c.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(interaction, ripple(), onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = TvType.label, color = fg, maxLines = 1)
    }
}

/** An episode: still, "3. Title", date · length, two lines of description, progress or the watched mark. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(e: Episode, wide: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = if (focused) Ext.onFocus else c.onSurface
    val dim = if (focused) Ext.onFocus else c.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().height(if (wide) 88.dp else 96.dp)
            .then(if (focused) Modifier.background(Ext.focus, shape) else Modifier)
            .then(if (focused) Modifier.clip(shape) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .combinedClickable(interaction, ripple(), onLongClick = onLongClick, onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
            Poster(e.image, episodeCode(ctx, e), Modifier.matchParentSize(), radius = 8.dp)
            val share = e.progress?.share()
            if (share != null) Spacer(Modifier.matchParentSize().padding(4.dp).progressLine(share, Ext.nowTrack, Ext.now, 3.dp))
            else if (e.progress?.watched == true) Icon(
                painterResource(R.drawable.ic_check_circle), stringResource(R.string.vod_watched), tint = Ext.success,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(c.surfaceContainerLowest, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.episode_line, e.episode, episodeName(ctx, e)), style = TvType.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(e.released?.take(10), e.duration?.let { durationText(it) }).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, style = TvType.badge, color = dim, maxLines = 1)
            e.plot?.takeIf { it.isNotBlank() }?.let { Text(it, style = TvType.bodySmall, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** SRC-011: the server's answer code and what to do about it. */
@Composable
private fun EpisodesError(e: Exception, onRetry: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_error), null, tint = c.error, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.vod_episodes_failed), style = TvType.title, modifier = Modifier.padding(top = 8.dp))
        Text(errorText(ctx, e), style = TvType.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (e is HttpException) Text(stringResource(R.string.vod_episodes_advice), style = TvType.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        PageButton(R.drawable.ic_refresh, stringResource(R.string.retry), true, Modifier.padding(top = 16.dp), onRetry)
    }
}
