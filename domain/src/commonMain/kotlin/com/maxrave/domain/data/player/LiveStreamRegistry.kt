package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The videos known to be live streams in this run of the app.
 *
 * Nothing about a queue item says it is live: a search result does not mark it, and the Home card
 * that does is only served to a signed-in account. The first trustworthy answer is the player
 * response's `videoDetails.isLive`, which only stream resolution ever sees — so the stream
 * repository records it here, and every player and screen that has to treat a live stream
 * differently reads it from here.
 *
 * In memory on purpose: a video stops being live when its broadcast ends, so a stored answer would
 * go stale, and resolving the stream again on the next run learns it again for free.
 */
object LiveStreamRegistry {
    private val _liveVideoIds = MutableStateFlow<Set<String>>(emptySet())

    /** Observed by screens: a seek bar has to turn into a LIVE label the moment a stream is found live. */
    val liveVideoIds: StateFlow<Set<String>> = _liveVideoIds.asStateFlow()

    fun isLive(videoId: String): Boolean = videoId in _liveVideoIds.value

    /** Called with every resolved player response, so a broadcast that has ended stops counting as live. */
    fun recordLiveStatus(
        videoId: String,
        isLive: Boolean,
    ) = _liveVideoIds.update { if (isLive) it + videoId else it - videoId }
}
