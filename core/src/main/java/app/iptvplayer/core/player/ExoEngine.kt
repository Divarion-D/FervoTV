package app.iptvplayer.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.common.AudioAttributes
import app.iptvplayer.core.Defaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Media3 ExoPlayer. The stream type is guessed from the URL extension (.m3u8 → HLS, anything else progressive:
 * TS, MP4, …); see [recovery] for what is retried before an error is reported.
 */
@OptIn(UnstableApi::class)
class ExoEngine(ctx: Context, private val config: PlaybackConfig = PlaybackConfig()) : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Defaults.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
    private val dataSource = DefaultDataSource.Factory(ctx, http)

    private val exo: ExoPlayer = run {
        ExoPlayer.Builder(ctx)
            .setRenderersFactory(DefaultRenderersFactory(ctx).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.bufferForPlaybackMs, config.bufferForPlaybackMs)
                    .build(),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
    }
    private val mediaSession = MediaSession.Builder(ctx, exo).build()

    private val main = Handler(Looper.getMainLooper())
    private val stall = StallWatch(Defaults.BUFFER_STALL_TIMEOUT_MS)
    private var formatRetries = 0
    private var current: PlayItem? = null
    private var reconnectAttempt = 0
    private var reconnectStartedAt = 0L
    private var muted = false
    private var volumePercent = config.defaultVolume.coerceIn(0, 150)
    private var audioOffsetMs = 0L
    private var videoEnabled = true
    private var scale = VideoScale.FIT
    private val log = PlaybackLog(ctx)

    init {
        exo.setVideoChangeFrameRateStrategy(
            if (config.matchFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
        )
    }

    private val reconnect = object : Runnable {
        override fun run() {
            val item = current ?: return
            if (!item.live || (config.giveUpAfterMs > 0L && SystemClock.elapsedRealtime() - reconnectStartedAt >= config.giveUpAfterMs) || reconnectAttempt >= config.reconnectAttempts.coerceAtLeast(1)) {
                _state.update { it.copy(reconnecting = false, buffering = false, error = PlaybackError(PlaybackError.Kind.Network, "RECONNECT_TIMEOUT")) }
                log.record(item.url, "reconnect-timeout")
                return
            }
            reconnectAttempt++
            _state.update { it.copy(reconnecting = true, buffering = true, reconnectAttempt = reconnectAttempt, error = null) }
            exo.setMediaItem(mediaItem(item))
            exo.prepare()
            exo.play()
            val delay = (1_000L shl (reconnectAttempt.coerceAtMost(4) - 1)).coerceAtMost(8_000L)
            main.postDelayed(this, delay)
        }
    }

    /** Once a second while buffering: no progress for [Defaults.BUFFER_STALL_TIMEOUT_MS] → stop and show an error. */
    private val checkStall = object : Runnable {
        override fun run() {
            if (exo.playbackState != Player.STATE_BUFFERING) return
            if (stall.stalled(exo.bufferedPosition, SystemClock.elapsedRealtime())) {
                exo.stop()
                _state.update {
                    it.copy(buffering = false, error = PlaybackError(PlaybackError.Kind.Stalled, "${Defaults.BUFFER_STALL_TIMEOUT_MS / 1000}"))
                }
            } else main.postDelayed(this, 1_000)
        }
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    main.removeCallbacks(reconnect)
                    reconnectAttempt = 0
                }
                _state.update {
                    it.copy(
                        buffering = state == Player.STATE_BUFFERING,
                        ended = state == Player.STATE_ENDED,
                        reconnecting = state != Player.STATE_READY && it.reconnecting,
                        reconnectAttempt = if (state == Player.STATE_READY) 0 else it.reconnectAttempt,
                        streamInfo = streamInfoInternal(),
                    )
                }
                main.removeCallbacks(checkStall)
                if (state == Player.STATE_BUFFERING) {
                    stall.reset()
                    checkStall.run()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                _state.update { it.copy(paused = !playWhenReady) }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    val fps = exo.videoFormat?.frameRate?.takeIf { it > 0 } ?: 0f
                    _state.update { it.copy(aspect = size.width * size.pixelWidthHeightRatio / size.height, height = size.height, fps = fps) }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audio = mutableListOf<AudioTrack>()
                val text = mutableListOf<SubtitleTrack>()
                tracks.groups.forEach { group ->
                    if (group.type == C.TRACK_TYPE_AUDIO || group.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            if (!group.isTrackSupported(i)) continue
                            if (group.type == C.TRACK_TYPE_AUDIO) audio += AudioTrack(f.language, f.sampleMimeType, f.channelCount, group.isTrackSelected(i))
                            else text += SubtitleTrack(f.language, f.sampleMimeType, group.isTrackSelected(i))
                        }
                    }
                }
                _state.update { it.copy(audioTracks = audio, subtitleTracks = text, streamInfo = streamInfoInternal()) }
            }

            override fun onPlayerError(e: PlaybackException) {
                val item = exo.currentMediaItem
                val playItem = current
                if (playItem?.live == true && errorKind(e.errorCode) == PlaybackError.Kind.Network) {
                    if (reconnectStartedAt == 0L) reconnectStartedAt = SystemClock.elapsedRealtime()
                    log.record(playItem.url, "network-error:${e.errorCodeName}")
                    main.removeCallbacks(reconnect)
                    main.post(reconnect)
                    return
                }
                when (recovery(e.errorCode, item?.localConfiguration?.mimeType != null, formatRetries)) {
                    Recovery.ToLiveEdge -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    Recovery.AsHls -> {
                        formatRetries = 1
                        exo.setMediaItem(item!!.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        exo.prepare()
                    }
                    Recovery.AsProgressive -> {
                        formatRetries = 2
                        val uri = item!!.localConfiguration!!.uri
                        exo.setMediaItem(MediaItem.fromUri(uri))
                        exo.prepare()
                    }
                    null -> _state.update {
                        it.copy(error = PlaybackError(errorKind(e.errorCode), e.errorCodeName.removePrefix("ERROR_CODE_")))
                    }
                }
                log.record(playItem?.url, "error:${e.errorCodeName}")
            }
        })
    }

    override fun play(item: PlayItem) {
        current = item
        http.setDefaultRequestProperties(item.headers)
        formatRetries = 0
        reconnectAttempt = 0
        reconnectStartedAt = 0L
        main.removeCallbacks(reconnect)
        videoEnabled = !config.audioOnly
        volumePercent = config.defaultVolume.coerceIn(0, 150)
        exo.volume = if (muted) 0f else volumePercent / 100f
        _state.update { it.copy(error = null, height = 0, fps = 0f, ended = false, reconnecting = false, reconnectAttempt = 0, videoEnabled = videoEnabled, volume = volumePercent, scale = scale, audioOffsetMs = audioOffsetMs) }
        // Without a start position a live stream begins at its live edge; with one, a film resumes (VOD-010).
        if (item.start > 0) exo.setMediaItem(mediaItem(item), item.start) else exo.setMediaItem(mediaItem(item))
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*config.preferredAudioLanguages.toTypedArray())
            .setPreferredTextLanguages(*config.preferredSubtitleLanguages.toTypedArray())
            .build()
        exo.prepare()
        exo.play()
    }

    override fun retry() {
        reconnectStartedAt = 0L
        reconnectAttempt = 0
        main.removeCallbacks(reconnect)
        formatRetries = 0
        _state.update { it.copy(error = null, reconnecting = false, reconnectAttempt = 0) }
        exo.prepare()
        exo.play()
    }

    override fun pause() = exo.pause()

    override fun resume() = exo.play()

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        exo.volume = if (muted) 0f else volumePercent / 100f
    }

    override fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, 150)
        exo.volume = if (muted) 0f else volumePercent / 100f
        _state.update { it.copy(volume = volumePercent) }
    }

    override fun setSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
        _state.update { it.copy(speed = speed.coerceIn(0.5f, 2f)) }
    }

    override fun setAudioOffset(ms: Long) {
        audioOffsetMs = ms.coerceIn(-10_000L, 10_000L)
        // Media3 applies the offset to the current audio renderer without reopening the media item.
        exo.setAudioAttributes(exo.audioAttributes, true)
        _state.update { it.copy(audioOffsetMs = audioOffsetMs) }
    }

    override fun selectAudioTrack(index: Int) = selectTrack(C.TRACK_TYPE_AUDIO, index)

    override fun selectSubtitleTrack(index: Int) = selectTrack(C.TRACK_TYPE_TEXT, index)

    override fun disableSubtitles() {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    override fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        exo.videoComponent?.setVideoSurface(null)
        _state.update { it.copy(videoEnabled = enabled) }
    }

    override fun setScale(scale: VideoScale) {
        this.scale = scale
        _state.update { it.copy(scale = scale) }
    }

    override fun addSubtitle(url: String, mimeType: String?, offsetMs: Long) {
        current?.let { item ->
            current = item.copy(subtitleUrl = url, subtitleMimeType = mimeType, subtitleOffsetMs = offsetMs)
            exo.setMediaItem(mediaItem(current!!), exo.currentPosition)
            exo.prepare()
            exo.play()
        }
    }

    override fun streamInfo(): StreamInfo? = streamInfoInternal()

    override val position: Long get() = exo.currentPosition.coerceAtLeast(0)
    override val duration: Long get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    override fun seekTo(ms: Long) {
        _state.update { it.copy(ended = false) }
        exo.seekTo(ms)
    }

    override fun stop() {
        main.removeCallbacks(reconnect)
        reconnectStartedAt = 0L
        exo.stop()
        _state.update { it.copy(error = null, buffering = false, reconnecting = false, reconnectAttempt = 0) }
    }

    override fun setVideoView(view: View?) = when (view) {
        is SurfaceView -> if (videoEnabled) exo.setVideoSurfaceView(view) else exo.clearVideoSurface()
        is TextureView -> if (videoEnabled) exo.setVideoTextureView(view) else exo.clearVideoSurface()
        null -> exo.clearVideoSurface()
        else -> error("Unsupported video view: ${view::class.java.name}")
    }

    override fun release() {
        main.removeCallbacks(checkStall)
        main.removeCallbacks(reconnect)
        mediaSession.release()
        exo.release()
    }

    private fun mediaItem(item: PlayItem): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(item.url))
        item.subtitleUrl?.let { subtitle ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle))
                        .setMimeType(item.subtitleMimeType)
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        item.drmLicenseUrl?.let { license ->
            val uuid = if (item.drmScheme.equals("clearkey", true)) C.CLEARKEY_UUID else C.WIDEVINE_UUID
            builder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(uuid).setLicenseUri(license).build())
        }
        return builder.build()
    }

    private fun selectTrack(type: Int, index: Int) {
        val target = tracksOf(type).getOrNull(index) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(target.first.mediaTrackGroup, listOf(target.second)))
            .build()
    }

    private fun tracksOf(type: Int): List<Pair<androidx.media3.common.Tracks.Group, Int>> =
        exo.currentTracks.groups.flatMap { group ->
            if (group.type != type) emptyList() else (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }

    private fun streamInfoInternal(): StreamInfo = StreamInfo(
        videoCodec = exo.videoFormat?.sampleMimeType,
        audioCodec = exo.audioFormat?.sampleMimeType,
        width = exo.videoFormat?.width ?: 0,
        height = exo.videoFormat?.height ?: 0,
        fps = exo.videoFormat?.frameRate?.takeIf { it > 0 } ?: 0f,
        bitrate = exo.videoFormat?.bitrate?.takeIf { it > 0 } ?: 0,
        audioChannels = exo.audioFormat?.channelCount ?: 0,
        hdr = exo.videoFormat?.colorInfo?.colorTransfer?.toString(),
        durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
        bufferedMs = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0),
    )
}

/**
 * Buffering without progress: e.g. an MP4 with its index (moov) at the end, served without Range support,
 * buffers forever and ExoPlayer never reports an error. [stalled] is fed the buffered position while buffering.
 */
internal class StallWatch(private val timeoutMs: Long) {
    private var last = -1L
    private var since = 0L

    fun reset() {
        last = -1
    }

    /** True once [progress] hasn't changed for [timeoutMs]. */
    fun stalled(progress: Long, now: Long): Boolean {
        if (progress != last) {
            last = progress
            since = now
        }
        return now - since >= timeoutMs
    }
}

internal enum class Recovery { ToLiveEdge, AsHls, AsProgressive }

/**
 * What to do about an error before showing it: fell behind the live window → back to the live edge; a link
 * without a known type that didn't parse as a file → once more as HLS (extensionless HLS links). [typed] = the item
 * already has a MIME type (set by us on the HLS retry), so the retry happens only once.
 */
internal fun recovery(errorCode: Int, typed: Boolean, formatRetries: Int = 0): Recovery? = when {
    errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Recovery.ToLiveEdge
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !typed && formatRetries == 0 -> Recovery.AsHls
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && typed && formatRetries == 1 -> Recovery.AsProgressive
    else -> null
}

/** Media3 groups error codes by thousands: 2xxx I/O, 3xxx parsing, 4xxx decoding, 6xxx DRM. */
internal fun errorKind(errorCode: Int): PlaybackError.Kind = when (errorCode / 1000) {
    2 -> PlaybackError.Kind.Network
    3 -> PlaybackError.Kind.Format
    4 -> PlaybackError.Kind.Decoder
    6 -> PlaybackError.Kind.Drm
    else -> PlaybackError.Kind.Other
}
