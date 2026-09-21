package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.utils.isRadioQueueId

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
     * 120, not the 150 the history is meant to stay under: the check runs right after a batch
     * append and a radio appends roughly every 50th track, so a threshold is only ever crossed at
     * ~47, ~97, ~147, ~197… A 150 here would skip the 147 pass and let the history reach ~197
     * before the first cut. At 120 the cut lands at ~147 and the history then swings 100…~147.
     */
    const val TRIM_ABOVE_HISTORY = 120

    /**
     * Whether a queue is a radio at all — the only kind of queue that is ever trimmed.
     *
     * The type alone does not say so. Favorites, Downloaded, Most played, Monthly recap, Your top
     * tracks, the Analytics lists and a playlist's Shuffle are all queued as [PlaylistType.RADIO]
     * too, and every one of them is a finite list the user picked. What a real radio also has is a
     * YouTube radio id it keeps extending from: `RD…`, minus `RDCLAK…`, which is a curated playlist
     * with an end.
     */
    fun appliesTo(
        playlistType: PlaylistType?,
        playlistId: String?,
    ): Boolean =
        playlistType == PlaylistType.RADIO &&
            playlistId != null &&
            playlistId.isRadioQueueId() &&
            !playlistId.removePrefix("VL").startsWith("RDCLAK")

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

    /**
     * [queue] once the player has removed [removedIds] from the front of its own list, or null when
     * those are not the tracks leading [queue].
     *
     * Null means the queue changed while the removal was on its way, and cutting it anyway would
     * shift every row against the player — the "tap a row, play another song" bug. Ids repeat in a
     * radio, which is why the match is positional and not a set lookup.
     */
    fun <T> afterFrontRemoved(
        queue: List<T>,
        removedIds: List<String>,
        idOf: (T) -> String,
    ): List<T>? {
        if (removedIds.size > queue.size) return null
        for (i in removedIds.indices) {
            if (idOf(queue[i]) != removedIds[i]) return null
        }
        return queue.drop(removedIds.size)
    }
}
