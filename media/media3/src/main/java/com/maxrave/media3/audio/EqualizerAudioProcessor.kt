package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ten band centres, in Hz.
 *
 * Identical to `MpvPlayer.EQ_BANDS_HZ` on desktop and to the centres AutoEq generates its
 * fixed-band output at, so one stored curve means the same thing on both platforms and an imported
 * profile lands on the bands it was computed for.
 */
val EQUALIZER_BANDS_HZ: IntArray = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

/**
 * Q per band, matching the `width=1.41` desktop passes to ffmpeg's `equalizer` filter — and the
 * `math.sqrt(2)` AutoEq uses for the same ten bands.
 */
private const val EQUALIZER_Q = 1.41

private const val COEFFS_PER_BAND = 5
private const val STATE_PER_BAND = 4

private const val PCM16_MIN = -32_768.0
private const val PCM16_MAX = 32_767.0

/**
 * One equalizer setting, as a single immutable value.
 *
 * Bundled rather than passed as two suppliers so the processor can tell "has this changed?" with
 * one reference comparison per buffer instead of comparing eleven floats — and so a curve and the
 * preamp meant to make room for it can never be read half-updated.
 */
data class EqualizerCurve(
    val bandsDb: List<Float>,
    val preampDb: Float,
) {
    /** No gain anywhere: the processor can hand the buffer straight through. */
    val isFlat: Boolean = preampDb == 0f && bandsDb.all { it == 0f }

    companion object {
        val FLAT = EqualizerCurve(emptyList(), 0f)
    }
}

/**
 * Media3 [AudioProcessor] applying the ten-band equalizer.
 *
 * Each band is a peaking-EQ biquad from Robert Bristow-Johnson's Audio EQ Cookbook — the same
 * formulas ffmpeg's `equalizer` filter implements, which is what desktop drives through mpv's `af`
 * chain. Writing them out here rather than reaching for Android's own `android.media.audiofx
 * .Equalizer` is deliberate: that one is implemented by the device's audio HAL, so its band count,
 * its centres and its response all vary by handset, and a curve dialled in on one phone would not
 * transfer to another — let alone to the desktop build or to an AutoEq profile.
 *
 * Follows the shape [SleepFadeAudioProcessor] established:
 * - the curve is read fresh on every buffer rather than captured, so an adjustment made while
 *   music is playing is audible immediately;
 * - one instance per player, since an [AudioProcessor] carries per-stream state, and they all read
 *   the same supplier so a single write covers both players of a crossfade;
 * - PCM 16-bit only, matching the rest of the chain and what the YouTube sources decode to.
 */
@UnstableApi
class EqualizerAudioProcessor(
    private val curve: () -> EqualizerCurve,
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0

    /** The curve the coefficients below were built from; compared by identity, never by value. */
    private var appliedCurve: EqualizerCurve? = null

    /** Flat b0, b1, b2, a1, a2 per band, already normalised by a0. */
    private var coefficients = DoubleArray(0)

    /** x1, x2, y1, y2 per band per channel, laid out channel-major. */
    private var state = DoubleArray(0)

    private var preampGain = 1.0

    /** True while the curve asks for nothing, which is the whole of playback for most users. */
    private var bypass = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        // Sized here and never again: every band keeps a stage whatever its gain, so the array only
        // depends on the format. That is what lets the curve change mid-track without dropping the
        // filter history, which would be an audible click on every adjustment.
        state = DoubleArray(EQUALIZER_BANDS_HZ.size * STATE_PER_BAND * channelCount)
        // Forces a rebuild against the new sample rate on the first buffer.
        appliedCurve = null
        return inputAudioFormat
    }

    // isActive() is deliberately NOT overridden. The base class answers it from the format
    // onConfigure just returned — `pendingOutputAudioFormat != NOT_SET`, assigned immediately
    // before the call — which is exactly the right question: active for PCM 16-bit, dropped for
    // anything else. Forcing it to true instead would claim the processor is in the chain while
    // handing back NOT_SET for a format it cannot read.
    //
    // Note this is about the *format*, never about the curve. A flat curve keeps the processor
    // active and takes the bulk-copy path below, because activity is only reconsidered on
    // configure — long before the user drags a band and expects to hear it.

    override fun onFlush() {
        state.fill(0.0)
    }

    override fun onReset() {
        state = DoubleArray(0)
        coefficients = DoubleArray(0)
        appliedCurve = null
        sampleRate = 0
        channelCount = 0
        bypass = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        syncCurve()

        if (bypass) {
            // `replaceOutputBuffer` hands back this processor's own buffer while the input belongs
            // to the previous one in the chain, so the two are never the same object.
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        // Media3 guarantees a whole number of frames per buffer, so restarting the channel counter
        // here keeps every sample on its own channel's filter history.
        var channel = 0
        while (inputBuffer.remaining() >= 2) {
            val processed = process(inputBuffer.short.toDouble(), channel)
            output.putShort(processed.coerceIn(PCM16_MIN, PCM16_MAX).toInt().toShort())
            channel++
            if (channel == channelCount) channel = 0
        }
        // PCM16 frames are always an even number of bytes, so this should never fire. It is here
        // because the contract is to consume the whole input: leaving a byte behind makes the
        // pipeline re-offer the same buffer forever, having made no progress.
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }

        output.flip()
    }

    /** Rebuild the coefficients if — and only if — the supplied curve is a different object. */
    private fun syncCurve() {
        val next = curve()
        if (next === appliedCurve) return
        appliedCurve = next
        rebuild(next)
    }

    private fun rebuild(next: EqualizerCurve) {
        val usable = channelCount > 0 && sampleRate > 0
        if (!usable || next.isFlat) {
            // Entering bypass clears the history rather than freezing it: the samples in there were
            // filtered by the old curve, and feeding them back in when the equalizer is switched on
            // again would splice a fragment of the previous shape onto the front of the new one.
            if (!bypass) state.fill(0.0)
            bypass = true
            return
        }

        preampGain = 10.0.pow(next.preampDb / 20.0)
        if (coefficients.size != EQUALIZER_BANDS_HZ.size * COEFFS_PER_BAND) {
            coefficients = DoubleArray(EQUALIZER_BANDS_HZ.size * COEFFS_PER_BAND)
        }

        val nyquist = sampleRate / 2.0
        EQUALIZER_BANDS_HZ.forEachIndexed { index, centreHz ->
            // A band above Nyquist has no meaning at this sample rate — 16 kHz is already past it
            // at 22.05 kHz, which some radio streams still use. Writing the identity stage rather
            // than skipping it keeps the array layout fixed.
            val gainDb = if (centreHz < nyquist) next.bandsDb.getOrElse(index) { 0f } else 0f
            writePeakingBiquad(index, centreHz.toDouble(), gainDb.toDouble())
        }
        bypass = false
    }

    /**
     * Peaking EQ, straight from the Audio EQ Cookbook:
     *
     * ```
     * A     = 10^(gainDb/40)
     * w0    = 2*pi*f0/Fs
     * alpha = sin(w0)/(2*Q)
     * b = [1 + alpha*A, -2*cos(w0), 1 - alpha*A]
     * a = [1 + alpha/A, -2*cos(w0), 1 - alpha/A]
     * ```
     *
     * At 0 dB, A is 1, so `alpha*A` and `alpha/A` are the same number and b equals a exactly: the
     * stage is the identity. That is what makes it safe to keep every band in the chain regardless
     * of its gain, and it is exactly why the state array never has to be resized.
     */
    private fun writePeakingBiquad(
        index: Int,
        centreHz: Double,
        gainDb: Double,
    ) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * centreHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * EQUALIZER_Q)

        val a0 = 1.0 + alpha / a
        val offset = index * COEFFS_PER_BAND
        coefficients[offset] = (1.0 + alpha * a) / a0
        coefficients[offset + 1] = (-2.0 * cosW0) / a0
        coefficients[offset + 2] = (1.0 - alpha * a) / a0
        coefficients[offset + 3] = (-2.0 * cosW0) / a0
        coefficients[offset + 4] = (1.0 - alpha / a) / a0
    }

    /**
     * One sample through the preamp and then all ten stages of its own channel.
     *
     * The preamp goes first, matching the desktop graph — `volume` ahead of the ten `equalizer`
     * entries — so the headroom is taken out before anything is boosted rather than after.
     */
    private fun process(
        input: Double,
        channel: Int,
    ): Double {
        var sample = input * preampGain
        var c = 0
        var s = channel * EQUALIZER_BANDS_HZ.size * STATE_PER_BAND
        while (c < coefficients.size) {
            val x1 = state[s]
            val x2 = state[s + 1]
            val y1 = state[s + 2]
            val y2 = state[s + 3]

            val out =
                coefficients[c] * sample +
                    coefficients[c + 1] * x1 +
                    coefficients[c + 2] * x2 -
                    coefficients[c + 3] * y1 -
                    coefficients[c + 4] * y2

            state[s] = sample
            state[s + 1] = x1
            state[s + 2] = out
            state[s + 3] = y1

            sample = out
            c += COEFFS_PER_BAND
            s += STATE_PER_BAND
        }
        return sample
    }
}
