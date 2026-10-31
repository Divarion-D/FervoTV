package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.runtime.snapshotFlow
import app.iptvplayer.core.home.ContinueItem
import app.iptvplayer.core.home.jumpLists
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
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.Visibility
import app.iptvplayer.core.db.VodFilter
import app.iptvplayer.core.db.VodGroup
import app.iptvplayer.core.db.decodeGroup
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.ExitGuard
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodInfo
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.model.VodSort
import app.iptvplayer.core.model.continueEpisode
import app.iptvplayer.core.model.nextEpisode
import app.iptvplayer.core.model.previousEpisode
import app.iptvplayer.core.model.resumeChoice
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.findActivity
import app.iptvplayer.isTv
import app.iptvplayer.remoteKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Where the remote's focus is in the library (Back and the key hints follow it). */
private enum class LibArea { Rail, Top, Categories, Grid }

private val VodGroupSaver = Saver<VodGroup, String>({ it.asChannelGroup().encode() }, { decodeGroup(it).asVodGroup() })

/**
 * «Фильмы» or «Сериалы» ([kind], 33-design 6.4): the provider's categories with «Избранное», «Недавние», «Все» and
 * the user's collections on the left, the catalogue as a poster grid or a list on the right (VOD-001…003), paged
 * from the database. OK on a poster opens the film card (VOD-004) or the series page (VOD-005); long OK / Menu — the
 * menu (VOD-013). Films and episodes play full screen through the app's one [player] (VOD-009, VOD-010).
 * Phone: the categories as chips, three posters a row, the bottom navigation. Saveable state (list, search, the
 * poster to come back to, the open page) survives Настройки and the other sections opened over it.
 */
@Composable
fun LibraryScreen(
    repo: Repository,
    source: Source,
    player: PlayerSession,
    settings: Settings,
    online: Boolean,
    task: BgTask?,
    kind: VodKind,
    library: Set<VodKind>,
    onSettings: (Settings) -> Unit,
    onSection: (Section) -> Unit,
    jump: Jump? = null,
    onJumpDone: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    continueItem: ContinueItem? = null,
    onContinue: (ContinueItem) -> Unit = {},
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val conf = LocalConfiguration.current
    val wide = isWideLayout()
    val compact = !tv && conf.screenHeightDp < 480
    val scope = rememberCoroutineScope()
    fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)
    val movies = kind == VodKind.MOVIE

    // --- What is listed ---
    var group by rememberSaveable(stateSaver = VodGroupSaver) { mutableStateOf<VodGroup>(VodGroup.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf(query.trim()) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val groupsRaw by remember(source, kind, showHidden) { repo.vodGroups(source, kind, showHidden) }.collectAsState(initial = null)
    val groups: List<GroupCount> = groupsRaw?.let { remember(it, ctx, conf) { it.named(ctx, kind) } }.orEmpty()
    val sectionTitle = stringResource(if (movies) R.string.section_movies else R.string.section_series)
    val groupTitle = groups.find { it.group == group.asChannelGroup() }?.title.orEmpty()
    LaunchedEffect(groupsRaw) {
        val list = groupsRaw ?: return@LaunchedEffect
        if (list.isNotEmpty() && list.none { it.group == group }) group = VodGroup.All // a refresh or a deleted collection
    }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(Defaults.SEARCH_DEBOUNCE_MS)
        search = query.trim()
    }
    val sort = if (movies) settings.movieSort else settings.seriesSort
    val asList = if (movies) settings.movieList else settings.seriesList
    val filter = VodFilter(source.cacheKey(), kind, group, search, sort, if (showHidden) Visibility.ALL else Visibility.VISIBLE)
    val items = remember(filter) { repo.vod(filter) }.collectAsLazyPagingItems()
    val listNow by rememberUpdatedState(asList)
    val itemsNow by rememberUpdatedState(items)

    // --- Focus ---
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val catState = rememberLazyListState()
    var target by rememberSaveable { mutableIntStateOf(0) } // the poster the focus requester sits on
    val focused = remember { intArrayOf(0) } // read in handlers only: moving the focus recomposes nothing
    val focusedCat = remember { intArrayOf(0) }
    var columns by remember { mutableIntStateOf(1) }
    val gridFocus = remember { FocusRequester() }
    val catFocus = remember { FocusRequester() }
    var catTarget by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val railFocus = rememberRailFocus()
    var area by remember { mutableStateOf(LibArea.Grid) }
    var beforeRail by remember { mutableStateOf(LibArea.Grid) }
    var refocus by remember { mutableStateOf(true) }

    // --- Pages, playback, sheets ---
    var pageKey by rememberSaveable { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<VodItem?>(null) }
    var edits by remember { mutableIntStateOf(0) } // the open page reads its item again
    var info by remember { mutableStateOf<VodInfo?>(null) }
    var playing by remember { mutableStateOf<VodPlay?>(null) }
    var lastEpisode by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, List<SheetGroup>>?>(null) }
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
    LaunchedEffect(pageKey, edits) {
        val k = pageKey
        page = if (k == null) null else repo.vodItem(source, kind, k) ?: run { pageKey = null; null }
    }
    LaunchedEffect(page?.key) {
        info = null
        val p = page ?: return@LaunchedEffect
        info = try {
            repo.vodInfo(source, p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // the card shows what the catalogue has
        }
    }
    // Leaving the section (another section, Настройки) stops what it played.
    DisposableEffect(player) { onDispose { player.close() } }

    // --- Moving the focus from code ---
    suspend fun focusCell(index: Int) {
        val i = index.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0))
        target = i
        focused[0] = i
        if (listNow) listState.scrollToItem(i) else gridState.scrollToItem(i)
        if (tv) {
            withFrameNanos {}
            runCatching { gridFocus.requestFocus() }
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

    fun focusSelectedCategory() = focusCategory(groups.indexOfFirst { it.group == group.asChannelGroup() }.coerceAtLeast(0))

    fun restoreFocus() {
        if (!tv || page != null || playing != null) return
        scope.launch {
            withFrameNanos {}
            if (area == LibArea.Categories) focusCategory(focusedCat[0]) else focusCell(focused[0])
        }
    }

    fun closeDialog() {
        dialog = null
        restoreFocus()
    }

    // A list the user picked opens at the top.
    var lastFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            target = 0
            focused[0] = 0
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }
    // On opening and back from a page or the player: the focus on the poster the user was on.
    val loaded = items.loadState.refresh is LoadState.NotLoading
    val hasItems = items.itemCount > 0
    LaunchedEffect(refocus, hasItems, loaded, pageKey, playing == null) {
        if (!refocus || pageKey != null || playing != null || !tv) return@LaunchedEffect
        if (hasItems) {
            refocus = false
            focusCell(target)
        } else if (loaded && groups.isNotEmpty()) {
            refocus = false
            focusSelectedCategory()
        }
    }

    // --- Playing (VOD-009, VOD-010) ---
    /** [start] null = as the resume setting says: continue, ask or from the start. */
    fun play(item: VodItem, episode: Episode?, episodes: List<Episode>, start: Long?) {
        if (start != null) {
            playing = VodPlay(item, episode, start, episodes)
            return
        }
        val choice = resumeChoice(if (episode != null) episode.progress else item.progress, settings.resume)
        if (!choice.ask) {
            playing = VodPlay(item, episode, choice.at, episodes)
            return
        }
        sheet = (episode?.let { "${episodeCode(ctx, it)} · ${episodeName(ctx, it)}" } ?: item.title) to listOf(
            null to listOf(
                SheetItem(R.drawable.ic_play_arrow, str(R.string.vod_continue_at, clockText(choice.at)), selected = null) { playing = VodPlay(item, episode, choice.at, episodes) },
                SheetItem(R.drawable.ic_replay, str(R.string.vod_from_start)) { playing = VodPlay(item, episode, 0, episodes) },
            ),
        )
    }

    /** A series from the catalogue: the episode it continues with (VOD-005). */
    fun watchSeries(item: VodItem) {
        scope.launch {
            val list = try {
                repo.episodes(source, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(str(R.string.vod_episodes_failed) + ": " + errorText(ctx, e))
                return@launch
            }
            continueEpisode(list)?.let { play(item, it, list, null) } ?: say(str(R.string.vod_no_episodes))
        }
    }

    fun save(p: VodPlay, position: Long, duration: Long) {
        scope.launch {
            if (p.episode == null) repo.savePosition(p.item, position, duration) else repo.savePosition(p.item, p.episode, position, duration)
        }
    }

    var jumpBack by remember { mutableStateOf(false) } // opened by a jump: Back from the grid goes back (HOME-012)
    var jumpReturn by remember { mutableStateOf(false) } // the page or player a jump opened: closing it goes back too

    fun closePlayer() {
        lastEpisode = playing?.episode?.key
        player.close()
        playing = null
        edits++
        refocus = true
        if (jumpReturn) {
            jumpReturn = false
            onBack?.invoke()
        }
    }

    fun openItem(index: Int, item: VodItem) {
        focused[0] = index
        target = index
        lastEpisode = null
        pageKey = item.key
    }

    fun closePage() {
        pageKey = null
        page = null
        refocus = true
        if (jumpReturn) {
            jumpReturn = false
            onBack?.invoke()
        }
    }

    // --- Opened on one item (HOME-002, HOME-006, HOME-012): its list, then the focus on it, its page or playing ---
    LaunchedEffect(jump?.id) {
        val j = jump ?: return@LaunchedEffect
        try {
        val lists = snapshotFlow { groupsRaw }.first { it != null }.orEmpty()
        val wanted = j.group?.let { decodeGroup(it).asVodGroup() }
        val candidates = jumpLists(wanted, group, lists.map { it.group }, VodGroup.All)
        query = ""
        search = ""
        group = candidates.first()
        lastFilter = filter.copy(group = group, search = "") // the list opens on the item, not at the top
        when (j.mode) {
            JumpMode.FOCUS -> {
                var i: Int? = null
                for (g in candidates) {
                    i = repo.vodIndexOf(filter.copy(group = g, search = ""), j.key) ?: continue
                    group = g
                    lastFilter = filter.copy(group = g, search = "")
                    break
                }
                if (i == null) { say(str(R.string.jump_missing)); return@LaunchedEffect }
                target = i
                focused[0] = i
                jumpBack = true
                refocus = true // TV: the focus on it
                snapshotFlow { itemsNow.itemCount }.first { it > i }
                if (listNow) listState.scrollToItem(i) else gridState.scrollToItem(i)
            }
            JumpMode.OPEN -> {
                jumpReturn = true
                lastEpisode = null
                pageKey = j.key
            }
            JumpMode.PLAY -> {
                // «Продолжить» goes on from where it stopped, without asking (HOME-002).
                val item = repo.vodItem(source, kind, j.key) ?: run { say(str(R.string.jump_missing)); return@LaunchedEffect }
                if (item.kind == VodKind.MOVIE) {
                    jumpReturn = true
                    playing = VodPlay(item, null, item.progress?.takeIf { !it.watched }?.position ?: 0)
                } else {
                    val list = try {
                        repo.episodes(source, item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        say(str(R.string.vod_episodes_failed) + ": " + errorText(ctx, e))
                        return@LaunchedEffect
                    }
                    val e = list.find { it.key == j.episode } ?: continueEpisode(list) ?: run { say(str(R.string.vod_no_episodes)); return@LaunchedEffect }
                    jumpReturn = true
                    playing = VodPlay(item, e, e.progress?.takeIf { !it.watched }?.position ?: 0, list)
                }
            }
        }
        } finally {
            // Keep the request alive through the suspending lookups; don't consume a replacement on cancellation.
            if (isActive) onJumpDone()
        }
    }

    // --- Menus (VOD-011…013, UI-011) ---
    fun changed() {
        edits++
    }

    fun newCollection(item: VodItem?) {
        dialog = {
            TextDialog(str(R.string.collection_new_title), "", null, { name ->
                if (name.isNotBlank()) scope.launch {
                    val id = repo.createVodCollection(kind, name)
                    if (item != null) {
                        repo.addToVodCollection(id, item.key)
                        say(str(R.string.collection_added, name.trim()))
                    }
                }
            }) { closeDialog() }
        }
    }

    fun addToCollection(item: VodItem) {
        scope.launch {
            val list = repo.vodCollections(kind).first()
            sheet = str(R.string.collection_pick) to listOf(
                null to list.map { c ->
                    SheetItem(R.drawable.ic_playlist_play, c.name) {
                        scope.launch { repo.addToVodCollection(c.id, item.key) }
                        say(str(R.string.collection_added, c.name))
                    }
                } + SheetItem(R.drawable.ic_new_label, str(R.string.collection_new)) { newCollection(item) },
            )
        }
    }

    /** The library and organizing lines of a film or series (on a poster, the film card and the series page). */
    fun itemLines(item: VodItem): List<SheetGroup> {
        val g = group
        val watched = item.progress?.watched == true
        return listOf(
            str(R.string.menu_library) to listOfNotNull(
                if (item.favorite) SheetItem(R.drawable.ic_favorite_fill, str(R.string.favorite_remove)) { scope.launch { repo.setVodFavorite(item.key, false); changed() } }
                else SheetItem(R.drawable.ic_favorite, str(R.string.favorite_add)) { scope.launch { repo.setVodFavorite(item.key, true); changed() } },
                if (item.kind == VodKind.MOVIE) SheetItem(
                    if (watched) R.drawable.ic_visibility_off else R.drawable.ic_check_circle, str(if (watched) R.string.vod_mark_unwatched else R.string.vod_mark_watched),
                ) { scope.launch { repo.setWatched(item, !watched); changed() } } else null,
                SheetItem(R.drawable.ic_playlist_add, str(R.string.collection_add)) { addToCollection(item) },
                (g as? VodGroup.Collection)?.let { c -> SheetItem(R.drawable.ic_playlist_remove, str(R.string.collection_remove)) { scope.launch { repo.removeFromVodCollection(c.id, item.key) } } },
            ),
            str(R.string.menu_organize) to listOf(
                SheetItem(R.drawable.ic_edit, str(R.string.rename)) {
                    dialog = {
                        TextDialog(str(R.string.vod_name), item.title, str(R.string.vod_name_reset_hint), { scope.launch { repo.renameVod(item.key, it); changed() } }) { closeDialog() }
                    }
                },
                if (item.hidden) SheetItem(R.drawable.ic_visibility, str(R.string.show)) { scope.launch { repo.setVodHidden(listOf(item.key), false); changed() } }
                else SheetItem(R.drawable.ic_visibility_off, str(R.string.hide), danger = true) { scope.launch { repo.setVodHidden(listOf(item.key), true); changed() } },
            ),
        )
    }

    fun itemMenu(index: Int, item: VodItem) {
        sheet = item.name to (
            listOf(
                str(R.string.menu_watching) to listOf(
                    SheetItem(R.drawable.ic_play_arrow, str(R.string.watch)) { if (item.kind == VodKind.MOVIE) play(item, null, emptyList(), null) else watchSeries(item) },
                    SheetItem(R.drawable.ic_info, str(R.string.vod_details)) { openItem(index, item) },
                ),
            ) + itemLines(item)
        )
    }

    /** VOD-012 (as LIVE-015): what changes, then back as the provider has it; [category] null = the whole section. */
    fun resetEdits(category: String?, title: String?) {
        scope.launch {
            val e = repo.vodEdits(source, kind, category)
            if (e.isEmpty) {
                say(str(R.string.vod_reset_none))
                return@launch
            }
            val order = listOfNotNull(str(R.string.reset_category_order).takeIf { e.categoryOrder }) + e.reordered.map { groupName(ctx, it) }
            val text = listOf(str(R.string.reset_names) to e.renamed, str(R.string.reset_hidden) to e.hidden, str(R.string.reset_order) to order)
                .filter { it.second.isNotEmpty() }
                .joinToString("\n") { (what, list) -> "$what: " + list.take(5).joinToString(", ") + if (list.size > 5) " " + ctx.resources.getQuantityString(R.plurals.reset_more, list.size - 5, list.size - 5) else "" }
            dialog = {
                ConfirmDialog(
                    if (title != null) str(R.string.vod_reset_category_title, title) else str(R.string.vod_reset_all_title),
                    text + "\n\n" + str(R.string.vod_reset_keeps), str(R.string.reset),
                    { scope.launch { repo.resetVodEdits(source, kind, category) } },
                ) { closeDialog() }
            }
        }
    }

    fun groupMenu(gc: GroupCount) {
        val g = gc.group.asVodGroup()
        val lines = mutableListOf<SheetItem>()
        when (g) {
            is VodGroup.Category -> {
                lines += SheetItem(R.drawable.ic_edit, str(R.string.rename)) {
                    dialog = {
                        TextDialog(str(R.string.category_name), gc.title, str(R.string.vod_name_reset_hint), { scope.launch { repo.renameVodCategory(source, kind, g.name, it) } }) { closeDialog() }
                    }
                }
                lines += if (gc.hidden) SheetItem(R.drawable.ic_visibility, str(R.string.category_show)) { scope.launch { repo.setVodCategoriesHidden(source, kind, listOf(g.name), false) } }
                else SheetItem(R.drawable.ic_visibility_off, str(R.string.category_hide), danger = true) { scope.launch { repo.setVodCategoriesHidden(source, kind, listOf(g.name), true) } }
                lines += SheetItem(R.drawable.ic_replay, str(R.string.vod_reset_category)) { resetEdits(g.name, gc.title) }
            }
            is VodGroup.Collection -> {
                lines += SheetItem(R.drawable.ic_edit, str(R.string.collection_rename)) {
                    dialog = {
                        TextDialog(str(R.string.collection_name), gc.title, null, { if (it.isNotBlank()) scope.launch { repo.renameVodCollection(g.id, it) } }) { closeDialog() }
                    }
                }
                lines += SheetItem(R.drawable.ic_delete, str(R.string.collection_delete), danger = true) {
                    dialog = {
                        ConfirmDialog(str(R.string.collection_delete_title, gc.title), str(R.string.vod_collection_delete_text), str(R.string.delete), { scope.launch { repo.deleteVodCollection(g.id) } }) { closeDialog() }
                    }
                }
            }
            VodGroup.All -> lines += SheetItem(R.drawable.ic_replay, str(R.string.vod_reset_all)) { resetEdits(null, null) }
            else -> {}
        }
        lines += SheetItem(R.drawable.ic_new_label, str(R.string.collection_new)) { newCollection(null) }
        lines += if (showHidden) SheetItem(R.drawable.ic_visibility_off, str(R.string.hide_hidden)) { showHidden = false }
        else SheetItem(R.drawable.ic_visibility, str(R.string.show_hidden)) { showHidden = true }
        sheet = gc.title to listOf(null to lines)
    }

    fun sortMenu() {
        sheet = str(R.string.vod_order) to listOf(
            null to VodSort.entries.map { s ->
                SheetItem(null, str(s.title()), selected = s == sort) { onSettings(if (movies) settings.copy(movieSort = s) else settings.copy(seriesSort = s)) }
            },
        )
    }

    val toggleView = { onSettings(if (movies) settings.copy(movieList = !asList) else settings.copy(seriesList = !asList)) }

    // --- Keys ---
    fun gridKeys(a: Action, long: Boolean): Boolean {
        val i = focused[0]
        return when (a) {
            Action.Ok, Action.Menu -> {
                val item = (if (i in 0 until items.itemCount) items.peek(i) else null) ?: return true // a list reloading can be empty for a moment
                if (long || a == Action.Menu) itemMenu(i, item) else openItem(i, item)
                true
            }
            Action.ChannelUp, Action.ChannelDown -> {
                val step = Defaults.LIST_PAGE * if (listNow) 1 else columns
                val to = when {
                    long -> if (a == Action.ChannelDown) items.itemCount - 1 else 0
                    a == Action.ChannelDown -> i + step
                    else -> i - step
                }
                scope.launch { focusCell(to) }
                true
            }
            // ◀ from the first column goes to the selected category, not to whichever is level with the poster.
            Action.Left -> if (!wide || (!listNow && i % columns != 0)) false else { focusSelectedCategory(); true }
            else -> false
        }
    }

    fun categoryKeys(a: Action, long: Boolean): Boolean {
        val i = focusedCat[0]
        val g = groups.getOrNull(i)
        return when (a) {
            Action.Ok, Action.Menu -> {
                if (g != null) { if (long || a == Action.Menu) groupMenu(g) else group = g.group.asVodGroup() }
                true
            }
            Action.ChannelUp, Action.ChannelDown -> {
                val to = if (long) (if (a == Action.ChannelDown) groups.size - 1 else 0) else i + if (a == Action.ChannelDown) Defaults.LIST_PAGE else -Defaults.LIST_PAGE
                focusCategory(to)
                true
            }
            Action.Right -> { scope.launch { focusCell(focused[0]) }; true }
            else -> false
        }
    }

    // --- Back (pages and the player have their own, registered later and so asked first) ---
    val exit = remember { ExitGuard() }
    val nothingOpen = page == null && playing == null
    BackHandler(nothingOpen) {
        val back = onBack
        if (back != null) back() // a phone goes back to the start section; a jump goes back where it came from
        else if (exit.press(System.currentTimeMillis())) ctx.findActivity()?.finish()
        else say(str(if (tv) R.string.exit_again_tv else R.string.exit_again_touch))
    }
    BackHandler(nothingOpen && tv && wide && area == LibArea.Categories) { runCatching { railFocus.getValue(kind.section()).requestFocus() } }
    BackHandler(nothingOpen && tv && wide && (area == LibArea.Grid || area == LibArea.Top) && query.isEmpty()) { focusSelectedCategory() }
    BackHandler(nothingOpen && jumpBack && onBack != null) { onBack?.invoke() }
    BackHandler(nothingOpen && query.isNotEmpty() && (!tv || area == LibArea.Grid || area == LibArea.Top)) {
        query = ""
        if (tv) runCatching { searchFocus.requestFocus() }
    }

    // --- Parts shared by both layouts ---
    val catalogue: @Composable (Modifier) -> Unit = { modifier ->
        if (items.itemCount == 0 && loaded) {
            if (group == VodGroup.Favorites && search.isEmpty()) EmptyState(
                R.drawable.ic_star_fill, stringResource(R.string.favorites_empty),
                stringResource(if (tv) R.string.vod_favorites_hint_tv else R.string.vod_favorites_hint_touch), modifier.fillMaxWidth(),
            ) else EmptyState(R.drawable.ic_search, stringResource(R.string.nothing_found), null, modifier.fillMaxWidth())
        } else BoxWithConstraints(modifier) {
            val keyed = Modifier.onFocusChanged { if (it.hasFocus) area = LibArea.Grid }.remoteKeys(::gridKeys)
            val gap = if (wide && !compact) 16.dp else 8.dp
            val edge = 6.dp // room for the focused poster's scale and glow
            val cols = if (!wide) 3 else ((maxWidth - edge * 2 + gap) / ((if (compact) 88.dp else 104.dp) + gap)).toInt().coerceAtLeast(1)
            SideEffect { columns = cols }
            val cell: @Composable (Int) -> Unit = { i ->
                val item = items[i]
                val mod = if (i == target) Modifier.focusRequester(gridFocus) else Modifier
                val onFocus = { focused[0] = i }
                val onClick = { if (item != null) openItem(i, item) }
                val onLong = { if (item != null) itemMenu(i, item) }
                if (asList) VodRow(item, !wide, mod, onFocus, onClick, onLong) else PosterCard(item, tv, mod, onFocus, onClick, onLong)
            }
            if (asList) LazyColumn(Modifier.fillMaxSize().then(keyed), listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items.itemCount, key = items.itemKey { it.id }) { i -> cell(i) }
            } else LazyVerticalGrid(
                GridCells.Fixed(cols),
                Modifier.fillMaxSize().then(keyed),
                gridState,
                contentPadding = PaddingValues(start = edge, end = edge, top = edge, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(items.itemCount, key = items.itemKey { it.id }) { i -> cell(i) }
            }
        }
    }
    val viewIcon = if (asList) R.drawable.ic_grid_view else R.drawable.ic_view_list
    val viewLabel = stringResource(if (asList) R.string.vod_view_posters else R.string.view_list)
    val orderLabel = stringResource(R.string.vod_order)
    val searchHint = stringResource(if (movies) R.string.search_movies else R.string.search_series)

    val wideLayout: @Composable BoxScope.() -> Unit = {
        val c = MaterialTheme.colorScheme
        Row(Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.safeDrawingPadding())) {
            if (!compact) Spacer(Modifier.width(80.dp)) // the rail is drawn over the content
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(
                    start = 16.dp, end = if (tv) 48.dp else if (compact) 16.dp else 24.dp,
                    top = if (compact) 8.dp else 24.dp, bottom = if (tv) 24.dp else if (compact) 8.dp else 16.dp,
                ),
            ) {
                // Top bar (4.6)
                Row(
                    Modifier.fillMaxWidth().height(40.dp)
                        .then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .onFocusChanged { if (it.hasFocus) area = LibArea.Top },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(sectionTitle, style = TvType.titleSmall)
                        if (groupTitle.isNotEmpty()) Text(
                            " " + stringResource(R.string.title_with_list, groupTitle), style = TvType.titleSmall.copy(fontWeight = FontWeight.Normal),
                            color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SearchField(query, { query = it }, searchHint, searchFocus, Modifier.width(240.dp), height = 40.dp)
                    ToolButton(viewIcon, viewLabel, onClick = toggleView)
                    ToolButton(R.drawable.ic_sort, orderLabel, onClick = ::sortMenu)
                    ContinueButton(continueItem, onClick = onContinue) // HOME-006
                    // No rail on a phone held sideways: the other sections are buttons here.
                    if (compact) SectionButtons(kind.section(), library, settings.homeVisible, onSection)
                    if (!online) OfflineMark()
                    if (!compact) Clock()
                }
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                Row(Modifier.weight(1f)) {
                    Column(
                        Modifier.width(if (compact) 160.dp else 176.dp).fillMaxHeight().background(c.surfaceContainerLow, RoundedCornerShape(24.dp))
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .onFocusChanged { if (it.hasFocus) area = LibArea.Categories },
                    ) {
                        Text(stringResource(R.string.categories), style = TvType.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
                        LazyColumn(Modifier.remoteKeys(::categoryKeys), catState) {
                            itemsIndexed(groups, key = { _, g -> g.group.encode() }) { i, g ->
                                CategoryItem(
                                    g, g.group == group.asChannelGroup(),
                                    if (i == catTarget) Modifier.focusRequester(catFocus) else Modifier,
                                    onFocus = { focusedCat[0] = i },
                                    onClick = { group = g.group.asVodGroup() },
                                    onLongClick = { groupMenu(g) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    catalogue(Modifier.weight(1f).fillMaxHeight())
                }
                if (tv || task != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tv) {
                        val ok = stringResource(R.string.key_ok)
                        val longOk = stringResource(R.string.key_long_ok)
                        @Composable fun h(key: String, action: Int) = key to stringResource(action)
                        Hints(
                            when (area) {
                                LibArea.Grid -> listOf(h(ok, R.string.hint_details), h("◀", R.string.hint_categories), h(longOk, R.string.hint_actions))
                                LibArea.Categories -> listOf(h(ok, R.string.hint_select), h("▶", if (movies) R.string.section_movies else R.string.section_series), h(longOk, R.string.hint_actions))
                                LibArea.Rail -> listOf(h(ok, R.string.hint_open), h("▶", R.string.hint_back), h(stringResource(R.string.key_back), R.string.hint_exit))
                                LibArea.Top -> listOf(h(ok, R.string.hint_select), h("▼", R.string.hint_to_list))
                            },
                            Modifier,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    task?.let { TaskPill(it, Modifier) }
                }
            }
        }
        if (!compact) SectionRail(
            kind.section(), library, railFocus, settings.homeVisible,
            onSection = onSection,
            onCurrent = { scope.launch { focusCell(focused[0]) } },
            // ▶ goes back exactly where the focus came from (UI-007).
            onRight = {
                when (beforeRail) {
                    LibArea.Categories -> focusCategory(focusedCat[0])
                    LibArea.Top -> runCatching { searchFocus.requestFocus() }
                    else -> scope.launch { focusCell(focused[0]) }
                }
            },
        ) { inside ->
            if (inside && area != LibArea.Rail) {
                beforeRail = area
                area = LibArea.Rail
            }
        }
    }

    val phoneLayout: @Composable BoxScope.() -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 16.dp)) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!online) OfflineMark()
                    SearchField(query, { query = it }, searchHint, searchFocus, Modifier.weight(1f), height = 48.dp)
                    ToolButton(viewIcon, viewLabel, size = 48.dp, onClick = toggleView)
                    ToolButton(R.drawable.ic_sort, orderLabel, size = 48.dp, onClick = ::sortMenu)
                }
                if (continueItem != null) ContinueButton(continueItem, Modifier.padding(top = 8.dp), height = 48.dp, onClick = onContinue)
                CategoryChips(groups, group.asChannelGroup(), null, { group = it.asVodGroup() }, ::groupMenu)
                catalogue(Modifier.weight(1f))
            }
            PhoneNav(kind.section(), library, settings.homeVisible, onSection)
        }
        task?.let { TaskPill(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp)) }
    }

    Box(Modifier.fillMaxSize()) {
        val p = page
        val now = playing
        when {
            now != null -> {
                val next = remember(now) { now.episode?.let { nextEpisode(now.episodes, it) } }
                val previous = remember(now) { now.episode?.let { previousEpisode(now.episodes, it) } }
                VodPlayer(
                    player, now,
                    title = now.item.title,
                    subtitle = now.episode?.let { "${episodeCode(ctx, it)} · ${episodeName(ctx, it)}" },
                    offline = !online, autoNext = settings.autoNext, next = next, previous = previous,
                    onSave = ::save,
                    // The next episode goes on where it was left, without asking in the middle of a series.
                    onEpisode = { e -> playing = now.copy(episode = e, start = e.progress?.takeIf { !it.watched }?.position ?: 0) },
                    onClose = ::closePlayer,
                )
            }
            p != null && p.kind == VodKind.MOVIE -> FilmPage(
                p, info, wide,
                onWatch = { start -> play(p, null, emptyList(), start) },
                onFavorite = { scope.launch { repo.setVodFavorite(p.key, !p.favorite); changed() } },
                onMenu = { sheet = p.name to itemLines(p) },
                onBack = ::closePage,
            )
            p != null -> SeriesPage(
                repo, source, p, wide, lastEpisode,
                itemMenu = { itemLines(p) },
                onPlay = { e, list, start -> play(p, e, list, start) },
                onFavorite = { scope.launch { repo.setVodFavorite(p.key, !p.favorite); changed() } },
                onBack = ::closePage,
            )
            pageKey != null -> {} // the page's item is loading
            wide -> wideLayout()
            else -> phoneLayout()
        }
        sheet?.let { (title, groups) ->
            ActionSheet(title, null, groups, wide) {
                sheet = null
                restoreFocus()
            }
        }
        toast?.let { (text, _) ->
            Toast(text, Modifier.align(Alignment.BottomCenter).then(if (wide) Modifier.padding(bottom = 84.dp) else Modifier.navigationBarsPadding().padding(bottom = 136.dp)))
        }
    }
    dialog?.invoke()
}
