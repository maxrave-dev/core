package org.simpmusic.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from Metrolist's ServerClockTest (GPL-3.0).
 *
 * The expected numbers are copied verbatim, not recomputed: they encode the exact offset formula
 * every client in a room has to agree on, so a "cleaner" arithmetic that shifts them by a few
 * milliseconds is a desync, not a refactor.
 */
class ServerClockTest {
    @Test
    fun mapsServerWallTimeOntoLocalMonotonicTime() {
        var elapsedRealtime = 1_000L
        val clock = ServerClock { elapsedRealtime }

        assertNull(clock.now())
        elapsedRealtime = 1_120L
        assertTrue(
            clock.recordPong(
                clientTime = 1_000L,
                serverReceiveTime = 10_000L,
                serverSendTime = 10_010L,
            ),
        )

        assertEquals(10_065L, clock.now())
        assertEquals(565L, clock.positionAt(500L, 10_000L, isPlaying = true))

        elapsedRealtime += 100L
        assertEquals(10_165L, clock.now())
        assertEquals(665L, clock.positionAt(500L, 10_000L, isPlaying = true))
        assertEquals(500L, clock.positionAt(500L, 10_000L, isPlaying = false))
    }

    @Test
    fun rejectsInvalidAndStaleSamples() {
        var elapsedRealtime = 100_000L
        val clock = ServerClock { elapsedRealtime }

        assertFalse(clock.recordPong(0L, 1_000L, 1_001L))
        assertFalse(clock.recordPong(100_001L, 1_000L, 1_001L))
        assertFalse(clock.recordPong(100_000L, 1_001L, 1_000L))
        elapsedRealtime = 200_001L
        assertFalse(clock.recordPong(100_000L, 1_000L, 1_001L))
        assertNull(clock.now())
    }

    @Test
    fun resetRemovesTheServerClockMapping() {
        var elapsedRealtime = 1_100L
        val clock = ServerClock { elapsedRealtime }
        clock.recordPong(1_000L, 10_000L, 10_000L)

        clock.reset()
        elapsedRealtime += 100L

        assertNull(clock.now())
        assertEquals(500L, clock.positionAt(500L, 10_000L, isPlaying = true))
    }
}
