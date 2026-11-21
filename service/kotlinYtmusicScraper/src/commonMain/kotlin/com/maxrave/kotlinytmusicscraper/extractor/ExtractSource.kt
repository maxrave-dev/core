package com.maxrave.kotlinytmusicscraper.extractor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which extractor, and which cipher decoder, produced the stream URLs for a video.
 *
 * Deliberately NOT stored on `NewFormatEntity`: a format row is cached and re-read for as long as it
 * is valid, so a persisted source would keep naming the path taken the first time, long after a
 * later play went down a different one. This is about a single extraction, so it lives only as long
 * as the process does.
 *
 * Bounded to the last [MAX_ENTRIES] videos — the only reader is the info sheet for whatever is
 * playing, so there is nothing to gain by remembering further back.
 */
object ExtractSource {
    private const val MAX_ENTRIES = 32
    private val sources = MutableStateFlow<Map<String, String>>(emptyMap())

    fun record(
        videoId: String,
        source: String,
    ) {
        if (videoId.isEmpty()) return
        sources.update { current ->
            val updated = LinkedHashMap<String, String>(current)
            updated.remove(videoId)
            updated[videoId] = source
            while (updated.size > MAX_ENTRIES) {
                updated.remove(updated.keys.firstOrNull() ?: break)
            }
            updated
        }
    }

    fun of(videoId: String): String? = sources.value[videoId]
}

object ExtractTiming {
    private const val MAX_ENTRIES = 32
    private val timings = MutableStateFlow<Map<String, String>>(emptyMap())

    fun record(
        videoId: String,
        timing: String,
    ) {
        if (videoId.isEmpty()) return
        timings.update { current ->
            val updated = LinkedHashMap<String, String>(current)
            updated.remove(videoId)
            updated[videoId] = timing
            while (updated.size > MAX_ENTRIES) {
                updated.remove(updated.keys.firstOrNull() ?: break)
            }
            updated
        }
    }

    fun consume(videoId: String): String? {
        var value: String? = null
        timings.update { current ->
            value = current[videoId]
            if (value == null) return@update current
            val updated = LinkedHashMap<String, String>(current)
            updated.remove(videoId)
            updated
        }
        return value
    }
}
