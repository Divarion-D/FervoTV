package app.iptvplayer.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/** PCM16 gain, limiter, night compression and per-stream loudness normalization. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioEffectsProcessor : BaseAudioProcessor() {
    @Volatile private var volume = 100
    @Volatile private var night = false
    @Volatile private var normalize = false
    private var normalizationGain = 1.0

    fun setEffects(volume: Int, night: Boolean, normalize: Boolean) {
        this.volume = volume.coerceIn(0, 150)
        this.night = night
        this.normalize = normalize
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun onFlush() {
        normalizationGain = 1.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            val input = inputBuffer.consumedDuplicate()
            val output = replaceOutputBuffer(input.remaining())
            output.put(input).flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        val count = inputBuffer.remaining() / 2
        val samples = ShortArray(count) { inputBuffer.short }
        if (normalize && samples.isNotEmpty()) {
            val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size).coerceAtLeast(1.0)
            val wanted = (Short.MAX_VALUE * .18 / rms).coerceIn(.5, 2.0)
            normalizationGain = normalizationGain * .9 + wanted * .1
        } else normalizationGain = 1.0
        val output = replaceOutputBuffer(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { sample ->
            var value = sample / 32768.0
            if (night) {
                val level = abs(value)
                value = sign(value) * if (level <= .25) level * 1.6 else (.25 + (level - .25) / 4) * 1.6
            }
            value *= normalizationGain * if (volume > 100) volume / 100.0 else 1.0
            val limited = if (abs(value) <= .9) value else sign(value) * (.9 + (1 - exp(-(abs(value) - .9) * 10)) * .1)
            output.putShort((limited.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
        }
        output.flip()
    }
}
