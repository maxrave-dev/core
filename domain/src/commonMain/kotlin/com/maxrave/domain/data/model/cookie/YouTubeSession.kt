package com.maxrave.domain.data.model.cookie

/**
 * The three values the YouTube scraper needs together: a brand channel's pageId is only
 * valid with the authuser that owns it, and both only with the cookie they came from.
 * Read as one DataStore snapshot so no consumer ever sees a mixed pair.
 */
data class YouTubeSession(
    val cookie: String,
    val pageId: String,
    val authUser: Int,
)
