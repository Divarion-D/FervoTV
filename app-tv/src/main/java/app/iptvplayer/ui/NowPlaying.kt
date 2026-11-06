package app.iptvplayer.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.iptvplayer.R
import app.iptvplayer.core.player.PlayerSession
import androidx.compose.ui.res.stringResource

/** The shared session's surface while its source screen is not visible; attaching it never restarts playback. */
@Composable
fun NowPlaying(player: PlayerSession, visible: Boolean) {
    val item by player.currentItem.collectAsState()
    val state by player.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    if (!visible || item == null) return

    fun stop() { expanded = false; player.close() }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (state.videoEnabled) PlayerSurface(player, Modifier.fillMaxSize())
            else Text(item?.title ?: stringResource(R.string.now_playing), color = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .85f)).padding(12.dp)) {
                PlayerTools(player)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) }
                TextButton(onClick = ::stop) { Text(stringResource(R.string.stop)) }
            }
        }
    } else if (state.videoEnabled) {
        Column(
            Modifier.padding(16.dp).width(320.dp).background(Color.Black, RoundedCornerShape(12.dp))
                .clickable { expanded = true }.padding(4.dp),
        ) {
            PlayerSurface(player, Modifier.fillMaxWidth().height(180.dp))
            NowPlayingActions(item?.title, { expanded = true }, ::stop)
        }
    } else {
        NowPlayingActions(
            item?.title,
            { expanded = true }, ::stop,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PlayerSurface(player: PlayerSession, modifier: Modifier) = Box(modifier) {
    AndroidView(
        factory = { SurfaceView(it).apply { isFocusable = false; player.setVideoView(this) } },
        modifier = Modifier.fillMaxSize(),
        onRelease = { player.releaseVideoView(it) },
    )
    SubtitleOverlay(player, Modifier.fillMaxSize())
}

@Composable
private fun NowPlayingActions(title: String?, onFull: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title ?: stringResource(R.string.now_playing), Modifier.weight(1f), maxLines = 1)
        TextButton(onClick = onFull) { Text(stringResource(R.string.full_screen)) }
        TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
    }
}
