package com.maxrave.domain.mediaservice.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A synthetic track with every answer known: 2 s of silence, 16 quiet bars of a 120 BPM kick
 * (accented on each bar's first beat) over a sustained A minor chord, 16 bars with the kick 15 dB
 * louder, then silence again. A Python mirror of the analyzer reads it as 120 BPM, sound from
 * 2000 to 65990 ms and a +8.2 dB rise at 34000 ms.
 */
class TrackStructureAnalyzerTest {
    private val rate = TrackStructureAnalyzer.SAMPLE_RATE
    private val beatSeconds = 0.5
    private val leadSeconds = 2.0
    private val bars = 32
    private val quietBars = 16

    private fun synth(): ShortArray {
        val musicSeconds = bars * 4 * beatSeconds
        val total = ((leadSeconds * 2 + musicSeconds) * rate).toInt()
        val pcm = ShortArray(total)
        val chord = doubleArrayOf(110.0, 220.0, 261.63, 329.63)
        for (i in pcm.indices) {
            val t = i.toDouble() / rate - leadSeconds
            if (t < 0 || t >= musicSeconds) continue
            val beat = (t / beatSeconds).toInt()
            val sinceBeat = t - beat * beatSeconds
            val loud = beat / 4 >= quietBars
            val accent = if (beat % 4 == 0) 1.0 else 0.6
            val kick = (if (loud) 0.6 else 0.1) * accent * exp(-sinceBeat * 30) * sin(2 * PI * 50.0 * sinceBeat)
            // Quiet enough that the louder kick makes an 8 dB rise, well past the 6 dB a drop needs.
            val pad = chord.sumOf { 0.015 * sin(2 * PI * it * t) }
            pcm[i] = ((kick + pad) * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    @Test
    fun `reads tempo, silent edges, the intro and where the music ends`() {
        val structure = assertNotNull(TrackStructureAnalyzer.analyze(synth()))

        assertTrue(abs(structure.bpm - 120.0) < 1.0, "bpm ${structure.bpm}")
        assertTrue(abs(structure.firstSoundMs - 2_000) <= 50, "first sound ${structure.firstSoundMs}")
        val musicEndMs = ((leadSeconds + bars * 4 * beatSeconds) * 1000).toLong()
        assertTrue(abs(structure.lastSoundMs - musicEndMs) <= 100, "last sound ${structure.lastSoundMs}")
        // The last bar has no beat after it; the closing bar line is extrapolated one bar on.
        assertTrue(abs(structure.musicEndMs - musicEndMs) <= 100, "music end ${structure.musicEndMs}")

        // The quiet half is the intro: 8 dB under the loud half, which is the only chorus and runs to
        // the end, so there is no outro to cut.
        val introEndMs = ((leadSeconds + quietBars * 4 * beatSeconds) * 1000).toLong()
        val introEnd = assertNotNull(structure.introEndMs)
        assertTrue(abs(introEnd - introEndMs) <= 100, "intro ends $introEnd, expected $introEndMs")
        assertTrue(structure.downbeatsMs.any { abs(it - introEndMs) <= 100 }, "no bar line where the intro ends")
        assertNull(structure.lastChorusEndMs)
    }

    @Test
    fun `too little sound is not analysed`() {
        assertNull(TrackStructureAnalyzer.analyze(ShortArray(rate * 60)))
    }

    @Test
    fun `stored results round trip, and an empty file reads as unusable`() {
        val structure = assertNotNull(TrackStructureAnalyzer.analyze(synth()))
        assertEquals(structure, TrackStructure.decode(structure.encode()))
        assertNull(TrackStructure.decode(""))
    }
}
