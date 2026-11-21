package com.maxrave.data.repository

import org.simpmusic.lyrics.models.response.LyricsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SimpMusicLyricsSelectionTest {
    @Test
    fun prefersRichSyncedOverHigherVotedLineSynced() {
        val lineHighVote =
            candidate(
                id = "line-top",
                vote = 25,
                syncedLyrics = "[00:05.00]Line\n[00:10.00]Two",
                richSyncLyrics = null,
            )
        val richLowVote =
            candidate(
                id = "rich-low",
                vote = 1,
                syncedLyrics = null,
                richSyncLyrics = "[00:05.00] <00:05.00>Word <00:05.50>sync",
            )

        val picked = assertNotNull(pickBestSimpMusicLyrics(listOf(lineHighVote, richLowVote)))
        assertEquals("rich-low", picked.simpMusicLyrics?.id)
        assertEquals("RICH_SYNCED", picked.syncType)
    }

    @Test
    fun breaksSameQualityTiesByHigherVoteAndSkipsEmptyCandidates() {
        val unusable =
            candidate(
                id = "bad",
                vote = 99,
                syncedLyrics = null,
                richSyncLyrics = null,
                plainLyric = "   ",
            )
        val lineLowVote =
            candidate(
                id = "line-1",
                vote = 3,
                syncedLyrics = "[00:05.00]A\n[00:10.00]B",
            )
        val lineHighVote =
            candidate(
                id = "line-2",
                vote = 12,
                syncedLyrics = "[00:05.00]A\n[00:10.00]B",
            )

        val picked = assertNotNull(pickBestSimpMusicLyrics(listOf(unusable, lineLowVote, lineHighVote)))
        assertEquals("line-2", picked.simpMusicLyrics?.id)
        assertEquals(12, picked.simpMusicLyrics?.vote)
    }

    @Test
    fun returnsNullWhenAllCandidatesUnusable() {
        val unusable = candidate(id = "bad", vote = 10, syncedLyrics = null, richSyncLyrics = null, plainLyric = "")
        assertNull(pickBestSimpMusicLyrics(listOf(unusable)))
    }

    private fun candidate(
        id: String,
        vote: Int,
        richSyncLyrics: String? = null,
        syncedLyrics: String? = null,
        plainLyric: String = "",
    ) = LyricsResponse(
        id = id,
        videoId = "vid1",
        songTitle = "Song",
        artistName = "Artist",
        albumName = "Album",
        durationSeconds = 180,
        plainLyric = plainLyric,
        syncedLyrics = syncedLyrics,
        richSyncLyrics = richSyncLyrics,
        trackType = "SONG",
        vote = vote,
        contributor = "c",
        contributorEmail = "e",
    )
}
