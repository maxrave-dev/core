package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

/** `comment/create_comment`. The params come from the comment box of the page's comments header. */
@Serializable
data class CreateCommentBody(
    val context: Context,
    val createCommentParams: String,
    val commentText: String,
)

/** `comment/create_comment_reply`. The params come from the reply command of the comment answered. */
@Serializable
data class CreateCommentReplyBody(
    val context: Context,
    val createReplyParams: String,
    val commentText: String,
)

/** `comment/perform_comment_action`: like, dislike and their undo, each a token from the comment's toolbar. */
@Serializable
data class PerformCommentActionBody(
    val context: Context,
    val actions: List<String>,
)
