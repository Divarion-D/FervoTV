package app.iptvplayer.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.OptIn
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
import app.iptvplayer.LogoIndex
import app.iptvplayer.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.iptvplayer.Action
import app.iptvplayer.Channel
import app.iptvplayer.Defaults
import app.iptvplayer.toAction
import app.iptvplayer.touchActions
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(channels: List<Channel>, startIndex: Int, logos: LogoIndex?, onIndexChange: (Int) -> Unit) {
    val ctx = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex) }
    var showInfo by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val exo = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Defaults.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build()
    }

    DisposableEffect(exo) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(e: PlaybackException) {
                val item = exo.currentMediaItem
                when {
                    e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    // Type is guessed from the URL extension; extensionless HLS links fail as progressive,
                    // so retry once as HLS before reporting.
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                        item != null && item.localConfiguration?.mimeType == null -> {
                        exo.setMediaItem(item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        exo.prepare()
                    }
                    else -> error = e.errorCodeName.removePrefix("ERROR_CODE_")
                }
            }
        })
        onDispose { exo.release() }
    }

    LaunchedEffect(index) {
        error = null
        showInfo = true
        onIndexChange(index)
        exo.setMediaItem(MediaItem.fromUri(channels[index].url))
        exo.prepare()
    }
    LaunchedEffect(index, showInfo) {
        if (showInfo) {
            delay(Defaults.PLAYER_INFO_HIDE_MS)
            showInfo = false
        }
    }
    LifecycleStartEffect(exo) {
        exo.play()
        onStopOrDispose { exo.pause() }
    }

    // Reads state holders only, so it is safe to keep across recompositions (see touchActions).
    val handle: (Action) -> Unit = remember {
        { a ->
            when (a) {
                Action.Up, Action.ChannelUp -> index = (index + 1).mod(channels.size)
                Action.Down, Action.ChannelDown -> index = (index - 1).mod(channels.size)
                Action.Ok -> if (error != null) {
                    error = null
                    exo.prepare()
                } else showInfo = !showInfo
                Action.Menu -> showInfo = !showInfo
                Action.PlayPause -> if (exo.isPlaying) exo.pause() else exo.play()
                Action.Left, Action.Right -> {} // reserved: channel list / audio tracks
            }
        }
    }

    val focus = remember { FocusRequester() }
    val ch = channels[index]
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            // Consume every mapped key so D-pad presses never move focus to the list underneath.
            .onKeyEvent { e -> e.toAction()?.also(handle) != null }
            .focusable()
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    player = exo
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.matchParentSize().touchActions(handle))
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (showInfo || error != null) InfoBar(ch, logos?.find(ch), index + 1, channels.size, error, Modifier.align(Alignment.BottomStart))
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
private fun InfoBar(ch: Channel, autoLogo: String?, number: Int, total: Int, error: String?, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(ch, autoLogo, Modifier.size(width = 112.dp, height = 70.dp).clip(RoundedCornerShape(12.dp)))
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
