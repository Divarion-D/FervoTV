package app.iptvplayer.core.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** PCM-only A/V correction. Encoded passthrough bypasses Media3 audio processors. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioDelayProcessor : BaseAudioProcessor() {
    @Volatile private var requestedMs = 0L
    private var appliedBytes = 0L
    private var adjustmentBytes = 0L

    fun setDelayMs(ms: Long) {
        requestedMs = ms.coerceIn(-500L, 500L)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun onFlush() {
        appliedBytes = 0
        adjustmentBytes = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameSize = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        val target = requestedMs * inputAudioFormat.sampleRate * frameSize / 1_000
        adjustmentBytes += target - appliedBytes
        appliedBytes = target

        if (adjustmentBytes < 0) {
            val skip = minOf(-adjustmentBytes, inputBuffer.remaining().toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + skip)
            adjustmentBytes += skip
        }
        val silence = adjustmentBytes.coerceAtLeast(0).toInt()
        val output = replaceOutputBuffer(silence + inputBuffer.remaining())
        repeat(silence) { output.put(0) }
        adjustmentBytes -= silence
        output.put(inputBuffer)
        output.flip()
    }
}
