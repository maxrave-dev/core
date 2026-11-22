package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.math.abs

object LyricsTranslationSyncValidator {
    private const val MIN_COVERAGE_RATIO = 0.85f

    fun isOutOfSync(
        originalLyrics: Lyrics,
        translatedLyrics: Lyrics,
    ): Boolean {
        val originalLines = originalLyrics.lines ?: return false
        val translatedLines = translatedLyrics.lines ?: return false
        if (originalLines.isEmpty() || translatedLines.isEmpty()) return false

        val translatableOriginal =
            originalLines.filter { line ->
                val clean = LyricsHeaderSanitizer.stripRichSyncTags(line.words)
                clean.any { it.isLetterOrDigit() }
            }
        val nonBlankTranslated = translatedLines.filter { it.words.isNotBlank() }
        if (translatableOriginal.isEmpty()) return false
        if (nonBlankTranslated.isEmpty()) return true

        // Reject translations that merged multiple verses into fewer timestamps (leaving blank gaps).
        if (nonBlankTranslated.size.toFloat() / translatableOriginal.size.toFloat() < MIN_COVERAGE_RATIO) {
            return true
        }

        val usedIndices = mutableSetOf<Int>()
        var matchedCount = 0
        translatableOriginal.forEach { originalLine ->
            val originalTime = originalLine.startTimeMs.toLongOrNull() ?: 0L
            var bestIndex = -1
            var bestDiff = Long.MAX_VALUE
            nonBlankTranslated.forEachIndexed { index, line ->
                if (index !in usedIndices) {
                    val diff = abs((line.startTimeMs.toLongOrNull() ?: 0L) - originalTime)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestIndex = index
                    }
                }
            }
            if (bestIndex >= 0 && bestDiff <= 1000L) {
                usedIndices.add(bestIndex)
                matchedCount++
            }
        }

        val coverage = matchedCount.toFloat() / translatableOriginal.size.toFloat()
        return coverage < MIN_COVERAGE_RATIO
    }
}
