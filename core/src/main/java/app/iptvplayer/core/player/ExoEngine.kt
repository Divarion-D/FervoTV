package app.iptvplayer.core.player

import android.content.Context
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
class ExoEngine(ctx: Context) : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val exo: ExoPlayer = run {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Defaults.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build()
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) =
                _state.update { it.copy(buffering = state == Player.STATE_BUFFERING) }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                _state.update { it.copy(paused = !playWhenReady) }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    _state.update { it.copy(aspect = size.width * size.pixelWidthHeightRatio / size.height) }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val item = exo.currentMediaItem
                when (recovery(e.errorCode, item?.localConfiguration?.mimeType != null)) {
                    Recovery.ToLiveEdge -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    Recovery.AsHls -> {
                        exo.setMediaItem(item!!.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        exo.prepare()
                    }
                    null -> _state.update {
                        it.copy(error = PlaybackError(errorKind(e.errorCode), e.errorCodeName.removePrefix("ERROR_CODE_")))
                    }
                }
            }
        })
    }

    override fun play(item: PlayItem) {
        _state.update { it.copy(error = null) }
        exo.setMediaItem(MediaItem.fromUri(item.url))
        exo.prepare()
        exo.play()
    }

    override fun retry() {
        _state.update { it.copy(error = null) }
        exo.prepare()
        exo.play()
    }

    override fun pause() = exo.pause()

    override fun resume() = exo.play()

    override fun stop() {
        exo.stop()
        _state.update { it.copy(error = null, buffering = false) }
    }

    override fun setVideoView(view: SurfaceView?) =
        if (view != null) exo.setVideoSurfaceView(view) else exo.clearVideoSurface()

    override fun release() = exo.release()
}

internal enum class Recovery { ToLiveEdge, AsHls }

/**
 * What to do about an error before showing it: fell behind the live window → back to the live edge; a link
 * without a known type that didn't parse as a file → once more as HLS (extensionless HLS links). [typed] = the item
 * already has a MIME type (set by us on the HLS retry), so the retry happens only once.
 */
internal fun recovery(errorCode: Int, typed: Boolean): Recovery? = when {
    errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Recovery.ToLiveEdge
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !typed -> Recovery.AsHls
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
