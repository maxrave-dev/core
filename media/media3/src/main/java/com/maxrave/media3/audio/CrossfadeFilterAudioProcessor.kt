package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] that applies a real-time Biquad low-pass or high-pass filter
 * to the audio stream. Used for DJ-style crossfade transitions.
 *
 * Key design:
 * - [enabled], [cutoffFrequencyHz], and [filterType] can be changed at runtime (thread-safe).
 * - When [enabled] is false, [isActive] returns false and ExoPlayer skips this processor
 *   entirely — zero CPU overhead during normal playback.
 * - Coefficient recalculation is lazy: only when cutoff or filter type changes.
 * - Supports PCM 16-bit audio (mono and stereo).
 */
@UnstableApi
class CrossfadeFilterAudioProcessor : BaseAudioProcessor() {

    /**
     * Whether the filter is active. When false, audio passes through unmodified.
     * Changing this triggers a flush in ExoPlayer's pipeline.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    pendingReset = true
                }
            }
        }

    /**
     * Current cutoff frequency in Hz. Updated during crossfade animation.
     */
    @Volatile
    var cutoffFrequencyHz: Float = 20000f
        set(value) {
            if (field != value) {
                field = value
                coefficientsDirty = true
            }
        }

    /**
     * Filter type: LOW_PASS for outgoing track, HIGH_PASS for incoming track.
     */
    @Volatile
    var filterType: BiquadFilter.FilterType = BiquadFilter.FilterType.LOW_PASS
        set(value) {
            if (field != value) {
                field = value
                coefficientsDirty = true
            }
        }

    private val filter = BiquadFilter()

    @Volatile
    private var coefficientsDirty = true

    @Volatile
    private var pendingReset = false

    private var sampleRate = 0
    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        coefficientsDirty = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (pendingReset) {
            pendingReset = false
            filter.reset()
        }

        if (!enabled || sampleRate == 0) {
            // Pass through: copy input to output (avoid put(sameBuffer) which throws)
            val output = replaceOutputBuffer(remaining)
            copyBuffer(inputBuffer, output, remaining)
            output.flip()
            return
        }

        // Recalculate coefficients on audio thread if cutoff or type changed
        if (coefficientsDirty) {
            coefficientsDirty = false
            filter.updateCoefficients(
                cutoffHz = cutoffFrequencyHz,
                sampleRate = sampleRate,
                type = filterType,
            )
        }

        val output = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())

        when (channelCount) {
            1 -> processMonoBlock(inputBuffer, output)
            2 -> processStereoBlock(inputBuffer, output)
            else -> {
                // Unsupported channel count: pass through
                copyBuffer(inputBuffer, output, remaining)
            }
        }

        output.flip()
    }

    private fun copyBuffer(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        if (src === dst) {
            dst.position(0)
            dst.limit(size)
            return
        }
        if (src.remaining() == size && dst.remaining() >= size) {
            dst.put(src)
        } else {
            val pos = src.position()
            for (i in 0 until size) {
                dst.put(src.get(pos + i))
            }
            src.position(pos + size)
        }
    }

    private fun processMonoBlock(input: ByteBuffer, output: ByteBuffer) {
        while (input.remaining() >= 2) {
            val sample = input.short.toDouble() / Short.MAX_VALUE
            val filtered = filter.processSampleMono(sample)
            output.putShort((filtered.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
        }
    }

    private fun processStereoBlock(input: ByteBuffer, output: ByteBuffer) {
        while (input.remaining() >= 4) {
            val left = input.short.toDouble() / Short.MAX_VALUE
            val right = input.short.toDouble() / Short.MAX_VALUE
            val filteredL = filter.processStereoLeft(left)
            val filteredR = filter.processStereoRight(right)
            output.putShort((filteredL.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
            output.putShort((filteredR.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
        }
    }

    override fun onFlush() {
        super.onFlush()
        pendingReset = false
        filter.reset()
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        pendingReset = false
        coefficientsDirty = true
        cutoffFrequencyHz = 20000f
        filterType = BiquadFilter.FilterType.LOW_PASS
        sampleRate = 0
        channelCount = 0
        filter.reset()
    }
}
