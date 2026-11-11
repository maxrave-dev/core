package com.maxrave.domain.data.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EightDEffectTest {
    @Test
    fun sonicLabControlContract() {
        assertEquals(3f, EightDEffect.MIN_PERIOD_SECONDS)
        assertEquals(30f, EightDEffect.MAX_PERIOD_SECONDS)
        assertEquals(0.5f, EightDEffect.PERIOD_STEP_SECONDS)
        assertEquals(10f, EightDEffect().periodSeconds)
        assertEquals(EightDDirection.LEFT_TO_RIGHT, EightDEffect().direction)
    }

    @Test
    fun periodsAreFiniteClampedAndSnapped() {
        assertEquals(10f, EightDEffect.normalizePeriod(Float.NaN))
        assertEquals(10f, EightDEffect.normalizePeriod(Float.POSITIVE_INFINITY))
        assertEquals(3f, EightDEffect.normalizePeriod(-1f))
        assertEquals(30f, EightDEffect.normalizePeriod(100f))
        assertEquals(10.5f, EightDEffect.normalizePeriod(10.4f))
        assertEquals(10f, EightDEffect.normalizePeriod(10.1f))
        for (halfSeconds in 6..60) {
            val value = halfSeconds / 2f
            assertEquals(value, EightDEffect.normalizePeriod(value))
        }
    }

    @Test
    fun unknownSavedDirectionKeepsTheDefault() {
        assertEquals(EightDDirection.LEFT_TO_RIGHT, EightDDirection.fromStored("future"))
        assertEquals(EightDDirection.RIGHT_TO_LEFT, EightDDirection.fromStored("RIGHT_TO_LEFT"))
    }

    @Test
    fun everyPeriodCompletesOneFullRevolution() {
        for (halfSeconds in 6..60) {
            val period = halfSeconds / 2f
            for (direction in EightDDirection.entries) {
                var now = 0L
                val orbit = EightDOrbit { now }
                orbit.setEffect(EightDEffect(direction, period))
                orbit.setPlaying(true)
                now = (period * 1_000_000_000.0).toLong()
                assertAngle(0.0, orbit.snapshot().phaseRadians)
                now += (period * 250_000_000.0).toLong()
                assertAngle(direction.sign * PI / 2, orbit.snapshot().phaseRadians)
            }
        }
    }

    @Test
    fun reversingPreservesTheCurrentPosition() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        now = 1_000_000_000
        val before = orbit.snapshot().phaseRadians
        orbit.setEffect(EightDEffect(EightDDirection.RIGHT_TO_LEFT))
        assertAngle(before, orbit.snapshot().phaseRadians)
        now += 1_000_000_000
        assertAngle(0.0, orbit.snapshot().phaseRadians)
    }

    @Test
    fun changingThePeriodDoesNotRestartTheOrbit() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        now = 1_000_000_000
        val before = orbit.snapshot().phaseRadians
        orbit.setEffect(EightDEffect(periodSeconds = 5f))
        assertAngle(before, orbit.snapshot().phaseRadians)
        now += 1_000_000_000
        assertAngle(before + 2 * PI / 5, orbit.snapshot().phaseRadians)
    }

    @Test
    fun pauseFreezesAndResumeContinues() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        now = 1_000_000_000
        orbit.setPlaying(false)
        val phase = orbit.snapshot().phaseRadians
        now += 100_000_000_000
        assertAngle(phase, orbit.snapshot().phaseRadians)
        orbit.setPlaying(true)
        now += 1_000_000_000
        assertAngle(phase + 2 * PI / 10, orbit.snapshot().phaseRadians)
    }

    @Test
    fun disablingFreezesWithoutLosingThePosition() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        now = 1_000_000_000
        orbit.setEffect(null)
        val phase = orbit.snapshot().phaseRadians
        now += 100_000_000_000
        assertAngle(phase, orbit.snapshot().phaseRadians)
        orbit.setEffect(EightDEffect())
        assertAngle(phase, orbit.snapshot().phaseRadians)
    }

    @Test
    fun duplicateCollectorEmissionsDoNotChangeTheRevision() {
        val orbit = EightDOrbit { 0L }
        orbit.setEffect(EightDEffect())
        val first = orbit.snapshot()
        orbit.setEffect(EightDEffect())
        assertEquals(first, orbit.snapshot())
        orbit.setPlaying(true)
        val playing = orbit.snapshot()
        orbit.setPlaying(true)
        assertEquals(playing, orbit.snapshot())
    }

    @Test
    fun freshPlayersJoinTheSameOrbitWithoutResettingIt() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        now = 2_000_000_000
        val current = orbit.snapshot()
        val incoming = orbit.snapshot()
        assertEquals(current, incoming)
        assertAngle(2 * PI / 5, incoming.phaseRadians)
    }

    @Test
    fun alphaFollowsBrownDudaEquationFive() {
        // α_min at θ_min (150°), and the +6 dB high-frequency boost (α = 2) facing the source.
        assertEquals(2.0, EightDHead.alpha(0.0), 1e-12)
        assertEquals(0.1, EightDHead.alpha(EightDHead.THETA_MIN_RADIANS), 1e-12)
        // Behind the head it rises again towards Rayleigh's bright spot.
        assertTrue(EightDHead.alpha(PI) > EightDHead.alpha(EightDHead.THETA_MIN_RADIANS))
    }

    @Test
    fun incidenceIsMeasuredFromEachEarsAxis() {
        val right = PI / 2
        assertEquals(0.0, EightDHead.incidence(right, EightDHead.Ear.RIGHT), 1e-12)
        assertEquals(PI, EightDHead.incidence(right, EightDHead.Ear.LEFT), 1e-12)
        // Straight ahead and straight behind are equally far from both ears.
        for (azimuth in listOf(0.0, PI, -PI, 3 * PI)) {
            assertEquals(PI / 2, EightDHead.incidence(azimuth, EightDHead.Ear.LEFT), 1e-9)
            assertEquals(PI / 2, EightDHead.incidence(azimuth, EightDHead.Ear.RIGHT), 1e-9)
        }
    }

    @Test
    fun interauralDelayAtTheSideIsWoodworthsPointSixFiveSixMilliseconds() {
        val near = EightDHead.earDelaySeconds(0.0)
        val far = EightDHead.earDelaySeconds(PI)
        assertEquals(0.0, near, 1e-15)
        assertEquals(0.0875 / 343.0 * (1 + PI / 2), far - near, 1e-12)
        assertEquals(EightDHead.MAX_DELAY_SECONDS, far, 1e-15)
        assertTrue(far - near in 0.000655..0.000657)
        // The delay grows without a jump through the side of the head.
        assertEquals(EightDHead.earDelaySeconds(PI / 2 - 1e-9), EightDHead.earDelaySeconds(PI / 2 + 1e-9), 1e-12)
    }

    @Test
    fun theBrighterEarIsUntouchedAndTheRatioIsThePapers() {
        // Divided by the near ear alone, both ears facing front lost 8 dB of highs: 1.4 LU quieter.
        assertEquals(1.0, EightDHead.shadowGain(PI / 2), 1e-12)
        for (step in 0..180) {
            val theta = step * PI / 180
            val gain = EightDHead.shadowGain(theta)
            val other = EightDHead.shadowGain(PI - theta)
            assertTrue(gain <= 1.0 && other <= 1.0)
            assertEquals(1.0, maxOf(gain, other), 1e-12)
            assertEquals(EightDHead.alpha(theta) / EightDHead.alpha(PI - theta), gain / other, 1e-9)
            assertEquals(EightDHead.shadowPole(theta), EightDHead.shadowPole(PI - theta), 1e-9)
        }
    }

    @Test
    fun headShadowPassesLowsUnchangedAndScalesHighsByTheGain() {
        for (gain in listOf(1.0, 0.5, 0.1405, 0.05)) {
            val dc = EightDHeadShadow().apply { setSampleRate(48_000) }
            var y = 0.0
            repeat(48_000) { y = dc.process(0.5, gain, POLE) }
            assertEquals(0.5, y, 1e-9)
            // Alternating samples are Nyquist, where the bilinear filter's gain is exactly the gain.
            val nyquist = EightDHeadShadow().apply { setSampleRate(48_000) }
            repeat(48_000) { y = nyquist.process(if (it % 2 == 0) 0.5 else -0.5, gain, POLE) }
            assertEquals(0.5 * gain, abs(y), 1e-9)
        }
    }

    @Test
    fun theNearEarIsUntouchedAndNoEarIsEverLouderThanItsInput() {
        // Equation (3) as written lifted the near ear's highs 6 dB: a loud master peaked at
        // +4.7 dBFS and clipped thousands of samples. Divided by the brighter ear, nothing grows.
        val near = EightDHeadShadow().apply { setSampleRate(48_000) }
        repeat(4_800) {
            val x = if ((it / 3) % 2 == 0) 1.0 else -1.0
            assertEquals(x, near.process(x, 1.0, POLE), 1e-12)
        }
        for (step in 0..36) {
            val theta = step * PI / 36
            val shadow = EightDHeadShadow().apply { setSampleRate(44_100) }
            repeat(4_800) {
                val x = if ((it / (1 + step % 5)) % 2 == 0) 1.0 else -1.0
                assertTrue(abs(shadow.process(x, EightDHead.shadowGain(theta), EightDHead.shadowPole(theta))) <= 1.0 + 1e-12)
            }
        }
    }

    @Test
    fun theFarEarIsNeverSilentAtLowFrequencies() {
        // The complaint with the equal-power pan: the far ear dropped out completely.
        for (step in 0 until 72) {
            val azimuth = step * PI / 36
            for (ear in EightDHead.Ear.entries) {
                val shadow = EightDHeadShadow().apply { setSampleRate(48_000) }
                var y = 0.0
                val theta = EightDHead.incidence(azimuth, ear)
                repeat(4_800) { y = shadow.process(0.5, EightDHead.shadowGain(theta), EightDHead.shadowPole(theta)) }
                assertEquals(0.5, y, 1e-6)
            }
        }
    }

    @Test
    fun changingDirectionChangesTheMotionNotThePeriod() {
        val forward = EightDEffect()
        val reverse = forward.copy(direction = EightDDirection.RIGHT_TO_LEFT)
        assertTrue(forward.radiansPerSecond > 0)
        assertFalse(reverse.radiansPerSecond > 0)
        assertEquals(forward.radiansPerSecond, -reverse.radiansPerSecond)
    }

    private companion object {
        val POLE = EightDHead.shadowPole(0.0)
    }

    private fun assertAngle(expected: Double, actual: Double) {
        val error = EightDOrbit.wrapPhase(expected - actual)
        assertTrue(abs(error) < 1e-8 || abs(error - 2 * PI) < 1e-8, "angle $actual != $expected")
    }
}
