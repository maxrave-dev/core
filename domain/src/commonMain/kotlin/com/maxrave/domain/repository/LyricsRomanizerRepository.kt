package com.maxrave.domain.repository

import com.maxrave.domain.data.model.lyrics.RomanizationLanguage

/**
 * A Latin-script reading of one lyric line.
 *
 * The romanizers themselves live in the `lyricsService` module, which `composeApp` cannot see —
 * `data` depends on it with `implementation`, so the dependency stops there. That is the layering
 * rule working, not an obstacle to route around: the UI asks for "a reading of this line" and does
 * not learn that kuromoji, pinyin4j or a Hangul decomposition produced it. Same shape as
 * [ListenTogetherRepository], where the UI never sees the wire protocol.
 */
interface LyricsRomanizerRepository {
    /**
     * @return the romanized line, or null when there is nothing to show — the line is already
     *   Latin, its language is not enabled, the platform has no library for it, or the result came
     *   back identical to the input.
     */
    fun romanize(
        line: String,
        enabled: Set<RomanizationLanguage>,
    ): String?
}
