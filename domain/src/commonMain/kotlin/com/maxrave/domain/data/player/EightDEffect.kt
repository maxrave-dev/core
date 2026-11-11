package com.maxrave.domain.data.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

enum class EightDDirection(val sign: Int) {
    LEFT_TO_RIGHT(1),
    RIGHT_TO_LEFT(-1),
    ;

    companion object {
        fun fromStored(value: String): EightDDirection = entries.firstOrNull { it.name == value } ?: LEFT_TO_RIGHT
    }
}

/**
 * Seconds per complete LFO cycle, not an intensity level.
 *
 * Control bounds, step and Classic default follow SonicLab's 8D tool:
 * https://soniclab.io/tools/8d-audio (production control code checked 2026-11-11).
 * These are a product precedent, not a claim about optimal listening comfort.
 */
data class EightDEffect(
    val direction: EightDDirection = EightDDirection.LEFT_TO_RIGHT,
    val periodSeconds: Float = DEFAULT_PERIOD_SECONDS,
) {
    val radiansPerSecond: Double
        get() = direction.sign * 2 * PI / normalizePeriod(periodSeconds)

    fun normalized(): EightDEffect = copy(periodSeconds = normalizePeriod(periodSeconds))

    companion object {
        const val MIN_PERIOD_SECONDS = 3f
        const val MAX_PERIOD_SECONDS = 30f
        const val PERIOD_STEP_SECONDS = 0.5f
        const val DEFAULT_PERIOD_SECONDS = 10f

        /** SonicLab also ramps direction changes over 50 ms; use that duration for bypass fades. */
        const val TRANSITION_MS = 50L

        fun normalizePeriod(value: Float): Float {
            if (!value.isFinite()) return DEFAULT_PERIOD_SECONDS
            val clamped = value.coerceIn(MIN_PERIOD_SECONDS, MAX_PERIOD_SECONDS)
            return (clamped / PERIOD_STEP_SECONDS).roundToInt() * PERIOD_STEP_SECONDS
        }
    }
}

/**
 * The spherical-head model of Brown & Duda, "A Structural Model for Binaural Sound Synthesis",
 * IEEE Trans. Speech Audio Process. 6(5), 1998, p. 477, equations (2)-(5). Constants are the
 * paper's: head radius a = 8.75 cm, c = 343 m/s, α_min = 0.1, θ_min = 150°.
 *
 * Why this and not a pan law: an equal-power pan cuts the far ear by the same amount at every
 * frequency and, at the edges, to silence, which is what made the first version hurt. A real head
 * shadows mostly high frequencies and delays the far ear. Measured on a published 8D track
 * (2026-11-11): the far ear sat 1.8 dB below the near one at the median and 9 dB at worst, the
 * bands under 500 Hz differed by at most a few dB while those above 4 kHz reached 17.8 dB, and the
 * far ear lagged by 0.67-0.77 ms. This model predicts 0 dB, 17 dB and 0.656 ms.
 *
 * Each ear renders its own channel; the song is not folded to mono. The same track kept the
 * song's stereo: its side-to-mid energy facing front was 0.088 under 300 Hz against the original's
 * 0.116, and higher than the original's from 300 Hz to 3 kHz. Folding to mono made the whole mix
 * one small point and combed its wide parts into a hollow sound.
 *
 * Azimuth: 0 = straight ahead, +π/2 = right, ±π = behind, -π/2 = left.
 */
object EightDHead {
    const val HEAD_RADIUS_M = 0.0875
    const val SPEED_OF_SOUND_M_S = 343.0
    const val ALPHA_MIN = 0.1
    const val THETA_MIN_RADIANS = 150.0 * PI / 180.0

    /** 2·ω0 with ω0 = c/a (equations 3 and 4), in rad/s: the zero of the ear's shadow sits at β/α. */
    const val SHADOW_BETA_RADIANS_PER_SECOND = 2 * SPEED_OF_SOUND_M_S / HEAD_RADIUS_M

    /** Longest delay [earDelaySeconds] returns: (a/c)(1 + π/2), 0.656 ms for an adult head. */
    const val MAX_DELAY_SECONDS = HEAD_RADIUS_M / SPEED_OF_SOUND_M_S * (1 + PI / 2)

    enum class Ear(internal val axis: Double) { LEFT(-PI / 2), RIGHT(PI / 2) }

    /** Angle of incidence θ for [ear], between the ear's axis and the source, in 0..π. */
    fun incidence(
        azimuth: Double,
        ear: Ear,
    ): Double {
        val u = azimuth - ear.axis + PI
        return abs(u - 2 * PI * floor(u / (2 * PI)) - PI)
    }

    /** Equation (5): where the head-shadow zero sits; 2 at the ear (+6 dB highs), 0.1 at 150°. */
    fun alpha(theta: Double): Double = (1 + ALPHA_MIN / 2) + (1 - ALPHA_MIN / 2) * cos(theta / THETA_MIN_RADIANS * PI)

    /**
     * α of the brighter of the two ears when one ear sits at [theta]; the other is at π − θ, since
     * the ears face opposite ways. The shared shadow is divided by this ear's response.
     */
    fun brighterAlpha(theta: Double): Double = max(alpha(theta), alpha(PI - theta))

    /**
     * High-frequency gain of [EightDHeadShadow] at [theta]: α relative to the brighter ear, so 1
     * for that ear and below 1 for the other, whose ratio stays the paper's. Equation (3) as
     * written lifts the near ear's highs by up to 6 dB, which pushed loud masters past full scale
     * and broke up; dividing by the near ear alone (α/2) instead left both ears 8 dB darker facing
     * front, and the song 1.4 LU quieter.
     */
    fun shadowGain(theta: Double): Double = alpha(theta) / brighterAlpha(theta)

    /** Pole of [EightDHeadShadow] at [theta] in rad/s, β over the brighter ear's α; equal for both ears. */
    fun shadowPole(theta: Double): Double = SHADOW_BETA_RADIANS_PER_SECOND / brighterAlpha(theta)

    /**
     * Equation (2), Woodworth's delay relative to the head's centre, shifted by a/c so that it is
     * never negative (the paper's causal form): 0 at the ear facing the source.
     */
    fun earDelaySeconds(theta: Double): Double {
        val r = HEAD_RADIUS_M / SPEED_OF_SOUND_M_S
        val relative = if (theta < PI / 2) -r * cos(theta) else r * (theta - PI / 2)
        return relative + r
    }
}

/**
 * Equation (3) divided by the brighter ear's response, H(α)/H(α_b) = (g·s + p)/(s + p) with
 * p = β/α_b ([EightDHead.shadowPole]) and g = α/α_b ([EightDHead.shadowGain]), by the bilinear
 * transform. Unity at DC and g at Nyquist, so lows reach both ears unchanged while highs follow the
 * head's shadow; the level difference between the ears is the paper's. With g ≤ 1 the impulse
 * response is non-negative and sums to 1, so no output sample exceeds the largest input; g = 1 is
 * an exact identity. The pole moves with the angle, so it is not prewarped: DC and Nyquist stay
 * exact and the pole, at most 1.65 kHz, shifts by under 0.5% at 44.1 kHz. g and p may change on
 * every sample; the state is one sample each way.
 *
 * MpvEffectGraphs writes the same arithmetic into an aeval expression for Desktop: keep them equal.
 */
class EightDHeadShadow {
    private var k = 0.0
    private var x1 = 0.0
    private var y1 = 0.0

    fun setSampleRate(sampleRate: Int) {
        k = 2.0 * sampleRate.coerceAtLeast(1)
    }

    fun process(
        x: Double,
        gain: Double,
        pole: Double,
    ): Double {
        val norm = 1 / (pole + k)
        val y = ((pole + gain * k) * x + (pole - gain * k) * x1 - (pole - k) * y1) * norm
        x1 = x
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        y1 = 0.0
    }
}
