package com.maxrave.domain.data.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A generated stereo impulse response.
 *
 * Deliberately not a `data class`: the generated `equals` would compare the two arrays by identity,
 * which is the same trap [DelayTaps] has to write its way out of — and nothing compares impulses
 * anyway, so there is no reason to carry the machinery.
 */
class StereoImpulse(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int,
)

/**
 * Synthesises the impulse response each [ReverbPreset] is convolved against.
 *
 * Generating the response rather than shipping recorded impulses buys three things: no audio assets
 * to license, no megabytes in the APK, and — the reason it lives in `core/domain` — both backends
 * convolve against *the same samples*. Desktop writes these floats to a WAV and hands it to
 * ffmpeg's `afir`; Android runs its own partitioned convolver over the identical array. One
 * setting, two backends, one impulse.
 *
 * The model is the standard one for synthetic reverb: an exponentially decaying burst of noise,
 * low-passed harder as it decays because air and soft furnishings eat the top end first, preceded
 * by a pre-delay and a handful of discrete early reflections that tell the ear how big the room is
 * before the diffuse tail arrives.
 *
 * **Determinism.** The noise comes from a xorshift64\* generator seeded from [ReverbPreset.seed],
 * so a preset renders the same samples on every run: nothing here reads a clock, a hash code, or a
 * platform random source. Across *different* platforms the arithmetic is identical except that
 * `exp`, `ln`, `sin` and `cos` are only specified to within an ulp or so by each runtime's math
 * library, so two platforms can differ in the last bit or two of a sample. That is roughly 150 dB
 * below anything audible and far under the parity tolerance the convolvers are checked against,
 * which is why the desktop cache file is keyed on preset and [VERSION] rather than on a digest of
 * the samples.
 */
object ReverbImpulseResponse {
    /**
     * The rate everything is specified at.
     *
     * Both backends are exact against each other here. At any other stream rate ffmpeg resamples
     * the IR for `afir` while Android generates directly at that rate; the two are then
     * statistically the same reverb rather than sample-identical. Accepted, and the common case by
     * a wide margin is 48 kHz.
     */
    const val SAMPLE_RATE: Int = 48_000

    /**
     * Bumped whenever the math below changes.
     *
     * Desktop caches each rendered impulse as `<preset>-v<VERSION>.wav`, so a build that generates
     * different samples must not reuse the file the previous build left behind — which it would,
     * silently, since the preset name alone would still match.
     */
    const val VERSION: Int = 1

    /** Longest impulse rendered. Convolution cost is linear in this, and CATHEDRAL overruns it. */
    private const val MAX_LENGTH_MS = 4_000

    /**
     * `exp(-6.907 t / rt60)` falls to 1/1000 — i.e. -60 dB — at `t = rt60`, which is what RT60
     * means. (`ln(1000)` is 6.9078; the extra digits are 0.007 dB over the whole tail.)
     */
    private const val DECAY_PER_RT60 = 6.907

    /**
     * `exp(-1.386 t / rt60)` is a factor of four over one RT60, so the damping cut-off is
     * [ReverbPreset.dampingHz] where the tail starts and a quarter of it where the tail ends —
     * two octaves of top end lost across the decay.
     */
    private const val DAMPING_DECAY_PER_RT60 = 1.3862943611198906

    /** Early reflections all land inside this window; after it the tail is diffuse. */
    private const val EARLY_REFLECTION_WINDOW_MS = 50

    /** Five discrete reflections: enough to place the walls, few enough to stay sparse. */
    private const val EARLY_REFLECTION_COUNT = 5

    /** Level of the first reflection, relative to the unnormalised noise tail. */
    private const val EARLY_REFLECTION_PEAK = 0.8

    /** Per-reflection falloff, so the fifth arrives at about a quarter of the first. */
    private const val EARLY_REFLECTION_DECAY = 0.35

    /** Decorrelates the right channel's noise from the left's. */
    private const val RIGHT_CHANNEL_SEED = 0x6A09E667F3BCC909L

    /** Keeps reflection placement independent of the tail noise, so one can change alone. */
    private const val EARLY_REFLECTION_SEED = 0x3C6EF372FE94F82BL

    /**
     * Render [preset] at [sampleRate].
     *
     * The result is normalised to unit energy per channel (`Σ h² = 1`), which is what makes the
     * presets match each other in loudness despite CATHEDRAL running six times longer than ROOM.
     * Desktop passes `irnorm=-1` to `afir` for exactly this reason: the impulse arrives already
     * scaled, and letting the filter normalise it again would undo the balance.
     */
    fun generate(
        preset: ReverbPreset,
        sampleRate: Int = SAMPLE_RATE,
    ): StereoImpulse {
        val rate = sampleRate.coerceAtLeast(1)
        val lengthMs = minOf(preset.preDelayMs + preset.rt60Ms, MAX_LENGTH_MS)
        val totalFrames = maxOf(1, lengthMs * rate / 1_000)
        // Clamped against the total rather than trusted: a preset whose pre-delay swallowed the
        // whole impulse would render pure silence, and silence cannot be normalised.
        val preDelayFrames = (preset.preDelayMs * rate / 1_000).coerceIn(0, totalFrames - 1)

        val left = FloatArray(totalFrames)
        val right = FloatArray(totalFrames)
        renderTail(left, preDelayFrames, preset, rate, Xorshift64Star(preset.seed))
        renderTail(right, preDelayFrames, preset, rate, Xorshift64Star(preset.seed xor RIGHT_CHANNEL_SEED))
        addEarlyReflections(left, right, preDelayFrames, preset, rate)
        normaliseToUnitEnergy(left)
        normaliseToUnitEnergy(right)
        return StereoImpulse(left = left, right = right, sampleRate = rate)
    }

    /**
     * Fill from [preDelayFrames] onward with damped, decaying noise.
     *
     * The low-pass is a one-pole whose coefficient is recomputed every sample from a cut-off that
     * itself decays — that is the whole "frequency-dependent damping" idea, and doing it per sample
     * rather than in blocks is what keeps the tail from stepping audibly as it darkens. The
     * coefficient `1 - exp(-2*pi*fc/fs)` stays inside `(0, 1)` for any positive cut-off, so the
     * filter cannot go unstable however the preset is dialled.
     */
    private fun renderTail(
        frames: FloatArray,
        preDelayFrames: Int,
        preset: ReverbPreset,
        rate: Int,
        random: Xorshift64Star,
    ) {
        val rt60Seconds = maxOf(preset.rt60Ms, 1) / 1_000.0
        val envelopeRate = -DECAY_PER_RT60 / rt60Seconds
        val dampingRate = -DAMPING_DECAY_PER_RT60 / rt60Seconds
        val dampingHz = preset.dampingHz.toDouble()
        val twoPiOverRate = 2.0 * PI / rate

        var lowPassed = 0.0
        for (index in preDelayFrames until frames.size) {
            val seconds = (index - preDelayFrames) / rate.toDouble()
            val cutoffHz = dampingHz * exp(dampingRate * seconds)
            val coefficient = 1.0 - exp(-twoPiOverRate * cutoffHz)
            lowPassed += coefficient * (random.nextGaussian() - lowPassed)
            frames[index] = (lowPassed * exp(envelopeRate * seconds)).toFloat()
        }
    }

    /**
     * Add the discrete reflections that arrive before the tail turns diffuse.
     *
     * Each reflection is dropped into its own slice of the window, so they stay sparse instead of
     * clustering the way independent draws would. Gains are signed: a real surface can invert
     * phase, and letting them all push the same way builds an audible comb instead of a room.
     *
     * The two channels share the arrival *times* but take the gain list in opposite order. Moving
     * the times apart instead would smear the stereo image of the source; swapping the gains widens
     * the room while leaving every reflection where the geometry put it.
     */
    private fun addEarlyReflections(
        left: FloatArray,
        right: FloatArray,
        preDelayFrames: Int,
        preset: ReverbPreset,
        rate: Int,
    ) {
        val windowFrames = EARLY_REFLECTION_WINDOW_MS * rate / 1_000
        val sliceFrames = windowFrames / EARLY_REFLECTION_COUNT
        if (sliceFrames <= 0) return

        val random = Xorshift64Star(preset.seed xor EARLY_REFLECTION_SEED)
        val offsets = IntArray(EARLY_REFLECTION_COUNT)
        val gains = FloatArray(EARLY_REFLECTION_COUNT)
        for (index in 0 until EARLY_REFLECTION_COUNT) {
            offsets[index] = index * sliceFrames + (random.nextDouble() * sliceFrames).toInt()
            val magnitude = EARLY_REFLECTION_PEAK * exp(-EARLY_REFLECTION_DECAY * index)
            gains[index] = (magnitude * (2.0 * random.nextDouble() - 1.0)).toFloat()
        }

        for (index in 0 until EARLY_REFLECTION_COUNT) {
            val position = preDelayFrames + offsets[index]
            if (position >= left.size) break
            left[position] += gains[index]
            right[position] += gains[EARLY_REFLECTION_COUNT - 1 - index]
        }
    }

    /** Scale so `Σ h² = 1`, leaving an all-zero channel alone rather than dividing by zero. */
    private fun normaliseToUnitEnergy(frames: FloatArray) {
        var energy = 0.0
        for (sample in frames) {
            energy += sample.toDouble() * sample.toDouble()
        }
        if (energy <= 0.0) return
        val scale = (1.0 / sqrt(energy)).toFloat()
        for (index in frames.indices) {
            frames[index] = frames[index] * scale
        }
    }
}

/**
 * xorshift64\*, the generator behind every sample above.
 *
 * Written out rather than taken from `kotlin.random.Random` because the impulse has to be
 * reproducible across platforms and releases: `Random(seed)` guarantees neither, and
 * `java.util.Random` is not reachable from common code at all. Nine lines of shifts also make the
 * sequence auditable, which matters when the only symptom of a broken one is "the reverb sounds
 * slightly different on this device".
 */
private class Xorshift64Star(
    seed: Long,
) {
    private var state: Long = if (seed == 0L) FALLBACK_SEED else seed

    /** Box–Muller produces normals in pairs; the second is kept rather than thrown away. */
    private var spareGaussian = 0.0
    private var hasSpareGaussian = false

    private fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * MULTIPLIER
    }

    /** Uniform in `[0, 1)`, built from the top 53 bits — xorshift's low bits are its weakest. */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Standard normal, via Box–Muller. */
    fun nextGaussian(): Double {
        if (hasSpareGaussian) {
            hasSpareGaussian = false
            return spareGaussian
        }
        // `1 - nextDouble()` lands in (0, 1]: at exactly zero `ln` would return -infinity and the
        // radius would come out NaN, poisoning every sample after it through the low-pass state.
        val radius = sqrt(-2.0 * ln(1.0 - nextDouble()))
        val angle = 2.0 * PI * nextDouble()
        spareGaussian = radius * sin(angle)
        hasSpareGaussian = true
        return radius * cos(angle)
    }

    private companion object {
        /** Used only if a preset ever xors down to zero; xorshift is stuck there forever. */
        const val FALLBACK_SEED = 0x106689D45497FDB5L
        const val MULTIPLIER = 0x2545F4914F6CDD1DL

        /** 2^-53, the step between consecutive doubles in `[0, 1)` at full 53-bit precision. */
        const val DOUBLE_UNIT = 1.0 / 9_007_199_254_740_992.0
    }
}