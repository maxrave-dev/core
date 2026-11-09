package org.simpmusic.lyrics

import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.models.response.LrclibObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LrclibSelectionTest {
    @Test
    fun prefersWordSyncAmongDurationMatches() {
        val plain = record(1, 137f)
        val rich = record(2, 139f, hasWordSync = true)
        assertEquals(rich, selectLrclibLyricsRecord(listOf(plain, rich), 137))
    }

    @Test
    fun wordSyncNeverBypassesDurationTolerance() {
        val outside = record(1, 148f, hasWordSync = true)
        val matching = record(2, 137f)
        assertEquals(matching, selectLrclibLyricsRecord(listOf(outside, matching), 137))
        assertNull(selectLrclibLyricsRecord(listOf(outside), 137))
    }

    @Test
    fun preservesInclusiveTenSecondBoundary() {
        val plain = record(1, 137f)
        val boundary = record(2, 147f, hasWordSync = true)
        assertEquals(boundary, selectLrclibLyricsRecord(listOf(plain, boundary), 137))
    }

    @Test
    fun preservesResponseOrderWithinWordSyncMatches() {
        val first = record(2, 139f, hasWordSync = true)
        val closer = record(3, 137f, hasWordSync = true)
        assertEquals(first, selectLrclibLyricsRecord(listOf(record(1, 137f), first, closer), 137))
    }

    @Test
    fun keepsFirstDurationMatchWhenNoWordSyncExists() {
        val firstMatch = record(2, 139f)
        assertEquals(firstMatch, selectLrclibLyricsRecord(listOf(record(1, 200f), firstMatch, record(3, 137f)), 137))
    }

    @Test
    fun unknownDurationStillPrefersWordSync() {
        val rich = record(2, 200f, hasWordSync = true)
        assertEquals(rich, selectLrclibLyricsRecord(listOf(record(1, 137f), rich), null))
    }

    @Test
    fun unknownDurationWithoutWordSyncKeepsFirstResult() {
        val first = record(1, 137f)
        assertEquals(first, selectLrclibLyricsRecord(listOf(first, record(2, 200f)), null))
    }

    @Test
    fun emptyResultsStillReturnNull() {
        assertNull(selectLrclibLyricsRecord(emptyList(), null))
        assertNull(selectLrclibLyricsRecord(emptyList(), 137))
    }

    @Test
    fun decodesTheServerWordSyncFlag() {
        val source =
            """
            {
              "id": 1, "name": "Example", "trackName": "Example", "artistName": "Example",
              "albumName": null, "duration": 137, "instrumental": false,
              "plainLyrics": null, "syncedLyrics": null, "hasWordSync": true
            }
            """.trimIndent()
        assertEquals(true, Json.decodeFromString<LrclibObject>(source).hasWordSync)
    }

    private fun record(
        id: Int,
        duration: Float,
        hasWordSync: Boolean = false,
    ): LrclibObject =
        LrclibObject(
            id = id,
            name = "Example",
            trackName = "Example",
            artistName = "Example",
            albumName = "Example",
            duration = duration,
            instrumental = false,
            plainLyrics = "Example",
            syncedLyrics = "[00:01.00]Example",
            hasWordSync = hasWordSync,
        )
}
