package com.maxrave.data.repository

import com.maxrave.spotify.model.response.spotify.search.SpotifySearchResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyTrackSelectionTest {
    @Test
    fun selectsTrackMatchingDurationInSecondsWithinTolerance() {
        val wrongVersion = track(id = "live", durationMs = 240_000)
        val studioVersion = track(id = "studio", durationMs = 181_500)
        val chosen = pickSpotifyTrack(listOf(wrongVersion, studioVersion), durationSeconds = 180)
        assertEquals("studio", chosen?.item?.data?.id)
    }

    @Test
    fun fallsBackToFirstTrackWhenNoDurationMatchesOrDurationIsUnknown() {
        val first = track(id = "first", durationMs = 240_000)
        val second = track(id = "second", durationMs = 300_000)
        assertEquals("first", pickSpotifyTrack(listOf(first, second), durationSeconds = 180)?.item?.data?.id)
        assertEquals("first", pickSpotifyTrack(listOf(first, second), durationSeconds = 0)?.item?.data?.id)
        assertEquals("first", pickSpotifyTrack(listOf(first, second), durationSeconds = null)?.item?.data?.id)
    }

    @Test
    fun returnsNullOnEmptyOrNullItems() {
        assertNull(pickSpotifyTrack(null, 180))
        assertNull(pickSpotifyTrack(emptyList(), 180))
    }

    private fun track(
        id: String,
        durationMs: Int,
    ) = SpotifySearchResponse.Data.Search.TracksV2.Items(
        item =
            SpotifySearchResponse.Data.Search.TracksV2.Items.Item(
                data =
                    SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX(
                        id = id,
                        duration =
                            SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX.Duration(
                                totalMilliseconds = durationMs,
                            ),
                    ),
            ),
    )
}
