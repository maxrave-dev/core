package com.maxrave.domain.data.player

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * One delay/echo setting, as a single immutable value.
 *
 * The three numbers here are what the user actually holds a slider over; [taps] turns them into the
 * flat tap list both backends need. Bundled into one object rather than passed as three fields for
 * the same reason [com.maxrave.domain.data.player.GenericPlaybackParameters] is: a player can tell
 * "has this changed?" with one reference comparison, and time/feedback/mix can never be read
 * half-updated across a change.
 *
 * @param timeMs spacing between echoes, in milliseconds.
 * @param feedback how much of each echo survives into the next, `0f..`[MAX_FEEDBACK]. It scales the
 *   *first* echo too (`decays[k] = mix * feedback^k` starts at `k = 1`), so `0f` means no echo at
 *   all rather than "one echo and no repeats".
 * @param mix wet level, `0f..1f`. `0f` is dry, `1f` drops the dry signal entirely.
 */
data class DelayEffect(
    val timeMs: Int,
    val feedback: Float,
    val mix: Float,
) {
    /**
     * The tap layout this setting expands to.
     *
     * Every value is clamped into range here rather than at the call sites, because this is the one
     * place both backends go through: a value restored from a DataStore written by a future build
     * (a wider slider, a preset that no longer exists) must not be able to produce an argument
     * ffmpeg rejects, and an `aecho` whose arguments are out of range fails the *whole* graph — on
     * desktop that graph also carries the equalizer, so a single bad number would silently take the
     * EQ down with it.
     */
    fun taps(): DelayTaps {
        val time = timeMs.coerceIn(MIN_TIME_MS, MAX_TIME_MS)
        val feedbackAmount = clamp(feedback, 0f, MAX_FEEDBACK)
        val wet = clamp(mix, 0f, 1f)

        val count = tapCount(feedbackAmount)
        val delaysMs = IntArray(count) { time * (it + 1) }
        // `decay` floored rather than allowed to reach zero: aecho documents its range as (0, 1]
        // and refuses a graph containing a zero decay outright ("decay[0]: 0.000000 is out of
        // allowed range"). DECAY_FLOOR is ~ -60 dB, i.e. inaudible, so a tap pinned to it is
        // silence in every sense except the one the filter checks.
        val decays =
            FloatArray(count) {
                (wet * feedbackAmount.pow(it + 1)).coerceAtLeast(DECAY_FLOOR)
            }

        val inGain = 1f - wet
        // aecho sums the dry path and every tap and then clips hard, so the worst case — all taps
        // peaking together with the dry signal — is what has to fit in unity. Dividing by it only
        // when it exceeds 1 means a quiet setting is left at full level instead of being boosted.
        val outGain = 1f / maxOf(1f, inGain + decays.sum())
        return DelayTaps(inGain = inGain, outGain = outGain, delaysMs = delaysMs, decays = decays)
    }

    companion object {
        const val MIN_TIME_MS: Int = 20
        const val MAX_TIME_MS: Int = 2_000
        const val MAX_FEEDBACK: Float = 0.9f

        /**
         * Hard ceiling on the tap count.
         *
         * At [MAX_FEEDBACK] the tail would need 29 taps to fall to [TAIL_FLOOR]; every tap costs a
         * full delay line of the same length on Android, so the tail is truncated instead. 12 taps
         * at 0.9 still reaches 0.28 of the wet level, which is a long echo by any musical standard.
         */
        const val MAX_TAPS: Int = 12

        /** Level at which a tap stops being worth synthesising: -26 dB below the wet signal. */
        const val TAIL_FLOOR: Float = 0.05f

        /** Smallest decay `aecho` accepts; see the note in [taps]. */
        private const val DECAY_FLOOR: Float = 0.001f

        /**
         * `coerceIn`, but with NaN pinned to [min] instead of passed through.
         *
         * NaN fails every comparison, so `Float.coerceIn` hands it straight back — and it then
         * survives `pow` and `coerceAtLeast` too, reaching `aecho` as the literal `nan` and failing
         * the graph the equalizer also lives in. [min] is the safe landing for both callers: it
         * makes the effect inaudible rather than loud. Infinities need no special case; those do
         * compare, so `coerceIn` already pins them to the right end.
         */
        private fun clamp(
            value: Float,
            min: Float,
            max: Float,
        ): Float = if (value.isNaN()) min else value.coerceIn(min, max)

        /**
         * How many taps it takes for `feedback^n` to fall to [TAIL_FLOOR], capped at [MAX_TAPS].
         *
         * The `feedback <= DECAY_FLOOR` branch exists because `ln(feedback)` walks off to negative
         * infinity as feedback approaches zero, and `ln(0)` is negative infinity outright — the
         * ratio is then zero or NaN rather than a tap count. One tap is the right answer there
         * anyway: nothing audible is being repeated.
         */
        private fun tapCount(feedback: Float): Int {
            if (feedback <= DECAY_FLOOR) return 1
            val exact = ln(TAIL_FLOOR.toDouble()) / ln(feedback.toDouble())
            return ceil(exact).toInt().coerceIn(1, MAX_TAPS)
        }
    }
}

/**
 * A delay expanded into the flat form both backends consume:
 *
 * ```
 * out[n] = outGain * (inGain * in[n] + Σ decays[k] * in[n - delaysMs[k]])
 * ```
 *
 * which is exactly ffmpeg's `aecho` — feed-*forward* taps read off the input, never off the output,
 * so there is no recursion and no chance of the tail running away. This is the ONLY definition of
 * the tap layout: desktop pastes these four values straight into an `aecho=` argument in mpv's `af`
 * chain, and Android's `EchoAudioProcessor` runs the same sum over its own delay lines. One setting,
 * two backends, one set of numbers — the same reason the equalizer's band centres are declared once.
 *
 * Every value here already satisfies `aecho`'s documented ranges (verified against ffmpeg 9.0.1,
 * which reports them in its own error text): [inGain] and [outGain] in `0..1`, each decay in
 * `(0, 1]`, each delay in `(0, 90000]` ms. At the widest setting the last tap sits at
 * `2000 ms x 12 = 24000 ms`, comfortably inside that last bound.
 */
data class DelayTaps(
    val inGain: Float,
    val outGain: Float,
    val delaysMs: IntArray,
    val decays: FloatArray,
) {
    // equals/hashCode are written out because the generated ones compare arrays by identity, which
    // would make two DelayTaps built from the same DelayEffect unequal. Both backends compare tap
    // layouts to decide whether a filter needs rebuilding, and an always-false equality there means
    // rebuilding the mpv graph — or the Android delay lines, audibly — on every single buffer.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DelayTaps) return false
        return inGain == other.inGain &&
            outGain == other.outGain &&
            delaysMs.contentEquals(other.delaysMs) &&
            decays.contentEquals(other.decays)
    }

    override fun hashCode(): Int {
        var result = inGain.hashCode()
        result = 31 * result + outGain.hashCode()
        result = 31 * result + delaysMs.contentHashCode()
        result = 31 * result + decays.contentHashCode()
        return result
    }
}

/**
 * The four reverb spaces offered in Settings.
 *
 * Each is a recipe for [ReverbImpulseResponse] rather than a shipped impulse file: generating the
 * response means no audio assets to license and no chance of the two platforms convolving against
 * slightly different data.
 *
 * @param rt60Ms time for the tail to fall 60 dB — the number that decides how big the space sounds.
 * @param preDelayMs silence before the tail starts, which is how far away the walls read as being.
 * @param dampingHz low-pass cut-off at the head of the tail; the tail loses highs as it decays, the
 *   way air and soft surfaces absorb them in a real room.
 * @param seed fixes the noise, so a preset sounds identical on every device and every run. Must be
 *   non-zero: xorshift is stuck at zero forever.
 */
enum class ReverbPreset(
    val rt60Ms: Int,
    val preDelayMs: Int,
    val dampingHz: Int,
    val seed: Long,
) {
    ROOM(rt60Ms = 600, preDelayMs = 5, dampingHz = 6_000, seed = 0x2F6E5B1D4C3A9187L),
    HALL(rt60Ms = 2_200, preDelayMs = 20, dampingHz = 4_500, seed = 0x51A7C3E9B2D40F63L),
    PLATE(rt60Ms = 1_500, preDelayMs = 0, dampingHz = 9_000, seed = 0x7B39D85F6C1E2A4DL),
    CATHEDRAL(rt60Ms = 4_000, preDelayMs = 30, dampingHz = 3_000, seed = 0x4C8E1F72A560D3B9L),
}

/**
 * One reverb setting.
 *
 * @param mix wet level, `0f..1f`. Both backends blend as `(1 - mix) * dry + mix * wet` so the two
 *   agree at every position of the slider, not just at the ends.
 */
data class ReverbEffect(
    val preset: ReverbPreset,
    val mix: Float,
)

/**
 * Both audio effects as one value, which is what [com.maxrave.domain.mediaservice.player.MediaPlayerInterface.setAudioEffects]
 * takes.
 *
 * A `null` member means that effect is off — not "on at zero" — so a backend can drop the filter
 * out of its chain entirely rather than paying for a no-op convolution on every buffer. Carrying
 * both in one object also means enabling one and disabling the other is a single write, which
 * matters because Android reads this field per buffer and would otherwise be able to observe a
 * half-applied change.
 */
data class AudioEffects(
    val delay: DelayEffect?,
    val reverb: ReverbEffect?,
) {
    companion object {
        /** Nothing applied; hands every backend back to its plain path. */
        val NONE = AudioEffects(delay = null, reverb = null)
    }
}