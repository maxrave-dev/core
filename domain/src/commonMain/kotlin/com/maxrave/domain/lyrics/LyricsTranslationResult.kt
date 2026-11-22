package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics

/**
 * Outcome of an on-demand free translation pass over a song's [Lyrics].
 */
sealed interface LyricsTranslationResult {
    data class Translated(
        val lyrics: Lyrics,
        val detectedSourceLanguage: String? = null,
    ) : LyricsTranslationResult

    data class AlreadyInTargetLanguage(
        val detectedLanguage: String,
    ) : LyricsTranslationResult
}
