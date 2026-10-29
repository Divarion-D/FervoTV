package app.iptvplayer.core.player

import android.view.SurfaceView
import kotlinx.coroutines.flow.StateFlow

/**
 * What to play. [live] = a channel of the live list: dropped in the background, resumed at the live edge.
 * [start] = where a film or an episode begins, ms (VOD-010); 0 = the start (live: the live edge).
 */
data class PlayItem(val url: String, val live: Boolean = true, val start: Long = 0)

data class PlaybackState(
    val buffering: Boolean = false,
    /** Paused by the user (or not started); buffering doesn't count. */
    val paused: Boolean = true,
    val error: PlaybackError? = null,
    /** Width / height of the picture, 0 until the first frame is known. */
    val aspect: Float = 0f,
    /** The picture's actual height in pixels and frame rate, 0 = not known yet ("1080p · 50" in the preview). */
    val height: Int = 0,
    val fps: Float = 0f,
    /** Played to the end (a film or an episode; VOD-009 goes on to the next one). */
    val ended: Boolean = false,
)

/**
 * [kind] is what a fallback chain (the next engine, P3) decides by and what the apps word for the user (UI-022);
 * [detail] is the engine's own code name, shown as is ([Kind.Stalled]: seconds without data).
 */
data class PlaybackError(val kind: Kind, val detail: String) {
    enum class Kind { Network, Format, Decoder, Drm, Other, Stalled }
}

/**
 * A playback engine (Media3 ExoPlayer now; FFmpeg audio and libmpv in P3). Screens never talk to an engine
 * directly, only through [PlayerSession]. All calls on the main thread.
 *
 * An engine tries the variants of a format itself (e.g. an extensionless link as HLS) and reports an error only when
 * it has nothing left to try.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>

    /** Prepares [item] and starts playing it, replacing the current one. */
    fun play(item: PlayItem)

    /** Prepares the current item again after an error. */
    fun retry()

    fun pause()
    fun resume()

    /** Sound off/on without touching the stream (the preview window, LIVE-006). */
    fun setMuted(muted: Boolean)

    /** Where playback is and how long the item is, ms; 0 = unknown (live, not prepared yet). Read when needed. */
    val position: Long
    val duration: Long

    /** Films and episodes: jump to [ms]. */
    fun seekTo(ms: Long)

    /** Drops the stream (network, decoders); the engine stays usable for the next [play]. */
    fun stop()

    /** Where the picture goes; null detaches. Switching views doesn't restart the stream. */
    fun setVideoView(view: SurfaceView?)

    fun release()
}

/**
 * One playback session for the whole app: it lives above the screens, so a screen (the preview window, full screen;
 * the corner player later) only attaches its video view, and moving between them doesn't restart the stream.
 * The engine is created on first use: an app that never plays doesn't pay for it.
 */
class PlayerSession(newEngine: () -> PlaybackEngine) {
    private val lazyEngine = lazy(newEngine)
    private val engine by lazyEngine
    private var item: PlayItem? = null
    private var dropped = false // a live stream released in the background
    private var view: SurfaceView? = null

    val state: StateFlow<PlaybackState> get() = engine.state

    /** What is open now (null = nothing). */
    val current: PlayItem? get() = item

    /** Starts [item]; the same item already playing goes on as is (preview → full screen without a restart). */
    fun open(item: PlayItem) {
        if (item == this.item && !dropped && engine.state.value.error == null) return
        this.item = item
        dropped = false
        engine.play(item)
    }

    /** The app went to the background: live is released (nothing to catch up on, no traffic), anything else pauses. */
    fun background() {
        val it = item ?: return
        if (it.live) {
            engine.stop()
            dropped = true
        } else engine.pause()
    }

    /** Back from the background: live starts again at the current moment, anything else continues. */
    fun foreground() {
        val it = item ?: return
        if (dropped) open(it) else engine.resume()
    }

    fun retry() {
        if (item != null) engine.retry()
    }

    fun togglePause() {
        if (item == null) return
        if (engine.state.value.paused) engine.resume() else engine.pause()
    }

    fun setMuted(muted: Boolean) = engine.setMuted(muted)

    /** Position and length of what plays, ms (0 = unknown or nothing open). */
    val position: Long get() = if (item != null) engine.position else 0
    val duration: Long get() = if (item != null) engine.duration else 0

    fun seekTo(ms: Long) {
        if (item != null) engine.seekTo(ms.coerceAtLeast(0))
    }

    /** Where the picture goes now; the previous view is let go. */
    fun setVideoView(view: SurfaceView?) {
        this.view = view
        engine.setVideoView(view)
    }

    /**
     * A screen's video view is going away: detached only if it is still the current one, so the order in which
     * two screens swap views (preview ↔ full screen) doesn't matter.
     */
    fun releaseVideoView(view: SurfaceView) {
        if (this.view === view) setVideoView(null)
    }

    /** The player screen closed: the stream stops, the engine stays for the next channel. */
    fun close() {
        item = null
        dropped = false
        if (lazyEngine.isInitialized()) engine.stop()
    }

    fun release() {
        item = null
        if (lazyEngine.isInitialized()) engine.release()
    }
}

/**
 * LIVE-010: "Недавние" gets a channel only after [minMs] of playing without a break, so zapping through channels
 * doesn't fill it. Fed the channel playing now (null = nothing, paused, failed or in the background) with a clock;
 * another channel or a break starts the count again.
 */
class WatchTimer(private val minMs: Long = app.iptvplayer.core.Defaults.RECENT_MIN_WATCH_MS) {
    private var key: String? = null
    private var since = 0L
    private var counted = false

    /** The key to record as watched, once per uninterrupted watch; null otherwise. */
    fun update(playing: String?, now: Long): String? {
        if (playing != key) {
            key = playing
            since = now
            counted = false
            return null
        }
        if (playing == null || counted || now - since < minMs) return null
        counted = true
        return playing
    }
}
