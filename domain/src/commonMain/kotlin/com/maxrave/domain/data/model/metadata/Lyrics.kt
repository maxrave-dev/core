package com.maxrave.domain.data.model.metadata

import kotlinx.serialization.Serializable

@Serializable
data class Lyrics(
    val error: Boolean = false,
    val lines: List<Line>?,
    val syncType: String?,
    val simpMusicLyrics: SimpMusicLyrics? = null,
    /** The provider's id for these lyrics, or [lyricsContentId] for one that gives none; null for the user's own. */
    val id: String? = null,
)

/** The id of lyrics whose provider gives none: their timing and text, so a changed copy gets a new id. */
fun lyricsContentId(lines: List<Line>?): String =
    lines
        .orEmpty()
        .joinToString("\n") { "${it.startTimeMs} ${it.words}" }
        .hashCode()
        .toUInt()
        .toString(16)

@Serializable
data class SimpMusicLyrics(
    val id: String,
    val vote: Int,
)