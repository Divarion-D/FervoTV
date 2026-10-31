package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.ProgrammeHit
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.db.minuteTicks
import app.iptvplayer.core.home.ContinueItem
import app.iptvplayer.core.home.continueRow
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Programme
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.playItem
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.search.SearchKind
import app.iptvplayer.core.search.SearchResults
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.ExitGuard
import app.iptvplayer.findActivity
import app.iptvplayer.isTv
import app.iptvplayer.remoteKeys
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One line of the results: a section heading or a found item ([id] stable: kind + key). */
private class Hit(val id: String, val kind: SearchKind, val channel: ChannelItem? = null, val programme: ProgrammeHit? = null, val vod: VodItem? = null)

/**
 * «Поиск» (HOME-007…012): one field across channels, programmes of the next 12 hours, films and series; results come
 * as the user types (the sections' word rules, HOME-010). Filter chips with counts — none on = everything (HOME-008).
 * On a wide screen the focused result shows on the right: a channel plays in the window like in «Эфир», a film or
 * series shows its poster and description. OK leads to the item in its own section ([onJump]); Back from there
 * comes back here with the query and the focus as they were. Without a query: quick links, recent queries (kept,
 * cleared from their menu) and what to go on with (HOME-011).
 */
@Composable
fun SearchScreen(
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
    val scope = rememberCoroutineScope()
    val now by remember { minuteTicks() }.collectAsState(System.currentTimeMillis())

    var query by rememberSaveable { mutableStateOf("") }
    var filterCode by rememberSaveable { mutableStateOf("") } // HOME-008: the kinds switched on, by name
    val filters = SearchKind.entries.filter { it.name in filterCode.split(',') }
    var results by remember { mutableStateOf(SearchResults()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(repo.searches) }
    var focusId by rememberSaveable { mutableStateOf<String?>(null) } // the result to come back to (HOME-012)
    var focused by remember { mutableStateOf<Hit?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, List<SheetGroup>>?>(null) }
    LaunchedEffect(source, query, now, settings.epgShift) {
        val q = query.trim()
        searchError = null
        if (q.isEmpty()) { results = SearchResults(); searching = false; return@LaunchedEffect }
        searching = true
        try {
            delay(Defaults.SEARCH_DEBOUNCE_MS)
            results = repo.search(source, q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = SearchResults(q)
            searchError = errorText(ctx, e)
        } finally {
            searching = false
        }
    }

    // The results as one list: a heading per kind, then its items.
    val hits = remember(results, filterCode, query) {
        if (results.query != query.trim()) return@remember emptyList<Hit>()
        val on = { k: SearchKind -> filters.isEmpty() || k in filters }
        buildList {
            if (on(SearchKind.CHANNELS)) results.channels.forEach { add(Hit("c:" + it.channel.key, SearchKind.CHANNELS, channel = it)) }
            if (on(SearchKind.PROGRAMMES)) results.programmes.forEach { add(Hit("p:" + it.key + ":" + it.programme.start, SearchKind.PROGRAMMES, programme = it)) }
            if (on(SearchKind.MOVIES)) results.movies.forEach { add(Hit("m:" + it.key, SearchKind.MOVIES, vod = it)) }
            if (on(SearchKind.SERIES)) results.series.forEach { add(Hit("s:" + it.key, SearchKind.SERIES, vod = it)) }
        }
    }

    // --- Focus ---
    val fieldFocus = remember { FocusRequester() }
    val hitFocus = remember { FocusRequester() }
    val railFocus = rememberRailFocus()
    val listState = rememberLazyListState()
    fun focusContent() {
        runCatching { if (hits.any { it.id == focusId }) hitFocus.requestFocus() else fieldFocus.requestFocus() }
    }
    // On opening: the field (no keyboard on TV, UI-004); back from a result: that result.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(hits, results.query, searching) {
        if (placed || !tv) return@LaunchedEffect
        if (query.isNotBlank() && results.query != query.trim() && focusId != null) return@LaunchedEffect // results still coming
        placed = true
        val i = hits.indexOfFirst { it.id == focusId }
        if (i >= 0) listState.scrollToItem(i + headingsBefore(hits, i))
        withFrameNanos {}
        focusContent()
    }

    // --- The window on the right (wide): the focused channel plays after a pause, as in «Эфир» ---
    var playingKey by remember { mutableStateOf<String?>(null) }
    val selection = hits.firstOrNull { it.id == focused?.id }?.takeIf { query.isNotBlank() }
    LaunchedEffect(selection?.channel?.channel?.key, wide, settings.preview, settings.previewSound) {
        playingKey = null
        player.close()
        val ch = selection?.channel ?: return@LaunchedEffect
        if (!wide || !settings.preview) return@LaunchedEffect
        delay(Defaults.PREVIEW_DELAY_MS)
        player.setMuted(!settings.previewSound)
        player.open(ch.channel.playItem())
        playingKey = ch.channel.key
    }
    DisposableEffect(player) { onDispose { player.close() } }
    var schedule by remember { mutableStateOf<List<Programme>>(emptyList()) }
    LaunchedEffect(selection?.channel?.channel?.key, settings.epgShift) {
        schedule = emptyList()
        val ch = selection?.channel ?: return@LaunchedEffect
        delay(150)
        val t = System.currentTimeMillis()
        schedule = repo.schedule(source, ch.channel.key, t, t + Defaults.SCHEDULE_HOURS * 3_600_000L)
    }

    // --- Opening a result in its section (HOME-012) ---
    fun open(h: Hit) {
        focusId = h.id
        repo.rememberSearch(query)
        recents = repo.searches
        when {
            h.channel != null -> onJump(Section.Live, Jump(h.channel.channel.key, ChannelGroup.Category(h.channel.channel.group).encode(), JumpMode.FOCUS))
            h.programme != null -> scope.launch {
                val group = repo.channel(source, h.programme.key)?.channel?.group
                onJump(Section.Live, Jump(h.programme.key, group?.let { ChannelGroup.Category(it).encode() }, JumpMode.FOCUS))
            }
            h.vod != null -> onJump(
                h.vod.kind.section(),
                // A film is focused in its grid, a series opens on its page.
                Jump(h.vod.key, ChannelGroup.Category(h.vod.category).encode(), if (h.vod.kind == VodKind.MOVIE) JumpMode.FOCUS else JumpMode.OPEN),
            )
        }
    }

    fun recentMenu(q: String) {
        sheet = q to listOf(
            null to listOf(
                SheetItem(R.drawable.ic_search, ctx.getString(R.string.search_again)) { query = q },
                SheetItem(R.drawable.ic_delete, ctx.getString(R.string.search_recent_remove)) { repo.forgetSearch(q); recents = repo.searches },
                SheetItem(R.drawable.ic_delete, ctx.getString(R.string.search_recent_clear), danger = true) { repo.clearSearches(); recents = repo.searches },
            ),
        )
    }

    val exit = remember { ExitGuard() }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(Defaults.TOAST_MS); toast = null } }
    BackHandler {
        val back = onBack
        if (back != null) back()
        else if (exit.press(System.currentTimeMillis())) ctx.findActivity()?.finish()
        else toast = ctx.getString(if (tv) R.string.exit_again_tv else R.string.exit_again_touch)
    }
    BackHandler(query.isNotEmpty()) {
        query = ""
        focusId = null
        if (tv) runCatching { fieldFocus.requestFocus() }
    }

    val field: @Composable (Modifier) -> Unit = { m ->
        SearchField(
            query, { query = it; focusId = null }, stringResource(R.string.search_everything), fieldFocus, m,
            height = if (wide) 48.dp else 56.dp, edit = !tv && query.isEmpty(),
        )
    }
    val chips: @Composable () -> Unit = {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(SearchKind.entries.filter { it.visible(library) }) { k ->
                FilterChip(stringResource(R.string.label_count, stringResource(k.title()), results.count(k)), k in filters) {
                    val next = if (k in filters) filters - k else filters + k
                    filterCode = next.joinToString(",") { it.name }
                }
            }
        }
    }
    val resultList: @Composable (Modifier) -> Unit = { m ->
        when {
            query.isBlank() -> EmptySearch(repo, source, settings, recents, library, tv, now, m, onSection, ::recentMenu, { query = it }) { s, j -> onJump(s, j) }
            searchError != null -> EmptyState(R.drawable.ic_search, stringResource(R.string.search_failed), searchError, m.fillMaxWidth())
            hits.isEmpty() && !searching -> EmptyState(R.drawable.ic_search, stringResource(R.string.nothing_found), null, m.fillMaxWidth())
            else -> LazyColumn(m, listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                hits.forEachIndexed { i, h ->
                    if (i == 0 || hits[i - 1].kind != h.kind) item(key = "h:" + h.kind) {
                        val shown = when (h.kind) {
                            SearchKind.CHANNELS -> results.channels.size
                            SearchKind.PROGRAMMES -> results.programmes.size
                            SearchKind.MOVIES -> results.movies.size
                            SearchKind.SERIES -> results.series.size
                        }
                        val total = results.count(h.kind)
                        val title = stringResource(h.kind.title())
                        Text(
                            if (total > shown) stringResource(R.string.search_shown_of, title, shown, total) else stringResource(R.string.label_count, title, total),
                            style = TvType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    item(key = h.id) {
                        val mod = (if (h.id == focusId) Modifier.focusRequester(hitFocus) else Modifier)
                        val onFocus = { focused = h; focusId = h.id }
                        when {
                            h.channel != null -> ChannelRow(h.channel, false, now, !wide, mod, onFocus, { open(h) }, { open(h) })
                            h.programme != null -> ProgrammeHitRow(h.programme, now, !wide, mod, onFocus) { open(h) }
                            h.vod != null -> VodRow(h.vod, !wide, mod, onFocus, { open(h) }, { open(h) })
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SectionFrame(
            Section.Search, library, online, task, stringResource(R.string.section_search), railFocus,
            homeVisible = settings.homeVisible,
            onSection = onSection, onCurrent = ::focusContent,
            continueItem = continueItem,
            onContinue = { item -> item.playJump().let { (s, j) -> onJump(s, j) } },
        ) { modifier ->
            if (wide) Row(modifier) {
                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                    field(Modifier.fillMaxWidth())
                    if (query.isNotBlank()) chips()
                    resultList(Modifier.weight(1f).fillMaxWidth())
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(0.42f).fillMaxHeight()) {
                    val f = selection
                    when {
                        f?.channel != null -> {
                            VideoWindow(f.channel, playingKey == f.channel.channel.key, player, !online, null, Modifier) { open(f) }
                            Spacer(Modifier.height(12.dp))
                            DetailsPanel(f.channel, schedule, now, Modifier.fillMaxWidth().weight(1f))
                        }
                        f?.programme != null -> ProgrammeDetails(f.programme, Modifier.fillMaxWidth())
                        f?.vod != null -> VodDetails(f.vod, Modifier.fillMaxWidth())
                    }
                }
            } else Column(modifier) {
                field(Modifier.fillMaxWidth())
                if (query.isNotBlank()) chips()
                resultList(Modifier.weight(1f).fillMaxWidth())
            }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 84.dp else 136.dp)) }
        sheet?.let { (title, groups) ->
            ActionSheet(title, null, groups, wide) {
                sheet = null
                if (tv) scope.launch { withFrameNanos {}; runCatching { fieldFocus.requestFocus() } }
            }
        }
    }
}

/** Headings in the list before the [i]-th hit (one per kind seen so far). */
private fun headingsBefore(hits: List<Hit>, i: Int): Int = hits.take(i + 1).map { it.kind }.distinct().size

private fun SearchKind.visible(library: Set<VodKind>) = when (this) {
    SearchKind.MOVIES -> VodKind.MOVIE in library
    SearchKind.SERIES -> VodKind.SERIES in library
    else -> true
}

private fun SearchKind.title(): Int = when (this) {
    SearchKind.CHANNELS -> R.string.search_kind_channels
    SearchKind.PROGRAMMES -> R.string.search_kind_programmes
    SearchKind.MOVIES -> R.string.section_movies
    SearchKind.SERIES -> R.string.section_series
}

/** HOME-011: without a query — quick links, recent queries, what to go on with. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptySearch(
    repo: Repository, source: Source, settings: Settings, recents: List<String>, library: Set<VodKind>, tv: Boolean, now: Long, modifier: Modifier,
    onSection: (Section) -> Unit, onRecentMenu: (String) -> Unit, onRecent: (String) -> Unit, onJump: (Section, Jump) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val vod by remember(source) { repo.unfinished(Defaults.HOME_ROW_LIMIT, source) }.collectAsState(initial = emptyList())
    val recent by remember(source) { repo.recentFlow(source) }.collectAsState(initial = emptyList())
    val cont = remember(vod, recent, settings.homeChannels) { continueRow(vod, recent, settings.homeChannels, Defaults.HOME_ROW_LIMIT) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "links") {
            Column {
                Text(stringResource(R.string.search_quick), style = TvType.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(railSections(library).filter { it != Section.Search && it != Section.More && it != Section.Home }) { s ->
                        ToolChip(s.icon(), stringResource(s.label())) { onSection(s) }
                    }
                }
            }
        }
        if (recents.isNotEmpty()) {
            item(key = "recentTitle") {
                Text(stringResource(R.string.search_recent), style = TvType.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
            }
            items(recents, key = { "r:$it" }) { q ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .background(if (focused) Ext.focus else Color.Transparent, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(interaction, ripple(), onLongClick = { onRecentMenu(q) }) { onRecent(q) }
                        .remoteKeysMenu({ onRecent(q) }) { onRecentMenu(q) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_history), null, tint = if (focused) Ext.onFocus else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(q, style = TvType.body, color = if (focused) Ext.onFocus else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (cont.isNotEmpty()) item(key = "continue") {
            Column {
                Text(stringResource(R.string.home_continue), style = TvType.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(6.dp)) {
                    itemsIndexed(cont, key = { _, item -> rowKey(item) }) { _, item ->
                        ContinueCard(item, now, tv, if (isWideLayout()) 196.dp else 160.dp, Modifier) { item.playJump().let { (s, j) -> onJump(s, j) } }
                    }
                }
            }
        }
    }
}

/** The remote on a line with a menu: OK = [onClick], long OK or the Menu key = [onMenu] (UI-011). */
@Composable
fun Modifier.remoteKeysMenu(onClick: () -> Unit, onMenu: () -> Unit): Modifier = remoteKeys { a, long ->
    when {
        a == Action.Menu || (a == Action.Ok && long) -> { onMenu(); true }
        a == Action.Ok -> { onClick(); true }
        else -> false
    }
}

/** A found programme (HOME-009): its time and channel; on now = the progress line. */
@Composable
private fun ProgrammeHitRow(hit: ProgrammeHit, now: Long, tall: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val hm = rememberTimeFormat()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val p = hit.programme
    val on = p.start <= now && p.stop > now
    val fg = if (focused) Ext.onFocus else c.onSurface
    val dim = if (focused) Ext.onFocus else c.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().height(if (tall) 72.dp else 56.dp)
            .background(if (focused) Ext.focus else Color.Transparent, RoundedCornerShape(12.dp))
            .then(if (on) Modifier.progressLine(p.progress(now), if (focused) Ext.onFocusTrack else Ext.nowTrack, if (focused) Ext.onFocus else Ext.now, 2.dp) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clip(RoundedCornerShape(12.dp))
            .clickable(interaction, ripple(), onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(hm(p.start), style = TvType.label, color = dim, modifier = Modifier.widthIn(min = 56.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = if (tall) MaterialTheme.typography.titleMedium else TvType.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.search_programme_on, hm(p.start), hm(p.stop), hit.channel),
                style = if (tall) MaterialTheme.typography.bodyMedium else TvType.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The focused programme on the right: title, time and channel, description. */
@Composable
private fun ProgrammeDetails(hit: ProgrammeHit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val hm = rememberTimeFormat()
    val p = hit.programme
    Column(modifier.background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(16.dp)) {
        Text(p.title, style = TvType.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.search_programme_on, hm(p.start), hm(p.stop), hit.channel), style = TvType.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        p.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** The focused film or series on the right: poster, title, facts, description. */
@Composable
private fun VodDetails(item: VodItem, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(16.dp)) {
        Poster(item.poster, item.title, Modifier.width(112.dp).aspectRatio(2f / 3f))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = TvType.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val facts = vodFacts(item, item.progress?.duration)
            if (facts.isNotEmpty()) Text(facts, style = TvType.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            item.plot?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TvType.bodySmall, color = c.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/**
 * Filter chip (33-design 4.15): 32 dp (a 48 dp touch target on a phone), tonal; on = ✓ and orange text; focus = fill.
 */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = if (focused) Ext.onFocus else if (selected) c.primary else c.onSurface
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.height(48.dp).clip(shape).clickable(interaction, ripple(), onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(32.dp)
                .background(if (focused) Ext.focus else c.surfaceContainerHigh, shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(painterResource(R.drawable.ic_check), null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}
