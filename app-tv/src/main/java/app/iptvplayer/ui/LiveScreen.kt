package app.iptvplayer.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.Visibility
import app.iptvplayer.core.db.decodeGroup
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.db.matchesWords
import app.iptvplayer.core.db.minuteTicks
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.ExitGuard
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.Programme
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.isTv
import app.iptvplayer.remoteKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Where the remote's focus is: decides Back, the key hints and where ▶ from the rail goes back to. */
private enum class Area { Rail, Top, Categories, List, Schedule }

/** An open sheet: title, the channel it is about (logo in the header), its groups of lines. */
private class SheetSpec(val title: String, val channel: ChannelItem?, val groups: List<SheetGroup>)

private val GroupSaver = Saver<ChannelGroup, String>({ it.encode() }, ::decodeGroup)

/**
 * «Эфир» (33-design 6.1). Wide screen (TV, tablet): rail, categories, channel rows (or tiles), the preview window
 * with the programme, a top bar with tools and the clock, key hints on TV. Phone: search and tools on top, the
 * preview window once a channel is tapped, category chips, rows or tiles; every action is a tap or a long press.
 *
 * Owns the playback session on this screen: the focused channel plays in the window after a pause
 * ([Defaults.PREVIEW_DELAY_MS]), OK or a tap opens it full screen without a restart (the same stream), and closing
 * the full screen brings it back to the window. Saveable state (list, queries, the channel to come back to)
 * survives Настройки opened over it (see App).
 */
@Composable
fun LiveScreen(
    repo: Repository,
    source: Source,
    player: PlayerSession,
    settings: Settings,
    online: Boolean,
    task: BgTask?,
    onSettings: (Settings) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val conf = LocalConfiguration.current
    // Wide layout: TV, and any screen from 600 dp held in landscape (tablet, phone on its side); a phone in landscape
    // (height under 480 dp) gets it compact: no rail (Настройки go to the top bar), narrower columns, small margins.
    val wide = tv || (conf.screenWidthDp >= 600 && conf.screenWidthDp > conf.screenHeightDp)
    val compact = !tv && conf.screenHeightDp < 480
    val useTiles = settings.liveTiles ?: !wide // LIVE-005: rows on a wide screen, tiles on a phone by default
    val tilesNow by rememberUpdatedState(useTiles)
    val scope = rememberCoroutineScope()
    fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    // --- What is listed ---
    var group by rememberSaveable(stateSaver = GroupSaver) {
        mutableStateOf(if (settings.rememberLive) repo.lastGroup else ChannelGroup.All)
    }
    var started by rememberSaveable { mutableStateOf(false) } // the first list is settled (SET-011)
    var query by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf(query.trim()) } // query after the input delay
    var groupQuery by rememberSaveable { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val groupsAll by remember(source, showHidden) { repo.groups(source, showHidden) }.collectAsState(initial = null)
    val searchGroups = (groupsAll?.size ?: 0) >= Defaults.CATEGORY_SEARCH_MIN
    val groups = remember(groupsAll, groupQuery, searchGroups) {
        groupsAll.orEmpty().filter { !searchGroups || matchesWords(it.title, groupQuery) }
    }
    val groupTitle = groupsAll?.find { it.group == group }?.title ?: "Все каналы"

    // The list the section opens on (LIVE-017 / SET-011): the remembered one, or else the first one with channels.
    LaunchedEffect(groupsAll) {
        val list = groupsAll ?: return@LaunchedEffect
        if (!started) {
            started = true
            if (!settings.rememberLive) group = list.firstOrNull { it.count > 0 }?.group ?: ChannelGroup.All
        }
        // A background refresh (or a deleted collection) can drop the list on screen.
        if (list.isNotEmpty() && list.none { it.group == group }) group = ChannelGroup.All
    }
    LaunchedEffect(group) { repo.lastGroup = group }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(Defaults.SEARCH_DEBOUNCE_MS)
        search = query.trim()
    }
    // By source key and list, not row ids: the same filter keeps working when a refresh replaces the rows.
    val filter = ChannelFilter(
        source.cacheKey(), group, search, settings.channelSort, if (showHidden) Visibility.ALL else Visibility.VISIBLE,
    )
    val currentFilter by rememberUpdatedState(filter)
    val items = remember(filter, settings.epgShift) { repo.items(filter) }.collectAsLazyPagingItems()
    val now by remember { minuteTicks() }.collectAsState(System.currentTimeMillis()) // one tick for all rows

    // --- Focus ---
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val catState = rememberLazyListState()
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) } // the channel to put the focus back on
    var listTarget by rememberSaveable { mutableIntStateOf(0) } // the row the list's focus requester sits on
    var refocus by remember { mutableStateOf(true) } // on start, after the player and after Настройки
    var fromSettings by rememberSaveable { mutableStateOf(false) }
    val listFocus = remember { FocusRequester() }
    val catFocus = remember { FocusRequester() }
    var catTarget by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val groupSearchFocus = remember { FocusRequester() }
    val railLive = remember { FocusRequester() }
    val railSettings = remember { FocusRequester() }
    val scheduleFocus = remember { FocusRequester() }
    val scheduleState = rememberLazyListState()
    var area by remember { mutableStateOf(Area.List) }
    var beforeRail by remember { mutableStateOf(Area.List) }
    val focusedRow = remember { intArrayOf(0) } // read in handlers only: moving the focus recomposes nothing
    val focusedCat = remember { intArrayOf(0) }
    var focusedProgramme by remember { mutableStateOf<Programme?>(null) }
    var selected by remember { mutableStateOf<ChannelItem?>(null) } // focused (TV) or tapped (phone) channel

    // --- Playback, sheets, messages ---
    var preview by remember { mutableStateOf<ChannelItem?>(null) } // the channel in the window
    var playingKey by remember { mutableStateOf<String?>(null) } // what the session plays for this screen
    var fullscreen by remember { mutableStateOf(false) }
    var playIndex by remember { mutableIntStateOf(0) }
    var schedule by remember { mutableStateOf<List<Programme>>(emptyList()) }
    var scheduleMode by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    fun say(text: String) {
        toast = text to System.nanoTime()
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(Defaults.TOAST_MS)
            toast = null
        }
    }

    // Background: live is released, back in front it starts again at the current moment (PLR-002).
    LifecycleStartEffect(player) {
        player.foreground()
        onStopOrDispose { player.background() }
    }
    // Leaving the section (Настройки, another source) stops what it played.
    DisposableEffect(player) { onDispose { player.close() } }

    fun showPreview(item: ChannelItem) {
        preview = item
        if (!settings.preview) {
            if (playingKey != null) player.close()
            playingKey = null
            return
        }
        player.setMuted(!settings.previewSound)
        player.open(PlayItem(item.channel.url))
        playingKey = item.channel.key
    }

    fun watch(index: Int, item: ChannelItem) {
        returnKey = item.channel.key
        playIndex = index
        scheduleMode = false
        player.setMuted(false)
        fullscreen = true
    }

    fun closeFullscreen() {
        fullscreen = false
        refocus = true
        val key = returnKey
        if (!settings.preview || key == null) {
            player.close()
            playingKey = null
            preview = null
            return
        }
        // The stream goes on in the window.
        player.setMuted(!settings.previewSound)
        playingKey = key
        // Only if nothing else was picked meanwhile: a late answer must not bring back a channel tapped before.
        scope.launch { repo.channel(source, key)?.let { if (playingKey == key && !fullscreen) preview = it } }
    }

    // TV: the focused channel starts in the window after a pause, so scrolling doesn't open every stream.
    LaunchedEffect(selected?.channel?.key) {
        val item = selected ?: return@LaunchedEffect
        if (!tv || fullscreen) return@LaunchedEffect
        if (playingKey != item.channel.key) delay(Defaults.PREVIEW_DELAY_MS)
        showPreview(item)
    }

    // The selected channel's programme (LIVE-006/007); Xtream without XMLTV asks the server's short EPG (EPG-002).
    LaunchedEffect(selected?.channel?.key, now / 60_000) {
        val ch = selected?.channel ?: return@LaunchedEffect
        delay(150)
        val t = System.currentTimeMillis()
        val from = t - if (ch.archiveDays > 0) Defaults.EPG_PAST_MS else 0
        val to = t + Defaults.SCHEDULE_HOURS * 3_600_000L
        schedule = repo.schedule(source, ch.key, from, to)
        try {
            repo.loadShortEpg(source, ch)
            schedule = repo.schedule(source, ch.key, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // no short EPG: the panel says there is no programme
        }
    }

    // --- Moving the focus from code ---
    suspend fun focusRow(index: Int) {
        val i = index.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0))
        listTarget = i
        if (tilesNow) gridState.scrollToItem(i) else listState.scrollToItem(i)
        if (tv) {
            withFrameNanos {}
            runCatching { listFocus.requestFocus() }
        }
    }

    fun focusCategory(index: Int) {
        scope.launch {
            catTarget = index.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
            catState.scrollToItem(catTarget)
            withFrameNanos {}
            runCatching { catFocus.requestFocus() }
        }
    }

    fun focusSelectedCategory() = focusCategory(groups.indexOfFirst { it.group == group }.coerceAtLeast(0))

    /** After a sheet or a dialog: back on the element it was opened on (UI-011). */
    fun restoreFocus() {
        if (!tv) return
        scope.launch {
            withFrameNanos {}
            if (area == Area.Categories) focusCategory(focusedCat[0]) else focusRow(focusedRow[0])
        }
    }

    fun closeDialog() {
        dialog = null
        restoreFocus()
    }

    // On start, after the player and Настройки: the focus (and scroll) where the user was (LIVE-017, UI-005).
    val hasItems = items.itemCount > 0
    LaunchedEffect(refocus, hasItems) {
        if (!refocus) return@LaunchedEffect
        if (fromSettings) {
            fromSettings = false
            refocus = false
            if (tv) {
                withFrameNanos {}
                runCatching { railSettings.requestFocus() }
            }
            return@LaunchedEffect
        }
        if (!hasItems) return@LaunchedEffect
        refocus = false
        if (returnKey == null && settings.rememberLive) returnKey = repo.recentKeys(source).firstOrNull()
        val at = returnKey?.let { repo.indexOf(currentFilter, it) } ?: listTarget
        focusRow(at)
    }

    // A list the user picked opens at the top.
    var lastFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            listTarget = 0
            focusedRow[0] = 0
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }

    // --- Number and previous channel from the player (LIVE-009, LIVE-018) ---
    suspend fun find(zap: Zap): Int? {
        val key = when (zap) {
            is Zap.Number -> repo.channelByNumber(source, zap.n)?.key ?: run { say(str(R.string.no_channel_number, zap.n)); return null }
            Zap.Previous -> repo.recentKeys(source).getOrNull(1) ?: run { say(str(R.string.no_previous_channel)); return null }
        }
        repo.indexOf(currentFilter, key)?.let { return it }
        // Not in the list on screen: the section switches to all channels (numbers belong to the whole source).
        query = ""
        search = ""
        group = ChannelGroup.All
        return repo.indexOf(ChannelFilter(source.cacheKey(), sort = settings.channelSort), key)
    }

    // --- Menus (LIVE-011…015, UI-011) ---
    fun newCollection(item: ChannelItem?) {
        dialog = {
            TextDialog(str(R.string.collection_new_title), "", null, { name ->
                if (name.isNotBlank()) scope.launch {
                    val id = repo.createCollection(name)
                    if (item != null) {
                        repo.addToCollection(id, item.channel.key)
                        say(str(R.string.collection_added, name.trim()))
                    }
                }
            }) { closeDialog() }
        }
    }

    fun addToCollection(item: ChannelItem) {
        scope.launch {
            val list = repo.collections().first()
            sheet = SheetSpec(
                str(R.string.collection_pick), item,
                listOf(
                    null to list.map { c ->
                        SheetItem(R.drawable.ic_playlist_play, c.name) {
                            scope.launch { repo.addToCollection(c.id, item.channel.key) }
                            say(str(R.string.collection_added, c.name))
                        }
                    } + SheetItem(R.drawable.ic_new_label, str(R.string.collection_new)) { newCollection(item) },
                ),
            )
        }
    }

    /** LIVE-015: what changes, then back as in the playlist; [category] null = all categories. */
    fun resetEdits(category: String?, title: String?) {
        scope.launch {
            val e = repo.edits(source, category)
            if (e.isEmpty) {
                say(str(R.string.reset_none))
                return@launch
            }
            val text = listOf(str(R.string.reset_names) to e.renamed, str(R.string.reset_hidden) to e.hidden, str(R.string.reset_order) to e.reordered)
                .filter { it.second.isNotEmpty() }
                .joinToString("\n") { (what, list) -> "$what: " + list.take(5).joinToString(", ") + if (list.size > 5) " " + str(R.string.reset_more, list.size - 5) else "" }
            dialog = {
                ConfirmDialog(
                    if (title != null) str(R.string.reset_category_title, title) else str(R.string.reset_all_title),
                    text + "\n\n" + str(R.string.reset_keeps), str(R.string.reset),
                    { scope.launch { repo.resetEdits(source, category) } },
                ) { closeDialog() }
            }
        }
    }

    fun channelMenu(item: ChannelItem, index: Int) {
        val ch = item.channel
        val g = group
        sheet = SheetSpec(
            ch.name, item,
            listOf(
                str(R.string.menu_watching) to listOf(SheetItem(R.drawable.ic_play_arrow, str(R.string.watch)) { watch(index, item) }),
                str(R.string.menu_channel) to listOfNotNull(
                    if (item.favorite) SheetItem(R.drawable.ic_favorite_fill, str(R.string.favorite_remove)) { scope.launch { repo.setFavorite(ch.key, false) } }
                    else SheetItem(R.drawable.ic_favorite, str(R.string.favorite_add)) { scope.launch { repo.setFavorite(ch.key, true) } },
                    SheetItem(R.drawable.ic_playlist_add, str(R.string.collection_add)) { addToCollection(item) },
                    (g as? ChannelGroup.Collection)?.let { c ->
                        SheetItem(R.drawable.ic_playlist_remove, str(R.string.collection_remove)) { scope.launch { repo.removeFromCollection(c.id, ch.key) } }
                    },
                    SheetItem(R.drawable.ic_edit, str(R.string.rename)) {
                        dialog = {
                            TextDialog(str(R.string.channel_name), ch.name, str(R.string.name_reset_hint), { scope.launch { repo.rename(ch.key, it) } }) { closeDialog() }
                        }
                    },
                    if (item.hidden) SheetItem(R.drawable.ic_visibility, str(R.string.show)) { scope.launch { repo.setHidden(listOf(ch.key), false) } }
                    else SheetItem(R.drawable.ic_visibility_off, str(R.string.hide), danger = true) { scope.launch { repo.setHidden(listOf(ch.key), true) } },
                ),
            ),
        )
    }

    fun groupMenu(gc: GroupCount) {
        val g = gc.group
        val lines = mutableListOf<SheetItem>()
        when (g) {
            is ChannelGroup.Category -> {
                lines += SheetItem(R.drawable.ic_edit, str(R.string.rename)) {
                    dialog = {
                        TextDialog(str(R.string.category_name), gc.title, str(R.string.name_reset_hint), { scope.launch { repo.renameCategory(source, g.name, it) } }) { closeDialog() }
                    }
                }
                lines += if (gc.hidden) SheetItem(R.drawable.ic_visibility, str(R.string.category_show)) { scope.launch { repo.setCategoriesHidden(source, listOf(g.name), false) } }
                else SheetItem(R.drawable.ic_visibility_off, str(R.string.category_hide), danger = true) { scope.launch { repo.setCategoriesHidden(source, listOf(g.name), true) } }
                lines += SheetItem(R.drawable.ic_replay, str(R.string.reset_category)) { resetEdits(g.name, gc.title) }
            }
            is ChannelGroup.Collection -> {
                lines += SheetItem(R.drawable.ic_edit, str(R.string.collection_rename)) {
                    dialog = {
                        TextDialog(str(R.string.collection_name), gc.title, null, { if (it.isNotBlank()) scope.launch { repo.renameCollection(g.id, it) } }) { closeDialog() }
                    }
                }
                lines += SheetItem(R.drawable.ic_delete, str(R.string.collection_delete), danger = true) {
                    dialog = {
                        ConfirmDialog(str(R.string.collection_delete_title, gc.title), str(R.string.collection_delete_text), str(R.string.delete), { scope.launch { repo.deleteCollection(g.id) } }) { closeDialog() }
                    }
                }
            }
            ChannelGroup.All -> lines += SheetItem(R.drawable.ic_replay, str(R.string.reset_all)) { resetEdits(null, null) }
            else -> {}
        }
        lines += SheetItem(R.drawable.ic_new_label, str(R.string.collection_new)) { newCollection(null) }
        lines += if (showHidden) SheetItem(R.drawable.ic_visibility_off, str(R.string.hide_hidden)) { showHidden = false }
        else SheetItem(R.drawable.ic_visibility, str(R.string.show_hidden)) { showHidden = true }
        sheet = SheetSpec(gc.title, null, listOf(null to lines))
    }

    fun sortMenu() {
        sheet = SheetSpec(
            str(R.string.channel_order), null,
            listOf(null to ChannelSort.entries.map { s -> SheetItem(null, s.title, selected = s == settings.channelSort) { onSettings(settings.copy(channelSort = s)) } }),
        )
    }

    fun programmeMenu(item: ChannelItem, p: Programme) {
        val hm = android.text.format.DateFormat.getTimeFormat(ctx)
        val at = "${hm.format(p.start)}–${hm.format(p.stop)}"
        sheet = SheetSpec(
            p.title, item,
            listOf(
                (at + (p.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) to
                    listOf(SheetItem(R.drawable.ic_play_arrow, str(R.string.watch_channel)) { watch(focusedRow[0], item) }),
            ),
        )
    }

    // --- Rows: tap / OK ---
    /** TV OK: full screen (or retry a failed preview). Touch: first tap previews, a second one opens full screen. */
    fun activate(index: Int, item: ChannelItem, touch: Boolean) {
        val inWindow = settings.preview && playingKey == item.channel.key
        when {
            !touch && inWindow && player.state.value.error != null -> player.retry()
            touch && settings.preview && preview?.channel?.key != item.channel.key -> {
                selected = item
                showPreview(item)
            }
            else -> watch(index, item)
        }
    }

    fun openPreviewFull() {
        val p = preview ?: return
        scope.launch {
            val i = repo.indexOf(currentFilter, p.channel.key) ?: focusedRow[0]
            if (preview?.channel?.key == p.channel.key && !fullscreen) watch(i, p) // another tap may have come first
        }
    }

    fun page(down: Boolean, long: Boolean, at: Int, count: Int, perRow: Int): Int = when {
        long -> if (down) count - 1 else 0
        down -> at + Defaults.LIST_PAGE * perRow
        else -> at - Defaults.LIST_PAGE * perRow
    }

    fun listKeys(a: Action, long: Boolean): Boolean {
        val i = focusedRow[0]
        return when (a) {
            Action.Ok, Action.Menu -> {
                val item = items.peek(i) ?: return true
                if (long || a == Action.Menu) channelMenu(item, i) else activate(i, item, touch = false)
                true
            }
            Action.ChannelUp, Action.ChannelDown -> {
                val perRow = if (tilesNow) gridState.layoutInfo.visibleItemsInfo.count { it.row == 0 }.coerceAtLeast(1) else 1
                scope.launch { focusRow(page(a == Action.ChannelDown, long, i, items.itemCount, perRow)) }
                true
            }
            Action.Left -> if (!wide || tilesNow) false else { focusSelectedCategory(); true }
            Action.Right -> if (!wide || tilesNow || schedule.isEmpty()) false else {
                scheduleMode = true
                scope.launch {
                    withFrameNanos {}
                    runCatching { scheduleFocus.requestFocus() }
                }
                true
            }
            else -> false
        }
    }

    fun categoryKeys(a: Action, long: Boolean): Boolean {
        val i = focusedCat[0]
        val g = groups.getOrNull(i)
        return when (a) {
            Action.Ok, Action.Menu -> {
                if (g != null) { if (long || a == Action.Menu) groupMenu(g) else group = g.group }
                true
            }
            // CH± only move the focus here; OK opens (LIVE-021).
            Action.ChannelUp, Action.ChannelDown -> { focusCategory(page(a == Action.ChannelDown, long, i, groups.size, 1)); true }
            Action.Right -> { scope.launch { focusRow(focusedRow[0]) }; true }
            else -> false
        }
    }

    fun leaveSchedule() {
        scheduleMode = false
        scope.launch { focusRow(focusedRow[0]) }
    }

    fun scheduleKeys(a: Action, long: Boolean): Boolean {
        val item = selected ?: return false
        return when (a) {
            Action.Ok, Action.Menu -> {
                val p = focusedProgramme ?: return true
                if (!long && a == Action.Ok && p.start <= System.currentTimeMillis()) watch(focusedRow[0], item) else programmeMenu(item, p)
                true
            }
            Action.Left -> { leaveSchedule(); true }
            else -> false
        }
    }

    // --- Back (UI-007: in the rail a second Back within 2 s leaves the app) ---
    val exit = remember { ExitGuard() }
    BackHandler(!fullscreen) {
        if (exit.press(System.currentTimeMillis())) (ctx as? Activity)?.finish()
        else say(str(if (tv) R.string.exit_again_tv else R.string.exit_again_touch))
    }
    BackHandler(!wide && preview != null && !fullscreen) {
        preview = null
        player.close()
        playingKey = null
    }
    BackHandler(tv && wide && area == Area.Categories && groupQuery.isEmpty()) { runCatching { railLive.requestFocus() } }
    BackHandler(tv && wide && (area == Area.List || area == Area.Top) && query.isEmpty()) { focusSelectedCategory() }
    BackHandler(!fullscreen && groupQuery.isNotEmpty() && (!tv || area == Area.Categories)) {
        groupQuery = ""
        if (tv) runCatching { groupSearchFocus.requestFocus() }
    }
    // Back in the list first clears the search and puts the focus on its field.
    BackHandler(!fullscreen && query.isNotEmpty() && (!tv || area == Area.List || area == Area.Top)) {
        query = ""
        if (tv) runCatching { searchFocus.requestFocus() }
    }
    BackHandler(scheduleMode && !fullscreen) { leaveSchedule() }
    BackHandler(fullscreen) { closeFullscreen() }

    // --- Parts shared by both layouts ---
    val channelArea: @Composable (Modifier, Boolean) -> Unit = { modifier, tall ->
        val loaded = items.loadState.refresh is LoadState.NotLoading
        if (items.itemCount == 0 && loaded) {
            if (group == ChannelGroup.Favorites && search.isEmpty()) {
                EmptyState(
                    R.drawable.ic_star_fill, stringResource(R.string.favorites_empty),
                    stringResource(if (tv) R.string.favorites_hint_tv else R.string.favorites_hint_touch), modifier.fillMaxWidth(),
                )
            } else EmptyState(R.drawable.ic_search, "Ничего не найдено", null, modifier.fillMaxWidth())
        } else {
            val keyed = Modifier
                .onFocusChanged { if (it.hasFocus) area = Area.List }
                .remoteKeys(::listKeys)
            val row: @Composable (Int, Modifier) -> Unit = { i, m ->
                val item = items[i]
                val onFocus = {
                    focusedRow[0] = i
                    if (item != null) selected = item
                }
                val mod = if (i == listTarget) m.focusRequester(listFocus) else m
                val chosen = item != null && item.channel.key == playingKey
                if (useTiles) ChannelCard(item, chosen, now, tv, mod, onFocus, { if (item != null) activate(i, item, touch = true) }) { if (item != null) channelMenu(item, i) }
                else ChannelRow(item, chosen, now, tall, mod, onFocus, { if (item != null) activate(i, item, touch = true) }) { if (item != null) channelMenu(item, i) }
            }
            if (useTiles) LazyVerticalGrid(
                GridCells.Adaptive(if (wide && !compact) 150.dp else 104.dp),
                modifier.then(keyed),
                gridState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide && !compact) 16.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wide && !compact) 16.dp else 12.dp),
            ) {
                items(items.itemCount, key = items.itemKey { it.channel.id }) { i -> row(i, Modifier) }
            } else LazyColumn(modifier.then(keyed), listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items.itemCount, key = items.itemKey { it.channel.id }) { i -> row(i, Modifier) }
            }
        }
    }

    val groupSearch: (@Composable (Modifier) -> Unit)? = if (searchGroups) {
        { m -> SearchField(groupQuery, { groupQuery = it }, "Поиск категорий", groupSearchFocus, m, height = if (wide) 40.dp else 48.dp) }
    } else null
    val viewIcon = if (useTiles) R.drawable.ic_view_list else R.drawable.ic_grid_view
    val viewLabel = stringResource(if (useTiles) R.string.view_list else R.string.view_tiles)
    val searchHint = stringResource(R.string.search_channels)
    val orderLabel = stringResource(R.string.channel_order)
    // Back to this screen's default view = no saved choice, so each layout keeps its own default.
    val toggleView = { onSettings(settings.copy(liveTiles = (!useTiles).takeIf { it == wide })) }

    Box(Modifier.fillMaxSize()) {
        if (wide) {
            val c = MaterialTheme.colorScheme
            Row(Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.safeDrawingPadding())) {
                if (!compact) Spacer(Modifier.width(80.dp)) // the rail is drawn over the content (it opens over it)
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(
                        start = 16.dp, end = if (tv) 48.dp else if (compact) 16.dp else 24.dp,
                        top = if (compact) 8.dp else 24.dp, bottom = if (tv) 24.dp else if (compact) 8.dp else 16.dp,
                    ),
                ) {
                    // Top bar (4.6)
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).onFocusChanged { if (it.hasFocus) area = Area.Top },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.section_live), style = TvType.titleSmall)
                            Text(
                                " " + stringResource(R.string.title_with_list, groupTitle), style = TvType.titleSmall.copy(fontWeight = FontWeight.Normal), color = c.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SearchField(query, { query = it }, searchHint, searchFocus, Modifier.width(240.dp), height = 40.dp)
                        ToolButton(viewIcon, viewLabel, onClick = toggleView)
                        ToolButton(R.drawable.ic_sort, orderLabel, onClick = ::sortMenu)
                        if (compact) ToolButton(R.drawable.ic_settings, stringResource(R.string.section_settings)) { fromSettings = true; onOpenSettings() }
                        if (!online) OfflineMark()
                        if (!compact) Clock() // a phone shows its own clock
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                    Row(Modifier.weight(1f)) {
                        // Categories (176)
                        Column(
                            Modifier.width(if (compact) 160.dp else 176.dp).fillMaxHeight().background(c.surfaceContainerLow, RoundedCornerShape(24.dp))
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .onFocusChanged { if (it.hasFocus) area = Area.Categories },
                        ) {
                            Text("Категории", style = TvType.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
                            groupSearch?.invoke(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                            LazyColumn(Modifier.remoteKeys(::categoryKeys), catState) {
                                itemsIndexed(groups, key = { _, g -> g.group.encode() }) { i, g ->
                                    CategoryItem(
                                        g, g.group == group,
                                        if (i == catTarget) Modifier.focusRequester(catFocus) else Modifier,
                                        onFocus = { focusedCat[0] = i },
                                        onClick = { group = g.group },
                                        onLongClick = { groupMenu(g) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        channelArea(Modifier.weight(1f).fillMaxHeight(), false)
                        Spacer(Modifier.width(16.dp))
                        // Preview and programme (272)
                        Column(Modifier.width(if (compact) 240.dp else 272.dp).fillMaxHeight()) {
                            VideoWindow(
                                preview, playingKey != null && playingKey == preview?.channel?.key, player, !online,
                                if (!settings.preview) stringResource(R.string.preview_off) else null, Modifier, ::openPreviewFull,
                            )
                            Spacer(Modifier.height(12.dp))
                            val item = selected ?: preview
                            if (scheduleMode && item != null) ScheduleList(
                                schedule, item.channel.archiveDays > 0, now, scheduleFocus, scheduleState,
                                Modifier.weight(1f).fillMaxWidth().onFocusChanged { if (it.hasFocus) area = Area.Schedule }.remoteKeys(::scheduleKeys),
                                onFocus = { focusedProgramme = it },
                            ) { p -> if (p.start <= System.currentTimeMillis()) watch(focusedRow[0], item) else programmeMenu(item, p) }
                            else DetailsPanel(item, schedule, now, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    // Key hints (TV) and the background task
                    if (tv || task != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tv) {
                            val ok = stringResource(R.string.key_ok)
                            val longOk = stringResource(R.string.key_long_ok)
                            @Composable fun h(key: String, action: Int) = key to stringResource(action)
                            Hints(
                                when (area) {
                                    Area.List -> listOf(h(ok, R.string.hint_watch), h("◀", R.string.hint_categories), h("▶", R.string.hint_schedule), h(longOk, R.string.hint_actions))
                                    Area.Categories -> listOf(h(ok, R.string.hint_select), h("▶", R.string.hint_channels), h("◀", R.string.hint_menu), h(longOk, R.string.hint_actions))
                                    Area.Schedule -> listOf(h(ok, R.string.hint_watch_or_details), h("◀", R.string.hint_to_channel))
                                    Area.Rail -> listOf(h(ok, R.string.hint_open), h("▶", R.string.hint_back), h(stringResource(R.string.key_back), R.string.hint_exit))
                                    Area.Top -> listOf(h(ok, R.string.hint_select), h("▼", R.string.hint_to_list))
                                },
                                Modifier,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        task?.let { TaskPill(it, Modifier) }
                    }
                }
            }
            if (!compact) Rail(
                listOf(RailEntry(R.drawable.ic_live_tv_fill, stringResource(R.string.section_live), true, railLive) { scope.launch { focusRow(focusedRow[0]) } }),
                listOf(RailEntry(R.drawable.ic_settings, stringResource(R.string.section_settings), false, railSettings) { fromSettings = true; onOpenSettings() }),
                // Off TV the system bars overlap the screen edges: the rail keeps clear of them.
                (if (tv) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)))
                    .onPreviewKeyEvent { e ->
                        // ▶ goes back exactly where the focus came from (UI-007).
                        if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                        when (beforeRail) {
                            Area.Categories -> focusCategory(focusedCat[0])
                            Area.Top -> runCatching { searchFocus.requestFocus() }
                            else -> scope.launch { focusRow(focusedRow[0]) }
                        }
                        true
                    },
            ) { inside ->
                if (inside && area != Area.Rail) {
                    beforeRail = area
                    area = Area.Rail
                }
            }
        } else {
            // Phone (6.7)
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!online) OfflineMark()
                    SearchField(query, { query = it }, searchHint, searchFocus, Modifier.weight(1f), height = 48.dp)
                    ToolButton(viewIcon, viewLabel, size = 48.dp, onClick = toggleView)
                    ToolButton(R.drawable.ic_sort, orderLabel, size = 48.dp, onClick = ::sortMenu)
                    ToolButton(R.drawable.ic_settings, stringResource(R.string.section_settings), size = 48.dp) { fromSettings = true; onOpenSettings() }
                }
                preview?.let { p ->
                    VideoWindow(
                        p, playingKey == p.channel.key, player, !online, null, Modifier.padding(top = 8.dp), ::openPreviewFull,
                    )
                    val cur = schedule.firstOrNull { it.start <= now && it.stop > now } ?: p.now?.takeIf { it.stop > now }
                    if (cur != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(stringResource(R.string.programme_left, cur.title, cur.minutesLeft(now)), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.fillMaxWidth().height(6.dp).progressLine(cur.progress(now), Ext.nowTrack, Ext.now, 2.dp))
                    }
                }
                CategoryChips(groups, group, groupSearch, { group = it }, ::groupMenu)
                channelArea(Modifier.weight(1f), true)
            }
            task?.let { TaskPill(it, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp)) }
        }

        if (fullscreen) {
            PlayerScreen(
                player, items.itemCount, playIndex, !online,
                channelAt = { i -> repo.channelAt(currentFilter, i) },
                onIndexChange = { i, ch ->
                    playIndex = i
                    returnKey = ch.key
                    scope.launch { repo.watched(ch.key) }
                },
                find = { find(it) },
            )
        }
        sheet?.let { s ->
            ActionSheet(s.title, s.channel?.channel, s.groups, wide) {
                sheet = null
                restoreFocus()
            }
        }
        toast?.let { (text, _) ->
            Toast(text, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 84.dp else 96.dp))
        }
    }
    dialog?.invoke()
}
