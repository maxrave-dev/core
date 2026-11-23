package com.maxrave.domain.data.entities

import androidx.room.Entity

/**
 * Lyrics the user deleted: this copy, from this provider, is skipped for the song from then on. Other
 * lyrics the provider serves later carry another [lyricsId] and still show.
 */
@Entity(tableName = "deleted_lyrics", primaryKeys = ["videoId", "provider", "lyricsId"])
data class DeletedLyricsEntity(
    val videoId: String,
    val provider: String,
    val lyricsId: String,
)
