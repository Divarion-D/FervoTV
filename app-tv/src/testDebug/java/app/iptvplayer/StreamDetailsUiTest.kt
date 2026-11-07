package app.iptvplayer

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlaybackEngine
import app.iptvplayer.core.player.PlaybackState
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.player.StreamInfo
import app.iptvplayer.core.player.VideoTrack
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.PlayerTools
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-port")
class StreamDetailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val engine = InfoEngine()

    private fun openDetails(info: StreamInfo) {
        engine.info = info
        val player = PlayerSession { engine }
        compose.setContent { AppTheme { Box(Modifier.fillMaxSize()) { PlayerTools(player) } } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more))
            .performTouchInput { click() }
        val title = compose.activity.getString(R.string.player_stream_info)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        // shortcut: host menu activation uses semantics; validate device pointer hit testing in QA-UI-001.
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun visible(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test fun protectedStreamHasNoExternalPlayerAction() {
        val player = PlayerSession { engine }
        player.open(PlayItem("https://example.invalid/protected.mpd", drmLicenseUrl = "https://license.invalid"))
        compose.setContent { AppTheme { Box(Modifier.fillMaxSize()) { PlayerTools(player) } } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.player_external)).assertDoesNotExist()
    }

    @Test fun qualityActionIsAvailableInThePlayerMenu() {
        engine.state.value = PlaybackState(videoTracks = listOf(
            VideoTrack(1280, 720, 2_000_000, "video/avc", true),
            VideoTrack(1920, 1080, 4_000_000, "video/avc", false),
        ))
        val player = PlayerSession { engine }
        compose.setContent { AppTheme { Box(Modifier.fillMaxSize()) { PlayerTools(player) } } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.player_quality)).assertIsDisplayed()
    }

    @Test fun streamColorAndActualDecoderAreShown() {
        openDetails(StreamInfo(hdr = "HDR (PQ)", bitDepth = 10, videoDecoder = "c2.vendor.hevc.decoder"))
        visible("Dynamic range (stream): HDR (PQ)")
        visible("Colour depth: 10 bits")
        visible("Video decoder: c2.vendor.hevc.decoder")
    }

    @Test fun measuredBitrateFpsAndDroppedFramesAreShown() {
        openDetails(StreamInfo(fps = 50f, measuredBitrate = 8_000_000, droppedFrames = 7))
        visible("Frame rate: 50.0 fps")
        visible("Actual bitrate: 8000 kbps")
        visible("Dropped frames: 7")
    }

    @Test fun missingMetadataIsNotReplacedWithSdrOrEightBits() {
        openDetails(StreamInfo())
        visible("Dynamic range (stream): Unknown")
        visible("Colour depth: Unknown")
        visible("Video decoder: Unknown")
    }

    @Test fun metadataRefreshesWhileTheDialogIsOpen() {
        openDetails(StreamInfo(hdr = "SDR", bitDepth = 8, videoDecoder = "first.decoder"))
        visible("Video decoder: first.decoder")
        compose.runOnUiThread {
            engine.info = StreamInfo(hdr = "HDR (HLG)", bitDepth = 10, videoDecoder = "second.decoder")
        }
        compose.mainClock.advanceTimeBy(1_100)
        visible("Dynamic range (stream): HDR (HLG)")
        visible("Colour depth: 10 bits")
        visible("Video decoder: second.decoder")
    }

    @Test
    @Config(qualifiers = "en-land")
    fun landscapeCanScrollToTheDecoder() {
        openDetails(StreamInfo(hdr = "Dolby Vision", bitDepth = 12, videoDecoder = "c2.vendor.dv.decoder"))
        visible("Colour depth: 12 bits")
        visible("Video decoder: c2.vendor.dv.decoder")
    }

    @Test
    @Config(qualifiers = "ru-port")
    fun russianDiagnosticsAreLocalized() {
        openDetails(StreamInfo(hdr = "HDR (PQ)", bitDepth = 10, videoDecoder = "c2.vendor.hevc.decoder"))
        visible("Динамический диапазон (поток): HDR (PQ)")
        visible("Глубина цвета: 10 бит")
        visible("Видеодекодер: c2.vendor.hevc.decoder")
    }

    private class InfoEngine : PlaybackEngine {
        var info = StreamInfo()
        override val state = MutableStateFlow(PlaybackState())
        override val position = 0L
        override val duration = 0L
        override fun streamInfo() = info
        override fun play(item: PlayItem) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun retry() = Unit
        override fun stop() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideoView(view: View?) = Unit
        override fun release() = Unit
    }
}
