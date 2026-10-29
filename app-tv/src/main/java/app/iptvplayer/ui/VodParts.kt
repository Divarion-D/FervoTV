package app.iptvplayer.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.db.VodGroup
import app.iptvplayer.core.db.VodGroupCount
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Progress
import app.iptvplayer.core.model.ResumeMode
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.model.VodSort
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation

// Parts of «Фильмы» / «Сериалы» (33-design 6.4). The same render budget as the live lists: one fill per pixel in a
// cell, rounded corners baked into the poster bitmap (no clip at rest), effects only on the focused poster.

// --- The library's lists shown with the live section's category parts ---

/** The category column's parts take live groups; the library's lists map onto them one to one. */
fun VodGroup.asChannelGroup(): ChannelGroup = when (this) {
    VodGroup.All -> ChannelGroup.All
    VodGroup.Favorites -> ChannelGroup.Favorites
    VodGroup.Recent -> ChannelGroup.Recent
    is VodGroup.Category -> ChannelGroup.Category(name)
    is VodGroup.Collection -> ChannelGroup.Collection(id)
}

fun ChannelGroup.asVodGroup(): VodGroup = when (this) {
    ChannelGroup.Favorites -> VodGroup.Favorites
    ChannelGroup.Recent -> VodGroup.Recent
    is ChannelGroup.Category -> VodGroup.Category(name)
    is ChannelGroup.Collection -> VodGroup.Collection(id)
    else -> VodGroup.All
}

/** UI-022: service lists named here, «Все» by section. */
fun List<VodGroupCount>.named(ctx: Context, kind: VodKind): List<GroupCount> = map { g ->
    val title = when (g.group) {
        VodGroup.Favorites -> ctx.getString(R.string.group_favorites)
        VodGroup.Recent -> ctx.getString(R.string.group_recent)
        VodGroup.All -> ctx.getString(if (kind == VodKind.MOVIE) R.string.group_all_movies else R.string.group_all_series)
        is VodGroup.Category -> if (g.title == NO_GROUP) ctx.getString(R.string.group_none) else g.title
        is VodGroup.Collection -> g.title
    }
    GroupCount(g.group.asChannelGroup(), title, g.count, g.hidden)
}

// --- Words for values ---

@StringRes
fun VodSort.title(): Int = when (this) {
    VodSort.PROVIDER -> R.string.vod_sort_provider
    VodSort.NAME -> R.string.vod_sort_name
    VodSort.ADDED -> R.string.vod_sort_added
    VodSort.YEAR -> R.string.vod_sort_year
    VodSort.RATING -> R.string.vod_sort_rating
}

@StringRes
fun ResumeMode.title(): Int = when (this) {
    ResumeMode.CONTINUE -> R.string.resume_continue
    ResumeMode.ASK -> R.string.resume_ask
    ResumeMode.START -> R.string.resume_start
}

/** A position as a clock: "1:05:09" / "12:34". */
fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** VOD-007: the clean episode title, or «Эпизод 3» when cleaning left nothing. */
fun episodeName(ctx: Context, e: Episode): String = e.title ?: ctx.getString(R.string.episode_n, e.episode)

/** "S1 · E2"; specials as "Special 2". */
fun episodeCode(ctx: Context, e: Episode): String =
    if (e.season == 0) ctx.getString(R.string.special_n, e.episode) else ctx.getString(R.string.episode_code, e.season, e.episode)

fun seasonName(ctx: Context, season: Int): String = if (season == 0) ctx.getString(R.string.season_specials) else ctx.getString(R.string.season_n, season)

/** "2021 · Drama · 1 h 52 min · ★ 7.5" — what is known of it. */
@Composable
fun vodFacts(item: VodItem, duration: Long?, genre: String? = item.genre): String = listOfNotNull(
    item.year?.toString(),
    genre?.takeIf { it.isNotBlank() },
    duration?.takeIf { it > 0 }?.let { durationText(it) },
    item.rating?.let { stringResource(R.string.vod_rating, "%.1f".format(it)) },
).joinToString(" · ")

/** Started and not finished: the share watched, else null. */
fun Progress.share(): Float? = if (watched || duration <= 0 || position <= 0) null else (position.toFloat() / duration).coerceIn(0f, 1f)

// --- Poster ---

/**
 * A poster with its corners baked in (one bitmap, decoded at the cell's size: Coil sizes the request by the cell's
 * constraints). Until it loads — or when there is none — one fill with the title, so a catalogue without pictures
 * still reads.
 */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier, radius: Dp = 12.dp, contentScale: ContentScale = ContentScale.Crop) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var loaded by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    val px = with(LocalDensity.current) { radius.toPx() }
    Box(if (loaded) modifier else modifier.background(c.surfaceContainerHighest, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        if (!loaded) Text(
            title, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp),
        )
        if (url != null && !failed) AsyncImage(
            model = remember(url, px) {
                ImageRequest.Builder(ctx).data(url)
                    .transformations(RoundedCornersTransformation(px))
                    .setHeader("User-Agent", Defaults.USER_AGENT)
                    .build()
            },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onSuccess = { loaded = true },
            onError = { failed = true },
        )
    }
}

/** A finished film: a check in the poster's corner (VOD-008). */
@Composable
private fun WatchedMark(modifier: Modifier) = Icon(
    painterResource(R.drawable.ic_check_circle), stringResource(R.string.vod_watched), tint = Ext.success,
    modifier = modifier.padding(6.dp).size(22.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape),
)

/**
 * Catalogue cell (VOD-002): poster 2:3 with progress or the watched mark, the provider's caption in two lines and the
 * year. Focus (TV): orange frame, ×1.05 and glow on this cell only; nothing is clipped at rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(item: VodItem?, tv: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (focused && tv) 1.05f else 1f, tween(100), label = "posterScale")
    Column(
        modifier
            .then(if (item?.hidden == true) Modifier.alpha(0.38f) else Modifier)
            .then(if (focused && tv) Modifier.zIndex(1f).graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(if (pressed) Modifier.clip(shape) else Modifier)
            .combinedClickable(interaction, if (tv) null else ripple(), onLongClick = onLongClick, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = Ext.focus, spotColor = Ext.focus).border(3.dp, Ext.focus, shape) else Modifier)
        ) {
            if (item == null) Spacer(Modifier.matchParentSize().background(c.surfaceContainerHighest, shape))
            else {
                Poster(item.poster, item.title, Modifier.matchParentSize())
                val share = item.progress?.share()
                if (share != null) Spacer(Modifier.matchParentSize().padding(horizontal = 8.dp, vertical = 8.dp).progressLine(share, Ext.nowTrack, Ext.now, 3.dp))
                else if (item.kind == VodKind.MOVIE && item.progress?.watched == true) WatchedMark(Modifier.align(Alignment.TopEnd))
                if (item.favorite) Icon(
                    painterResource(R.drawable.ic_favorite_fill), stringResource(R.string.row_favorite), tint = c.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp),
                )
            }
        }
        Text(
            item?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, color = if (focused) c.primary else c.onSurface,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Text(item?.year?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1)
    }
}

/** List view (VOD-002): small poster, name, year · genre · duration · rating, progress under the text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VodRow(item: VodItem?, tall: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
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
            .height(if (tall) 96.dp else 84.dp)
            .then(if (item?.hidden == true) Modifier.alpha(0.38f) else Modifier)
            .then(if (focused) Modifier.background(Ext.focus, shape) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(if (pressed || focused) Modifier.clip(shape) else Modifier)
            .combinedClickable(interaction, ripple(), onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item == null) return@Row
        Poster(item.poster, item.title, Modifier.fillMaxHeight().aspectRatio(2f / 3f), radius = 6.dp)
        Spacer(Modifier.width(12.dp))
        val share = item.progress?.share()
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (share != null) Modifier.progressLine(share, if (focused) Ext.onFocusTrack else Ext.nowTrack, if (focused) Ext.onFocus else Ext.now, 2.dp) else Modifier),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(item.name, style = if (tall) MaterialTheme.typography.titleMedium else TvType.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = vodFacts(item, item.progress?.duration)
            if (facts.isNotEmpty()) Text(facts, style = if (tall) MaterialTheme.typography.bodyMedium else TvType.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.favorite) Icon(painterResource(R.drawable.ic_favorite_fill), stringResource(R.string.row_favorite), tint = if (focused) Ext.onFocus else c.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        if (item.kind == VodKind.MOVIE && item.progress?.watched == true) Icon(
            painterResource(R.drawable.ic_check_circle), stringResource(R.string.vod_watched), tint = if (focused) Ext.onFocus else Ext.success,
            modifier = Modifier.padding(start = 6.dp).size(16.dp),
        )
    }
}
