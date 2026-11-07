package app.iptvplayer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.player.PlayerMemory
import app.iptvplayer.core.player.VideoScale
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-land-television")
class PlayerSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var settings by mutableStateOf(Settings())
    private val repositoryUrl = "https://github.com/Divarion-D/FervoTV"
    private val playerMemory by lazy { PlayerMemory(compose.activity) }

    private fun repositoryIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(repositoryUrl))

    private fun openGroup(title: Int) {
        compose.setContent {
            AppTheme {
                SettingsScreen(
                    settings, "", "", "", false, { settings = it }, {}, {},
                    "", false, false, null, {}, {}, {}, {}, {}, playerMemory, {},
                )
            }
        }
        click(title)
    }

    private fun click(title: Int) = compose.onNodeWithText(compose.activity.getString(title))
        .performScrollTo().performTouchInput { click() }

    @Test fun playerGroupShowsAndChangesBufferSettings() {
        openGroup(R.string.settings_player)
        val initial = settings.playerPrebufferSeconds
        click(R.string.settings_player_prebuffer)
        assertEquals(initial + 2, settings.playerPrebufferSeconds)
        click(R.string.settings_player_seek_step)
        assertEquals(15, settings.playerSeekSeconds)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_player_audio_languages)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_resume)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_auto_next)).assertDoesNotExist()
    }

    @Test fun playerGroupShowsCountAndConfirmsScaleReset() {
        playerMemory.reset(PlayerMemory.Kind.SCALE)
        playerMemory.put("movie-42", live = false, PlayerMemory.Kind.SCALE, VideoScale.FILL.name)
        openGroup(R.string.settings_player)

        compose.onNodeWithText(compose.activity.getString(R.string.settings_player_reset_count, 1)).performScrollTo().assertIsDisplayed()
        click(R.string.settings_player_reset_scale)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_player_reset_confirm_action)).performClick()

        assertNull(playerMemory.get("movie-42", live = false, PlayerMemory.Kind.SCALE))
    }

    @Test fun subtitleAppearanceHasLiveExampleAndDisablesFileStyle() {
        openGroup(R.string.settings_player)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_subtitle_example)).performScrollTo().assertIsDisplayed()
        click(R.string.settings_subtitle_file_style)
        assertEquals(false, settings.playerSubtitleEmbeddedStyles)
        click(R.string.settings_subtitle_size)
        assertEquals(125, settings.playerSubtitleSize)
    }

    @Test fun audioEffectsAreOffByDefaultAndCanBeEnabled() {
        openGroup(R.string.settings_player)
        assertEquals(false, settings.playerNightMode)
        assertEquals(false, settings.playerNormalizeVolume)
        click(R.string.settings_player_night_mode)
        click(R.string.settings_player_normalize)
        assertEquals(true, settings.playerNightMode)
        assertEquals(true, settings.playerNormalizeVolume)
    }

    @Test fun diagnosticCollectionIsOffByDefaultAndCanBeEnabled() {
        openGroup(R.string.settings_player)
        assertEquals(false, settings.playerVerboseLogging)
        assertEquals(false, settings.playerMeasuredMetrics)
        click(R.string.settings_player_verbose_log)
        click(R.string.settings_player_measured_metrics)
        assertEquals(true, settings.playerVerboseLogging)
        assertEquals(true, settings.playerMeasuredMetrics)
    }

    @Test fun externalPlayerCanBeSelectedSeparatelyForEachContentType() {
        openGroup(R.string.settings_player)
        assertEquals(false, settings.playerExternalLive)
        assertEquals(false, settings.playerExternalMovies)
        assertEquals(false, settings.playerExternalSeries)
        click(R.string.settings_player_external_live)
        click(R.string.settings_player_external_movies)
        click(R.string.settings_player_external_series)
        assertEquals(true, settings.playerExternalLive)
        assertEquals(true, settings.playerExternalMovies)
        assertEquals(true, settings.playerExternalSeries)
    }

    @Test fun libraryKeepsResumeAndAutoNextWithoutPlayerSettings() {
        openGroup(R.string.settings_library)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_resume)).assertIsDisplayed()
        click(R.string.settings_auto_next)
        assertEquals(false, settings.autoNext)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_player_prebuffer)).assertDoesNotExist()
    }

    private fun checkRepositoryWithoutBrowser() {
        shadowOf(compose.activity.application).checkActivities(true)
        shadowOf(compose.activity.packageManager).setResolveInfosForIntent(repositoryIntent(), emptyList())
        assertNull(repositoryIntent().resolveActivity(compose.activity.packageManager))
        openGroup(R.string.settings_about)
        compose.onNodeWithText(repositoryUrl).performScrollTo().assertIsDisplayed()
        click(R.string.settings_github)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(repositoryUrl).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [25, 34], qualifiers = "en-land-television")
    fun repositoryLinkStaysVisibleWithoutBrowserOrQrOnTv() {
        checkRepositoryWithoutBrowser()
    }

    @Test
    @Config(qualifiers = "en-port")
    fun repositoryLinkStaysVisibleWithoutBrowserOrQrOnPhone() {
        checkRepositoryWithoutBrowser()
    }

    @Test fun repositoryLinkOpensTheBrowserWhenAvailable() {
        shadowOf(compose.activity.application).checkActivities(true)
        shadowOf(compose.activity.packageManager).addResolveInfoForIntent(repositoryIntent(), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.browser"; name = "test.browser.Main" }
        })
        openGroup(R.string.settings_about)
        compose.onNodeWithText(repositoryUrl).performScrollTo().assertIsDisplayed()
        click(R.string.settings_github)
        val launched = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(repositoryUrl, launched.dataString)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun aboutUsesPackageVersionAndShowsAuthorAndLicenses() {
        @Suppress("DEPRECATION")
        val info = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0).apply {
            versionName = "9.8.7"
            versionCode = 987
            applicationInfo!!.flags = applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        }
        shadowOf(compose.activity.packageManager).installPackage(info)
        openGroup(R.string.settings_about)
        compose.onNodeWithText("9.8.7 · build 987 · debug").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Divarion-D").performScrollTo().assertIsDisplayed()
        click(R.string.settings_licenses)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_licenses)).assertIsDisplayed()
        compose.onNodeWithText("Android Jetpack: Compose", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("База каналов и логотипов iptv-org", substring = true).performScrollTo().assertIsDisplayed()
    }
}
