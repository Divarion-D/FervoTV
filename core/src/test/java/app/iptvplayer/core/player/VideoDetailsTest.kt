package app.iptvplayer.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDetailsTest {
    private fun format(transfer: Int = Format.NO_VALUE, luma: Int = Format.NO_VALUE, chroma: Int = luma): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setColorInfo(
            ColorInfo.Builder().setColorTransfer(transfer).setLumaBitdepth(luma).setChromaBitdepth(chroma).build(),
        ).build()

    @Test fun hdrTransfersHaveReadableNames() {
        assertEquals("HDR (PQ)", format(C.COLOR_TRANSFER_ST2084).dynamicRange())
        assertEquals("HDR (HLG)", format(C.COLOR_TRANSFER_HLG).dynamicRange())
    }

    @Test fun onlyExplicitSdrMetadataIsCalledSdr() {
        assertEquals("SDR", format(C.COLOR_TRANSFER_SDR).dynamicRange())
        assertNull(format().dynamicRange())
        assertNull(format(999).dynamicRange())
        assertNull(Format.Builder().build().dynamicRange())
        assertNull((null as Format?).dynamicRange())
    }

    @Test fun dolbyVisionIsNotReducedToItsBaseLayerTransfer() {
        val dolby = format(C.COLOR_TRANSFER_ST2084).buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()
        assertEquals("Dolby Vision", dolby.dynamicRange())
    }

    @Test fun bitDepthComesFromMatchingComponentMetadata() {
        assertEquals(8, format(luma = 8).colorBitDepth())
        assertEquals(10, format(luma = 10).colorBitDepth())
        assertEquals(12, format(luma = 12).colorBitDepth())
    }

    @Test fun hdrDoesNotInventAnUnreportedBitDepth() {
        assertEquals(0, format(C.COLOR_TRANSFER_ST2084).colorBitDepth())
        assertEquals(0, Format.Builder().build().colorBitDepth())
        assertEquals(0, (null as Format?).colorBitDepth())
    }

    @Test fun mismatchedOrInvalidComponentDepthIsUnknown() {
        assertEquals(0, format(luma = 10, chroma = 8).colorBitDepth())
        assertEquals(0, format(luma = 0).colorBitDepth())
    }

    private val event = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)

    @Test fun decoderIsUnknownBeforeInitialization() {
        assertNull(VideoDecoderInfo().name)
    }

    @Test fun decoderInitializationAndReleaseAreReflected() {
        val info = VideoDecoderInfo()
        info.onVideoDecoderInitialized(event, "c2.vendor.hevc.decoder", 0, 0)
        assertEquals("c2.vendor.hevc.decoder", info.name)
        info.onVideoDecoderReleased(event, "c2.vendor.hevc.decoder")
        assertNull(info.name)
    }

    @Test fun releasingAnOldDecoderDoesNotClearTheNewOne() {
        val info = VideoDecoderInfo()
        info.onVideoDecoderInitialized(event, "old.decoder", 0, 0)
        info.onVideoDecoderInitialized(event, "new.decoder", 0, 0)
        info.onVideoDecoderReleased(event, "old.decoder")
        assertEquals("new.decoder", info.name)
    }

    @Test fun disablingVideoClearsTheDecoder() {
        val info = VideoDecoderInfo()
        info.onVideoDecoderInitialized(event, "c2.android.avc.decoder", 0, 0)
        info.onVideoDisabled(event, DecoderCounters())
        assertNull(info.name)
    }
}
