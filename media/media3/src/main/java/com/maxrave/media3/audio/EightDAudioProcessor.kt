package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.EightDOrbit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Playback-only stereo DSP. It neither downloads audio nor touches the waveform/AutoMix readers. */
@UnstableApi
class EightDAudioProcessor(orbit: EightDOrbit) : BaseAudioProcessor() {
    private val kernel = EightDKernel(orbit)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    /** See [EightDKernel.markStarted]; called from this processor's player's own listener. */
    fun markStarted() = kernel.markStarted()

    // No onFlush: a flush keeps the phase (see EightDKernel).
    override fun onReset() = kernel.reset()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        kernel.beginBuffer(inputAudioFormat.sampleRate)
        if (kernel.isBypassed) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.nativeOrder())
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                while (inputBuffer.remaining() >= 8) {
                    kernel.process(inputBuffer.float.toDouble(), inputBuffer.float.toDouble())
                    output.putFloat(kernel.left.toFloat())
                    output.putFloat(kernel.right.toFloat())
                }
            } else {
                while (inputBuffer.remaining() >= 4) {
                    kernel.process(inputBuffer.short / 32768.0, inputBuffer.short / 32768.0)
                    output.putShort((kernel.left * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                    output.putShort((kernel.right * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            // Audio buffers are frame-aligned; still always consume malformed trailing bytes.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }
}
