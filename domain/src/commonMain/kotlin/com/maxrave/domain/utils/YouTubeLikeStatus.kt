package com.maxrave.domain.utils

/**
 * The signed-in account's rating of a track on YouTube Music. One `next` call answers all three
 * states, so like and dislike travel together instead of being fetched separately.
 */
enum class YouTubeLikeStatus {
    LIKE,
    DISLIKE,
    INDIFFERENT,
}
