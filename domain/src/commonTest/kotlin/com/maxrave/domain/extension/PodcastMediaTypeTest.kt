package com.maxrave.domain.extension

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodcastMediaTypeTest {
    @Test
    fun podcastTrackKeepsItsTypeInPlayerMetadata() {
        val track = track(category = MERGING_DATA_TYPE.PODCAST)

        assertTrue(track.isPodcast())
        assertTrue(track.toGenericMediaItem().isPodcast())
        assertEquals(MERGING_DATA_TYPE.PODCAST, track.toGenericMediaItem().metadata.description)
    }

    @Test
    fun regularTrackIsNotMarkedAsPodcast() {
        val track = track(category = "Music")

        assertFalse(track.isPodcast())
        assertFalse(track.toGenericMediaItem().isPodcast())
    }

    @Test
    fun podcastMetadataSurvivesConversionToDatabaseEntity() {
        val mediaItem =
            GenericMediaItem(
                mediaId = "episode-id",
                uri = "episode-id",
                metadata = GenericMediaMetadata(description = MERGING_DATA_TYPE.PODCAST),
            )

        assertTrue(mediaItem.toSongEntity().isPodcast())
    }

    private fun track(category: String) =
        Track(
            album = null,
            artists = emptyList(),
            duration = "10:00",
            durationSeconds = 600,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = listOf(Thumbnail(height = 100, url = "https://example.com/episode.jpg", width = 100)),
            title = "Episode",
            videoId = "episode-id",
            videoType = category,
            category = category,
            feedbackTokens = null,
            resultType = category,
        )
}
