package com.maxrave.domain.repository

import com.maxrave.domain.data.model.spotify.SpotifyLibraryResult
import com.maxrave.domain.data.model.spotify.SpotifyPlaylistDetail
import com.maxrave.domain.data.model.spotify.SpotifyPlaylistInfo
import com.maxrave.domain.data.model.spotify.SpotifyTracksPage

/**
 * Read-only view of the signed-in Spotify account's library. Nothing here writes to Spotify.
 */
interface SpotifyLibraryRepository {
    /** True when an sp_dc cookie is saved (it may still turn out to be expired). */
    suspend fun isLoggedIn(): Boolean

    suspend fun getPlaylistFromLink(
        input: String,
    ): SpotifyLibraryResult<SpotifyPlaylistDetail>

    suspend fun getPlaylists(
        onProgress: ((count: Int) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): SpotifyLibraryResult<List<SpotifyPlaylistInfo>>

    suspend fun getPlaylistTracks(
        playlistId: String,
        onProgress: ((loaded: Int) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): SpotifyLibraryResult<SpotifyTracksPage>

    suspend fun getLikedTracks(
        onProgress: ((loaded: Int) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): SpotifyLibraryResult<SpotifyTracksPage>
}
