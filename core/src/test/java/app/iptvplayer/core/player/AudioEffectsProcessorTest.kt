package app.iptvplayer.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
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
}
