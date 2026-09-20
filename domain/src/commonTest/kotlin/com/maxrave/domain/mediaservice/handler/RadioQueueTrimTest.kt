package com.maxrave.domain.mediaservice.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trim drops tracks the user can no longer reach, from a list the player and the queue state
 * both index into, so an off-by-one here is not a cosmetic bug: it would cut the playing track out
 * from under the player, or shift the two lists against each other and make the queue screen play
 * the wrong song.
 *
 * Every case below is a number the caller can actually hand this function, including the ones that
 * should leave the queue alone.
 */
class RadioQueueTrimTest {
    @Test
    fun `leaves the queue alone until the history passes the threshold`() {
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = 0, queueSize = 50))
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = 100, queueSize = 200))
        assertEquals(
            0,
            RadioQueueTrim.countToDropFromFront(
                currentIndex = RadioQueueTrim.TRIM_ABOVE_HISTORY,
                queueSize = 200,
            ),
        )
    }

    @Test
    fun `trims down to the kept history once above the threshold`() {
        assertEquals(51, RadioQueueTrim.countToDropFromFront(currentIndex = 151, queueSize = 200))
        assertEquals(60, RadioQueueTrim.countToDropFromFront(currentIndex = 160, queueSize = 210))
        assertEquals(99, RadioQueueTrim.countToDropFromFront(currentIndex = 199, queueSize = 200))
    }

    @Test
    fun `refuses nonsense input instead of cutting something`() {
        // The index is out of the list: the two lists are out of step, so touching them is the
        // last thing to do.
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = 200, queueSize = 120))
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = 200, queueSize = 200))
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = -5, queueSize = 200))
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = 160, queueSize = 0))
        assertEquals(0, RadioQueueTrim.countToDropFromFront(currentIndex = 160, queueSize = 90))
    }

    @Test
    fun `keeps the playing track and exactly the kept history behind it`() {
        for (currentIndex in 151..400) {
            val queueSize = currentIndex + 50
            val dropped = RadioQueueTrim.countToDropFromFront(currentIndex, queueSize)
            assertTrue(dropped > 0, "should trim at index $currentIndex")
            assertTrue(dropped < queueSize, "must never drop the whole queue")
            assertEquals(
                RadioQueueTrim.KEEP_HISTORY,
                currentIndex - dropped,
                "the playing track must land exactly ${RadioQueueTrim.KEEP_HISTORY} tracks in",
            )
        }
    }
}
