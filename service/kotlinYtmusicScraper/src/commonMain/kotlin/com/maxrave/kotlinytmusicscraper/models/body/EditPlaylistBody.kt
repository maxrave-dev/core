package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class EditPlaylistBody(
    val context: Context,
    val playlistId: String,
    val actions: List<Action>,
) {
    @Serializable
    data class Action(
        val action: String = "ACTION_SET_PLAYLIST_NAME",
        val playlistName: String? = null,
        val addedVideoId: String? = null,
        val removedVideoId: String? = null,
        val setVideoId: String? = null,
        val movedSetVideoIdSuccessor: String? = null,
        // Set only by ACTION_SET_CUSTOM_THUMBNAIL; the blob comes from the two-step upload in
        // Ytmusic.uploadPlaylistCustomThumbnail.
        val addedCustomThumbnail: AddedCustomThumbnail? = null,
    )

    /**
     * The uploaded image, referenced by the blob id the upload endpoint hands back.
     *
     * [imageKey] is fixed: YouTube Music only accepts one image slot per playlist, and it names
     * that slot `studio_square_thumbnail`. It is sent as a literal rather than derived because the
     * server rejects the action without it.
     */
    @Serializable
    data class AddedCustomThumbnail(
        val playlistScottyEncryptedBlobId: String,
        val imageKey: ImageKey = ImageKey(),
    ) {
        @Serializable
        data class ImageKey(
            val name: String = "studio_square_thumbnail",
            val type: String = "PLAYLIST_IMAGE_TYPE_CUSTOM_THUMBNAIL",
        )
    }
}