package app.iptvplayer.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoRecoveryTest {
    @Test fun retryAfterIsUsedOnlyForConnectionLimitResponses() {
        assertEquals(12_000L, providerRetryAfterMs(429, mapOf("Retry-After" to listOf("12")), nowEpochMs = 0))
        assertEquals(3_000L, providerRetryAfterMs(429, mapOf("retry-after" to listOf("3")), nowEpochMs = 0))
        assertNull(providerRetryAfterMs(503, mapOf("Retry-After" to listOf("12")), nowEpochMs = 0))
        assertNull(providerRetryAfterMs(429, mapOf("Retry-After" to listOf("invalid")), nowEpochMs = 0))
    }

    @Test fun providerWaitDoesNotConsumeGiveUpWindow() {
        assertEquals(25_000L, reconnectElapsedMs(startedAt = 10_000, now = 40_000, providerWaitMs = 5_000))
        assertEquals(0L, reconnectElapsedMs(startedAt = 10_000, now = 5_000, providerWaitMs = 0))
    }

    @Test
    fun behindLiveWindowGoesBackToLive() {
        assertEquals(Recovery.ToLiveEdge, recovery(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, typed = false))
        assertEquals(Recovery.ToLiveEdge, recovery(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, typed = true))
    }

    @Test
    fun untypedLinkIsRetriedAsHlsOnce() {
        assertEquals(Recovery.AsHls, recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = false))
        assertEquals(Recovery.AsProgressive, recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = true, formatRetries = 2))
        // Both format variants have failed: do not loop forever.
        assertNull(recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = false, formatRetries = 3))
    }

    @Test fun malformedUntypedManifestTriesDashThenHls() {
        assertEquals(Recovery.AsDash, recovery(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, typed = false))
        assertEquals(Recovery.AsHls, recovery(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, typed = true, formatRetries = 1))
        assertNull(recovery(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, typed = true, formatRetries = 2))
    }

    @Test
    fun otherErrorsAreShown() {
        assertNull(recovery(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, typed = false))
        assertNull(recovery(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, typed = false))
    }

    @Test
    fun bufferingWithoutProgressStalls() {
        val w = StallWatch(timeoutMs = 20_000)
        assertFalse(w.stalled(0, now = 1_000))
        assertFalse(w.stalled(0, now = 20_999))
        assertTrue(w.stalled(0, now = 21_000))
        // Progress restarts the clock.
        assertFalse(w.stalled(500, now = 22_000))
        assertFalse(w.stalled(500, now = 41_999))
        assertTrue(w.stalled(500, now = 42_000))
        // A new buffering period starts from scratch, even at the same position.
        w.reset()
        assertFalse(w.stalled(500, now = 50_000))
    }

    @Test
    fun errorKinds() {
        assertEquals(PlaybackError.Kind.Network, errorKind(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlaybackError.Kind.Network, errorKind(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(PlaybackError.Kind.Format, errorKind(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackError.Kind.Decoder, errorKind(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackError.Kind.Drm, errorKind(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
        assertEquals(PlaybackError.Kind.Other, errorKind(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun measuredStreamMetricsRequireTwoMatchingFpsSamplesAndAccumulateDrops() {
        val metrics = StreamMetrics()
        metrics.bandwidth(8_000_000)
        metrics.frames(50, elapsedMs = 1_000)
        assertEquals(0f, metrics.fps)
        metrics.frames(100, elapsedMs = 2_000)
        assertEquals(0f, metrics.fps)
        metrics.frames(150, elapsedMs = 3_000)
        assertEquals(50f, metrics.fps)
        metrics.frames(198, elapsedMs = 4_000)
        assertEquals(50f, metrics.fps) // one different sample cannot switch the display mode
        metrics.frames(246, elapsedMs = 5_000)
        assertEquals(48f, metrics.fps)
        metrics.dropped(3)
        metrics.dropped(2)
        assertEquals(8_000_000, metrics.bitrate)
        assertEquals(5, metrics.droppedFrames)
    }

    @Test fun invalidMeasuredSamplesAreIgnoredAndResetStartsFresh() {
        val metrics = StreamMetrics()
        metrics.bandwidth(-1)
        metrics.frames(1, 0)
        metrics.dropped(-2)
        assertEquals(0, metrics.bitrate)
        assertEquals(0f, metrics.fps)
        assertEquals(0, metrics.droppedFrames)
        metrics.bandwidth(123)
        metrics.dropped(4)
        metrics.reset()
        assertEquals(0, metrics.bitrate)
        assertEquals(0, metrics.droppedFrames)
    }
}
