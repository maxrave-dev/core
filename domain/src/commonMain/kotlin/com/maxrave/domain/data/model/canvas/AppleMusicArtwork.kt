package com.maxrave.domain.data.model.canvas

data class AppleMusicArtwork(
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
