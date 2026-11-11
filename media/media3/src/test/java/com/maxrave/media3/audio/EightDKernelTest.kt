package com.maxrave.media3.audio

import com.maxrave.domain.data.player.EightDDirection
import com.maxrave.domain.data.player.EightDEffect
import com.maxrave.domain.data.player.EightDHead
import com.maxrave.domain.data.player.EightDOrbit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EightDKernelTest {
    @Test
    fun disabledIsAnExactIdentity() {
        val kernel = EightDKernel(EightDOrbit { 0L }).apply { markStarted() }
        kernel.beginBuffer(48_000)
        assertTrue(kernel.isBypassed)
        kernel.process(0.37, -0.82)
        assertEquals(0.37, kernel.left, 0.0)
        assertEquals(-0.82, kernel.right, 0.0)
    }

    @Test
    fun frameCountNotBufferSizeDrivesRotation() {
        val first = activeKernel()
        val second = activeKernel()
        repeat(48_000) {
            if (it % 256 == 0) first.beginBuffer(48_000)
            if (it % 1024 == 0) second.beginBuffer(48_000)
            val x = sin(it * 0.05)
            first.process(x, 0.7 * x)
            second.process(x, 0.7 * x)
            assertEquals(first.left, second.left, 1e-12)
            assertEquals(first.right, second.right, 1e-12)
        }
    }

    @Test
    fun reversingDoesNotJumpTheCurrentSample() {
        val orbit = EightDOrbit { 0L }
        orbit.setEffect(EightDEffect())
        val kernel = EightDKernel(orbit).apply { markStarted() }
        kernel.beginBuffer(48_000)
        repeat(48_000) { kernel.process(0.4, 0.8) }
        val before = kernel.phaseRadians
        orbit.setEffect(EightDEffect(EightDDirection.RIGHT_TO_LEFT))
        kernel.beginBuffer(48_000)
        assertEquals(before, kernel.phaseRadians, 0.0)
        kernel.process(0.4, 0.8)
        assertTrue(kernel.phaseRadians < before)
    }

    @Test
    fun changingPeriodDoesNotJumpTheCurrentSample() {
        val orbit = EightDOrbit { 0L }
        orbit.setEffect(EightDEffect())
        val kernel = EightDKernel(orbit).apply { markStarted() }
        kernel.beginBuffer(48_000)
        repeat(48_000) { kernel.process(0.4, 0.8) }
        val before = kernel.phaseRadians
        orbit.setEffect(EightDEffect(periodSeconds = 5f))
        kernel.beginBuffer(48_000)
        assertEquals(before, kernel.phaseRadians, 0.0)
    }

    @Test
    fun disablingFadesBackToIdentityAndThenBypasses() {
        val orbit = EightDOrbit { 0L }
        orbit.setEffect(EightDEffect())
        val kernel = EightDKernel(orbit).apply { markStarted() }
        kernel.beginBuffer(48_000)
        repeat(48_000) { kernel.process(0.4, 0.8) }
        orbit.setEffect(null)
        kernel.beginBuffer(48_000)
        repeat(48_000) { kernel.process(0.4, 0.8) }
        assertTrue(kernel.isBypassed)
        assertEquals(0.4, kernel.left, 0.0)
        assertEquals(0.8, kernel.right, 0.0)
    }

    @Test
    fun aPausedPrecacheStaysDryAndJoinsTheOrbitWhenItStarts() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        val kernel = EightDKernel(orbit)
        // Filling the AudioTrack while paused, long before the track is heard.
        now = 1_000_000_000
        kernel.beginBuffer(48_000)
        repeat(4_800) {
            kernel.process(1.0, 0.0)
            assertEquals(1.0, kernel.left, 0.0)
            assertEquals(0.0, kernel.right, 0.0)
        }
        // Started at a quarter turn: joins the live angle, not the one at precache time,
        // and fades in from the dry audio that is heard first.
        now = 2_500_000_000
        kernel.markStarted()
        kernel.beginBuffer(48_000)
        assertEquals(orbit.snapshot().phaseRadians, kernel.phaseRadians, 1e-12)
        kernel.process(1.0, 0.0)
        assertEquals(1.0, kernel.left, 1e-3)
        assertEquals(0.0, kernel.right, 1e-3)
        // Once in, each ear keeps its own channel.
        repeat(4_800) { kernel.process(1.0, 0.0) }
        assertEquals(1.0, kernel.left, 1e-3)
        assertEquals(0.0, kernel.right, 0.0)
    }

    @Test
    fun aPlayerStartedBeforeItRendersJoinsAtFullDepth() {
        val kernel = kernelAtTheRightOfTheHead()
        // Fully wet from the start: both ears read one sample late, then the near (right) ear
        // hears the source while the far (left) one has not yet. Dry, both would read 1.0.
        kernel.process(1.0, 1.0)
        kernel.process(1.0, 1.0)
        assertEquals(0.0, kernel.left, 1e-9)
        assertTrue(kernel.right > 0.9)
    }

    @Test
    fun aCentredSourceReachesBothEarsAtFullLevelEverywhere() {
        // The complaint with the equal-power pan: a far ear at -180 dB.
        for (step in 0 until 24) {
            val kernel = kernelAt(azimuth = step * PI / 12)
            repeat(4_800) { kernel.process(1.0, 1.0) }
            assertEquals(1.0, kernel.left, 1e-9)
            assertEquals(1.0, kernel.right, 1e-9)
        }
    }

    @Test
    fun eachEarKeepsItsOwnChannel() {
        // Folding to mono shrank the whole mix to one point and combed its wide parts hollow.
        for (step in 0 until 24) {
            val kernel = kernelAt(azimuth = step * PI / 12)
            repeat(4_800) { kernel.process(1.0, 0.0) }
            assertEquals(1.0, kernel.left, 1e-9)
            assertEquals(0.0, kernel.right, 0.0)
        }
    }

    @Test
    fun aFullScaleToneIsNeverLouderAtAnyAngle() {
        // Equation (3) as written lifted a 1 kHz tone at the near ear to 1.47 and clipped.
        for (step in 0 until 24) {
            val kernel = kernelAt(azimuth = step * PI / 12)
            repeat(9_600) {
                val x = sin(2 * PI * 1_000.0 * it / 48_000)
                kernel.process(x, x)
                assertTrue(abs(kernel.left) <= 1.0 + 1e-3 && abs(kernel.right) <= 1.0 + 1e-3)
            }
        }
    }

    @Test
    fun lowsReachBothEarsAndHighsAreShadowedAsInTheReferenceTrack() {
        // Source at the right ear. Reference track: < 4 dB apart below 500 Hz, up to 17.8 dB above 4 kHz.
        val low = interauralLevelDifferenceDb(frequency = 200.0)
        val high = interauralLevelDifferenceDb(frequency = 8_000.0)
        assertTrue("200 Hz ILD $low", low in 0.0..2.0)
        assertTrue("8 kHz ILD $high", high in 12.0..18.0)
    }

    @Test
    fun theFarEarLagsByTheWoodworthDelay() {
        val kernel = kernelAtTheRightOfTheHead()
        val left = DoubleArray(96)
        val right = DoubleArray(96)
        for (i in left.indices) {
            val x = if (i == 0) 1.0 else 0.0
            kernel.process(x, x)
            left[i] = kernel.left
            right[i] = kernel.right
        }
        val nearPeak = right.indices.maxBy { abs(right[it]) }
        val farOnset = left.indices.first { abs(left[it]) > 0.01 }
        val expected = EightDHead.MAX_DELAY_SECONDS * 48_000
        assertTrue("near $nearPeak far $farOnset", (farOnset - nearPeak) in (expected - 2).toInt()..(expected + 1).toInt())
    }

    @Test
    fun reconfiguringTheSampleRateKeepsTheDelayInSeconds() {
        val kernel = kernelAt(azimuth = PI / 2)
        kernel.beginBuffer(96_000)
        val left = DoubleArray(192)
        for (i in left.indices) {
            val x = if (i == 0) 1.0 else 0.0
            kernel.process(x, x)
            left[i] = kernel.left
        }
        val farOnset = left.indices.first { abs(left[it]) > 0.01 }
        assertTrue("far onset $farOnset", farOnset in 60..66)
    }

    @Test
    fun switchingOnMidStreamStillFadesIn() {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect())
        orbit.setPlaying(true)
        now = 2_500_000_000
        // Off at a quarter turn, where the head-rendered audio is far from the dry input.
        orbit.setEffect(null)
        val kernel = EightDKernel(orbit).apply { markStarted() }
        kernel.beginBuffer(48_000)
        kernel.process(1.0, 0.0)
        orbit.setEffect(EightDEffect())
        kernel.beginBuffer(48_000)
        kernel.process(1.0, 0.0)
        // The first wet sample is still almost the dry input.
        assertEquals(1.0, kernel.left, 1e-3)
    }

    @Test
    fun outputsStayFiniteWhileSwitchingOnAndOff() {
        val orbit = EightDOrbit { 0L }
        orbit.setEffect(EightDEffect(periodSeconds = EightDEffect.MIN_PERIOD_SECONDS))
        val kernel = EightDKernel(orbit).apply { markStarted() }
        for (rate in listOf(44_100, 48_000, 96_000)) {
            repeat(rate) {
                // A new buffer every 256 frames; the effect flips every 1000 frames, mid-fade.
                if (it % 1_000 == 0) orbit.setEffect(if ((it / 1_000) % 2 == 0) EightDEffect() else null)
                if (it % 256 == 0) kernel.beginBuffer(rate)
                val x = sin(it * 0.3)
                kernel.process(x, 0.5 * x)
                // The head shadow never amplifies; only the cubic delay interpolation can overshoot,
                // by at most the sum of its weights' magnitudes, 1.25.
                assertTrue(kernel.left.isFinite() && abs(kernel.left) <= 1.25)
                assertTrue(kernel.right.isFinite() && abs(kernel.right) <= 1.25)
            }
        }
    }

    private fun interauralLevelDifferenceDb(frequency: Double): Double {
        val kernel = kernelAtTheRightOfTheHead()
        var left = 0.0
        var right = 0.0
        repeat(9_600) {
            val x = sin(2 * PI * frequency * it / 48_000)
            kernel.process(x, x)
            if (it >= 4_800) {
                left += kernel.left * kernel.left
                right += kernel.right * kernel.right
            }
        }
        return 20 * log10(sqrt(right) / sqrt(left))
    }

    /** A kernel joined at the right of the head, turning as slowly as allowed (30 s per turn). */
    private fun kernelAtTheRightOfTheHead() = kernelAt(azimuth = PI / 2)

    private fun kernelAt(azimuth: Double): EightDKernel {
        var now = 0L
        val orbit = EightDOrbit { now }
        orbit.setEffect(EightDEffect(periodSeconds = EightDEffect.MAX_PERIOD_SECONDS))
        orbit.setPlaying(true)
        now = (azimuth / (2 * PI) * EightDEffect.MAX_PERIOD_SECONDS * 1_000_000_000).toLong()
        return EightDKernel(orbit).also {
            it.markStarted()
            it.beginBuffer(48_000)
        }
    }

    private fun activeKernel(): EightDKernel {
        val orbit = EightDOrbit { 0L }
        orbit.setEffect(EightDEffect())
        return EightDKernel(orbit).also {
            it.markStarted()
            it.beginBuffer(48_000)
        }
    }
}
