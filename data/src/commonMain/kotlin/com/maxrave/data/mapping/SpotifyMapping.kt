package com.maxrave.data.mapping

import com.maxrave.domain.data.model.spotify.SpotifyPlaylist
import com.maxrave.domain.data.model.spotify.SpotifyTrack
import com.maxrave.spotify.model.response.spotify.playlist.SpotifyLibraryPlaylistsResponse
import com.maxrave.spotify.model.response.spotify.playlist.SpotifyPlaylistTracksResponse

internal fun SpotifyLibraryPlaylistsResponse.toSpotifyPlaylists(): List<SpotifyPlaylist> =
    data
        ?.me
        ?.libraryV3
        ?.items
        ?.mapNotNull { it.item?.data }
        ?.mapNotNull { playlist ->
            val id = playlist.id ?: return@mapNotNull null
            SpotifyPlaylist(
                id = id,
                name = playlist.name.orEmpty(),
                description = playlist.description,
                imageUrl =
                    playlist.images
                        ?.items
                        ?.firstOrNull()
                        ?.sources
                        ?.maxByOrNull { it.width ?: 0 }
                        ?.url,
                ownerName = playlist.ownerV2?.data?.name,
                trackCount = playlist.content?.totalCount,
            )
        }.orEmpty()

internal fun SpotifyPlaylistTracksResponse.toSpotifyTracks(): List<SpotifyTrack> =
    data
        ?.playlistV2
        ?.content
        ?.items
        ?.mapNotNull { it.itemV2?.data }
        ?.mapNotNull { track ->
            val id = track.spotifyId ?: return@mapNotNull null
            SpotifyTrack(
                id = id,
                title = track.name.orEmpty(),
                artists = track.artistNames,
                albumName = track.albumOfTrack?.name,
                albumImageUrl =
                    track.albumOfTrack
                        ?.coverArt
                        ?.sources
                        ?.maxByOrNull { it.width ?: 0 }
                        ?.url,
                durationMs = track.durationMs,
                isExplicit = track.isExplicit,
            )
        }.orEmpty()
