package com.maxrave.domain.mediaservice.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BassEnvelopeTest {
    private val sampleRate = 8_000

    /**
     * [seconds] of a 60 Hz burst for the first 100 ms of every 500 ms, [amplitude] at each millisecond, and
     * [between] in the gaps.
     */
    private fun envelope(
        seconds: Int = 2,
        amplitude: (Long) -> Float = { 0.8f },
        between: (Int) -> Float = { 0f },
    ): BassEnvelope =
        BassEnvelope().apply {
            repeat(sampleRate * seconds) { n ->
                val timeUs = n * 1_000_000L / sampleRate
                val ms = timeUs / 1000L
                val inBurst = ms % 500L < 100L
                val sample = if (inBurst) amplitude(ms) * sin(2 * PI * 60 * n / sampleRate).toFloat() else between(n)
                add(timeUs, sample, sampleRate)
            }
        }

    @Test
    fun quietVerseKicksPulseAsPlainlyAsTheDrop() {
        // Four seconds of kicks at a quarter of the drop's level, then the drop.
        val bass = envelope(seconds = 8, amplitude = { ms -> if (ms < 4_000L) 0.2f else 0.8f })
        assertTrue(bass.pulseAt(1_050) > 0.8f)
        assertTrue(bass.pulseAt(6_050) > 0.8f)
        assertTrue(bass.pulseAt(1_300) < 0.05f)
    }

    @Test
    fun pulsesOnBassHitsAndRestsBetweenThem() {
        val bass = envelope()
        assertTrue(bass.pulseAt(50) > 0.8f)
        assertTrue(bass.pulseAt(1_050) > 0.8f)
        assertTrue(bass.pulseAt(300) < 0.05f)
        assertTrue(bass.pulseAt(1_300) < 0.05f)
    }

    @Test
    fun highFrequenciesDoNotPulse() {
        // As loud as the bass, but a 2 kHz tone: the low-pass leaves almost nothing of it.
        val bass = envelope(between = { n -> 0.8f * sin(2 * PI * 2_000 * n / sampleRate).toFloat() })
        assertTrue(bass.pulseAt(50) > 0.8f)
        assertTrue(bass.pulseAt(300) < 0.05f)
    }

    @Test
    fun unmeasuredPositionsDoNotPulse() {
        val bass = envelope()
        assertEquals(0f, bass.pulseAt(5_000))
        assertEquals(0f, bass.pulseAt(-1))
        assertEquals(0f, BassEnvelope().pulseAt(50))
    }

    @Test
    fun storedLevelsPulseTheSame() {
        val bass = envelope()
        val restored = BassEnvelope.fromLevels(bass.levels())
        listOf(50L, 300L, 1_050L, 1_300L).forEach { positionMs ->
            assertEquals(bass.pulseAt(positionMs), restored.pulseAt(positionMs), 0.001f)
        }
    }
}
