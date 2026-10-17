package com.maxrave.domain.data.model.comments

enum class CommentLikeState { NONE, LIKED, DISLIKED }

/**
 * One comment, or one reply, from YouTube Music's Comments tab. The action tokens and [replyParams]
 * are null when YouTube gave the request nothing to act with: signed out, or signed in to an
 * account without a channel.
 */
data class YouTubeComment(
    val id: String,
    val author: String,
    val authorAvatar: String?,
    val authorChannelId: String?,
    val isVerified: Boolean,
    val isCreator: Boolean,
    val text: String,
    /** "2 days ago", in the request's language. */
    val publishedTime: String,
    val likeCount: String,
    /** What the count reads once this account likes it ("4" → "5"); YouTube sends it ready. */
    val likeCountLiked: String,
    val likeState: CommentLikeState,
    val isHearted: Boolean,
    val isPinned: Boolean,
    /** "41 replies", in the request's language. */
    val repliesLabel: String?,
    val repliesToken: String?,
    val likeAction: String?,
    val unlikeAction: String?,
    val dislikeAction: String?,
    val undislikeAction: String?,
    val replyParams: String?,
    val replyPlaceholder: String?,
)

data class YouTubeCommentsHeader(
    /** "24,146 Comments" */
    val countText: String?,
    /** "24K" */
    val countShort: String?,
    val topLabel: String?,
    val topToken: String?,
    val newestLabel: String?,
    val newestToken: String?,
    /** Null when YouTube offers this request no comment box: signed out, or no channel. */
    val createParams: String?,
    val placeholder: String?,
    /** The signed-in account's own avatar, beside the comment box. */
    val userAvatar: String?,
)

/**
 * A page of comments or of replies. [header] comes with the first page and with a sort. [nextToken]
 * loads the next page; on a replies page it is "Show more replies", labelled [nextLabel].
 */
data class YouTubeCommentsPage(
    val header: YouTubeCommentsHeader?,
    val comments: List<YouTubeComment>,
    val nextToken: String?,
    val nextLabel: String?,
)
