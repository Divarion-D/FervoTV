package app.iptvplayer.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDelayProcessorTest {
    private val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @Test fun positiveDelayPrependsSilenceAndNegativeDelayDropsFrames() {
        val delayed = AudioDelayProcessor().apply { configure(format); flush(); setDelayMs(25) }
        delayed.queueInput(bytes(9_600, 1))
        val output = delayed.output
        assertEquals(14_400, output.remaining())
        repeat(4_800) { assertEquals(0, output.get().toInt()) }
        assertEquals(1, output.get().toInt())

        val advanced = AudioDelayProcessor().apply { configure(format); flush(); setDelayMs(-25) }
        advanced.queueInput(bytes(9_600, 2))
        assertEquals(4_800, advanced.output.remaining())
        assertTrue(advanced.isActive)
    }

    @Test fun reusedOutputBufferCanBeQueuedAgainAtZeroDelay() {
        val processor = AudioDelayProcessor().apply { configure(format); flush() }
        processor.queueInput(bytes(8, 1))
        val reused = processor.output
        reused.clear()
        repeat(8) { reused.put(2) }
        reused.flip()

        processor.queueInput(reused)

        val output = processor.output
        assertEquals(8, output.remaining())
        repeat(8) { assertEquals(2, output.get().toInt()) }
    }

    private fun bytes(size: Int, value: Byte) = ByteBuffer.allocateDirect(size).apply {
        repeat(size) { put(value) }
        flip()
    }
}
