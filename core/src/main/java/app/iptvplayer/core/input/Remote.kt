package app.iptvplayer.core.input

import app.iptvplayer.core.Defaults

/**
 * Dialing a channel number with digit keys (LIVE-009): digits collect, OK switches at once, otherwise the dial
 * switches by itself [Defaults.NUMBER_DIAL_MS] after the last digit ([dueAt]); Back cancels. Immutable: the screen
 * keeps it in a state and restarts its timer on every change.
 */
data class NumberDial(val text: String = "", private val at: Long = 0) {
    /** One more digit at [now]; past [Defaults.NUMBER_DIAL_DIGITS] digits only the timer restarts. */
    fun digit(d: Int, now: Long) = NumberDial(if (text.length < Defaults.NUMBER_DIAL_DIGITS) text + d else text, now)

    /** When the dial switches by itself; null = nothing dialed. */
    val dueAt: Long? get() = if (text.isEmpty()) null else at + Defaults.NUMBER_DIAL_MS

    val number: Int? get() = text.toIntOrNull()
}

/** "Нажмите Back ещё раз, чтобы выйти" (UI-007): [press] is true for a second Back within [windowMs] of the first. */
class ExitGuard(private val windowMs: Long = Defaults.EXIT_CONFIRM_MS) {
    private var last: Long? = null

    fun press(now: Long): Boolean {
        val exit = last?.let { now - it <= windowMs } == true
        last = if (exit) null else now
        return exit
    }
}

/**
 * Panels over the full-screen video (LIVE-019, LIVE-020): ◀ opens the channels of the list the channel was started
 * from, ◀ again all categories; ▶ opens the recent channels. The opposite arrow goes back a step; Back closes.
 */
enum class VideoPanel {
    None, Channels, Categories, Recent;

    fun next(a: Action): VideoPanel = when (this) {
        None -> when (a) { Action.Left -> Channels; Action.Right -> Recent; else -> this }
        Channels -> when (a) { Action.Left -> Categories; Action.Right -> None; else -> this }
        Categories -> if (a == Action.Right) Channels else this
        Recent -> if (a == Action.Left) None else this
    }
}
