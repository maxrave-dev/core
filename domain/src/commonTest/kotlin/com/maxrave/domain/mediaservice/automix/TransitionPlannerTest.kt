package com.maxrave.domain.mediaservice.automix

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planner only decides where the fade sits: the outgoing window and the second the incoming track
 * plays from. Its length is the adapters' formula, handed in and kept. The fade ends on the outgoing
 * music's end, never starts before the middle of its last drop and never runs past its last sound.
 * Wherever loudness did not tell the parts apart, the incoming track plays from 0.
 */
class TransitionPlannerTest {
    private fun track(
        lengthMs: Long,
        musicEndMs: Long = lengthMs,
        introEndMs: Long? = null,
        lastChorusStartMs: Long? = null,
        lastChorusEndMs: Long? = null,
    ) = TrackStructure(
        bpm = 148.0,
        beatMs = 60_000 / 148.0,
        downbeatsMs = emptyList(),
        sections = emptyList(),
        firstSoundMs = 0,
        lastSoundMs = lengthMs,
        musicEndMs = musicEndMs,
        introEndMs = introEndMs,
        lastChorusStartMs = lastChorusStartMs,
        lastChorusEndMs = lastChorusEndMs,
    )

    @Test
    fun `the fade keeps the formula's length and mixes out over the outgoing outro`() {
        // BtZCAfq_514 as measured: last chorus 137-163 s, then an outro to 176 s.
        val outgoing = track(177_540, musicEndMs = 176_000, lastChorusStartMs = 137_000, lastChorusEndMs = 163_000)
        val plan = assertNotNull(TransitionPlanner.plan(outgoing, track(200_000), 20_000, 0.94f))

        assertEquals(20_000L, plan.durationMs)
        assertEquals(176_000L, plan.outgoingEndMs, absoluteTolerance = 2)
        // Slowed to 0.94 over the fade, the outgoing track covers 20 s x (1 - 0.06 x 0.7).
        assertEquals((176_000 - 20_000 * (1 - 0.06 * 0.7)).toLong(), plan.outgoingStartMs, absoluteTolerance = 2)
    }

    @Test
    fun `an outro too short for the fade still leaves the first half of the last drop clean`() {
        // Drop 120-152 s, a 3 s outro, sound to 170 s: ending on the music's end would start at 132 s.
        val outgoing = track(170_000, musicEndMs = 155_000, lastChorusStartMs = 120_000, lastChorusEndMs = 152_000)
        val plan = assertNotNull(TransitionPlanner.plan(outgoing, track(200_000), 23_000, 1f))

        assertEquals(136_000L, plan.outgoingStartMs)
        assertEquals(159_000L, plan.outgoingEndMs)
        assertEquals(23_000L, plan.durationMs)
    }

    @Test
    fun `a song that ends on its drop fades no later than its last sound`() {
        // Drop 120-150 s to the music's end, sound to 152 s: the drop's middle (135 s) plus the fade
        // would run 6 s past the last sound, so the fade keeps its length and starts at 129 s.
        val outgoing = track(152_000, musicEndMs = 150_000, lastChorusStartMs = 120_000)
        val plan = assertNotNull(TransitionPlanner.plan(outgoing, track(200_000), 23_000, 1f))

        assertEquals(129_000L, plan.outgoingStartMs)
        assertEquals(152_000L, plan.outgoingEndMs)
    }

    @Test
    fun `an intro longer than the fade starts the incoming track so its intro ends on the outgoing end`() {
        // BtZCAfq_514's own intro runs 0-33 s.
        val plan = assertNotNull(TransitionPlanner.plan(track(240_000), track(177_540, introEndMs = 33_000), 25_000, 1f))

        assertEquals(8_000L, plan.incomingStartMs)
        assertEquals(plan.durationMs, plan.outgoingEndMs - plan.outgoingStartMs, absoluteTolerance = 1)
    }

    @Test
    fun `parts loudness does not separate fall back to the old fade`() {
        val plan = assertNotNull(TransitionPlanner.plan(track(240_000, musicEndMs = 236_000), track(200_000), 20_000, 1f))
        assertEquals(236_000L, plan.outgoingEndMs)
        assertEquals(0L, plan.incomingStartMs)
        // An intro shorter than the fade also plays from 0.
        assertEquals(0L, assertNotNull(TransitionPlanner.plan(track(240_000), track(200_000, introEndMs = 9_000), 20_000, 1f)).incomingStartMs)
    }

    @Test
    fun `an outgoing song too short for the fade falls back to the old one`() {
        assertNull(TransitionPlanner.plan(track(15_000), track(200_000), 20_000, 1f))
    }

    @Test
    fun `a late start keeps the incoming track in step`() {
        val plan = TransitionPlan(outgoingStartMs = 100_000, outgoingEndMs = 112_000, durationMs = 12_000, incomingStartMs = 2_000)

        val late = assertNotNull(plan.startingAt(100_400))
        assertEquals(2_400L, late.incomingStartMs)
        assertEquals(11_600L, late.durationMs)
        assertEquals(late.incomingStartMs + late.durationMs, plan.incomingStartMs + plan.durationMs)

        assertEquals(plan, plan.startingAt(99_800))
        assertNull(plan.startingAt(111_500), "under a second left")
        assertNull(plan.startingAt(90_000), "nowhere near the window")
    }

    private fun assertEquals(
        expected: Long,
        actual: Long,
        absoluteTolerance: Long,
    ) = assertTrue(abs(expected - actual) <= absoluteTolerance, "expected $expected ± $absoluteTolerance, was $actual")
}
