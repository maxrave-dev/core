package com.maxrave.data.lyrics

import com.maxrave.domain.data.model.lyrics.RomanizationLanguage
import com.maxrave.domain.repository.LyricsRomanizerRepository
import org.simpmusic.lyrics.romanization.LyricsRomanizer

/**
 * The only place that knows the romanization engine exists.
 *
 * No caching layer here on purpose: ten of the twelve languages are a table lookup per character,
 * and the two that are not are already lazy inside their own platform objects. A cache keyed by
 * line would spend more memory holding a song's lyrics twice than it saves.
 */
class LyricsRomanizerRepositoryImpl : LyricsRomanizerRepository {
    override fun romanize(
        line: String,
        enabled: Set<RomanizationLanguage>,
    ): String? = LyricsRomanizer.romanize(line, enabled)
}
