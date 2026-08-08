package com.maxrave.domain.data.model.spotify

/**
 * A track as reported by Spotify's own metadata - title/artist/album/duration only.
 * Not directly playable: SimpMusic streams from YouTube, so this must first be
 * resolved to a matching YouTube Music track (see [com.maxrave.domain.repository.SpotifyLibraryRepository]).
 */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val albumName: String?,
    val albumImageUrl: String?,
    val durationMs: Long?,
    val isExplicit: Boolean,
)
