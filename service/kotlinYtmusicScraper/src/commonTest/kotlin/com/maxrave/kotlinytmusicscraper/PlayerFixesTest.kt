package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.Artist
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerFixesTest {
    @Test
    fun gridContinuationAllowsMissingItemsField() {
        val payload = """{"itemSize":"GRID_ITEM_SIZE_LARGE","continuations":null}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<BrowseResponse.ContinuationContents.GridContinuation>(payload)
        assertTrue(decoded.items.isEmpty())
    }

    @Test
    fun audioVariantMatcherSelectsMatchingStudioAtvAndRejectsRemix() {
        val candidates =
            listOf(
                SongItem(
                    id = "REMIX_ID",
                    title = "I Can't Handle Change (Slowed + Reverb)",
                    artists = listOf(Artist("Roar", null)),
                    duration = 240,
                    thumbnail = "",
                    musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                ),
                SongItem(
                    id = "HzYrZoALCCA",
                    title = "I Can't Handle Change",
                    artists = listOf(Artist("Roar", null)),
                    duration = 199,
                    thumbnail = "",
                    musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                ),
            )
        val picked =
            YouTube.AudioVariantMatcher.pickBestAudioCandidate(
                candidates = candidates,
                expectedTitle = "I Can't Handle Change (Official Video)",
                expectedArtist = "Roar",
                expectedDurationSec = 199,
                excludeVideoId = "KLD_OyCSloA",
            )
        assertEquals("HzYrZoALCCA", picked?.id)
    }
}
