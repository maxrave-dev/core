package com.maxrave.spotify.model.response.spotify.playlist

import kotlinx.serialization.Serializable

/**
 * Response shape for the "fetchPlaylist" persisted GraphQL query.
 * Field names mirror Spotify's internal pathfinder schema (reverse-engineered), not an
 * official/public contract - keep [kotlinx.serialization.json.Json.ignoreUnknownKeys] on.
 */
@Serializable
data class SpotifyPlaylistTracksResponse(
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val playlistV2: PlaylistV2? = null,
    )

    @Serializable
    data class PlaylistV2(
        val uri: String? = null,
        val name: String? = null,
        val images: SpotifyLibraryPlaylistsResponse.PlaylistImages? = null,
        val ownerV2: SpotifyLibraryPlaylistsResponse.OwnerV2? = null,
        val content: PlaylistContent? = null,
    )

    @Serializable
    data class PlaylistContent(
        val items: List<PlaylistItem>? = null,
        val totalCount: Int? = null,
        val pagingInfo: SpotifyLibraryPlaylistsResponse.PagingInfo? = null,
    )

    @Serializable
    data class PlaylistItem(
        val uid: String? = null,
        val itemV2: TrackWrapper? = null,
    )

    @Serializable
    data class TrackWrapper(
        val data: TrackData? = null,
    )

    @Serializable
    data class TrackData(
        // "spotify:track:<id>"
        val uri: String? = null,
        val id: String? = null,
        val name: String? = null,
        val duration: TrackDuration? = null,
        val trackDuration: TrackDuration? = null,
        val contentRating: ContentRating? = null,
        val playcount: String? = null,
        val albumOfTrack: AlbumOfTrack? = null,
        val artists: ArtistList? = null,
        val firstArtist: ArtistList? = null,
    ) {
        val spotifyId: String?
            get() = id ?: uri?.substringAfterLast(':')

        val durationMs: Long?
            get() = duration?.totalMilliseconds ?: trackDuration?.totalMilliseconds

        val artistNames: List<String>
            get() =
                (firstArtist?.items ?: artists?.items)
                    ?.mapNotNull { it.profile?.name }
                    .orEmpty()

        val isExplicit: Boolean
            get() = contentRating?.label?.equals("EXPLICIT", ignoreCase = true) == true
    }

    @Serializable
    data class TrackDuration(
        val totalMilliseconds: Long? = null,
    )

    @Serializable
    data class ContentRating(
        val label: String? = null,
    )

    @Serializable
    data class AlbumOfTrack(
        val uri: String? = null,
        val name: String? = null,
        val coverArt: CoverArt? = null,
    )

    @Serializable
    data class CoverArt(
        val sources: List<SpotifyLibraryPlaylistsResponse.ImageSource>? = null,
    )

    @Serializable
    data class ArtistList(
        val items: List<ArtistItem>? = null,
    )

    @Serializable
    data class ArtistItem(
        val profile: ArtistProfile? = null,
    )

    @Serializable
    data class ArtistProfile(
        val name: String? = null,
    )
}
