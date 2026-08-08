package com.maxrave.domain.utils

import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.spotify.SpotifyTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun fakeCandidate(
    videoId: String,
    title: String,
    artistNames: List<String>,
): SongsResult =
    SongsResult(
        album = null,
        artists = artistNames.map { Artist(id = null, name = it) },
        category = null,
        duration = null,
        durationSeconds = null,
        feedbackTokens = null,
        isExplicit = null,
        resultType = null,
        thumbnails = null,
        title = title,
        videoId = videoId,
        videoType = null,
        year = "",
    )

private fun fakeSpotifyTrack(
    title: String,
    artists: List<String>,
): SpotifyTrack =
    SpotifyTrack(
        id = "spotify-track-id",
        title = title,
        artists = artists,
        albumName = null,
        albumImageUrl = null,
        durationMs = null,
        isExplicit = false,
    )

class SpotifyTrackMatcherTest {
    @Test
    fun `returns null when there are no candidates`() {
        val track = fakeSpotifyTrack("Song", listOf("Artist"))
        assertNull(rankSongMatches(track, emptyList()))
    }

    @Test
    fun `prefers the official upload when the track name matches`() {
        val track = fakeSpotifyTrack("Blinding Lights", listOf("The Weeknd"))
        val official = fakeCandidate("v1", "The Weeknd - Blinding Lights (Official Video)", listOf("The Weeknd"))
        val liveCover = fakeCandidate("v2", "Blinding Lights (Live Cover)", listOf("Some Cover Channel"))

        val best = rankSongMatches(track, listOf(liveCover, official))

        assertEquals("v1", best?.videoId)
    }

    @Test
    fun `prefers an artist-matching upload over an unrelated one with a similar title`() {
        val track = fakeSpotifyTrack("Sunflower", listOf("Post Malone", "Swae Lee"))
        val correctArtist = fakeCandidate("v1", "Sunflower", listOf("Post Malone"))
        val wrongArtist = fakeCandidate("v2", "Sunflower", listOf("Random Cover Band"))

        val best = rankSongMatches(track, listOf(wrongArtist, correctArtist))

        assertEquals("v1", best?.videoId)
    }

    @Test
    fun `falls back to the only candidate even with a low score`() {
        val track = fakeSpotifyTrack("Some Obscure Track", listOf("Some Artist"))
        val onlyCandidate = fakeCandidate("v1", "Totally Unrelated Title", listOf("Unrelated Channel"))

        val best = rankSongMatches(track, listOf(onlyCandidate))

        assertEquals("v1", best?.videoId)
    }
}
