package com.maxrave.spotify.model.response.spotify.playlist

import kotlinx.serialization.Serializable

/**
 * Response shape for the "libraryV3" persisted GraphQL query filtered to `["Playlists"]`.
 * Field names mirror Spotify's internal pathfinder schema (reverse-engineered), not an
 * official/public contract - keep [kotlinx.serialization.json.Json.ignoreUnknownKeys] on.
 */
@Serializable
data class SpotifyLibraryPlaylistsResponse(
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val me: Me? = null,
    )

    @Serializable
    data class Me(
        val libraryV3: LibraryV3? = null,
    )

    @Serializable
    data class LibraryV3(
        val items: List<LibraryItem>? = null,
        val totalCount: Int? = null,
        val pagingInfo: PagingInfo? = null,
    )

    @Serializable
    data class PagingInfo(
        val limit: Int? = null,
        val offset: Int? = null,
        val nextOffset: Int? = null,
    )

    @Serializable
    data class LibraryItem(
        val item: ItemWrapper? = null,
    )

    @Serializable
    data class ItemWrapper(
        val data: PlaylistData? = null,
    )

    @Serializable
    data class PlaylistData(
        // "spotify:playlist:<id>"
        val uri: String? = null,
        val name: String? = null,
        val description: String? = null,
        val images: PlaylistImages? = null,
        val ownerV2: OwnerV2? = null,
        val content: PlaylistContentSummary? = null,
    ) {
        val id: String?
            get() = uri?.substringAfterLast(':')
    }

    @Serializable
    data class PlaylistImages(
        val items: List<PlaylistImageItem>? = null,
    )

    @Serializable
    data class PlaylistImageItem(
        val sources: List<ImageSource>? = null,
    )

    @Serializable
    data class ImageSource(
        val url: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    @Serializable
    data class OwnerV2(
        val data: OwnerData? = null,
    )

    @Serializable
    data class OwnerData(
        val name: String? = null,
    )

    @Serializable
    data class PlaylistContentSummary(
        val totalCount: Int? = null,
    )
}
