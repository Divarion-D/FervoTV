package app.iptvplayer.core.input

import app.iptvplayer.core.Defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTest {
    @Test
    fun dialCollectsDigitsAndSwitchesAfterThePause() {
        var d = NumberDial()
        assertNull(d.dueAt)
        assertNull(d.number)
        d = d.digit(1, 1_000).digit(0, 1_500).digit(7, 2_900)
        assertEquals("107", d.text)
        assertEquals(107, d.number)
        assertEquals(2_900 + Defaults.NUMBER_DIAL_MS, d.dueAt) // the timer counts from the last digit
    }

    @Test
    fun dialStopsAtMaxDigitsButRestartsTheTimer() {
        var d = NumberDial()
        repeat(Defaults.NUMBER_DIAL_DIGITS + 2) { d = d.digit(9, it * 100L) }
        assertEquals("9".repeat(Defaults.NUMBER_DIAL_DIGITS), d.text)
        assertEquals((Defaults.NUMBER_DIAL_DIGITS + 1) * 100L + Defaults.NUMBER_DIAL_MS, d.dueAt)
        assertEquals(0, NumberDial().digit(0, 0).number) // "0": no such channel, the screen says so
    }

    @Test
    fun secondBackWithinTheWindowExits() {
        val g = ExitGuard(2_000)
        assertFalse(g.press(10_000)) // "Нажмите Back ещё раз"
        assertTrue(g.press(11_500))
        assertFalse(g.press(12_000)) // starts over after leaving
        assertFalse(g.press(14_500)) // too late: this one is a new first press
        assertTrue(g.press(16_000))
    }
}
