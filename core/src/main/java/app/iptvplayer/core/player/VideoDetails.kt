package app.iptvplayer.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Describes the input stream, not the display's HDR capability or tone-mapped output. */
@OptIn(UnstableApi::class)
internal fun Format?.dynamicRange(): String? = when {
    this?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    else -> when (this?.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_SDR -> "SDR"
        C.COLOR_TRANSFER_ST2084 -> "HDR (PQ)"
        C.COLOR_TRANSFER_HLG -> "HDR (HLG)"
        else -> null
    }
}

@OptIn(UnstableApi::class)
internal fun Format?.colorBitDepth(): Int = this?.colorInfo?.let {
    // A single depth must describe both components; missing/conflicting metadata is not a guess at 8/10 bits.
    it.lumaBitdepth.takeIf { depth -> depth > 0 && depth == it.chromaBitdepth }
} ?: 0

@OptIn(UnstableApi::class)
internal class VideoDecoderInfo : AnalyticsListener {
    var name: String? = null
        private set

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        name = decoderName
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (name == decoderName) name = null
    }

    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        name = null
    }
}
