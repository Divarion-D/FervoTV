package app.iptvplayer

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent.*
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

/**
 * One input vocabulary for the whole app. Remote keys and touch gestures are both mapped
 * to these, so screens handle [Action] and never raw keycodes or pointer events.
 * A new input type (gamepad, number keys, voice) = one more mapper into [Action].
 * Back is not here: it goes through BackHandler, which covers remote, phone button and gesture.
 */
enum class Action { Up, Down, Left, Right, Ok, ChannelUp, ChannelDown, PlayPause, Menu }

fun KeyEvent.toAction(): Action? {
    if (type != KeyEventType.KeyDown) return null
    return when (nativeKeyEvent.keyCode) {
        KEYCODE_DPAD_UP -> Action.Up
        KEYCODE_DPAD_DOWN -> Action.Down
        KEYCODE_DPAD_LEFT -> Action.Left
        KEYCODE_DPAD_RIGHT -> Action.Right
        KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> Action.Ok
        KEYCODE_CHANNEL_UP, KEYCODE_PAGE_UP -> Action.ChannelUp
        KEYCODE_CHANNEL_DOWN, KEYCODE_PAGE_DOWN -> Action.ChannelDown
        KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, KEYCODE_SPACE -> Action.PlayPause
        KEYCODE_MENU, KEYCODE_INFO, KEYCODE_GUIDE -> Action.Menu
        else -> null
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
