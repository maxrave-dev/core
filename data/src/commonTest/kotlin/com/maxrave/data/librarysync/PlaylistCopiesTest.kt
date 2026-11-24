package com.maxrave.data.librarysync

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * How library sync tells that a playlist the other device sends is one this device already had before
 * the two were paired (same name, same songs), and which copy both devices keep when cleaning up.
 */
class PlaylistCopiesTest {
    @Test
    fun `the same songs match whatever their order or repeats`() {
        assertEquals(songSetKey(listOf("a", "b", "c")), songSetKey(listOf("c", "a", "b", "a")))
    }

    @Test
    fun `a song more, a song less or another song does not match`() {
        val key = songSetKey(listOf("a", "b", "c"))
        assertNotEquals(key, songSetKey(listOf("a", "b")))
        assertNotEquals(key, songSetKey(listOf("a", "b", "c", "d")))
        assertNotEquals(key, songSetKey(listOf("a", "b", "x")))
    }

    @Test
    fun `an empty playlist is never matched by its songs`() {
        assertNull(songSetKey(emptyList()))
    }

    @Test
    fun `every device keeps the copy linked to YouTube, then the smallest syncId`() {
        val plain = LocalPlaylistEntity(id = 1, title = "abcvz", syncId = "b")
        val smallest = LocalPlaylistEntity(id = 9, title = "abcvz", syncId = "a")
        val linked = LocalPlaylistEntity(id = 5, title = "abcvz", syncId = "c", youtubePlaylistId = "PL1")
        val neverSynced = LocalPlaylistEntity(id = 0, title = "abcvz")

        assertEquals(linked, listOf(plain, smallest, linked, neverSynced).sortedWith(keptCopyOrder).first())
        // The same copies listed in another order, as another device's ids would have them.
        assertEquals(smallest, listOf(neverSynced, plain, smallest).sortedWith(keptCopyOrder).first())
    }
}
