package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class Badges(
    val musicInlineBadgeRenderer: MusicInlineBadgeRenderer?,
    // The red LIVE chip under a broadcast that is on air right now (Home's "Station" shelf).
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
