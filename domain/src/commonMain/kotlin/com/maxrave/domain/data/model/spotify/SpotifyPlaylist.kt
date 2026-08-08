package com.maxrave.domain.data.model.spotify

/**
 * A playlist fetched live from Spotify's own library (not a SimpMusic [com.maxrave.domain.data.entities.LocalPlaylistEntity]).
 * Its tracks are Spotify metadata only - they aren't playable until resolved to a
 * matching YouTube Music [com.maxrave.domain.data.model.browse.album.Track] via
 * [com.maxrave.domain.repository.SpotifyLibraryRepository.resolveTrack].
 */
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val ownerName: String?,
    val trackCount: Int?,
)
