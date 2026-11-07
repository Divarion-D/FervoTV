package app.iptvplayer.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.iptvplayer.core.player.PlayerSession

@Composable
fun SubtitleOverlay(player: PlayerSession, modifier: Modifier = Modifier) = AndroidView(
    factory = { context ->
        (player.createSubtitleView(context) ?: View(context)).also(player::setSubtitleView)
    },
    modifier = modifier,
    onRelease = player::releaseSubtitleView,
)
