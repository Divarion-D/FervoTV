package app.iptvplayer.core.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.common.AudioAttributes
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.SubtitleBackground
import app.iptvplayer.core.model.SubtitleColor
import app.iptvplayer.core.model.SubtitleFont
import app.iptvplayer.core.model.SubtitlePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

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
    private val defaultDrm = DefaultDrmSessionManagerProvider()
    private val drm = androidx.media3.exoplayer.drm.DrmSessionManagerProvider { item ->
        val local = item.localConfiguration?.drmConfiguration?.licenseUri?.toString()?.let(::localClearKeyResponse)
        if (local == null) defaultDrm.get(item)
        else DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(local))
    }
    private val audioDelay = AudioDelayProcessor()
    private val audioEffects = AudioEffectsProcessor()
    private val renderers = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(audioDelay, audioEffects)).build()
    }.setExtensionRendererMode(extensionRendererMode(config.preferSoftwareAudio))
        .setEnableDecoderFallback(true)
    private val trackSelector = DefaultTrackSelector(ctx)

    private val exo: ExoPlayer = run {
        ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.bufferForPlaybackMs, config.bufferForPlaybackMs)
                    .build(),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setDrmSessionManagerProvider(drm))
            .build()
    }
    private var previousHandler: (() -> Unit)? = null
    private var nextHandler: (() -> Unit)? = null
    private var workingFormatListener: ((String) -> Unit)? = null
    private val sessionPlayer = object : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands = Player.Commands.Builder()
            .addAll(super.getAvailableCommands())
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .build()

        override fun seekToPrevious() = previousHandler?.invoke() ?: super.seekToPrevious()
        override fun seekToNext() = nextHandler?.invoke() ?: super.seekToNext()
    }
    private val mediaSession = MediaSession.Builder(ctx, sessionPlayer).setId(UUID.randomUUID().toString()).build()
    private val videoDecoder = VideoDecoderInfo()
    private val metrics = StreamMetrics()

    private val main = Handler(Looper.getMainLooper())
    private val stall = StallWatch(Defaults.BUFFER_STALL_TIMEOUT_MS)
    private var formatRetries = 0
    private var softwareAudioRetry = false
    private var disabledHardwareAudioRenderer: Int? = null
    private var current: PlayItem? = null
    private var reconnectAttempt = 0
    private var reconnectStartedAt = 0L
    private var providerWaitMs = 0L
    private var providerRetryAt = 0L
    private var muted = false
    private var volumePercent = config.defaultVolume.coerceIn(0, 150)
    private var audioOffsetMs = 0L
    private var videoEnabled = true
    private var scale = VideoScale.FIT
    private var pendingAudioTrack: Int? = null
    private var pendingSubtitleTrack: Int? = null
    private var subtitleView: SubtitleView? = null
    private val log = PlaybackLog(ctx)

    init {
        exo.addAnalyticsListener(videoDecoder)
        if (config.measuredMetrics) {
            exo.addAnalyticsListener(object : AnalyticsListener {
                override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                    metrics.bandwidth(bitrateEstimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }

                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    metrics.dropped(droppedFrames)
                }
            })
            var frames = 0L
            exo.setVideoFrameMetadataListener { _, _, _, _ ->
                frames++
                metrics.frames(frames, SystemClock.elapsedRealtime())
            }
        }
        exo.setVideoChangeFrameRateStrategy(
            if (config.matchFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
        )
        if (config.maxVideoHeight > 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, config.maxVideoHeight)
                .build()
        }
        setAudioOffset(config.defaultAudioDelayMs.toLong())
        audioEffects.setEffects(volumePercent, config.nightMode, config.normalizeVolume)
    }

    private val reconnect = object : Runnable {
        override fun run() {
            val item = current ?: return
            val now = SystemClock.elapsedRealtime()
            providerRetryAt = 0L
            main.removeCallbacks(reconnectCountdown)
            if (!item.live || (config.giveUpAfterMs > 0L && reconnectElapsedMs(reconnectStartedAt, now, providerWaitMs) >= config.giveUpAfterMs) || reconnectAttempt >= config.reconnectAttempts.coerceAtLeast(1)) {
                _state.update { it.copy(reconnecting = false, buffering = false, reconnectInSeconds = 0, error = PlaybackError(PlaybackError.Kind.Network, "RECONNECT_TIMEOUT")) }
                log.record(item.url, "reconnect-timeout")
                return
            }
            reconnectAttempt++
            _state.update { it.copy(reconnecting = true, buffering = true, reconnectAttempt = reconnectAttempt, reconnectInSeconds = 0, error = null) }
            exo.setMediaItem(mediaItem(item))
            exo.prepare()
            exo.play()
            val delay = (1_000L shl (reconnectAttempt.coerceAtMost(4) - 1)).coerceAtMost(8_000L)
            main.postDelayed(this, delay)
        }
    }

    private val reconnectCountdown = object : Runnable {
        override fun run() {
            val remaining = (providerRetryAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            _state.update { it.copy(reconnecting = true, buffering = true, reconnectInSeconds = ((remaining + 999) / 1_000).toInt(), error = null) }
            if (remaining > 0) main.postDelayed(this, minOf(1_000L, remaining))
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
                    main.removeCallbacks(reconnectCountdown)
                    reconnectAttempt = 0
                    reconnectStartedAt = 0L
                    providerWaitMs = 0L
                    providerRetryAt = 0L
                    if (formatRetries > 0) {
                        workingFormatListener?.invoke(exo.currentMediaItem?.localConfiguration?.mimeType.orEmpty())
                        formatRetries = 0
                    }
                    if (config.verboseLogging) log.record(current?.url, "ready")
                }
                _state.update {
                    it.copy(
                        buffering = state == Player.STATE_BUFFERING,
                        ended = state == Player.STATE_ENDED,
                        reconnecting = state != Player.STATE_READY && it.reconnecting,
                        reconnectAttempt = if (state == Player.STATE_READY) 0 else it.reconnectAttempt,
                        reconnectInSeconds = if (state == Player.STATE_READY) 0 else it.reconnectInSeconds,
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
                    val fps = exo.videoFormat?.frameRate?.takeIf { it > 0 } ?: metrics.fps
                    _state.update { it.copy(aspect = size.width * size.pixelWidthHeightRatio / size.height, height = size.height, fps = fps) }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audio = mutableListOf<AudioTrack>()
                val text = mutableListOf<SubtitleTrack>()
                val video = mutableListOf<VideoTrack>()
                tracks.groups.forEach { group ->
                    if (group.type == C.TRACK_TYPE_AUDIO || group.type == C.TRACK_TYPE_TEXT || group.type == C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            if (!group.isTrackSupported(i)) continue
                            if (group.type == C.TRACK_TYPE_AUDIO) audio += AudioTrack(f.language, f.sampleMimeType, f.channelCount, group.isTrackSelected(i))
                            else if (group.type == C.TRACK_TYPE_TEXT) text += SubtitleTrack(f.language, f.sampleMimeType, group.isTrackSelected(i))
                            else video += VideoTrack(f.width, f.height, f.bitrate, f.sampleMimeType, group.isTrackSelected(i))
                        }
                    }
                }
                _state.update { it.copy(audioTracks = audio, subtitleTracks = text, videoTracks = video, streamInfo = streamInfoInternal()) }
                if (config.verboseLogging) log.record(current?.url, "tracks:a=${audio.size},s=${text.size},v=${video.size}")
                pendingAudioTrack?.takeIf { it in audio.indices }?.let { pendingAudioTrack = null; selectAudioTrack(it) }
                pendingSubtitleTrack?.let {
                    pendingSubtitleTrack = null
                    if (it < 0) disableSubtitles() else if (it in text.indices) selectSubtitleTrack(it)
                }
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                subtitleView?.setCues(styledCues(cueGroup.cues))
            }

            override fun onPlayerError(e: PlaybackException) {
                val item = exo.currentMediaItem
                val playItem = current
                if (shouldRetryWithSoftwareAudio(e.errorCode, softwareAudioRetry, config.preferSoftwareAudio)) {
                    val hardwareAudio = (e as? ExoPlaybackException)?.rendererIndex
                        ?.takeIf { it in 0 until exo.rendererCount && exo.getRenderer(it) is MediaCodecAudioRenderer }
                    if (hardwareAudio != null) {
                        softwareAudioRetry = true
                        disabledHardwareAudioRenderer = hardwareAudio
                        trackSelector.setParameters(trackSelector.parameters.buildUpon().setRendererDisabled(hardwareAudio, true))
                        log.record(playItem?.url, "decoder-init:ffmpeg-retry")
                        exo.prepare()
                        exo.play()
                        return
                    }
                }
                if (playItem?.live == true && errorKind(e.errorCode) == PlaybackError.Kind.Network) {
                    val now = SystemClock.elapsedRealtime()
                    if (reconnectStartedAt == 0L) reconnectStartedAt = now
                    log.record(playItem.url, "network-error:${e.errorCodeName}")
                    main.removeCallbacks(reconnect)
                    main.removeCallbacks(reconnectCountdown)
                    val providerDelay = providerRetryAfterMs(e, System.currentTimeMillis())
                    if (providerDelay == null) main.post(reconnect) else {
                        providerWaitMs += providerDelay
                        providerRetryAt = now + providerDelay
                        log.record(playItem.url, "provider-retry-after:${providerDelay / 1_000}s")
                        reconnectCountdown.run()
                        main.postDelayed(reconnect, providerDelay)
                    }
                    return
                }
                when (recovery(e.errorCode, formatRetries)) {
                    Recovery.ToLiveEdge -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    Recovery.AsHls -> {
                        formatRetries = 2
                        exo.setMediaItem(item!!.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        exo.prepare()
                    }
                    Recovery.AsDash -> {
                        formatRetries = 1
                        exo.setMediaItem(item!!.buildUpon().setMimeType(MimeTypes.APPLICATION_MPD).build())
                        exo.prepare()
                    }
                    Recovery.AsProgressive -> {
                        formatRetries = 3
                        exo.setMediaItem(item!!.buildUpon().setMimeType(null).build())
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
        disabledHardwareAudioRenderer?.let { renderer ->
            trackSelector.setParameters(trackSelector.parameters.buildUpon().setRendererDisabled(renderer, false))
        }
        disabledHardwareAudioRenderer = null
        softwareAudioRetry = false
        current = item
        metrics.reset()
        if (config.verboseLogging) log.record(item.url, "play")
        pendingAudioTrack = item.rememberedAudioTrack
        pendingSubtitleTrack = item.rememberedSubtitleTrack
        http.setUserAgent(item.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: Defaults.USER_AGENT)
        http.setDefaultRequestProperties(item.headers)
        formatRetries = 0
        reconnectAttempt = 0
        reconnectStartedAt = 0L
        providerWaitMs = 0L
        providerRetryAt = 0L
        main.removeCallbacks(reconnect)
        main.removeCallbacks(reconnectCountdown)
        videoEnabled = !config.audioOnly
        volumePercent = config.defaultVolume.coerceIn(0, 150)
        audioEffects.setEffects(volumePercent, config.nightMode, config.normalizeVolume)
        exo.volume = if (muted) 0f else minOf(volumePercent, 100) / 100f
        _state.update { it.copy(error = null, height = 0, fps = 0f, ended = false, reconnecting = false, reconnectAttempt = 0, reconnectInSeconds = 0, videoEnabled = videoEnabled, volume = volumePercent, scale = scale, audioOffsetMs = audioOffsetMs) }
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
        providerWaitMs = 0L
        providerRetryAt = 0L
        main.removeCallbacks(reconnect)
        main.removeCallbacks(reconnectCountdown)
        formatRetries = 0
        _state.update { it.copy(error = null, reconnecting = false, reconnectAttempt = 0) }
        exo.prepare()
        exo.play()
    }

    override fun pause() = exo.pause()

    override fun resume() = exo.play()

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        exo.volume = if (muted) 0f else minOf(volumePercent, 100) / 100f
    }

    override fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, 150)
        audioEffects.setEffects(volumePercent, config.nightMode, config.normalizeVolume)
        exo.volume = if (muted) 0f else minOf(volumePercent, 100) / 100f
        _state.update { it.copy(volume = volumePercent) }
    }

    override fun setSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
        _state.update { it.copy(speed = speed.coerceIn(0.5f, 2f)) }
    }

    override fun setAudioOffset(ms: Long) {
        audioOffsetMs = ms.coerceIn(-500L, 500L)
        audioDelay.setDelayMs(audioOffsetMs)
        _state.update { it.copy(audioOffsetMs = audioOffsetMs) }
    }

    override fun selectAudioTrack(index: Int) = selectTrack(C.TRACK_TYPE_AUDIO, index)

    override fun selectSubtitleTrack(index: Int) = selectTrack(C.TRACK_TYPE_TEXT, index)

    override fun selectVideoTrack(index: Int) = selectTrack(C.TRACK_TYPE_VIDEO, index)

    override fun clearVideoTrackOverride() {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
    }

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

    override fun setMediaNavigationHandlers(previous: (() -> Unit)?, next: (() -> Unit)?) {
        previousHandler = previous
        nextHandler = next
    }

    override fun setWorkingFormatListener(listener: ((String) -> Unit)?) {
        workingFormatListener = listener
    }

    override val position: Long get() = exo.currentPosition.coerceAtLeast(0)
    override val duration: Long get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    override fun seekTo(ms: Long) {
        _state.update { it.copy(ended = false) }
        exo.seekTo(ms)
    }

    override fun stop() {
        main.removeCallbacks(reconnect)
        main.removeCallbacks(reconnectCountdown)
        reconnectStartedAt = 0L
        providerWaitMs = 0L
        providerRetryAt = 0L
        exo.stop()
        _state.update { it.copy(error = null, buffering = false, reconnecting = false, reconnectAttempt = 0, reconnectInSeconds = 0) }
    }

    override fun setVideoView(view: View?) = when (view) {
        is SurfaceView -> if (videoEnabled) exo.setVideoSurfaceView(view) else exo.clearVideoSurface()
        is TextureView -> if (videoEnabled) exo.setVideoTextureView(view) else exo.clearVideoSurface()
        null -> exo.clearVideoSurface()
        else -> error("Unsupported video view: ${view::class.java.name}")
    }

    override fun createSubtitleView(context: Context): View = SubtitleView(context)

    override fun setSubtitleView(view: View?) {
        subtitleView = when (view) {
            is SubtitleView -> view.apply {
                setApplyEmbeddedStyles(config.subtitleEmbeddedStyles)
                setApplyEmbeddedFontSizes(config.subtitleEmbeddedStyles)
                setStyle(
                    CaptionStyleCompat(
                        when (config.subtitleColor) {
                            SubtitleColor.WHITE -> Color.WHITE
                            SubtitleColor.YELLOW -> Color.YELLOW
                            SubtitleColor.CYAN -> Color.CYAN
                        },
                        when (config.subtitleBackground) {
                            SubtitleBackground.NONE -> Color.TRANSPARENT
                            SubtitleBackground.BLACK -> Color.BLACK
                            SubtitleBackground.TRANSLUCENT -> 0x99000000.toInt()
                        },
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        Color.BLACK,
                        when (config.subtitleFont) {
                            SubtitleFont.SANS -> Typeface.SANS_SERIF
                            SubtitleFont.SERIF -> Typeface.SERIF
                            SubtitleFont.MONO -> Typeface.MONOSPACE
                        },
                    ),
                )
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * config.subtitleSize.coerceIn(50, 200) / 100f)
                setCues(styledCues(exo.currentCues.cues))
            }
            null -> null
            else -> error("Unsupported subtitle view: ${view::class.java.name}")
        }
    }

    private fun styledCues(cues: List<Cue>): List<Cue> {
        if (config.subtitleEmbeddedStyles || config.subtitlePosition == SubtitlePosition.BOTTOM) return cues
        val (line, anchor) = when (config.subtitlePosition) {
            SubtitlePosition.TOP -> 0.05f to Cue.ANCHOR_TYPE_START
            SubtitlePosition.CENTER -> 0.5f to Cue.ANCHOR_TYPE_MIDDLE
            SubtitlePosition.BOTTOM -> return cues
        }
        return cues.map { it.buildUpon().setLine(line, Cue.LINE_TYPE_FRACTION).setLineAnchor(anchor).build() }
    }

    override fun release() {
        main.removeCallbacks(checkStall)
        main.removeCallbacks(reconnect)
        main.removeCallbacks(reconnectCountdown)
        mediaSession.release()
        exo.release()
    }

    private fun mediaItem(item: PlayItem): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(item.url))
        item.rememberedFormat?.let { builder.setMimeType(it.ifEmpty { null }) }
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
        fps = exo.videoFormat?.frameRate?.takeIf { it > 0 } ?: metrics.fps,
        bitrate = exo.videoFormat?.bitrate?.takeIf { it > 0 } ?: 0,
        audioChannels = exo.audioFormat?.channelCount ?: 0,
        hdr = exo.videoFormat.dynamicRange(),
        durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
        bufferedMs = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0),
        bitDepth = exo.videoFormat.colorBitDepth(),
        videoDecoder = videoDecoder.name,
        measuredBitrate = metrics.bitrate,
        droppedFrames = if (config.measuredMetrics) metrics.droppedFrames else -1,
    )
}

internal fun reconnectElapsedMs(startedAt: Long, now: Long, providerWaitMs: Long): Long =
    (now - startedAt - providerWaitMs).coerceAtLeast(0)

internal fun providerRetryAfterMs(statusCode: Int, headers: Map<String, List<String>>, nowEpochMs: Long): Long? {
    if (statusCode != 429) return null
    val value = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val delay = value.toLongOrNull()?.let { it * 1_000L } ?: runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse(value)?.time?.minus(nowEpochMs)
    }.getOrNull() ?: return null
    return delay.coerceIn(0L, 86_400_000L)
}

@OptIn(UnstableApi::class)
private fun providerRetryAfterMs(error: Throwable, nowEpochMs: Long): Long? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return providerRetryAfterMs(cause.responseCode, cause.headerFields, nowEpochMs)
        }
        cause = cause.cause
    }
    return null
}

/** Small cumulative sampler shared by actual bitrate, dropped frames and stable measured FPS. */
internal class StreamMetrics {
    var bitrate = 0; private set
    var droppedFrames = 0; private set
    var fps = 0f; private set
    private var lastFrames = 0L
    private var lastMs = 0L
    private var candidate = 0
    private var candidateCount = 0

    fun bandwidth(value: Int) { if (value > 0) bitrate = value }
    fun dropped(value: Int) { if (value > 0) droppedFrames += value }

    fun frames(totalFrames: Long, elapsedMs: Long) {
        if (elapsedMs <= 0 || totalFrames < lastFrames) return
        if (lastMs == 0L) { lastFrames = totalFrames; lastMs = elapsedMs; return }
        val duration = elapsedMs - lastMs
        if (duration < 900) return
        val sample = (((totalFrames - lastFrames) * 1_000f) / duration).toInt()
        lastFrames = totalFrames
        lastMs = elapsedMs
        if (sample <= 0) return
        if (sample == candidate) candidateCount++ else { candidate = sample; candidateCount = 1 }
        if (candidateCount >= 2) fps = candidate.toFloat()
    }

    fun reset() {
        bitrate = 0; droppedFrames = 0; fps = 0f
        lastFrames = 0; lastMs = 0; candidate = 0; candidateCount = 0
    }
}

internal fun localClearKeyResponse(uri: String): ByteArray? {
    val prefix = "data:application/json,"
    if (!uri.startsWith(prefix)) return null
    return java.net.URLDecoder.decode(uri.removePrefix(prefix), "UTF-8").toByteArray(Charsets.UTF_8)
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

internal enum class Recovery { ToLiveEdge, AsDash, AsHls, AsProgressive }

/** Hardware first by default; PLR-012's switch makes the same automatic FFmpeg fallback preferred. */
@OptIn(UnstableApi::class)
internal fun extensionRendererMode(preferSoftwareAudio: Boolean): Int =
    if (preferSoftwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON

internal fun shouldRetryWithSoftwareAudio(errorCode: Int, alreadyTried: Boolean, preferSoftwareAudio: Boolean): Boolean =
    errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && !alreadyTried && !preferSoftwareAudio

/**
 * What to do before showing a format error: try each explicit format once and keep the chain finite.
 */
internal fun recovery(errorCode: Int, formatRetries: Int = 0): Recovery? {
    if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return Recovery.ToLiveEdge
    if (errorCode != PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED &&
        errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    ) return null
    return when (formatRetries) {
        0 -> Recovery.AsDash
        1 -> Recovery.AsHls
        2 -> Recovery.AsProgressive
        else -> null
    }
}

/** Media3 groups error codes by thousands: 2xxx I/O, 3xxx parsing, 4xxx decoding, 6xxx DRM. */
internal fun errorKind(errorCode: Int): PlaybackError.Kind = when (errorCode / 1000) {
    2 -> PlaybackError.Kind.Network
    3 -> PlaybackError.Kind.Format
    4 -> PlaybackError.Kind.Decoder
    6 -> PlaybackError.Kind.Drm
    else -> PlaybackError.Kind.Other
}
