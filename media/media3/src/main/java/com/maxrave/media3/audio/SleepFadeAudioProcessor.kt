package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] that attenuates playback by the sleep timer's fade-out gain.
 *
 * This exists as a *second, independent volume line*. The crossfade ramp owns the player's own
 * `volume` and rewrites it fifty times per transition, so anything else sharing that line either
 * gets overwritten or has to be folded into every single write to it. Down here the two lines
 * simply multiply inside the audio pipeline and neither has to know the other exists — the same
 * split mpv gives us on desktop, where the crossfade rides the software volume while the sleep
 * fade rides the device mixer.
 *
 * Key design:
 * - [gain] is read fresh on every buffer rather than captured, so a fade that starts — or keeps
 *   advancing — in the middle of a crossfade still reaches the output.
 * - One instance per player, because an [AudioProcessor] carries per-stream state (format, output
 *   buffer) and cannot be shared across two of them. They all read the same gain, which is what
 *   makes a single write cover every player at once.
 * - At full gain the samples are passed through untouched, which is every moment except the last
 *   few seconds of a sleep timer.
 * - Supports PCM 16-bit, matching [CrossfadeFilterAudioProcessor] and what the YouTube sources
 *   decode to.
 */
@UnstableApi
class SleepFadeAudioProcessor(
    private val gain: () -> Float,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    /**
     * Always active: the gain changes at runtime, and a processor dropped from the chain would not
     * be consulted again until the next flush — by which point the sleep timer has already stopped
     * playback. The full-gain path below is cheap enough to keep it in place permanently.
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        val currentGain = gain()

        if (currentGain >= 1f) {
            // Bulk copy. This branch runs on every buffer for the whole of playback whether a sleep
            // timer exists or not, so it must not be a per-byte loop. `replaceOutputBuffer` hands
            // back this processor's own buffer while the input belongs to the previous one in the
            // chain, so the two are never the same object and `put` cannot throw.
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        // Channel count is irrelevant here: attenuation is per sample, and every channel of an
        // interleaved frame takes the same factor.
        while (inputBuffer.remaining() >= 2) {
            output.putShort((inputBuffer.short * currentGain).toInt().toShort())
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
