package org.simpmusic.lyrics.models.request

import kotlinx.serialization.Serializable

/** Deletes lyrics or a translation by [id]; the server allows it only for the [contributorEmail] it was contributed under. */
@Serializable
data class DeleteBody(
    val id: String,
    val contributorEmail: String,
)
