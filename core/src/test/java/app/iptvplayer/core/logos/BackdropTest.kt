package app.iptvplayer.core.logos

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropTest {
    private fun fill(argb: Int) = IntArray(4 * 4) { argb }

    @Test
    fun picksByPixels() {
        assertEquals(Backdrop.Tinted(null), pickBackdrop(fill(0), 4)) // fully transparent
        assertEquals(Backdrop.Solid(0xFF123456.toInt()), pickBackdrop(fill(0xFF123456.toInt()), 4)) // opaque: edge colour
        // Black ink on transparency (centre only) needs a light plate; bright yellow gets a yellow tint.
        val ink = IntArray(16).also { it[5] = 0xFF000000.toInt() }
        assertEquals(Backdrop.Light, pickBackdrop(ink, 4))
        val yellow = IntArray(16).also { it[5] = 0xFFFFFF00.toInt() }
        assertEquals(Backdrop.Tinted(0xFFFFFF00.toInt()), pickBackdrop(yellow, 4))
    }
}
