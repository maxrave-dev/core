package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer

internal fun parseLibraryPlaylist(input: List<GridRenderer.Item>): List<PlaylistsResult> =
    input.mapNotNull { item ->
        item.musicTwoRowItemRenderer?.let(::parseMusicTwoRowPlaylistItem)
    }

internal fun parseNextLibraryPlaylist(input: List<MusicTwoRowItemRenderer>): List<PlaylistsResult> =
    input.mapNotNull(::parseMusicTwoRowPlaylistItem)

private fun parseMusicTwoRowPlaylistItem(renderer: MusicTwoRowItemRenderer): PlaylistsResult? {
    val browseId =
        renderer.navigationEndpoint
            .browseEndpoint
            ?.browseId
            ?.takeIf { it.isNotBlank() }
            ?: renderer.navigationEndpoint
                .watchPlaylistEndpoint
                ?.playlistId
                ?.takeIf { it.isNotBlank() }
            ?: renderer.navigationEndpoint
                .watchEndpoint
                ?.playlistId
                ?.takeIf { it.isNotBlank() }
            ?: renderer.playlistId
                ?.takeIf { it.isNotBlank() }
            ?: return null

    return PlaylistsResult(
        author =
            renderer.subtitle
                ?.runs
                ?.firstOrNull()
                ?.text ?: "",
        browseId = browseId,
        category = "",
        itemCount = "",
        resultType = "",
        thumbnails =
            renderer.thumbnailRenderer
                .musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail() ?: listOf(),
        title =
            renderer.title
                .runs
                ?.firstOrNull()
                ?.text ?: "",
    )
}
