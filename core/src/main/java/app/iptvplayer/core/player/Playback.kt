package app.iptvplayer.core.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.SubtitleBackground
import app.iptvplayer.core.model.SubtitleColor
import app.iptvplayer.core.model.SubtitleFont
import app.iptvplayer.core.model.SubtitlePosition
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What to play. [live] = a channel of the live list: dropped in the background, resumed at the live edge.
 * [start] = where a film or an episode begins, ms (VOD-010); 0 = the start (live: the live edge).
 */
data class PlayItem(
    val url: String,
    val live: Boolean = true,
    val start: Long = 0,
    val subtitleUrl: String? = null,
    val subtitleMimeType: String? = null,
    val subtitleOffsetMs: Long = 0,
    val drmLicenseUrl: String? = null,
    val drmScheme: String? = null,
    /** Per-stream request headers; credentials must never be put in this map. */
    val headers: Map<String, String> = emptyMap(),
    /** Stable identity for per-item player choices; hashed before persistence. */
    val key: String = url,
    val rememberedAudioTrack: Int? = null,
    /** -1 remembers subtitles off. */
    val rememberedSubtitleTrack: Int? = null,
    /** Successful live fallback MIME; empty means progressive. Never restored for VOD. */
    val rememberedFormat: String? = null,
    val title: String? = null,
    val content: PlaybackContent = if (live) PlaybackContent.LIVE else PlaybackContent.MOVIE,
)

enum class PlaybackContent { LIVE, MOVIE, SERIES }

fun Source.playbackHeaders(): Map<String, String> = buildMap {
    val agent = when (this@playbackHeaders) { is Source.M3u -> userAgent; is Source.Xtream -> userAgent }
    val ref = when (this@playbackHeaders) { is Source.M3u -> referer; is Source.Xtream -> referer }
    agent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    ref?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
}

/** Converts a channel's playlist metadata into request headers without ever placing credentials in the URL. */
fun app.iptvplayer.core.model.Channel.playItem(live: Boolean = true, start: Long = 0): PlayItem = PlayItem(
    url = url,
    key = key,
    live = live,
    start = start,
    drmLicenseUrl = drmLicenseUrl,
    drmScheme = drmScheme,
    headers = buildMap {
        userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
    }, title = name,
)

data class AudioTrack(val language: String?, val codec: String?, val channels: Int, val selected: Boolean)
data class SubtitleTrack(val language: String?, val codec: String?, val selected: Boolean)
data class VideoTrack(val width: Int, val height: Int, val bitrate: Int, val codec: String?, val selected: Boolean)
data class StreamInfo(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val bitrate: Int = 0,
    val audioChannels: Int = 0,
    val hdr: String? = null,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val engine: String = "ExoPlayer",
    val bitDepth: Int = 0,
    val videoDecoder: String? = null,
    val measuredBitrate: Int = 0,
    /** -1 when measured metrics are disabled. */
    val droppedFrames: Int = -1,
)

enum class VideoScale { FIT, FILL, STRETCH, ORIGINAL, RATIO_16_9, RATIO_4_3 }

data class PlaybackConfig(
    val giveUpAfterMs: Long = Defaults.PLAYER_GIVE_UP_AFTER_MS,
    val reconnectAttempts: Int = Defaults.PLAYER_RECONNECT_ATTEMPTS,
    val defaultVolume: Int = Defaults.PLAYER_VOLUME,
    val audioOnly: Boolean = false,
    val preferredAudioLanguages: List<String> = emptyList(),
    val preferSoftwareAudio: Boolean = false,
    val preferredSubtitleLanguages: List<String> = emptyList(),
    val minBufferMs: Int = Defaults.PLAYER_MIN_BUFFER_MS,
    val maxBufferMs: Int = Defaults.PLAYER_MAX_BUFFER_MS,
    val bufferForPlaybackMs: Int = Defaults.PLAYER_BUFFER_FOR_PLAYBACK_MS,
    val matchFrameRate: Boolean = false,
    /** 0 = automatic device/display limit. */
    val maxVideoHeight: Int = 0,
    val defaultAudioDelayMs: Int = 0,
    val subtitleEmbeddedStyles: Boolean = true,
    val subtitleFont: SubtitleFont = SubtitleFont.SANS,
    val subtitleSize: Int = 100,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.TRANSLUCENT,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM,
    val nightMode: Boolean = false,
    val normalizeVolume: Boolean = false,
    val verboseLogging: Boolean = false,
    /** Collect actual throughput, rendered FPS and dropped-frame counters for PLR-024. */
    val measuredMetrics: Boolean = false,
)

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
    val reconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    /** Provider-requested wait before the next live reconnect; 0 for normal exponential backoff. */
    val reconnectInSeconds: Int = 0,
    val videoEnabled: Boolean = true,
    val speed: Float = 1f,
    val volume: Int = Defaults.PLAYER_VOLUME,
    val scale: VideoScale = VideoScale.FIT,
    val audioOffsetMs: Long = 0L,
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val videoTracks: List<VideoTrack> = emptyList(),
    val streamInfo: StreamInfo? = null,
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
    fun setVideoView(view: View?)

    /** Creates and attaches the engine's cue renderer; null means that engine has no subtitle renderer. */
    fun createSubtitleView(context: Context): View? = null
    fun setSubtitleView(view: View?) = Unit

    /** Optional P3 controls. Default implementations keep test engines source-compatible. */
    fun selectAudioTrack(index: Int) = Unit
    fun selectSubtitleTrack(index: Int) = Unit
    fun selectVideoTrack(index: Int) = Unit
    fun clearVideoTrackOverride() = Unit
    fun disableSubtitles() = Unit
    fun setSpeed(speed: Float) = Unit
    fun setVolume(percent: Int) = Unit
    fun setAudioOffset(ms: Long) = Unit
    fun setVideoEnabled(enabled: Boolean) = Unit
    fun setScale(scale: VideoScale) = Unit
    fun addSubtitle(url: String, mimeType: String? = null, offsetMs: Long = 0) = Unit
    fun streamInfo(): StreamInfo? = null
    fun setMediaNavigationHandlers(previous: (() -> Unit)?, next: (() -> Unit)?) = Unit
    fun setWorkingFormatListener(listener: ((String) -> Unit)?) = Unit

    fun release()
}

/**
 * One playback session for the whole app: it lives above the screens, so a screen (the preview window, full screen;
 * the corner player later) only attaches its video view, and moving between them doesn't restart the stream.
 * The engine is created on first use: an app that never plays doesn't pay for it.
 */
class PlayerSession(private val memory: PlayerMemory? = null, newEngine: () -> PlaybackEngine) {
    private val lazyEngine = lazy {
        newEngine().also { engine ->
            engine.setWorkingFormatListener { format ->
                item?.takeIf { it.live }?.let { memory?.put(it.key, live = true, PlayerMemory.Kind.FORMAT, format) }
            }
        }
    }
    private val engine by lazyEngine
    private var item: PlayItem? = null
    private val _current = MutableStateFlow<PlayItem?>(null)
    val currentItem: StateFlow<PlayItem?> = _current.asStateFlow()
    private var dropped = false // a live stream released in the background
    private var backgrounded = false
    private var resumeOnForeground = false
    private var view: View? = null
    private var subtitleView: View? = null
    private var videoEnabled = true
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs.asStateFlow()
    private val _sleepAtEnd = MutableStateFlow(false)
    val sleepAtEnd: StateFlow<Boolean> = _sleepAtEnd.asStateFlow()
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepDeadline = 0L
    private val sleepTick = object : Runnable {
        override fun run() {
            val left = sleepDeadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0L) {
                _sleepRemainingMs.value = 0L
                close()
            } else {
                _sleepRemainingMs.value = left
                sleepHandler.postDelayed(this, minOf(left, 1_000L))
            }
        }
    }

    val state: StateFlow<PlaybackState> get() = engine.state

    /** What is open now (null = nothing). */
    val current: PlayItem? get() = item

    /** Starts [item]; the same item already playing goes on as is (preview → full screen without a restart). */
    fun open(item: PlayItem) {
        val effective = item.copy(
            rememberedAudioTrack = memory?.get(item.key, item.live, PlayerMemory.Kind.AUDIO_TRACK)?.toIntOrNull(),
            rememberedSubtitleTrack = memory?.get(item.key, item.live, PlayerMemory.Kind.SUBTITLE_TRACK)?.toIntOrNull(),
            rememberedFormat = if (item.live) memory?.get(item.key, live = true, PlayerMemory.Kind.FORMAT) else null,
        )
        if (effective == this.item && !dropped && engine.state.value.error == null) return
        this.item = effective
        _current.value = effective
        dropped = false
        engine.play(effective)
        memory?.get(item.key, item.live, PlayerMemory.Kind.SCALE)
            ?.let { runCatching { VideoScale.valueOf(it) }.getOrNull() }
            ?.let(engine::setScale)
        memory?.get(item.key, item.live, PlayerMemory.Kind.VOLUME)?.toIntOrNull()?.let(engine::setVolume)
        memory?.get(item.key, item.live, PlayerMemory.Kind.AUDIO_DELAY)?.toLongOrNull()?.let(engine::setAudioOffset)
    }

    /** The app went to the background: live is released (nothing to catch up on, no traffic), anything else pauses. */
    fun background() {
        val it = item ?: return
        if (backgrounded) return
        backgrounded = true
        if (it.live) {
            engine.stop()
            dropped = true
        } else {
            resumeOnForeground = !engine.state.value.paused
            engine.pause()
        }
    }

    /** Back from the background: live starts again at the current moment, anything else continues. */
    fun foreground() {
        val it = item ?: return
        if (!backgrounded) return
        backgrounded = false
        if (dropped) open(it) else if (resumeOnForeground) engine.resume()
        resumeOnForeground = false
    }

    fun retry() {
        if (item != null) engine.retry()
    }

    fun togglePause() {
        if (item == null) return
        setPaused(!engine.state.value.paused)
    }

    fun setPaused(paused: Boolean) {
        if (item == null) return
        if (paused) engine.pause() else engine.resume()
    }

    fun setMuted(muted: Boolean) = engine.setMuted(muted)
    fun selectAudioTrack(index: Int) {
        engine.selectAudioTrack(index)
        item?.let { memory?.put(it.key, it.live, PlayerMemory.Kind.AUDIO_TRACK, index.toString()) }
    }
    fun selectSubtitleTrack(index: Int) {
        engine.selectSubtitleTrack(index)
        item?.let { memory?.put(it.key, it.live, PlayerMemory.Kind.SUBTITLE_TRACK, index.toString()) }
    }
    fun disableSubtitles() {
        engine.disableSubtitles()
        item?.let { memory?.put(it.key, it.live, PlayerMemory.Kind.SUBTITLE_TRACK, "-1") }
    }
    fun selectVideoTrack(index: Int) = engine.selectVideoTrack(index)
    fun clearVideoTrackOverride() = engine.clearVideoTrackOverride()
    fun setSpeed(speed: Float) = engine.setSpeed(speed)
    fun setVolume(percent: Int) {
        engine.setVolume(percent)
        item?.let { memory?.put(it.key, it.live, PlayerMemory.Kind.VOLUME, percent.coerceIn(0, 150).toString()) }
    }
    fun setAudioOffset(ms: Long) {
        engine.setAudioOffset(ms)
        item?.let { memory?.put(it.key, it.live, PlayerMemory.Kind.AUDIO_DELAY, ms.toString()) }
    }
    fun setScale(scale: VideoScale) {
        engine.setScale(scale)
        item?.let { memory?.put(it.key, it.live, PlayerMemory.Kind.SCALE, scale.name) }
    }
    fun addSubtitle(url: String, mimeType: String? = null, offsetMs: Long = 0) = engine.addSubtitle(url, mimeType, offsetMs)
    fun streamInfo(): StreamInfo? = engine.streamInfo()
    fun setMediaNavigationHandlers(previous: (() -> Unit)?, next: (() -> Unit)?) =
        engine.setMediaNavigationHandlers(previous, next)

    fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        engine.setVideoEnabled(enabled)
        if (enabled) engine.setVideoView(view)
    }

    /** Starts a one-shot sleep timer. A value of null cancels it. */
    fun setSleepTimer(remainingMs: Long?) {
        sleepHandler.removeCallbacks(sleepTick)
        _sleepAtEnd.value = false
        if (remainingMs == null) {
            sleepDeadline = 0L
            _sleepRemainingMs.value = null
        } else {
            sleepDeadline = android.os.SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(0)
            sleepTick.run()
        }
    }

    fun setSleepAtEnd(enabled: Boolean) {
        sleepHandler.removeCallbacks(sleepTick)
        sleepDeadline = 0L
        _sleepRemainingMs.value = null
        _sleepAtEnd.value = enabled
    }

    /** True once at item end; the screen closes instead of starting the next episode. */
    fun consumeSleepAtEnd(): Boolean = _sleepAtEnd.value.also { if (it) _sleepAtEnd.value = false }

    /** Position and length of what plays, ms (0 = unknown or nothing open). */
    val position: Long get() = if (item != null) engine.position else 0
    val duration: Long get() = if (item != null) engine.duration else 0

    fun seekTo(ms: Long) {
        if (item != null) engine.seekTo(ms.coerceAtLeast(0))
    }

    /** Where the picture goes now; the previous view is let go. */
    fun setVideoView(view: View?) {
        this.view = view
        engine.setVideoView(view)
    }

    /**
     * A screen's video view is going away: detached only if it is still the current one, so the order in which
     * two screens swap views (preview ↔ full screen) doesn't matter.
     */
    fun releaseVideoView(view: View) {
        if (this.view === view) setVideoView(null)
    }

    fun createSubtitleView(context: Context): View? = engine.createSubtitleView(context)

    fun setSubtitleView(view: View?) {
        subtitleView = view
        engine.setSubtitleView(view)
    }

    fun releaseSubtitleView(view: View) {
        if (subtitleView === view) setSubtitleView(null)
    }

    /** The player screen closed: the stream stops, the engine stays for the next channel. */
    fun close() {
        item = null
        _current.value = null
        dropped = false
        backgrounded = false
        resumeOnForeground = false
        if (lazyEngine.isInitialized()) engine.stop()
        sleepHandler.removeCallbacks(sleepTick)
        _sleepRemainingMs.value = null
        _sleepAtEnd.value = false
    }

    fun release() {
        item = null
        _current.value = null
        backgrounded = false
        resumeOnForeground = false
        if (lazyEngine.isInitialized()) engine.release()
        sleepHandler.removeCallbacks(sleepTick)
        _sleepRemainingMs.value = null
        _sleepAtEnd.value = false
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
