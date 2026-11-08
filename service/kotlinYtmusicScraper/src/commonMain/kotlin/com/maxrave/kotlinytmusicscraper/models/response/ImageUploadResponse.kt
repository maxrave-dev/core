package com.maxrave.kotlinytmusicscraper.models.response

import kotlinx.serialization.Serializable

/**
 * Answer to the second leg of the playlist-thumbnail upload — the one that actually carries the
 * bytes. The blob id it returns is what `ACTION_SET_CUSTOM_THUMBNAIL` attaches to the playlist.
 */
@Serializable
data class ImageUploadResponse(
    val encryptedBlobId: String,
)
