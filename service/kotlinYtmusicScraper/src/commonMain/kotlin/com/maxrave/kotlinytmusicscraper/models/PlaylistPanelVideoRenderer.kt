package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistPanelVideoRenderer(
    val title: Runs?,
    val lengthText: Runs?,
    val longBylineText: Runs?,
    val shortBylineText: Runs?,
    val badges: List<Badges>?,
    val videoId: String?,
    val playlistSetVideoId: String?,
    val selected: Boolean,
    val thumbnail: Thumbnails,
    val unplayableText: Runs?,
    val menu: Menu?,
    val navigationEndpoint: NavigationEndpoint,
) {
    /**
     * A broadcast on air right now. Queue rows measured on 2026-10-16 carried no LIVE chip (a live
     * seed showed only its elapsed time as `lengthText`), so this is usually false here and the
     * queue learns a broadcast is live when its stream resolves.
     */
    val isLive: Boolean
        get() = badges?.any { it.liveBadgeRenderer != null } == true
}