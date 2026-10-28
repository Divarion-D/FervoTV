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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import androidx.lifecycle.compose.LifecycleStartEffect
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlayerSession
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import app.iptvplayer.toAction
import app.iptvplayer.touchActions
import kotlinx.coroutines.delay

/**
 * Plays position [startIndex] of a filtered channel list of [count] channels; [channelAt] reads a position from the
 * database. Channel up/down goes round the list. Playback goes through [player], never an engine directly.
 */
@Composable
fun PlayerScreen(
    player: PlayerSession,
    count: Int,
    startIndex: Int,
    channelAt: suspend (Int) -> Channel?,
    onIndexChange: (Int) -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex) }
    var ch by remember { mutableStateOf<Channel?>(null) }
    val total by rememberUpdatedState(count) // a background refresh can change it
    var showInfo by remember { mutableStateOf(true) }
    val state by player.state.collectAsState()
    val error = state.error?.detail

    LaunchedEffect(index) {
        showInfo = true
        onIndexChange(index)
        val next = channelAt(index) ?: return@LaunchedEffect
        ch = next
        player.open(PlayItem(next.url, live = true))
    }
    LaunchedEffect(index, showInfo) {
        if (showInfo) {
            delay(Defaults.PLAYER_INFO_HIDE_MS)
            showInfo = false
        }
    }
    // Background: live is released, back in front it starts again at the current moment (PLR-002).
    LifecycleStartEffect(player) {
        player.foreground()
        onStopOrDispose { player.background() }
    }
    DisposableEffect(player) { onDispose { player.close() } }

    // Reads state holders only, so it is safe to keep across recompositions (see touchActions).
    val handle: (Action) -> Unit = remember {
        { a ->
            when (a) {
                // Always, also while a stream is still starting: the newer index cancels the older load.
                Action.Up, Action.ChannelUp -> if (total > 0) index = (index + 1).mod(total)
                Action.Down, Action.ChannelDown -> if (total > 0) index = (index - 1).mod(total)
                Action.Ok -> if (player.state.value.error != null) player.retry() else showInfo = !showInfo
                Action.Menu -> showInfo = !showInfo
                Action.PlayPause -> player.togglePause()
                Action.Left, Action.Right -> {} // reserved: channel list / audio tracks
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
            .onKeyEvent { e -> e.toAction()?.also(handle) != null }
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
            onRelease = { player.setVideoView(null) },
        )
        Box(Modifier.matchParentSize().touchActions(handle))
        if (state.buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (shown != null && (showInfo || error != null)) {
            InfoBar(shown, index + 1, total, error, Modifier.align(Alignment.BottomStart))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

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
        ChannelLogo(ch, Modifier.size(width = 112.dp, height = 70.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.group, style = MaterialTheme.typography.labelLarge, color = c.primary, maxLines = 1)
            Text(
                ch.name, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("Канал $number из $total", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            if (error != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = c.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ошибка: $error · OK — повторить", style = MaterialTheme.typography.bodyMedium, color = c.error)
                }
            }
        }
    }
}
