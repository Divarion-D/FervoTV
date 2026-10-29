package app.iptvplayer.core.input

import android.view.KeyEvent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyActionTest {
    @Test
    fun channelKeys() {
        for (k in listOf(KEYCODE_CHANNEL_UP, KEYCODE_PAGE_UP, KEYCODE_MEDIA_NEXT)) assertEquals(Action.ChannelUp, keyAction(k))
        for (k in listOf(KEYCODE_CHANNEL_DOWN, KEYCODE_PAGE_DOWN, KEYCODE_MEDIA_PREVIOUS)) assertEquals(Action.ChannelDown, keyAction(k))
    }

    @Test
    fun otherKeys() {
        assertEquals(Action.Ok, keyAction(KEYCODE_DPAD_CENTER))
        assertEquals(Action.Ok, keyAction(KEYCODE_ENTER))
        assertEquals(Action.PlayPause, keyAction(KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(Action.Menu, keyAction(KEYCODE_INFO))
        assertEquals(Action.Up, keyAction(KEYCODE_DPAD_UP))
        assertNull(keyAction(KEYCODE_BACK)) // Back goes through BackHandler
        assertNull(keyAction(KEYCODE_A))
        assertEquals(Action.Recall, keyAction(KEYCODE_LAST_CHANNEL))
    }

    @Test
    fun digits() {
        assertEquals(0, keyDigit(KEYCODE_0))
        assertEquals(9, keyDigit(KEYCODE_9))
        assertEquals(7, keyDigit(KEYCODE_NUMPAD_7))
        assertNull(keyDigit(KEYCODE_A))
        assertNull(keyAction(KEYCODE_5)) // digits are not commands
    }
}
