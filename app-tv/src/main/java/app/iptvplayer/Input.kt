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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Compose key event → [Action] (key-down only); the key table itself is [keyAction] in core. */
fun KeyEvent.toAction(): Action? =
    if (type == KeyEventType.KeyDown) keyAction(nativeKeyEvent.keyCode) else null

/**
 * Remote keys of one focus area (a list, a column, a sheet) as [Action]s, with long presses told apart: `long` =
 * held past the system long-press timeout (long OK = the item's menu, long CH± = to the start/end, LIVE-021).
 * A key used as long has its repeats and release swallowed. OK always fires on release, so a long OK never also
 * clicks: areas using this handle OK themselves (clickables still get touch). An OK release whose press began
 * elsewhere (a long OK that opened a menu here) is swallowed too, so the menu doesn't fire its first item.
 * Unused keys (arrows, unless [handle] takes them) go on to focus movement as usual, repeats included.
 */
@Composable
fun Modifier.remoteKeys(handle: (Action, long: Boolean) -> Boolean): Modifier {
    val down = remember { HashSet<Action>() }
    val held = remember { HashSet<Action>() }
    val latest by rememberUpdatedState(handle)
    return onPreviewKeyEvent { e ->
        val a = keyAction(e.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
        val n = e.nativeKeyEvent
        when (e.type) {
            KeyEventType.KeyDown -> when {
                n.repeatCount == 0 -> {
                    down += a
                    a == Action.Ok || latest(a, false)
                }
                a !in down -> a == Action.Ok
                a in held -> true
                n.isLongPress && latest(a, true) -> { held += a; true }
                else -> a == Action.Ok
            }
            KeyEventType.KeyUp -> when {
                !down.remove(a) -> a == Action.Ok
                held.remove(a) -> true
                a == Action.Ok -> { latest(Action.Ok, false); true }
                else -> false
            }
            else -> false
        }
    }
}

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
