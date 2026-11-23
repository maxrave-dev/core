package com.maxrave.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMatchTest {
    @Test
    fun theOriginalIsNotTheRemix() {
        assertEquals(0f, trackMatchScore("Told You So (Brooks Remix)", listOf("Martin Garrix"), "Told You So", listOf("Martin Garrix", "Jex")))
        assertEquals(
            1f,
            trackMatchScore("Told You So (Brooks Remix)", listOf("Martin Garrix"), "Told You So - Brooks Remix", listOf("Martin Garrix", "Jex", "Brooks")),
        )
    }

    @Test
    fun anotherSongOrAnotherArtistScoresZero() {
        assertEquals(0f, trackMatchScore("Shape of You", listOf("Ed Sheeran"), "Perfect", listOf("Ed Sheeran")))
        assertEquals(0f, trackMatchScore("Shape of You", listOf("Ed Sheeran"), "Shape of You", listOf("Some Cover Band")))
    }

    @Test
    fun featuredArtistsAndAccentsDoNotGetInTheWay() {
        assertEquals(1f, trackMatchScore("Stay With Me (feat. Mary J. Blige)", listOf("Sam Smith"), "Stay With Me", listOf("Sam Smith")))
        assertEquals(1f, trackMatchScore("Chúng Ta Của Hiện Tại", listOf("Sơn Tùng M-TP"), "Chúng Ta Của Hiện Tại", listOf("Sơn Tùng M-TP")))
    }

    @Test
    fun unknownArtistsLeaveTheTitleToDecide() {
        assertTrue(trackMatchScore("Song (Live)", emptyList(), "Song - Live at Wembley", listOf("A")) > 0.6f)
    }
}
