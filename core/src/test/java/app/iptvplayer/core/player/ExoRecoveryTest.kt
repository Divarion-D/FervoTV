package app.iptvplayer.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // Already retried (the HLS retry sets the MIME type): now it's an error.
        assertNull(recovery(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, typed = true))
    }

    @Test
    fun otherErrorsAreShown() {
        assertNull(recovery(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, typed = false))
        assertNull(recovery(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, typed = false))
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
