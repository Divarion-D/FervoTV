package app.iptvplayer

import android.content.Intent
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.ui.externalPlayerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalPlayerTest {
    @Test fun vodIntentCarriesBestEffortPositionAndTitle() {
        val intent = externalPlayerIntent(PlayItem("https://example.test/movie.mp4", live = false), 12_345, "Movie")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.test/movie.mp4", intent.dataString)
        assertEquals("video/*", intent.type)
        assertEquals(12_345L, intent.getLongExtra("position", -1))
        assertEquals("Movie", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun liveIntentDoesNotInventAStartPosition() {
        assertFalse(externalPlayerIntent(PlayItem("https://example.test/live")).hasExtra("position"))
    }
}
