package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.AudioEffects
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] applying the delay/echo effect.
 *
 * The arithmetic lives in [EchoKernel] and is ffmpeg `aecho`'s, sample for sample, because that is
 * the filter desktop drives through mpv's `af` chain — the same reason [EqualizerAudioProcessor]
 * writes out the Audio EQ Cookbook biquads instead of reaching for `android.media.audiofx`. One
 * stored setting, one sound, on both platforms.
 *
 * The buffer contract is [EqualizerAudioProcessor]'s:
 * - the effects are read fresh on every buffer rather than captured, so a slider dragged during
 *   playback is audible immediately;
 * - one instance per player, since an [AudioProcessor] carries per-stream state, and they all read
 *   the same supplier so a single write covers both players of a crossfade;
 * - PCM 16-bit only, matching the rest of the chain and what the YouTube sources decode to.
 *
 * It sits after the equalizer and before the crossfade filter. That order is not cosmetic: the
 * crossfade ramp has to be able to fade an echo tail out with the track it belongs to, which it can
 * only do if the tail is already in the signal by the time it gets there.
 */
@UnstableApi
class EchoAudioProcessor(
    private val effects: () -> AudioEffects,
) : BaseAudioProcessor() {
    private val kernel = EchoKernel()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        kernel.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    // isActive() is deliberately NOT overridden, for the reason spelled out in
    // EqualizerAudioProcessor: the base class answers it from the format onConfigure just returned,
    // which is exactly the right question — active for PCM 16-bit, dropped for anything else.
    // Whether a delay is configured is a separate matter entirely, and is decided per buffer by the
    // bypass branch below, because activity is only reconsidered on configure.

    override fun onFlush() {
        // A seek or a track change: the recorded audio is from somewhere else in the timeline and
        // must not echo into what comes next.
        kernel.flush()
    }

    override fun onReset() {
        kernel.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        kernel.sync(effects())

        if (kernel.isBypassed) {
            // `replaceOutputBuffer` hands back this processor's own buffer while the input belongs
            // to the previous one in the chain, so the two are never the same object.
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        kernel.beginBuffer()
        while (inputBuffer.remaining() >= 2) {
            output.putShort(kernel.processInterleaved(inputBuffer.short.toInt()).toShort())
        }
        // PCM16 frames are always an even number of bytes, so this should never fire. It is here
        // because the contract is to consume the whole input: leaving a byte behind makes the
        // pipeline re-offer the same buffer forever, having made no progress.
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }

        output.flip()
    }
}
