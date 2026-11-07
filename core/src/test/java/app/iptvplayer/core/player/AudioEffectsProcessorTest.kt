package app.iptvplayer.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsProcessorTest {
    private fun process(samples: ShortArray, volume: Int = 100, night: Boolean = false, normalize: Boolean = false): ShortArray {
        val processor = AudioEffectsProcessor()
        processor.setEffects(volume, night, normalize)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / 2) { output.short }
    }

    @Test fun boostAmplifiesQuietSamplesWithoutClippingLoudOnes() {
        val output = process(shortArrayOf(1_000, 30_000), volume = 150)
        assertTrue(output[0] in 1_400..1_600)
        assertTrue(output[1] > 30_000)
        assertTrue(output[1] <= Short.MAX_VALUE)
    }

    @Test fun nightModeCompressesDynamicRange() {
        val output = process(shortArrayOf(2_000, 20_000), night = true)
        assertTrue(abs(output[1].toInt()) / abs(output[0].toInt()).toDouble() < 6.0)
    }

    @Test fun reusedNonPcmOutputBufferCanBeQueuedAgain() {
        val processor = AudioEffectsProcessor().apply {
            configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
            flush()
        }
        processor.queueInput(ByteBuffer.allocateDirect(8).apply { repeat(8) { put(1) }; flip() })
        val reused = processor.output
        reused.clear()
        repeat(8) { reused.put(2) }
        reused.flip()

        processor.queueInput(reused)

        val output = processor.output
        assertEquals(8, output.remaining())
        repeat(8) { assertEquals(2, output.get().toInt()) }
    }
}
