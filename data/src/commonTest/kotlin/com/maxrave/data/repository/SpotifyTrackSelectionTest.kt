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

    @Test
    fun strictLyricsMatchRejectsWrongArtistTitleAndDurationWithoutFallback() {
        val wrongArtistSameTitle =
            track(
                id = "paper-kites",
                durationMs = 137_000,
                title = "Bloom",
                artist = "The Paper Kites",
            )
        val rightArtist =
            track(
                id = "jpn-bloom",
                durationMs = 137_000,
                title = "Bloom",
                artist = "j^p^n",
            )

        assertNull(
            pickSpotifyTrack(
                items = listOf(wrongArtistSameTitle),
                durationSeconds = 137,
                expectedTitle = "Bloom",
                expectedArtist = "j^p^n",
                requireMetadataMatch = true,
            ),
        )
        assertEquals(
            "jpn-bloom",
            pickSpotifyTrack(
                items = listOf(wrongArtistSameTitle, rightArtist),
                durationSeconds = 137,
                expectedTitle = "Bloom",
                expectedArtist = "j^p^n",
                requireMetadataMatch = true,
            )?.item?.data?.id,
        )
    }

    private fun track(
        id: String,
        durationMs: Int,
        title: String? = null,
        artist: String? = null,
    ) = SpotifySearchResponse.Data.Search.TracksV2.Items(
        item =
            SpotifySearchResponse.Data.Search.TracksV2.Items.Item(
                data =
                    SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX(
                        id = id,
                        name = title,
                        artists =
                            artist?.let {
                                SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX.Artists(
                                    items =
                                        listOf(
                                            SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX.Artists.ItemX(
                                                profile =
                                                    SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX.Artists.ItemX.Profile(
                                                        name = it,
                                                    ),
                                            ),
                                        ),
                                )
                            },
                        duration =
                            SpotifySearchResponse.Data.Search.TracksV2.Items.Item.DataX.Duration(
                                totalMilliseconds = durationMs,
                            ),
                    ),
            ),
    )
}
