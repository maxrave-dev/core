package com.maxrave.domain.mediaservice.handler

/**
 * How many already-played tracks a radio queue keeps behind the current one.
 *
 * A radio appends a batch of ~50 whenever fewer than 3 tracks remain and nothing ever drops what
 * was played, so the queue — and every per-append pass over it — keeps getting more expensive
 * (#2504). A playlist or an album is finite and is never trimmed while it is still itself: the
 * tracks the user picked all stay.
 *
 * With *Endless queue* on, one of those queues does become a radio once it runs past the list the
 * user picked — the handler re-points it at the last track's radio and keeps appending — and it is
 * marked `RADIO` at that moment, so from there on it is trimmed like any other radio.
 */
object RadioQueueTrim {
    /** Kept behind the current track after a trim. Roughly two radio batches of history. */
    const val KEEP_HISTORY = 100

    /**
     * Trim only once the history passes this. The gap to [KEEP_HISTORY] is what keeps the trim from
     * running on every single track change, which would put back the per-item storm it avoids.
     *
     * It is a floor, not a ceiling: the check runs right after a batch append, which happens at
     * roughly every 50th track, so in practice the history grows to ~197 before the first cut and
     * then swings between 100 and ~197.
     */
    const val TRIM_ABOVE_HISTORY = 150

    /**
     * How many tracks to drop from the FRONT of a radio queue, or 0 when it should be left alone.
     *
     * [currentIndex] is the playing track's index and therefore also the number of tracks behind
     * it. The result never touches the playing track or anything after it, and never exceeds
     * [queueSize].
     */
    fun countToDropFromFront(
        currentIndex: Int,
        queueSize: Int,
    ): Int {
        if (currentIndex <= TRIM_ABOVE_HISTORY) return 0
        if (queueSize <= KEEP_HISTORY || currentIndex >= queueSize) return 0
        return (currentIndex - KEEP_HISTORY).coerceAtMost(queueSize - 1)
    }
}
