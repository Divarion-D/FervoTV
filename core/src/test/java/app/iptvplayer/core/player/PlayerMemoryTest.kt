package app.iptvplayer.core.player

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerMemoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val memory = PlayerMemory(context)

    @Test fun valuesAreSeparatedCountedAndResetWithoutStoringTheItemKey() {
        memory.put("channel|account|42", live = true, PlayerMemory.Kind.SCALE, VideoScale.FILL.name)
        memory.put("channel|account|42", live = true, PlayerMemory.Kind.VOLUME, "125")
        memory.put("movie|account|9", live = false, PlayerMemory.Kind.ENGINE, "alternative")
        memory.put("channel|account|7", live = true, PlayerMemory.Kind.ENGINE, "alternative")
        memory.put("movie|account|9", live = false, PlayerMemory.Kind.AUDIO_TRACK, "en")

        assertEquals(VideoScale.FILL.name, memory.get("channel|account|42", live = true, PlayerMemory.Kind.SCALE))
        assertEquals(PlayerMemory.Counts(engine = 2, liveEngine = 1, scale = 1, volume = 1, audioTrack = 1), memory.counts())
        assertFalse(context.getSharedPreferences("player_memory", Context.MODE_PRIVATE).all.keys.any { "account" in it })

        memory.reset(PlayerMemory.Kind.ENGINE, liveOnly = true)
        assertEquals("alternative", memory.get("movie|account|9", live = false, PlayerMemory.Kind.ENGINE))
        memory.reset(PlayerMemory.Kind.ENGINE)
        assertNull(memory.get("movie|account|9", live = false, PlayerMemory.Kind.ENGINE))
        assertTrue(memory.counts().engine == 0)
    }
}
