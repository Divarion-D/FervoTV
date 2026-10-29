package app.iptvplayer.core.input

import android.view.KeyEvent.*

/**
 * One input vocabulary for both apps. Remote keys and touch gestures are both mapped
 * to these, so screens handle [Action] and never raw keycodes or pointer events.
 * A new input type (gamepad, number keys, voice) = one more mapper into [Action].
 * Back is not here: it goes through BackHandler, which covers remote, phone button and gesture.
 */
enum class Action {
    Up, Down, Left, Right, Ok, ChannelUp, ChannelDown, PlayPause, Menu,

    /** The previous channel (LIVE-018): Last/Recall on the remote. */
    Recall,
}

/** Remote/keyboard key (android.view.KeyEvent keycode) → [Action]; null = not ours. */
fun keyAction(keyCode: Int): Action? = when (keyCode) {
    KEYCODE_DPAD_UP -> Action.Up
    KEYCODE_DPAD_DOWN -> Action.Down
    KEYCODE_DPAD_LEFT -> Action.Left
    KEYCODE_DPAD_RIGHT -> Action.Right
    KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> Action.Ok
    KEYCODE_CHANNEL_UP, KEYCODE_PAGE_UP, KEYCODE_MEDIA_NEXT -> Action.ChannelUp
    KEYCODE_CHANNEL_DOWN, KEYCODE_PAGE_DOWN, KEYCODE_MEDIA_PREVIOUS -> Action.ChannelDown
    KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, KEYCODE_SPACE -> Action.PlayPause
    KEYCODE_MENU, KEYCODE_INFO, KEYCODE_GUIDE -> Action.Menu
    KEYCODE_LAST_CHANNEL -> Action.Recall
    else -> null
}

/** Digit keys of the remote and the keyboard (LIVE-009 dialing) → 0..9; null = not a digit. */
fun keyDigit(keyCode: Int): Int? = when (keyCode) {
    in KEYCODE_0..KEYCODE_9 -> keyCode - KEYCODE_0
    in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 -> keyCode - KEYCODE_NUMPAD_0
    else -> null
}
