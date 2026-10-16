package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class Badges(
    val musicInlineBadgeRenderer: MusicInlineBadgeRenderer?,
    // The red LIVE chip on a broadcast that is on air right now: Home's live shelf, search rows and
    // playlist rows. Queue rows (`next`) leave it off.
    val liveBadgeRenderer: LiveBadgeRenderer? = null,
) {
    @Serializable
    data class MusicInlineBadgeRenderer(
        val icon: Icon,
    )

    // Its presence is the whole signal; the label is YouTube's own "Live" text.
    @Serializable
    data class LiveBadgeRenderer(
        val label: Runs? = null,
    )
}
