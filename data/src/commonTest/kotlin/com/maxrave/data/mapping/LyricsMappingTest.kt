package com.maxrave.data.mapping

import org.simpmusic.lyrics.models.response.LyricsResponse
import org.simpmusic.lyrics.models.response.TranslatedLyricsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsMappingTest {
    @Test
    fun malformedRichSyncFallsBackToSyncedLyrics() {
        val response =
            response(
                richSyncLyrics = "unparseable rich sync content",
                syncedLyrics = "[00:27.12]Synced line",
                plainLyric = "Plain line",
            )
        val mapped = assertNotNull(response.toLyrics())
        assertEquals("LINE_SYNCED", mapped.syncType)
        assertEquals("27120", mapped.lines?.single()?.startTimeMs)
        assertEquals("Synced line", mapped.lines?.single()?.words)
    }

    @Test
    fun richSyncWithoutWordTimingFallsBackToSyncedLyrics() {
        val response =
            response(
                richSyncLyrics = "[00:10.00] Line only without word tags",
                syncedLyrics = "[00:27.12]Proper synced",
                plainLyric = "Plain",
            )
        val mapped = assertNotNull(response.toLyrics())
        assertEquals("LINE_SYNCED", mapped.syncType)
        assertEquals("Proper synced", mapped.lines?.single()?.words)
    }

    @Test
    fun malformedRichAndSyncedFallBackToPlainLyrics() {
        val response =
            response(
                richSyncLyrics = "invalid",
                syncedLyrics = "[ti:Header Only]",
                plainLyric = "Plain line",
            )
        val mapped = assertNotNull(response.toLyrics())
        assertEquals("UNSYNCED", mapped.syncType)
        assertEquals("Plain line", mapped.lines?.single()?.words)
    }

    @Test
    fun completelyEmptyOrUnparseableLyricsReturnNullInsteadOfEmptySuccess() {
        val response =
            response(
                richSyncLyrics = "invalid",
                syncedLyrics = "also invalid",
                plainLyric = "   \n  ",
            )
        assertNull(response.toLyrics())
    }

    @Test
    fun translatedLyricsReturnNullWhenUnparseable() {
        val invalid =
            TranslatedLyricsResponse(
                id = "1",
                videoId = "v1",
                translatedLyric = "not timed text",
                language = "pt",
                vote = 1,
                contributor = "c",
                contributorEmail = "c@e.com",
            )
        assertNull(invalid.toLyrics())

        val valid = invalid.copy(translatedLyric = "[00:27.12]Olá")
        val mapped = assertNotNull(valid.toLyrics())
        assertEquals("27120", mapped.lines?.single()?.startTimeMs)
        assertEquals("Olá", mapped.lines?.single()?.words)
    }

    private fun response(
        richSyncLyrics: String?,
        syncedLyrics: String?,
        plainLyric: String,
    ) = LyricsResponse(
        id = "id1",
        videoId = "vid1",
        songTitle = "Song",
        artistName = "Artist",
        albumName = "Album",
        durationSeconds = 180,
        plainLyric = plainLyric,
        syncedLyrics = syncedLyrics,
        richSyncLyrics = richSyncLyrics,
        trackType = "SONG",
        vote = 0,
        contributor = "c",
        contributorEmail = "e",
    )
}
