package org.simpmusic.lyrics.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Lyrics(
    @SerialName("lyrics")
    val lyrics: LyricsX? = null,
    /** The provider's id for these lyrics, when it has one (LRCLIB's record id). */
    val id: String? = null,
) {
    @Serializable
    data class LyricsX(
        @SerialName("lines")
        val lines: List<Line>?,
        @SerialName("syncType")
        val syncType: String?,
    ) {
        @Serializable
        data class Line(
            @SerialName("endTimeMs")
            val endTimeMs: String,
            @SerialName("startTimeMs")
            val startTimeMs: String,
            @SerialName("syllables")
            val syllables: List<String>? = null,
            @SerialName("words")
            val words: String,
        )
    }
}