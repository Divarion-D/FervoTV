package app.iptvplayer.core.player

import android.view.SurfaceView
import kotlinx.coroutines.flow.StateFlow

/** What to play. [live] = a channel of the live list: dropped in the background, resumed at the live edge. */
data class PlayItem(val url: String, val live: Boolean = true)

data class PlaybackState(
    val buffering: Boolean = false,
    /** Paused by the user (or not started); buffering doesn't count. */
    val paused: Boolean = true,
    val error: PlaybackError? = null,
    /** Width / height of the picture, 0 until the first frame is known. */
    val aspect: Float = 0f,
)

/**
 * [kind] is what a fallback chain (the next engine, P3) decides by; [detail] is the engine's own code name,
 * shown to the user as is.
 */
data class PlaybackError(val kind: Kind, val detail: String) {
    enum class Kind { Network, Format, Decoder, Drm, Other }
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

    /** Drops the stream (network, decoders); the engine stays usable for the next [play]. */
    fun stop()

    /** Where the picture goes; null detaches. Switching views doesn't restart the stream. */
    fun setVideoView(view: SurfaceView?)

    fun release()
}

/**
 * One playback session for the whole app: it lives above the screens, so a screen (full screen now; preview and
 * the corner player later) only attaches its video view, and moving between them doesn't restart the stream.
 * The engine is created on first use: an app that never plays doesn't pay for it.
 */
class PlayerSession(newEngine: () -> PlaybackEngine) {
    private val lazyEngine = lazy(newEngine)
    private val engine by lazyEngine
    private var item: PlayItem? = null
    private var dropped = false // a live stream released in the background

    val state: StateFlow<PlaybackState> get() = engine.state

    fun open(item: PlayItem) {
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

    fun setVideoView(view: SurfaceView?) = engine.setVideoView(view)

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
