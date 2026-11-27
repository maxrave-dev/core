package com.maxrave.domain.data.model.spotify

/** A playlist in the user's Spotify library. */
data class SpotifyPlaylistInfo(
    val id: String,
    val name: String,
    val thumbnail: String?,
    val trackCount: Int,
    val ownerName: String?,
)

/** A Spotify track reduced to what is needed to find the same recording on YT Music. */
data class SpotifyTrackInfo(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    val albumName: String?,
    val thumbnail: String?,
)

/** What a library read returns: the tracks plus how many entries could not be used at all. */
data class SpotifyTracksPage(
    val tracks: List<SpotifyTrackInfo>,
    /** Local files, podcast episodes and tracks Spotify has removed: nothing to look up for them. */
    val skippedUnavailable: Int,
)

@kotlinx.serialization.Serializable
data class SyncedSpotifyPlaylist(
    val spotifyId: String,
    val type: String, // "playlist" or "album"
    val title: String,
    val localPlaylistId: Long,
    val lastTrackCount: Int = 0,
    val thumbnail: String? = null,
)

data class SpotifyPlaylistDetail(
    val id: String,
    val type: String,
    val title: String,
    val author: String?,
    val thumbnail: String?,
    val tracks: List<SpotifyTrackInfo>,
)

enum class SpotifyLibraryError {
    /** No sp_dc saved: the user never signed in to Spotify in the app. */
    NOT_LOGGED_IN,

    /** Spotify answered with a guest token or 401: the saved session is expired or revoked. */
    SESSION_EXPIRED,

    /** The account may not read this playlist (Spotify-made or another user's). */
    NOT_ALLOWED,

    /** Spotify kept answering 429 after the retries. */
    RATE_LIMITED,

    NETWORK,

    INVALID_LINK,

    NOT_FOUND,
}

sealed interface SpotifyLibraryResult<out T> {
    data class Success<T>(
        val data: T,
    ) : SpotifyLibraryResult<T>

    data class Failure(
        val error: SpotifyLibraryError,
        val customMessage: String? = null,
    ) : SpotifyLibraryResult<Nothing>
}
