package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.iptvplayer.R
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.ChannelFilter
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.Visibility
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.source.cacheKey
import kotlinx.coroutines.launch

/** LIVE-012: one entry point for category/channel names and visibility, ready for the PROF-006 gate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveManagementScreen(repo: Repository, source: Source, initialCategory: String? = null, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val categoriesRaw by remember(source) { repo.groups(source, withHidden = true) }.collectAsState(initial = null)
    val categories = categoriesRaw.orEmpty().filter { it.group is ChannelGroup.Category }
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var visibilityName by rememberSaveable { mutableStateOf(Visibility.ALL.name) }
    val visibility = Visibility.valueOf(visibilityName)
    var rangeStart by rememberSaveable(category, visibility) { mutableIntStateOf(-1) }
    var rename by remember { mutableStateOf<RenameTarget?>(null) }

    fun back() {
        if (category == null) onClose() else category = null
    }
    BackHandler(onBack = ::back)

    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        val selectedCategory = categories.firstOrNull { (it.group as ChannelGroup.Category).name == category }
        TopAppBar(
            title = { Text(selectedCategory?.title ?: stringResource(R.string.manage_live)) },
            navigationIcon = { BackButton(::back) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().weight(1f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (choice in listOf(Visibility.ALL, Visibility.VISIBLE, Visibility.HIDDEN)) FilterChip(
                    stringResource(choice.label()), choice == visibility,
                ) { visibilityName = choice.name }
            }
            Text(
                stringResource(R.string.manage_range_hint),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (category == null) {
                val shown = categories.filter { visibility.accepts(it.hidden) }
                LaunchedEffect(shown) { if (rangeStart > shown.lastIndex) rangeStart = -1 }
                LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(shown, key = { _, item -> (item.group as ChannelGroup.Category).name }) { index, item ->
                        val name = (item.group as ChannelGroup.Category).name
                        ManagementRow(
                            title = item.title,
                            subtitle = stringResource(R.string.manage_channel_count, item.count),
                            hidden = item.hidden,
                            selected = index == rangeStart,
                            leading = R.drawable.ic_category,
                            onSelect = { rangeStart = index },
                            onOpen = { category = name },
                            onRename = { rename = RenameTarget.Category(name, item.title) },
                            onVisibility = { long ->
                                val range = if (long) rangeBetween(rangeStart, index) else index..index
                                val names = shown.slice(range).map { (it.group as ChannelGroup.Category).name }
                                scope.launch { repo.setCategoriesHidden(source, names, !item.hidden) }
                                rangeStart = -1
                            },
                        )
                    }
                }
            } else {
                val filter = remember(source, category, visibility) {
                    ChannelFilter(source.cacheKey(), ChannelGroup.Category(category!!), visibility = visibility)
                }
                val items = remember(filter) { repo.items(filter) }.collectAsLazyPagingItems()
                LaunchedEffect(filter) { rangeStart = -1 }
                LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items.itemCount, key = items.itemKey { "${it.channel.key}|${it.occurrence}" }) { index ->
                        val item = items[index]
                        ChannelManagementRow(
                            item = item,
                            selected = index == rangeStart,
                            onSelect = { rangeStart = index },
                            onRename = { item?.let { rename = RenameTarget.Channel(it.channel.key, it.channel.name) } },
                            onVisibility = { long ->
                                val row = item ?: return@ChannelManagementRow
                                scope.launch {
                                    val range = if (long) rangeBetween(rangeStart, index) else index..index
                                    repo.setHidden(repo.channelKeys(filter, range), !row.hidden)
                                }
                                rangeStart = -1
                            },
                        )
                    }
                }
            }
        }
    }

    rename?.let { target ->
        TextDialog(
            stringResource(if (target is RenameTarget.Category) R.string.category_name else R.string.channel_name),
            target.title,
            stringResource(R.string.name_reset_hint),
            { title ->
                scope.launch {
                    when (target) {
                        is RenameTarget.Category -> repo.renameCategory(source, target.key, title)
                        is RenameTarget.Channel -> repo.rename(target.key, title)
                    }
                }
            },
            { rename = null },
        )
    }
}

private sealed class RenameTarget(val key: String, val title: String) {
    class Category(key: String, title: String) : RenameTarget(key, title)
    class Channel(key: String, title: String) : RenameTarget(key, title)
}

internal fun rangeBetween(start: Int, end: Int): IntRange = if (start < 0) end..end else minOf(start, end)..maxOf(start, end)

private fun Visibility.accepts(hidden: Boolean) = when (this) {
    Visibility.ALL -> true
    Visibility.VISIBLE -> !hidden
    Visibility.HIDDEN -> hidden
}

@androidx.annotation.StringRes
private fun Visibility.label() = when (this) {
    Visibility.ALL -> R.string.filter_all
    Visibility.VISIBLE -> R.string.filter_visible
    Visibility.HIDDEN -> R.string.filter_hidden
}

@Composable
private fun ManagementRow(
    title: String,
    subtitle: String,
    hidden: Boolean,
    selected: Boolean,
    @androidx.annotation.DrawableRes leading: Int,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onVisibility: (Boolean) -> Unit,
) = ManagementListItem(title, subtitle, hidden, selected, leading, onSelect) {
    ManageButton(R.drawable.ic_tv, stringResource(R.string.manage_channels), onOpen)
    ManageButton(R.drawable.ic_edit, stringResource(R.string.rename), onRename)
    RangeButton(hidden, onVisibility)
}

@Composable
private fun ChannelManagementRow(
    item: ChannelItem?,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onVisibility: (Boolean) -> Unit,
) {
    if (item == null) {
        Spacer(Modifier.fillMaxWidth().height(64.dp))
        return
    }
    ManagementListItem(
        item.channel.name, stringResource(R.string.channel_number, item.number), item.hidden, selected,
        R.drawable.ic_tv, onSelect,
    ) {
        ManageButton(R.drawable.ic_edit, stringResource(R.string.rename), onRename)
        RangeButton(item.hidden, onVisibility)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManagementListItem(
    title: String,
    subtitle: String,
    hidden: Boolean,
    selected: Boolean,
    @androidx.annotation.DrawableRes leading: Int,
    onSelect: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(painterResource(leading), null, Modifier.size(24.dp)) },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { trailing() } },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(onLongClick = onSelect, onClick = onSelect)
            .then(if (hidden) Modifier.padding(start = 8.dp) else Modifier),
    )
}

@Composable
private fun RangeButton(hidden: Boolean, onClick: (Boolean) -> Unit) = ManageButton(
    if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
    stringResource(if (hidden) R.string.show else R.string.hide),
    onClick = { onClick(false) },
    onLongClick = { onClick(true) },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManageButton(
    @androidx.annotation.DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Box(
        Modifier.size(48.dp).clip(CircleShape)
            .semantics { contentDescription = description }
            .combinedClickable(interaction, ripple(), onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
