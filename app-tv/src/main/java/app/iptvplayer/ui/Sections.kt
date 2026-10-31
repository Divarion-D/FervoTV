package app.iptvplayer.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.encode
import app.iptvplayer.core.home.ChannelContinue
import app.iptvplayer.core.home.ContinueItem
import app.iptvplayer.core.home.VodContinue
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.isTv

// Sections and the ways between them (UI-007, UI-012, HOME-006, HOME-012): the rail, the phone's bottom bar,
// «Продолжить» in the top bar and the jumps from the home screen and the search into a section.

/** Sections of the app, in the rail's order top to bottom; «Ещё» sits at its foot. */
enum class Section { Search, Home, Live, Guide, Movies, Series, More }

/** The library section of a [VodKind]. */
fun VodKind.section(): Section = if (this == VodKind.MOVIE) Section.Movies else Section.Series

fun Section.kind(): VodKind? = when (this) {
    Section.Movies -> VodKind.MOVIE
    Section.Series -> VodKind.SERIES
    else -> null
}

@DrawableRes
fun Section.icon(current: Boolean = false): Int = when (this) {
    Section.Search -> R.drawable.ic_search
    Section.Home -> R.drawable.ic_home
    Section.Live -> if (current) R.drawable.ic_live_tv_fill else R.drawable.ic_live_tv
    Section.Guide -> R.drawable.ic_guide
    Section.Movies -> R.drawable.ic_movie
    Section.Series -> R.drawable.ic_series
    Section.More -> R.drawable.ic_more
}

@StringRes
fun Section.label(): Int = when (this) {
    Section.Search -> R.string.section_search
    Section.Home -> R.string.section_home
    Section.Live -> R.string.section_live
    Section.Guide -> R.string.section_guide
    Section.Movies -> R.string.section_movies
    Section.Series -> R.string.section_series
    Section.More -> R.string.section_more
}

/** UI-007: the rail's sections; «Фильмы» / «Сериалы» only when the source has them ([library], VOD-001). */
fun railSections(library: Set<VodKind>, homeVisible: Boolean = true): List<Section> =
    Section.entries.filter { s -> (s != Section.Home || homeVisible) && (s.kind()?.let { it in library } ?: true) }

/**
 * The phone's bottom bar (33-design 4.5): Главная, Эфир, Телегид, the library sections, «Ещё» — at most five, so with
 * both films and series «Телегид» leaves it (a button in «Эфир» opens it). Search is a field on the home screen.
 */
fun phoneSections(library: Set<VodKind>, homeVisible: Boolean = true): List<Section> {
    val all = railSections(library, homeVisible) - Section.Search
    return if (all.size > 5) all - Section.Guide else all
}

/** One focus requester per rail entry, for Back and ▶ to land on (UI-005). */
@Composable
fun rememberRailFocus(): Map<Section, FocusRequester> = remember { Section.entries.associateWith { FocusRequester() } }

/**
 * The rail of a wide screen (UI-007): the sections with [current] marked, «Ещё» at the foot. Choosing the current one
 * calls [onCurrent] (back into the content), another one [onSection]; ▶ goes back where the focus came from ([onRight]).
 */
@Composable
fun SectionRail(
    current: Section, library: Set<VodKind>, focus: Map<Section, FocusRequester>, homeVisible: Boolean = true,
    onSection: (Section) -> Unit, onCurrent: () -> Unit, onRight: () -> Unit, suppressExpansion: Boolean = false,
    onFocusIn: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val entries = railSections(library, homeVisible).map { s ->
        s to RailEntry(s.icon(s == current), stringResource(s.label()), s == current, focus.getValue(s)) { if (s == current) onCurrent() else onSection(s) }
    }
    Rail(
        entries.filter { it.first != Section.More }.map { it.second },
        entries.filter { it.first == Section.More }.map { it.second },
        // Off TV the system bars overlap the screen edges: the rail keeps clear of them.
        (if (tv) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                onRight()
                true
            },
        onFocusIn,
        suppressExpansion,
    )
}

/** The phone's bottom bar (UI-007, 33-design 4.5). */
@Composable
fun PhoneNav(current: Section, library: Set<VodKind>, homeVisible: Boolean = true, onSelect: (Section) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        for (s in phoneSections(library, homeVisible)) {
            NavigationBarItem(
                selected = s == current,
                onClick = { if (s != current) onSelect(s) },
                icon = { Icon(painterResource(s.icon(s == current)), contentDescription = null) },
                label = { Text(stringResource(s.label()), maxLines = 1) },
            )
        }
    }
}

/** A phone held sideways has no rail: the other sections are buttons in the top bar (the guide has its own). */
@Composable
fun SectionButtons(current: Section, library: Set<VodKind>, homeVisible: Boolean = true, onSection: (Section) -> Unit) {
    railSections(library, homeVisible).filter { it != current && it != Section.Guide }.forEach { s ->
        ToolButton(s.icon(), stringResource(s.label())) { onSection(s) }
    }
}

// --- Jumps into a section (HOME-002, HOME-006, HOME-012) ---

/** What the section does with the item it is opened on: focus it in its list, open its page, or play it. */
enum class JumpMode { FOCUS, OPEN, PLAY }

/**
 * A section opened on one item: [key] of the channel, film or series; [group] = its provider category to open (null =
 * the list as it is); [episode] = the episode of a series to play. [id] makes every request new.
 */
data class Jump(val key: String, val group: String?, val mode: JumpMode, val episode: String? = null, val id: Long = System.nanoTime())

/** HOME-002, HOME-006: OK on something to go on with plays it in its section, from where it stopped. */
fun ContinueItem.playJump(): Pair<Section, Jump> = when (this) {
    is ChannelContinue -> Section.Live to Jump(channel.channel.key, ChannelGroup.Category(channel.channel.group).encode(), JumpMode.PLAY)
    is VodContinue -> kind.section() to Jump(itemKey, null, JumpMode.PLAY, episode = next?.key ?: watch.vkey.takeIf { watch.kind == VodKind.SERIES })
}

/** "Лабиринт", "Шоу · S1 · E3", "Первый канал": what an item to go on with is. */
@Composable
fun continueWhat(item: ContinueItem): String {
    val ctx = LocalContext.current
    return when (item) {
        is ChannelContinue -> item.channel.channel.name
        is VodContinue -> {
            val w = item.watch
            val n = item.next
            when {
                n != null -> item.watch.title + " · " + episodeCode(ctx, n)
                w.kind == VodKind.SERIES && w.season != null && w.episode != null -> w.title + " · " + episodeCode(ctx, w.season!!, w.episode!!)
                else -> w.title
            }
        }
    }
}

/**
 * HOME-006 «Продолжить» in the top bar: says what it starts; tonal pill, orange fill in focus. Nothing to go on
 * with = no button.
 */
@Composable
fun ContinueButton(item: ContinueItem?, modifier: Modifier = Modifier, height: Dp = 40.dp, onClick: (ContinueItem) -> Unit) {
    if (item == null) return
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = if (focused) Ext.onFocus else c.onSurface
    Row(
        modifier
            .height(height)
            .widthIn(max = 260.dp)
            .background(if (focused) Ext.focus else c.surfaceContainerHigh, CircleShape)
            .clip(CircleShape)
            .clickable(interaction, ripple()) { onClick(item) }
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_play_arrow), null, tint = if (focused) Ext.onFocus else c.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.continue_what, continueWhat(item)), style = TvType.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The frame of the screens added in P2 (home, search, «Ещё» and its pages): the rail and a top bar with the title,
 * the screen's [tools], «Продолжить», the network mark and the clock on a wide screen (33-design 4.5, 4.6); the
 * title, tools and the bottom bar on a phone. [content] gets the space left.
 */
@Composable
fun SectionFrame(
    section: Section,
    library: Set<VodKind>,
    online: Boolean,
    task: BgTask?,
    title: String,
    railFocus: Map<Section, FocusRequester>,
    homeVisible: Boolean = true,
    onSection: (Section) -> Unit,
    onCurrent: () -> Unit,
    continueItem: ContinueItem? = null,
    onContinue: (ContinueItem) -> Unit = {},
    tools: @Composable RowScope.(Dp) -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val wide = isWideLayout()
    val compact = !tv && LocalConfiguration.current.screenHeightDp < 480
    Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.safeDrawingPadding())) {
                if (!compact) Spacer(Modifier.width(80.dp)) // the rail is drawn over the content
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(
                        start = 16.dp, end = if (tv) 48.dp else if (compact) 16.dp else 24.dp,
                        top = if (compact) 8.dp else 24.dp, bottom = if (tv) 24.dp else if (compact) 8.dp else 16.dp,
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(title, style = TvType.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        tools(40.dp)
                        ContinueButton(continueItem, onClick = onContinue)
                        if (compact) SectionButtons(section, library, homeVisible, onSection)
                        if (!online) OfflineMark()
                        if (!compact) Clock()
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                    content(Modifier.weight(1f).fillMaxWidth())
                    if (task != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(32.dp), horizontalArrangement = Arrangement.End) { TaskPill(task, Modifier) }
                }
            }
            if (!compact) SectionRail(section, library, railFocus, homeVisible, onSection, onCurrent, onRight = onCurrent)
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (!online) OfflineMark()
                        tools(48.dp)
                    }
                    if (continueItem != null) {
                        ContinueButton(continueItem, Modifier.padding(bottom = 8.dp), height = 48.dp, onClick = onContinue)
                    }
                    content(Modifier.weight(1f).fillMaxWidth())
                }
                val phone = phoneSections(library, homeVisible)
                PhoneNav(if (section in phone) section else if (section == Section.Search) if (homeVisible) Section.Home else Section.Live else Section.More, library, homeVisible, onSection)
            }
            task?.let { TaskPill(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp)) }
        }
    }
}
