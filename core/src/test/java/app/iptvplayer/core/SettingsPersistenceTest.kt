package app.iptvplayer.core

import android.content.Context
import androidx.room.Room
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.model.SubtitleBackground
import app.iptvplayer.core.model.SubtitleColor
import app.iptvplayer.core.model.SubtitleFont
import app.iptvplayer.core.model.SubtitlePosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsPersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDb

    @Before fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    @Test fun playerNetworkAndQualitySettingsSurviveRepositoryRecreation() {
        val repo = Repository(context, db)
        repo.settings = repo.settings.copy(
            playerMinBufferSeconds = 15,
            playerMaxBufferSeconds = 90,
            playerPrebufferSeconds = 10,
            playerMaxVideoHeight = 1080,
            playerAudioDelayMs = -75,
            playerSoftwareAudio = true,
            playerSubtitleEmbeddedStyles = false,
            playerSubtitleFont = SubtitleFont.MONO,
            playerSubtitleSize = 150,
            playerSubtitleColor = SubtitleColor.CYAN,
            playerSubtitleBackground = SubtitleBackground.BLACK,
            playerSubtitlePosition = SubtitlePosition.TOP,
            playerNightMode = true,
            playerNormalizeVolume = true,
            playerVerboseLogging = true,
            playerMeasuredMetrics = true,
            playerExternalLive = true,
            playerExternalMovies = true,
            playerExternalSeries = true,
            playerSeekSeconds = 30,
        )

        val restored = Repository(context, db).settings
        assertEquals(15, restored.playerMinBufferSeconds)
        assertEquals(90, restored.playerMaxBufferSeconds)
        assertEquals(10, restored.playerPrebufferSeconds)
        assertEquals(1080, restored.playerMaxVideoHeight)
        assertEquals(-75, restored.playerAudioDelayMs)
        assertEquals(true, restored.playerSoftwareAudio)
        assertEquals(false, restored.playerSubtitleEmbeddedStyles)
        assertEquals(SubtitleFont.MONO, restored.playerSubtitleFont)
        assertEquals(150, restored.playerSubtitleSize)
        assertEquals(SubtitleColor.CYAN, restored.playerSubtitleColor)
        assertEquals(SubtitleBackground.BLACK, restored.playerSubtitleBackground)
        assertEquals(SubtitlePosition.TOP, restored.playerSubtitlePosition)
        assertEquals(true, restored.playerNightMode)
        assertEquals(true, restored.playerNormalizeVolume)
        assertEquals(true, restored.playerVerboseLogging)
        assertEquals(true, restored.playerMeasuredMetrics)
        assertEquals(true, restored.playerExternalLive)
        assertEquals(true, restored.playerExternalMovies)
        assertEquals(true, restored.playerExternalSeries)
        assertEquals(30, restored.playerSeekSeconds)
    }
}
