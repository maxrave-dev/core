package com.maxrave.spotify.model.response.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Models for the user's Spotify library (playlists + liked songs).
 *
 * Every field has a default and nothing is required: Spotify renamed and removed several fields in
 * February 2026 (`track` -> `item`, `tracks` -> `items` on playlists), so a response in either shape
 * must still decode instead of throwing.
 */
@Serializable
data class SpotifyPaging<T>(
    @SerialName("items")
    val items: List<T> = emptyList(),
    @SerialName("next")
    val next: String? = null,
    @SerialName("total")
    val total: Int = 0,
)

@Serializable
data class SpotifyMe(
    @SerialName("id")
    val id: String = "",
    @SerialName("display_name")
    val displayName: String? = null,
)

@Serializable
data class SpotifyImage(
    @SerialName("url")
    val url: String = "",
)

@Serializable
data class SpotifyOwner(
    @SerialName("id")
    val id: String = "",
    @SerialName("display_name")
    val displayName: String? = null,
)

@Serializable
data class SpotifyPlaylistCount(
    @SerialName("total")
    val total: Int = 0,
)

@Serializable
data class SpotifyPlaylistSummary(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("images")
    val images: List<SpotifyImage>? = null,
    @SerialName("owner")
    val owner: SpotifyOwner? = null,
    // Pre-Feb-2026 name of the track count; kept as a fallback.
    @SerialName("tracks")
    val tracks: SpotifyPlaylistCount? = null,
    @SerialName("items")
    val items: SpotifyPlaylistCount? = null,
) {
    val trackCount: Int get() = items?.total ?: tracks?.total ?: 0
}

@Serializable
data class SpotifyArtistRef(
    @SerialName("name")
    val name: String = "",
)

@Serializable
data class SpotifyAlbumRef(
    @SerialName("name")
    val name: String = "",
    @SerialName("images")
    val images: List<SpotifyImage>? = null,
)

@Serializable
data class SpotifyTrack(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String = "",
    // "track" or "episode"; podcasts can sit in a playlist and must be skipped.
    @SerialName("type")
    val type: String? = null,
    @SerialName("is_local")
    val isLocal: Boolean = false,
    @SerialName("duration_ms")
    val durationMs: Long = 0,
    @SerialName("artists")
    val artists: List<SpotifyArtistRef> = emptyList(),
    @SerialName("album")
    val album: SpotifyAlbumRef? = null,
)

/**
 * One entry of a playlist. The inner object is `item` since February 2026 and `track` before it;
 * it is null for tracks that were removed from Spotify.
 */
@Serializable
data class SpotifyPlaylistEntry(
    @SerialName("item")
    val item: SpotifyTrack? = null,
    @SerialName("track")
    val track: SpotifyTrack? = null,
) {
    val resolved: SpotifyTrack? get() = item ?: track
}

@Serializable
data class SpotifySavedTrack(
    @SerialName("track")
    val track: SpotifyTrack? = null,
    @SerialName("item")
    val item: SpotifyTrack? = null,
) {
    val resolved: SpotifyTrack? get() = track ?: item
}
