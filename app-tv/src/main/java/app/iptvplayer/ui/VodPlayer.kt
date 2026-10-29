package app.iptvplayer.ui

import android.app.Activity
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.model.Episode
import app.iptvplayer.core.model.VodItem
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.isTv
import app.iptvplayer.toAction
import kotlinx.coroutines.delay

/** A film or an episode to play from [start] ms; [episodes] = the series' list (next / previous, VOD-009). */
data class VodPlay(val item: VodItem, val episode: Episode? = null, val start: Long = 0, val episodes: List<Episode> = emptyList()) {
    val url: String get() = episode?.url ?: item.url.orEmpty()
}

/**
 * Full-screen film or episode (VOD-009, VOD-010; the P3 panel will grow from this): the same [PlayerSession] as live,
 * with a seek bar. Remote: ◀▶ seek by [Defaults.SEEK_STEP_MS] (held — keeps going), OK shows the panel and then
 * pauses, ▲▼ / Menu show it, CH± previous / next episode, Back hides the panel, then closes. Touch: a tap shows the
 * panel with its buttons and the seek bar.
 *
 * The position goes to [onSave] every [Defaults.VOD_SAVE_MS] while playing, when the app goes to the background, on
 * closing and when switching episodes. [next] starts by itself at the end when [autoNext], after a card counting down
 * the last [Defaults.AUTO_NEXT_MS] that can be cancelled; otherwise the end closes the player.
 */
@Composable
fun VodPlayer(
    player: PlayerSession,
    play: VodPlay,
    title: String,
    subtitle: String?,
    offline: Boolean,
    autoNext: Boolean,
    next: Episode?,
    previous: Episode?,
    onSave: (VodPlay, Long, Long) -> Unit,
    onEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val tv = remember { ctx.isTv() }
    val state by player.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf(true) }
    var panelTick by remember { mutableIntStateOf(0) } // any key restarts the hide timer
    var cancelled by remember(play) { mutableStateOf(false) } // the "next episode" card was dismissed
    val error = state.error?.let { if (offline) stringResource(R.string.no_connection) else playbackErrorText(ctx, it) }
    val latestSave by rememberUpdatedState(onSave)
    val current by rememberUpdatedState(play)

    fun save() {
        val d = player.duration
        if (d > 0) latestSave(current, player.position, d)
    }

    LaunchedEffect(play) {
        position = play.start
        duration = 0
        player.setMuted(false)
        player.open(PlayItem(play.url, live = false, start = play.start))
    }
    // The bar's clock; the position is read from the engine, nothing is pushed per frame.
    LaunchedEffect(play) {
        while (true) {
            position = player.position
            duration = player.duration
            delay(500)
        }
    }
    // VOD-010: saved while playing, so a crash or a power cut loses at most a few seconds.
    LaunchedEffect(play) {
        while (true) {
            delay(Defaults.VOD_SAVE_MS)
            if (!player.state.value.paused && player.state.value.error == null) save()
        }
    }
    LifecycleStartEffect(player) {
        player.foreground()
        onStopOrDispose {
            save()
            player.background()
        }
    }
    LaunchedEffect(panel, panelTick, state.paused) {
        if (panel && !state.paused) {
            delay(Defaults.PLAYER_INFO_HIDE_MS)
            panel = false
        }
    }

    val left = (duration - position).coerceAtLeast(0)
    val countdown = autoNext && next != null && !cancelled && duration > 0 && position > 0 && left <= Defaults.AUTO_NEXT_MS && error == null

    fun goTo(e: Episode) {
        save()
        onEpisode(e)
    }

    fun close() {
        save()
        onClose()
    }

    // VOD-009: at the end — the next episode, or back to the page.
    LaunchedEffect(state.ended, play) {
        // The engine's own state: the collected one may still be the previous episode's end.
        if (!player.state.value.ended || player.current?.url != play.url) return@LaunchedEffect
        val d = player.duration
        if (d > 0) latestSave(current, d, d) // finished: watched (VOD-008)
        if (autoNext && next != null && !cancelled) onEpisode(next) else onClose()
    }

    fun seek(by: Long) {
        val d = player.duration
        val to = (player.position + by).coerceIn(0, if (d > 0) d else Long.MAX_VALUE)
        player.seekTo(to)
        position = to
        panel = true
        panelTick++
    }

    val handle: (Action) -> Boolean = { a ->
        panelTick++
        when (a) {
            Action.Left -> seek(-Defaults.SEEK_STEP_MS)
            Action.Right -> seek(Defaults.SEEK_STEP_MS)
            Action.Up, Action.Down, Action.Menu -> panel = true
            Action.Ok -> when {
                state.error != null -> player.retry()
                countdown -> next?.let(::goTo)
                !panel -> panel = true
                else -> player.togglePause()
            }
            Action.PlayPause -> { player.togglePause(); panel = true }
            Action.ChannelUp -> next?.let(::goTo)
            Action.ChannelDown -> previous?.let(::goTo)
            Action.Recall -> {}
        }
        true
    }

    BackHandler { close() }
    BackHandler(panel && !countdown) { panel = false }
    BackHandler(countdown) { cancelled = true }

    val focus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e -> e.toAction()?.let(handle) ?: false }
            .focusable()
    ) {
        AndroidView(
            factory = {
                SurfaceView(it).apply {
                    keepScreenOn = true
                    isFocusable = false
                    player.setVideoView(this)
                }
            },
            modifier = (if (state.aspect > 0f) Modifier.aspectRatio(state.aspect) else Modifier.fillMaxSize()).align(Alignment.Center),
            onRelease = { player.releaseVideoView(it) },
        )
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { panel = !panel; panelTick++ } })
        if (state.buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (error != null) Column(
            Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_error), null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(32.dp))
            Text(error, style = TvType.body, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(if (tv) R.string.vod_retry_tv else R.string.vod_retry_touch), style = TvType.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (panel || state.paused || error != null) VodPanel(
            title, subtitle, position, duration, state.paused, tv, previous != null, next != null,
            Modifier.align(Alignment.BottomStart),
            onSeekTo = { player.seekTo(it); position = it; panelTick++ },
            onSeek = ::seek,
            onPause = { player.togglePause(); panelTick++ },
            onPrevious = { previous?.let(::goTo) },
            onNext = { next?.let(::goTo) },
        )
        if (!tv && (panel || state.paused)) Icon(
            painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(48.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clickable(onClick = ::close).padding(12.dp),
        )
        if (countdown) NextCard(
            ctx.getString(R.string.next_episode_in, ((left + 999) / 1000).toInt()),
            "${episodeCode(ctx, next)} · ${episodeName(ctx, next)}", tv,
            Modifier.align(Alignment.BottomEnd).padding(32.dp),
            onNow = { goTo(next) }, onCancel = { cancelled = true },
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Full screen: system bars hidden while the player is open.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Title, the seek bar with times and (touch) the buttons, on a gradient at the bottom (4.12). */
@Composable
private fun VodPanel(
    title: String, subtitle: String?, position: Long, duration: Long, paused: Boolean, tv: Boolean,
    hasPrevious: Boolean, hasNext: Boolean, modifier: Modifier,
    onSeekTo: (Long) -> Unit, onSeek: (Long) -> Unit, onPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = if (tv) 48.dp else 20.dp, end = if (tv) 48.dp else 20.dp, top = 64.dp, bottom = 24.dp),
    ) {
        if (subtitle != null) Text(subtitle, style = TvType.label, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, style = TvType.headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        val share = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        if (tv) {
            Spacer(Modifier.fillMaxWidth().height(6.dp).progressLine(share, Ext.nowTrack, Ext.now, 6.dp))
        } else {
            // Dragging moves the thumb; the stream seeks once, where the finger lets go.
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: share,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onSeekTo((it * duration).toLong()) }; dragging = null },
                enabled = duration > 0,
                colors = SliderDefaults.colors(thumbColor = Ext.now, activeTrackColor = Ext.now, inactiveTrackColor = Ext.nowTrack),
                modifier = Modifier.focusProperties { canFocus = false },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clockText(position), style = TvType.label, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (tv && paused) Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.pause), tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) clockText(duration) else "", style = TvType.label, color = Color.White.copy(alpha = 0.7f))
        }
        // Touch: the buttons in a row of their own, so a phone held upright fits them all.
        if (!tv) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (hasPrevious) PanelButton(R.drawable.ic_skip_previous, stringResource(R.string.episode_previous), onPrevious)
            PanelButton(R.drawable.ic_replay_10, stringResource(R.string.seek_back)) { onSeek(-Defaults.SEEK_STEP_MS) }
            PanelButton(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause, stringResource(if (paused) R.string.play else R.string.pause), onPause)
            PanelButton(R.drawable.ic_forward_10, stringResource(R.string.seek_forward)) { onSeek(Defaults.SEEK_STEP_MS) }
            if (hasNext) PanelButton(R.drawable.ic_skip_next, stringResource(R.string.episode_next), onNext)
        }
    }
}

@Composable
private fun PanelButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size(28.dp)) }
}

/** VOD-009: "Next episode in 12 s" with «Смотреть сейчас» and «Отмена» (remote: OK / Back). */
@Composable
private fun NextCard(head: String, what: String, tv: Boolean, modifier: Modifier, onNow: () -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.width(340.dp).background(c.surfaceContainerHighest, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(head, style = TvType.label, color = c.primary)
        Text(what, style = TvType.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(R.drawable.ic_skip_next, stringResource(R.string.watch_now), if (tv) stringResource(R.string.key_ok) else null, true, onNow)
            Pill(R.drawable.ic_close, stringResource(R.string.cancel), if (tv) stringResource(R.string.key_back) else null, false, onCancel)
        }
    }
}

@Composable
private fun Pill(@DrawableRes icon: Int, label: String, key: String?, main: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.height(40.dp).background(if (main) Ext.focus else c.surfaceContainerHigh, CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .focusProperties { canFocus = false }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (main) Ext.onFocus else c.onSurface
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = TvType.label, color = fg)
        if (key != null) Text("  $key", style = TvType.badge, color = fg.copy(alpha = 0.7f))
    }
}
