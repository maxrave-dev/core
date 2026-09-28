package org.simpmusic.lyrics.am

import kotlinx.serialization.Serializable

@Serializable
data class ITunesSearchResponse(
    val resultCount: Int? = null,
    val results: List<ITunesItem> = emptyList(),
)

@Serializable
data class ITunesItem(
    val wrapperType: String? = null,
    val kind: String? = null,
    val artistId: Long? = null,
    val collectionId: Long? = null,
    val trackId: Long? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val trackName: String? = null,
    val collectionCensoredName: String? = null,
    val trackCensoredName: String? = null,
    val artistViewUrl: String? = null,
    val collectionViewUrl: String? = null,
    val trackViewUrl: String? = null,
    val previewUrl: String? = null,
    val artworkUrl30: String? = null,
    val artworkUrl60: String? = null,
    val artworkUrl100: String? = null,
    val releaseDate: String? = null,
    val primaryGenreName: String? = null,
    val trackTimeMillis: Long? = null,
)

@Serializable
data class AppleMusicArtworkResult(
    val found: Boolean,
    val hasMotion: Boolean = false,
    val trackId: Long? = null,
    val albumId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val staticArtworkUrl: String? = null,
    val squareMotionUrl: String? = null,
    val tallMotionUrl: String? = null,
    val directMp4Url: String? = null,
    val bestMotionUrl: String? = null,
    val appleMusicUrl: String? = null,
)
