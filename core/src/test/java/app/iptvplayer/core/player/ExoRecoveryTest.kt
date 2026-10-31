package app.iptvplayer.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoRecoveryTest {
    @Test
    fun behindLiveWindowGoesBackToLive() {
        assertEquals(Recovery.ToLiveEdge, recovery(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, typed = false))
        assertEquals(Recovery.ToLiveEdge, recovery(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, typed = true))
    }

    @Test
    fun untypedLinkIsRetriedAsHlsOnce() {
        assertEquals(Recovery.AsHls, recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = false))
        assertEquals(Recovery.AsProgressive, recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = true, formatRetries = 1))
        // Both format variants have failed: do not loop forever.
        assertNull(recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = false, formatRetries = 2))
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
}
