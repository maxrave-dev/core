package com.maxrave.domain.repository

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.spotify.SpotifyPlaylist
import com.maxrave.domain.data.model.spotify.SpotifyTrack
import com.maxrave.domain.utils.LocalResource
import kotlinx.coroutines.flow.Flow

/**
 * Live-browsing access to the logged-in user's Spotify playlists (see [com.maxrave.spotify.Spotify]
 * for the underlying `sp_dc`-cookie + TOTP auth, already implemented for Canvas/lyrics).
 *
 * Playlists/tracks here are fetched fresh from Spotify each time - they are intentionally
 * *not* persisted as [com.maxrave.domain.data.entities.LocalPlaylistEntity] rows, mirroring how
 * the existing YouTube Music library chip already works.
 */
interface SpotifyLibraryRepository {
    /**
     * Whether the user has a saved Spotify session (`sp_dc` cookie), i.e. can browse playlists.
     */
    fun isLoggedIn(): Flow<Boolean>

    fun getUserPlaylists(offset: Int = 0, limit: Int = 50): Flow<LocalResource<List<SpotifyPlaylist>>>

    fun getPlaylistTracks(
        playlistId: String,
        offset: Int = 0,
        limit: Int = 100,
    ): Flow<LocalResource<List<SpotifyTrack>>>

    /**
     * Resolve a Spotify track to the best-matching YouTube Music [Track], by searching
     * SimpMusic's own YouTube Music search for "<title> <first artist>" and ranking the
     * results (see `SpotifyTrackMatcher.rankSongMatches`). Returns `null` if no candidate
     * was found - callers should show the track as unavailable rather than fail the whole
     * playlist load.
     *
     * Resolution is cached in-memory per Spotify track id for the lifetime of the process.
     */
    suspend fun resolveTrack(track: SpotifyTrack): Track?
}
