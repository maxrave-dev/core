package com.maxrave.domain.repository

import com.maxrave.domain.data.model.lyrics.RomanizationDictionaryState
import com.maxrave.domain.data.model.lyrics.RomanizationLanguage
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * Where the Japanese dictionary pack stands. Platforms that bundle the dictionary (Desktop)
     * are [RomanizationDictionaryState.READY] from the start, so the settings screen never offers
     * them a download.
     */
    val japaneseDictionaryState: StateFlow<RomanizationDictionaryState>

    /**
     * Fetch, verify and install the Japanese dictionary pack, moving [japaneseDictionaryState]
     * through DOWNLOADING to READY or FAILED. A no-op when the pack is already READY; a FAILED
     * state is retried by simply calling this again. Safe to call concurrently — a second caller
     * waits for the running download instead of starting another.
     */
    suspend fun downloadJapaneseDictionary()
}
