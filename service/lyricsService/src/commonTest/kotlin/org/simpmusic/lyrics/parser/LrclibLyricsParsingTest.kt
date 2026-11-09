package org.simpmusic.lyrics.parser

import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.models.response.LrclibObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LrclibLyricsParsingTest {
    @Test
    fun lyricsfileTakesPriorityOverLegacyFields() {
        val result = assertNotNull(parseLrclibLyrics(record(lyricsfile = yaml("plain: YAML lyrics")))?.lyrics)
        assertEquals("UNSYNCED", result.syncType)
        assertEquals("YAML lyrics", result.lines?.single()?.words)
    }

    @Test
    fun missingOrInvalidLyricsfileFallsBackToSyncedLyrics() {
        for (source in listOf(null, "", " ", "[", yaml("lines: []"), yaml("plain: Hello").replace("'1.0'", "'9.0'"))) {
            val result = assertNotNull(parseLrclibLyrics(record(lyricsfile = source))?.lyrics)
            assertEquals("LINE_SYNCED", result.syncType)
            assertEquals("Legacy synced", result.lines?.single()?.words)
        }
    }

    @Test
    fun plainLyricsRemainTheLastFallback() {
        val result = assertNotNull(parseLrclibLyrics(record(syncedLyrics = null))?.lyrics)
        assertEquals("UNSYNCED", result.syncType)
        assertEquals("Legacy plain", result.lines?.single()?.words)
    }

    @Test
    fun noAvailableLyricsStillReturnsNull() {
        assertNull(parseLrclibLyrics(record(syncedLyrics = null, plainLyrics = null)))
    }

    @Test
    fun oversizedYamlDoesNotBlockLegacyFallback() {
        val result = assertNotNull(parseLrclibLyrics(record(lyricsfile = yaml("plain: " + "a".repeat(1_000_001))))?.lyrics)
        assertEquals("Legacy synced", result.lines?.single()?.words)
    }

    @Test
    fun oldJsonWithoutLyricsfileStillDecodes() {
        val source =
            """
            {
              "id": 1, "name": "Example", "trackName": "Example", "artistName": "Example",
              "albumName": null, "duration": 137, "instrumental": false,
              "plainLyrics": "Legacy plain", "syncedLyrics": null
            }
            """.trimIndent()
        val decoded = Json.decodeFromString<LrclibObject>(source)
        assertNull(decoded.lyricsfile)
        assertEquals(false, decoded.hasWordSync)
        assertEquals("Legacy plain", parseLrclibLyrics(decoded)?.lyrics?.lines?.single()?.words)
    }

    @Test
    fun wordSyncComesFromYamlRatherThanTheAvailabilityFlag() {
        val source =
            """
            {
              "id": 1, "name": "Example", "trackName": "Example", "artistName": "Example",
              "albumName": null, "duration": 137, "instrumental": false,
              "plainLyrics": null, "syncedLyrics": null, "hasWordSync": false,
              "lyricsfile": "version: '1.0'\nmetadata:\n  title: Example\n  artist: Example\nlines:\n- text: Hello\n  start_ms: 390\n  words:\n  - text: Hello\n    start_ms: 390"
            }
            """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<LrclibObject>(source)
        assertEquals("RICH_SYNCED", parseLrclibLyrics(decoded)?.lyrics?.syncType)
    }

    private fun record(
        lyricsfile: String? = null,
        syncedLyrics: String? = "[00:01.00]Legacy synced",
        plainLyrics: String? = "Legacy plain",
    ): LrclibObject =
        LrclibObject(
            id = 1,
            name = "Example",
            trackName = "Example",
            artistName = "Example",
            albumName = "Example",
            duration = 137f,
            instrumental = false,
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            lyricsfile = lyricsfile,
        )

    private fun yaml(body: String): String = "version: '1.0'\nmetadata:\n  title: Example\n  artist: Example\n$body"
}
