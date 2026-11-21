package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.math.abs

object LyricsTranslationSyncValidator {
    fun isOutOfSync(
        originalLyrics: Lyrics,
        translatedLyrics: Lyrics,
    ): Boolean {
        val originalLines = originalLyrics.lines ?: return false
        val translatedLines = translatedLyrics.lines ?: return false
        if (originalLines.isEmpty() || translatedLines.isEmpty()) return false

        var timeSyncErrorCount = 0
        val totalLines = originalLines.size

        if (originalLines.size == translatedLines.size) {
            originalLines.forEachIndexed { index, originalLine ->
                val originalTime = originalLine.startTimeMs.toLongOrNull() ?: 0L
                val translatedTime = translatedLines[index].startTimeMs.toLongOrNull() ?: 0L
                if (abs(originalTime - translatedTime) > 1000L) {
                    timeSyncErrorCount++
                }
            }
        } else {
            val usedIndices = mutableSetOf<Int>()
            originalLines.forEach { originalLine ->
                val originalTime = originalLine.startTimeMs.toLongOrNull() ?: 0L
                var bestIndex = -1
                var bestDiff = Long.MAX_VALUE
                translatedLines.forEachIndexed { index, line ->
                    if (index !in usedIndices) {
                        val diff = abs((line.startTimeMs.toLongOrNull() ?: 0L) - originalTime)
                        if (diff < bestDiff) {
                            bestDiff = diff
                            bestIndex = index
                        }
                    }
                }
                if (bestIndex >= 0) {
                    usedIndices.add(bestIndex)
                    if (bestDiff > 1000L) {
                        timeSyncErrorCount++
                    }
                } else {
                    timeSyncErrorCount++
                }
            }
        }

        val syncErrorRatio = if (totalLines > 0) timeSyncErrorCount.toFloat() / totalLines else 0f
        return syncErrorRatio > 0.25f || (totalLines > 0 && timeSyncErrorCount > totalLines / 2)
    }
}
