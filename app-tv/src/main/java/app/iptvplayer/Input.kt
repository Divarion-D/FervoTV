package app.iptvplayer

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.input.keyAction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Compose key event → [Action] (key-down only); the key table itself is [keyAction] in core. */
fun KeyEvent.toAction(): Action? =
    if (type == KeyEventType.KeyDown) keyAction(nativeKeyEvent.keyCode) else null

/**
 * Touch → [Action]: tap = Ok, vertical swipe = channel up/down.
 * The pointerInput blocks live for the modifier's lifetime, so [onAction] must read
 * state holders rather than capture values.
 */
fun Modifier.touchActions(onAction: (Action) -> Unit): Modifier = this
    .pointerInput(Unit) { detectTapGestures { onAction(Action.Ok) } }
    .pointerInput(Unit) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f },
            onDragEnd = { if (abs(dy) > Defaults.SWIPE_THRESHOLD_DP.dp.toPx()) onAction(if (dy < 0) Action.ChannelUp else Action.ChannelDown) },
        ) { _, d -> dy += d }
    }

fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/** On TV nothing is focused at start, so the remote does nothing until something is. */
@Composable
fun Modifier.initialFocus(): Modifier {
    if (!LocalContext.current.isTv()) return this
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { fr.requestFocus() }
    }
    return focusRequester(fr)
}
