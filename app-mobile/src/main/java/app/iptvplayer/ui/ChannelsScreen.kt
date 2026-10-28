package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.db.matchesWords
import app.iptvplayer.core.player.PlayerSession
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import app.iptvplayer.isTv
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay

private const val ALL = "Все"
private val BLANK = Channel("", "", "")

/** Saveable state (category, queries, position) survives Настройки and «Что нового» opened over it (see App). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelsScreen(repo: Repository, source: Source, player: PlayerSession, onOpenSettings: () -> Unit) {
    val tv = LocalContext.current.isTv()
    // Straight from the database: a background refresh updates counts and the grid in place.
    val categories by remember(source) { repo.categories(source) }.collectAsState(initial = emptyList())
    val counts = remember(categories) { categories.associate { it.name to it.count } + (ALL to categories.sumOf { it.count }) }
    var group by rememberSaveable { mutableStateOf(ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf(query.trim()) } // query after the input delay
    // Category search: only for long category lists (LIVE-002).
    var groupQuery by rememberSaveable { mutableStateOf("") }
    val searchGroups = categories.size >= Defaults.CATEGORY_SEARCH_MIN
    val groups = remember(categories, groupQuery, searchGroups) {
        listOf(ALL) + categories.map { it.name }.filter { !searchGroups || matchesWords(it, groupQuery) }
    }
    var playing by remember { mutableStateOf<ChannelFilter?>(null) }
    var current by rememberSaveable { mutableIntStateOf(0) } // index in the filtered list to return focus to
    var refocus by remember { mutableStateOf(true) } // on start, on return from the player and from Настройки
    var fromSettings by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val currentFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val groupSearchFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    // A background playlist refresh can drop the selected category.
    if (group != ALL && categories.isNotEmpty() && group !in counts) group = ALL

    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(Defaults.SEARCH_DEBOUNCE_MS)
        search = query.trim()
    }
    // By source key and category name: the same filter keeps working when a refresh replaces the rows.
    val filter = ChannelFilter(source.cacheKey(), group.takeIf { it != ALL }, search)
    val channels = remember(filter) { repo.channels(filter) }.collectAsLazyPagingItems()

    // Back to the top only when the user changed the filter, not when a refresh replaced the list.
    var lastFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            current = 0
            gridState.scrollToItem(0)
        }
    }

    // On start (once the first page is there) and on return from the player: show (and on TV focus) the last played channel.
    val hasChannels = channels.itemCount > 0
    LaunchedEffect(refocus, hasChannels) {
        if (!refocus) return@LaunchedEffect
        if (fromSettings) { // back on the button Настройки were opened with
            fromSettings = false
            refocus = false
            if (tv) {
                withFrameNanos {}
                runCatching { settingsFocus.requestFocus() }
            }
            return@LaunchedEffect
        }
        if (!hasChannels) return@LaunchedEffect
        if (current < channels.itemCount) gridState.scrollToItem(current)
        if (tv) {
            withFrameNanos {}
            runCatching { currentFocus.requestFocus() }
        }
        refocus = false
    }

    // Back in the list first clears a search (channels, then categories) and puts the focus on its field.
    fun focusOnTv(fr: FocusRequester) {
        if (tv) runCatching { fr.requestFocus() }
    }
    BackHandler(playing == null && (query.isNotEmpty() || groupQuery.isNotEmpty())) {
        if (query.isNotEmpty()) {
            query = ""
            focusOnTv(searchFocus)
        } else {
            groupQuery = ""
            focusOnTv(groupSearchFocus)
        }
    }
    BackHandler(playing != null) { playing = null; refocus = true }

    // The player is drawn on top instead of replacing the list, so filters, scroll and focus survive.
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            val wide = maxWidth >= 600.dp

            val header: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(query, { query = it }, "Поиск каналов", searchFocus, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    FilledTonalIconButton(
                        onClick = { fromSettings = true; onOpenSettings() },
                        modifier = Modifier.focusRing(CircleShape).focusRequester(settingsFocus).size(48.dp),
                    ) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Настройки") }
                }
            }

            val grid: @Composable (Modifier) -> Unit = { modifier ->
                // Empty only once the database answered, not while the first page loads.
                if (channels.itemCount == 0 && channels.loadState.append.endOfPaginationReached) {
                    EmptyState(modifier.fillMaxWidth())
                } else LazyVerticalGrid(
                    GridCells.Adaptive(if (wide) 150.dp else 104.dp),
                    // Coming back from the categories (▶) lands on the channel the focus left from (UI-005).
                    modifier.focusRestorer().focusGroup(),
                    gridState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Not-yet-loaded positions are placeholders (null): drawn as blank cards so the grid keeps its size.
                    items(channels.itemCount) { i ->
                        val ch = channels[i]
                        ChannelCard(
                            i + 1, ch ?: BLANK,
                            if (i == current) Modifier.focusRequester(currentFocus) else Modifier,
                        ) { if (ch != null) { current = i; playing = filter } }
                    }
                }
            }

            val groupSearch: (@Composable (Modifier) -> Unit)? = if (searchGroups) {
                { m -> SearchField(groupQuery, { groupQuery = it }, "Поиск категорий", groupSearchFocus, m) }
            } else null

            if (wide) Row {
                CategoryPanel(groups, counts, group, { group = it }, groupSearch, Modifier.width(280.dp).fillMaxHeight())
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    header()
                    Spacer(Modifier.height(8.dp))
                    grid(Modifier.weight(1f))
                }
            } else Column {
                header()
                CategoryChips(groups, group, groupSearch) { group = it }
                grid(Modifier.weight(1f))
            }
        }

        playing?.let { f ->
            PlayerScreen(player, channels.itemCount, current, { i -> repo.channelAt(f, i) }, onIndexChange = { i -> current = i })
        }
    }
}

/**
 * M3 search bar look. At rest it's a clickable pill; the text field (and keyboard) only opens on OK/tap.
 * Otherwise on TV just moving focus over it pops the keyboard and traps the remote.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, hint: String, pillFocus: FocusRequester, modifier: Modifier) {
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
                { IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Очистить") } }
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
        Surface(
            onClick = { editing = true },
            modifier = modifier.height(56.dp).focusRing(CircleShape).focusRequester(pillFocus),
            shape = CircleShape,
            color = c.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = c.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(
                    query.ifEmpty { hint },
                    Modifier.weight(1f),
                    color = if (query.isEmpty()) c.onSurfaceVariant else c.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Очистить", tint = c.onSurfaceVariant)
                    }
                }
            }
        }
        // Back from the keyboard: keep the remote's focus on the search pill instead of losing it.
        LaunchedEffect(Unit) {
            if (wasEditing) runCatching { pillFocus.requestFocus() }
        }
    }
}

/** Wide screens / TV: categories as a navigation-drawer-style panel with channel counts. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryPanel(
    groups: List<String>,
    counts: Map<String, Int>,
    selected: String,
    onSelect: (String) -> Unit,
    search: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = c.surfaceContainerLow) {
        LazyColumn(
            Modifier.focusRestorer().focusGroup(), // ◀ from the grid comes back to the category it left
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_live_tv), contentDescription = null, tint = c.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Категории", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (search != null) item { search(Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            items(groups) { g ->
                var focused by remember { mutableStateOf(false) }
                NavigationDrawerItem(
                    // Full name without cutting: a long one scrolls while focused or selected (LIVE-001).
                    label = {
                        Text(
                            g, maxLines = 1,
                            modifier = if (focused || g == selected) Modifier.basicMarquee() else Modifier,
                            overflow = if (focused || g == selected) TextOverflow.Clip else TextOverflow.Ellipsis,
                        )
                    },
                    selected = g == selected,
                    onClick = { onSelect(g) },
                    icon = { Icon(painterResource(if (g == ALL) R.drawable.ic_tv else R.drawable.ic_category), contentDescription = null) },
                    badge = { Text("${counts[g] ?: 0}", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.onFocusChanged { focused = it.isFocused }.focusRing(CircleShape),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = c.primary,
                        selectedTextColor = c.onPrimary,
                        selectedIconColor = c.onPrimary,
                        selectedBadgeColor = c.onPrimary,
                    ),
                )
            }
        }
    }
}

/** Narrow screens: categories as M3 filter chips. */
@Composable
private fun CategoryChips(
    groups: List<String>,
    selected: String,
    search: (@Composable (Modifier) -> Unit)?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (search != null) item { search(Modifier.width(220.dp)) }
        items(groups) { g ->
            val isSelected = g == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(g) },
                label = { Text(g) },
                leadingIcon = if (isSelected) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
                modifier = Modifier.focusRing(FilterChipDefaults.shape),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painterResource(R.drawable.ic_search), contentDescription = null,
            modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Channel card: logo on top, name and "number · category" below.
 * Focus follows Google's TV guidance ("big, bold focus"): orange fill, slight scale-up and an orange glow.
 */
@Composable
private fun ChannelCard(number: Int, ch: Channel, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "cardScale")
    Card(
        onClick = onClick,
        modifier = modifier
            .zIndex(if (focused) 1f else 0f) // scaled card draws over its neighbours
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 24.dp else 0.dp, shape, ambientColor = c.primary, spotColor = c.primary)
            .focusRing(shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (focused) c.primary else c.surfaceContainer),
        interactionSource = interaction,
    ) {
        ChannelLogo(ch, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                ch.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (focused) c.onPrimary else c.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$number · ${ch.group}",
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) c.onPrimary.copy(alpha = 0.75f) else c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

