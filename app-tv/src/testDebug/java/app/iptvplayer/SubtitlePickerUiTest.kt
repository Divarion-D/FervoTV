package app.iptvplayer

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import app.iptvplayer.core.player.PlayItem
import app.iptvplayer.core.player.PlaybackEngine
import app.iptvplayer.core.player.PlaybackState
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.PlayerTools
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-port")
class SubtitlePickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val engine = SubtitleEngine()
    private val uri = Uri.parse("content://subtitle-test/document/42")

    private fun selectFile(mime: String, name: String, supported: Boolean = true) {
        ShadowContentResolver.registerProviderInternal("subtitle-test", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = mime
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        })
        val player = PlayerSession { engine }
        compose.setContent { AppTheme { PlayerTools(player) } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.section_more)).performTouchInput { click() }
        val title = compose.activity.getString(R.string.player_subtitle_file)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        // shortcut: host sheet activation uses semantics; physical DPAD acceptance is separate.
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread {
            val launch = shadowOf(compose.activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            compose.activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
        compose.waitForIdle()
        assertEquals(if (supported) uri.toString() else null, engine.subtitle?.first)
    }

    @Test fun srtIsNotPassedToTheWebVttParser() {
        selectFile("application/x-subrip", "captions.srt")
        assertEquals("application/x-subrip", engine.subtitle?.second)
    }

    @Test fun genericDocumentMimeUsesTheDisplayNameNotTheOpaqueUri() {
        selectFile("text/plain", "CAPTIONS.SRT")
        assertEquals("application/x-subrip", engine.subtitle?.second)
    }

    @Test fun webVttKeepsItsOwnMimeType() {
        selectFile("text/vtt", "captions.vtt")
        assertEquals("text/vtt", engine.subtitle?.second)
    }

    @Test fun genericWebVttDocumentUsesItsDisplayName() {
        selectFile("application/octet-stream", "CAPTIONS.VTT")
        assertEquals("text/vtt", engine.subtitle?.second)
    }

    @Test fun unknownDocumentDoesNotRestartPlaybackAsWebVtt() {
        selectFile("text/plain", "unknown.txt", supported = false)
        assertEquals(null, engine.subtitle)
    }

    private class SubtitleEngine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackState())
        override val position = 12_000L
        override val duration = 60_000L
        var subtitle: Pair<String, String?>? = null
        override fun addSubtitle(url: String, mimeType: String?, offsetMs: Long) { subtitle = url to mimeType }
        override fun play(item: PlayItem) = Unit
        override fun retry() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun seekTo(ms: Long) = Unit
        override fun setVideoView(view: View?) = Unit
        override fun release() = Unit
    }
}
