package com.maxrave.domain.mediaservice.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertEquals(21, RadioQueueTrim.countToDropFromFront(currentIndex = 121, queueSize = 200))
        assertEquals(47, RadioQueueTrim.countToDropFromFront(currentIndex = 147, queueSize = 200))
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
    fun `trims only real radios and never a list the user picked`() {
        // Song, endpoint and artist radios.
        assertTrue(RadioQueueTrim.appliesTo(PlaylistType.RADIO, "RDAMVMdQw4w9WgXcQ"))
        assertTrue(RadioQueueTrim.appliesTo(PlaylistType.RADIO, "RDEMabc"))
        assertTrue(RadioQueueTrim.appliesTo(PlaylistType.RADIO, "RDATabc"))
        // Favorites, Downloaded, Most played, Monthly recap and the Analytics lists: RADIO, no id.
        assertFalse(RadioQueueTrim.appliesTo(PlaylistType.RADIO, null))
        // A playlist's Shuffle plays from the playlist's own id.
        assertFalse(RadioQueueTrim.appliesTo(PlaylistType.RADIO, "PLabc"))
        // A curated playlist is RD-prefixed but finite, with or without the library's VL prefix.
        assertFalse(RadioQueueTrim.appliesTo(PlaylistType.RADIO, "RDCLAK5uy_abc"))
        assertFalse(RadioQueueTrim.appliesTo(PlaylistType.RADIO, "VLRDCLAK5uy_abc"))
        // Not a radio until Endless queue re-types it.
        assertFalse(RadioQueueTrim.appliesTo(PlaylistType.PLAYLIST, "RDAMVMabc"))
        assertFalse(RadioQueueTrim.appliesTo(null, "RDAMVMabc"))
    }

    @Test
    fun `cuts the mirrored queue only when the removed tracks are its front`() {
        val queue = listOf("a", "b", "a", "c", "d")
        assertEquals(
            listOf("a", "c", "d"),
            RadioQueueTrim.afterFrontRemoved(queue, listOf("a", "b")) { it },
        )
        // A song the radio repeated is matched by position, not by id alone.
        assertEquals(
            listOf("c", "d"),
            RadioQueueTrim.afterFrontRemoved(queue, listOf("a", "b", "a")) { it },
        )
        // The queue changed under the request: leave it, never cut it out of step.
        assertNull(RadioQueueTrim.afterFrontRemoved(queue, listOf("b", "a")) { it })
        assertNull(RadioQueueTrim.afterFrontRemoved(queue, listOf("a", "c")) { it })
        assertNull(RadioQueueTrim.afterFrontRemoved(listOf("a"), listOf("a", "b")) { it })
    }

    @Test
    fun `keeps the playing track and exactly the kept history behind it`() {
        for (currentIndex in 121..400) {
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
