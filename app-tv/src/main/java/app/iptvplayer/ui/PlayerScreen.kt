package app.iptvplayer.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.iptvplayer.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import app.iptvplayer.core.input.NumberDial
import app.iptvplayer.core.input.keyDigit
import kotlinx.coroutines.launch
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.db.ChannelGroup
import androidx.compose.foundation.layout.BoxScope
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlayerSession
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import app.iptvplayer.toAction
import app.iptvplayer.touchActions
import kotlinx.coroutines.delay

/** Switching by more than one step (LIVE-009, LIVE-018); the live screen finds the channel's place in its list. */
sealed interface Zap {
    data class Number(val n: Int) : Zap
    data object Previous : Zap

    /** A channel picked in a panel over the video (LIVE-019/020); [group] = the list it was picked from, null = any. */
    data class Pick(val key: String, val group: ChannelGroup?) : Zap
}

/**
 * Plays position [startIndex] of a filtered channel list of [count] channels; [channelAt] reads a position from the
 * database. Channel up/down goes round the list; digits dial a number, Last/Recall goes back to the previous channel
 * ([find] answers where that channel is, null = none, already reported). Playback goes through [player], never an
 * engine directly; the live screen owns the session (open/close, background), this screen only shows it.
 * ◀ / ▶ (and swipes from the screen's edges) go to [onSide]: the live screen opens its panels in [overlay], which
 * switches channels through the `jump` it is given; while [panelOpen] the panel has the remote.
 */
@Composable
fun PlayerScreen(
    player: PlayerSession,
    count: Int,
    startIndex: Int,
    offline: Boolean,
    channelAt: suspend (Int) -> Channel?,
    onIndexChange: (Int, Channel) -> Unit,
    find: suspend (Zap) -> Int?,
    panelOpen: Boolean = false,
    onSide: (Action) -> Unit = {},
    overlay: @Composable BoxScope.(jump: (Zap) -> Unit) -> Unit = {},
) {
    var index by remember { mutableIntStateOf(startIndex) }
    var reopen by remember { mutableIntStateOf(0) } // a jump can land on the same index of another list
    var ch by remember { mutableStateOf<Channel?>(null) }
    val total by rememberUpdatedState(count) // a background refresh can change it
    var showInfo by remember { mutableStateOf(true) }
    val state by player.state.collectAsState()
    val noConnection = stringResource(R.string.no_connection)
    val error = state.error?.let { if (offline) noConnection else playbackErrorText(LocalContext.current, it) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(index, reopen) {
        showInfo = true
        val next = channelAt(index) ?: return@LaunchedEffect
        ch = next
        onIndexChange(index, next)
        player.open(PlayItem(next.url, live = true)) // the preview's stream goes on as is
    }
    LaunchedEffect(index, showInfo) {
        if (showInfo) {
            delay(Defaults.PLAYER_INFO_HIDE_MS)
            showInfo = false
        }
    }

    fun jump(zap: Zap) {
        scope.launch {
            find(zap)?.let { index = it; reopen++ }
        }
    }

    // Number dialing (LIVE-009): OK switches at once, otherwise the dial does by itself; Back cancels.
    var dial by remember { mutableStateOf(NumberDial()) }
    fun commit() {
        val n = dial.number
        dial = NumberDial()
        if (n != null) jump(Zap.Number(n))
    }
    LaunchedEffect(dial) {
        val due = dial.dueAt ?: return@LaunchedEffect
        delay(due - System.currentTimeMillis())
        commit()
    }
    BackHandler(dial.text.isNotEmpty()) { dial = NumberDial() }

    val latestSide by rememberUpdatedState(onSide)
    val panelNow by rememberUpdatedState(panelOpen)
    // Reads state holders only, so it is safe to keep across recompositions (see touchActions).
    val handle: (Action) -> Unit = remember {
        { a ->
            when (a) {
                // Always, also while a stream is still starting: the newer index cancels the older load.
                Action.Up, Action.ChannelUp -> if (total > 0) index = (index + 1).mod(total)
                Action.Down, Action.ChannelDown -> if (total > 0) index = (index - 1).mod(total)
                Action.Ok -> when {
                    dial.text.isNotEmpty() -> commit()
                    player.state.value.error != null -> player.retry()
                    else -> showInfo = !showInfo
                }
                Action.Menu -> showInfo = !showInfo
                Action.PlayPause -> player.togglePause()
                Action.Recall -> jump(Zap.Previous)
                Action.Left, Action.Right -> latestSide(a) // channel list / recent channels (LIVE-019/020)
            }
        }
    }

    val focus = remember { FocusRequester() }
    val shown = ch
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            // Consume every mapped key so D-pad presses never move focus to the list underneath.
            .onKeyEvent { e ->
                if (panelNow) return@onKeyEvent false // the panel's list moves its own focus
                val digit = if (e.type == KeyEventType.KeyDown) keyDigit(e.nativeKeyEvent.keyCode) else null
                if (digit != null) {
                    dial = dial.digit(digit, System.currentTimeMillis())
                    true
                } else e.toAction()?.also(handle) != null
            }
            .focusable()
    ) {
        // The picture keeps its aspect ratio, black bars around it.
        AndroidView(
            factory = {
                SurfaceView(it).apply {
                    keepScreenOn = true // the screen doesn't dim while the player is open
                    isFocusable = false
                    player.setVideoView(this)
                }
            },
            modifier = (if (state.aspect > 0f) Modifier.aspectRatio(state.aspect) else Modifier.fillMaxSize())
                .align(Alignment.Center),
            onRelease = { player.releaseVideoView(it) },
        )
        Box(Modifier.matchParentSize().touchActions(handle))
        if (state.buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (shown != null && (showInfo || error != null)) {
            InfoBar(shown, index + 1, total, error, Modifier.align(Alignment.BottomStart))
        }
        // The clock is always there in the player (UI-009); the dialed number right under it (LIVE-009).
        Column(Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 24.dp), horizontalAlignment = Alignment.End) {
            Clock(
                Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                date = false,
            )
            if (dial.text.isNotEmpty()) Text(
                dial.text, style = TvType.display,
                modifier = Modifier.padding(top = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        overlay(::jump)
    }
    // Back from a panel: the remote is the player's again.
    LaunchedEffect(panelOpen) { if (!panelOpen) runCatching { focus.requestFocus() } }

    // Full-screen video: hide system bars while the player is open (swipe from the edge shows them briefly).
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Bottom info bar on a gradient scrim: logo, category, name, position, error. */
@Composable
private fun InfoBar(ch: Channel, number: Int, total: Int, error: String?, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(ch, Modifier.size(width = 112.dp, height = 70.dp), top = 12.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(groupName(LocalContext.current, ch.group), style = MaterialTheme.typography.labelLarge, color = c.primary, maxLines = 1)
            Text(
                ch.name, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.player_position, number, total), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            if (error != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = c.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.player_error, error), style = MaterialTheme.typography.bodyMedium, color = c.error)
                }
            }
        }
    }
}
