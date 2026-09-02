package com.maxrave.domain.data.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the two things both audio backends quote verbatim.
 *
 * [DelayEffect.taps] feeds arguments straight into ffmpeg's `aecho`, which validates its own ranges
 * and fails the *entire* filter graph when one is out — on desktop that graph also carries the
 * equalizer, so a bad number here silently takes the EQ down with it. Every assertion about ranges
 * below is that failure mode, checked in Kotlin instead of at runtime on a user's machine.
 *
 * [ReverbImpulseResponse] is checked for the properties the two convolvers depend on rather than
 * against golden samples: a golden array would have to be regenerated on every tweak and would
 * still not catch the interesting bug, which is the generator quietly stopping being deterministic.
 */
class AudioEffectsTest {
    /**
     * `aecho`'s own limits, read off ffmpeg 9.0.1's error text: in_gain and out_gain "from 0 to 1",
     * `decay[n] … out of allowed range: (0, 1]`, `delay[n] … out of allowed range: (0, 90000]`.
     */
    private fun assertWithinAechoRanges(
        effect: DelayEffect,
        taps: DelayTaps,
    ) {
        val where = "for $effect"
        assertTrue(taps.inGain in 0f..1f, "inGain ${taps.inGain} outside 0..1 $where")
        assertTrue(taps.outGain > 0f && taps.outGain <= 1f, "outGain ${taps.outGain} outside (0, 1] $where")
        assertEquals(taps.delaysMs.size, taps.decays.size, "tap arrays differ in length $where")
        assertTrue(taps.delaysMs.isNotEmpty(), "aecho needs at least one tap $where")
        taps.delaysMs.forEach {
            assertTrue(it > 0 && it <= 90_000, "delay $it outside (0, 90000] $where")
        }
        taps.decays.forEach {
            assertTrue(it > 0f && it <= 1f, "decay $it outside (0, 1] $where")
        }
        // What the filter actually sums before it clips: the dry path plus every tap at its peak.
        val worstCase = taps.outGain * (taps.inGain + taps.decays.sum())
        assertTrue(worstCase <= 1f + 1e-6f, "worst-case gain $worstCase exceeds unity $where")
    }

    @Test
    fun `taps stay inside aecho ranges across the whole setting grid`() {
        val times = listOf(DelayEffect.MIN_TIME_MS, 80, 400, 600, DelayEffect.MAX_TIME_MS)
        val feedbacks = listOf(0f, 0.001f, 0.002f, 0.3f, 0.45f, 0.7f, DelayEffect.MAX_FEEDBACK)
        val mixes = listOf(0f, 0.25f, 0.3f, 0.4f, 1f)
        for (time in times) {
            for (feedback in feedbacks) {
                for (mix in mixes) {
                    val effect = DelayEffect(timeMs = time, feedback = feedback, mix = mix)
                    assertWithinAechoRanges(effect, effect.taps())
                }
            }
        }
    }

    @Test
    fun `taps clamp values a future build could have stored`() {
        // Sliders get widened, presets get removed, and DataStore keeps whatever the last build
        // wrote. None of it may reach the filter unclamped.
        val hostile =
            listOf(
                DelayEffect(timeMs = 0, feedback = -1f, mix = -1f),
                DelayEffect(timeMs = -5_000, feedback = 5f, mix = 9f),
                DelayEffect(timeMs = 1_000_000, feedback = 0.99f, mix = 1.5f),
                DelayEffect(timeMs = 400, feedback = Float.NaN, mix = Float.NaN),
                DelayEffect(timeMs = 400, feedback = Float.POSITIVE_INFINITY, mix = Float.NEGATIVE_INFINITY),
            )
        hostile.forEach { assertWithinAechoRanges(it, it.taps()) }

        val clampedTime = DelayEffect(timeMs = 1_000_000, feedback = 0f, mix = 0f).taps()
        assertEquals(DelayEffect.MAX_TIME_MS, clampedTime.delaysMs.first(), "time not clamped to the maximum")
        val clampedShort = DelayEffect(timeMs = 0, feedback = 0f, mix = 0f).taps()
        assertEquals(DelayEffect.MIN_TIME_MS, clampedShort.delaysMs.first(), "time not clamped to the minimum")
    }

    @Test
    fun `tap count follows the tail floor and stops at the cap`() {
        // ln(0.05)/ln(feedback), rounded up. 0.45 -> 3.7517 -> 4; 0.9 -> 28.4332, capped at 12.
        assertEquals(1, tapCountFor(0f), "no feedback should still emit exactly one tap")
        assertEquals(1, tapCountFor(0.001f))
        assertEquals(1, tapCountFor(0.002f))
        assertEquals(4, tapCountFor(0.45f))
        assertEquals(5, tapCountFor(0.5f))
        assertEquals(9, tapCountFor(0.7f))
        assertEquals(DelayEffect.MAX_TAPS, tapCountFor(DelayEffect.MAX_FEEDBACK))
    }

    private fun tapCountFor(feedback: Float): Int = DelayEffect(timeMs = 400, feedback = feedback, mix = 0.5f).taps().delaysMs.size

    @Test
    fun `taps are evenly spaced and decay geometrically`() {
        val taps = DelayEffect(timeMs = 400, feedback = 0.45f, mix = 0.3f).taps()
        assertContentEquals(intArrayOf(400, 800, 1_200, 1_600), taps.delaysMs, "taps are multiples of the delay time")
        assertEquals(0.7f, taps.inGain, "inGain is 1 - mix")
        taps.decays.forEachIndexed { index, decay ->
            // mix * feedback^(index + 1)
            var expected = 0.3f
            repeat(index + 1) { expected *= 0.45f }
            assertTrue(abs(decay - expected) < 1e-6f, "decay[$index] was $decay, expected $expected")
        }
    }

    @Test
    fun `identical taps compare equal despite living in different arrays`() {
        // The generated data-class equals compares arrays by identity; both backends decide whether
        // to rebuild a filter from this comparison, so getting it wrong means rebuilding forever.
        val effect = DelayEffect(timeMs = 400, feedback = 0.45f, mix = 0.3f)
        val first = effect.taps()
        val second = effect.taps()
        assertFalse(first === second, "taps() must not be returning a cached instance for this test to mean anything")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == DelayEffect(timeMs = 500, feedback = 0.45f, mix = 0.3f).taps())
    }

    @Test
    fun `NONE carries no effects`() {
        assertNull(AudioEffects.NONE.delay)
        assertNull(AudioEffects.NONE.reverb)
    }

    @Test
    fun `every preset seed is distinct and non-zero`() {
        // A zero seed leaves xorshift stuck at zero forever, and two presets sharing a seed would
        // render as literally the same room under two names.
        val seeds = ReverbPreset.entries.map { it.seed }
        assertEquals(ReverbPreset.entries.size, seeds.toSet().size, "preset seeds collide")
        seeds.forEach { assertTrue(it != 0L, "a preset seed is zero") }
    }

    @Test
    fun `impulse length is pre-delay plus rt60, capped at four seconds`() {
        ReverbPreset.entries.forEach { preset ->
            val impulse = ReverbImpulseResponse.generate(preset)
            val cappedMs = minOf(preset.preDelayMs + preset.rt60Ms, 4_000)
            val expected = cappedMs * ReverbImpulseResponse.SAMPLE_RATE / 1_000
            assertEquals(expected, impulse.left.size, "${preset.name} left length")
            assertEquals(expected, impulse.right.size, "${preset.name} right length")
            assertEquals(ReverbImpulseResponse.SAMPLE_RATE, impulse.sampleRate)
        }
        // CATHEDRAL is the one the cap actually bites on: 30 + 4000 ms of tail asked for.
        assertEquals(
            4_000 * ReverbImpulseResponse.SAMPLE_RATE / 1_000,
            ReverbImpulseResponse.generate(ReverbPreset.CATHEDRAL).left.size,
        )
    }

    @Test
    fun `pre-delay is silence`() {
        // PLATE has no pre-delay by design, so it is excluded rather than asserted vacuously.
        ReverbPreset.entries.filter { it.preDelayMs > 0 }.forEach { preset ->
            val impulse = ReverbImpulseResponse.generate(preset)
            val preDelayFrames = preset.preDelayMs * ReverbImpulseResponse.SAMPLE_RATE / 1_000
            assertTrue(preDelayFrames > 0, "${preset.name} should have a pre-delay to check")
            for (index in 0 until preDelayFrames) {
                assertEquals(0f, impulse.left[index], "${preset.name} left[$index] inside the pre-delay")
                assertEquals(0f, impulse.right[index], "${preset.name} right[$index] inside the pre-delay")
            }
            assertFalse(
                (preDelayFrames until impulse.left.size).all { impulse.left[it] == 0f },
                "${preset.name} is silent after its pre-delay",
            )
        }
        assertEquals(0, ReverbPreset.PLATE.preDelayMs, "PLATE is the no-pre-delay preset this test excludes")
    }

    @Test
    fun `each channel is normalised to unit energy`() {
        // Desktop passes irnorm=-1 so afir does NOT renormalise; this is the only thing keeping the
        // four presets at a matched loudness despite CATHEDRAL running six times longer than ROOM.
        ReverbPreset.entries.forEach { preset ->
            val impulse = ReverbImpulseResponse.generate(preset)
            assertTrue(abs(energyOf(impulse.left) - 1.0) < 1e-3, "${preset.name} left energy")
            assertTrue(abs(energyOf(impulse.right) - 1.0) < 1e-3, "${preset.name} right energy")
        }
    }

    private fun energyOf(frames: FloatArray): Double {
        var energy = 0.0
        for (sample in frames) energy += sample.toDouble() * sample.toDouble()
        return energy
    }

    @Test
    fun `generation is deterministic`() {
        // The desktop cache file is named for the preset and VERSION only, so a generator that
        // drifted between runs would keep serving whichever impulse happened to be written first.
        ReverbPreset.entries.forEach { preset ->
            val first = ReverbImpulseResponse.generate(preset)
            val second = ReverbImpulseResponse.generate(preset)
            assertContentEquals(first.left, second.left, "${preset.name} left drifted between runs")
            assertContentEquals(first.right, second.right, "${preset.name} right drifted between runs")
        }
    }

    @Test
    fun `channels are decorrelated`() {
        ReverbPreset.entries.forEach { preset ->
            val impulse = ReverbImpulseResponse.generate(preset)
            assertFalse(
                impulse.left.contentEquals(impulse.right),
                "${preset.name} renders identical channels, which collapses the reverb to mono",
            )
            // Both channels carry unit energy, so this dot product IS their correlation coefficient.
            var correlation = 0.0
            for (index in impulse.left.indices) {
                correlation += impulse.left[index].toDouble() * impulse.right[index].toDouble()
            }
            assertTrue(abs(correlation) < 0.2, "${preset.name} channels correlate at $correlation")
        }
    }

    @Test
    fun `a non-default sample rate scales the impulse rather than failing`() {
        // Android generates at whatever the stream decodes to; only 48 kHz is sample-exact against
        // the WAV desktop hands to afir, but every rate has to produce a usable impulse.
        val rate = 44_100
        val impulse = ReverbImpulseResponse.generate(ReverbPreset.ROOM, rate)
        assertEquals(rate, impulse.sampleRate)
        assertEquals((ReverbPreset.ROOM.preDelayMs + ReverbPreset.ROOM.rt60Ms) * rate / 1_000, impulse.left.size)
        assertTrue(abs(energyOf(impulse.left) - 1.0) < 1e-3)
    }
}