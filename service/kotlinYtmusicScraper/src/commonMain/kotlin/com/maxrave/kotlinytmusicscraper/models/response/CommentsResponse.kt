package com.maxrave.kotlinytmusicscraper.models.response

import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What `next` answers to a comments token (the Comments tab of the watch page, the next page, a
 * sort, a thread's replies), and what `comment/create_comment*` answers with.
 *
 * A thread carries only keys. The comment itself (text, author, counts) and its toolbar (like
 * state; the like, dislike and reply commands) arrive as [frameworkUpdates] mutations under those
 * keys. Commands are kept as raw JSON: YouTube wraps them differently over time, and only one
 * string inside each is needed.
 */
@Serializable
data class CommentsResponse(
    val onResponseReceivedEndpoints: List<ResponseEndpoint>? = null,
    val frameworkUpdates: FrameworkUpdates? = null,
    /** Only on `comment/create_comment*`: where the new comment's keys are. */
    val actions: List<JsonElement>? = null,
) {
    @Serializable
    data class ResponseEndpoint(
        val reloadContinuationItemsCommand: ContinuationItems? = null,
        val appendContinuationItemsAction: ContinuationItems? = null,
    )

    @Serializable
    data class ContinuationItems(
        val continuationItems: List<Item>? = null,
    )

    @Serializable
    data class Item(
        val commentsHeaderRenderer: HeaderRenderer? = null,
        val commentThreadRenderer: ThreadRenderer? = null,
        /** A reply: replies pages list the comments themselves, not threads. */
        val commentViewModel: CommentViewModel? = null,
        val continuationItemRenderer: ContinuationItemRenderer? = null,
    )

    @Serializable
    data class HeaderRenderer(
        val countText: Runs? = null,
        val commentsCount: Runs? = null,
        val sortMenu: SortMenu? = null,
        val createRenderer: CreateRenderer? = null,
    )

    @Serializable
    data class SortMenu(
        val sortFilterSubMenuRenderer: SortFilterSubMenuRenderer? = null,
    )

    @Serializable
    data class SortFilterSubMenuRenderer(
        val subMenuItems: List<SubMenuItem>? = null,
    )

    @Serializable
    data class SubMenuItem(
        val title: String? = null,
        val serviceEndpoint: JsonElement? = null,
    )

    @Serializable
    data class CreateRenderer(
        val commentSimpleboxRenderer: SimpleboxRenderer? = null,
    )

    /** The comment box. Signed out (or without a channel) it has no submit button, only [prepareAccountEndpoint]. */
    @Serializable
    data class SimpleboxRenderer(
        val submitButton: JsonElement? = null,
        val placeholderText: Runs? = null,
        val authorThumbnail: Thumbnails? = null,
        val prepareAccountEndpoint: JsonElement? = null,
    )

    @Serializable
    data class ThreadRenderer(
        val commentViewModel: CommentViewModelHolder? = null,
        val replies: Replies? = null,
    )

    @Serializable
    data class CommentViewModelHolder(
        val commentViewModel: CommentViewModel? = null,
    )

    @Serializable
    data class CommentViewModel(
        val commentId: String? = null,
        val commentKey: String? = null,
        val toolbarStateKey: String? = null,
        val toolbarSurfaceKey: String? = null,
        val pinnedText: String? = null,
    )

    @Serializable
    data class Replies(
        val commentRepliesRenderer: RepliesRenderer? = null,
    )

    @Serializable
    data class RepliesRenderer(
        /** "41 replies", already in the request's language. */
        val viewReplies: JsonElement? = null,
        // YouTube Music hands the first replies token under subThreads; the WEB client under contents.
        val subThreads: List<Item>? = null,
        val contents: List<Item>? = null,
    )

    /** The next page (a top-level item), or "Show more replies" (a button) on a replies page. */
    @Serializable
    data class ContinuationItemRenderer(
        val continuationEndpoint: JsonElement? = null,
        val button: JsonElement? = null,
    )

    @Serializable
    data class FrameworkUpdates(
        val entityBatchUpdate: EntityBatchUpdate? = null,
    )

    @Serializable
    data class EntityBatchUpdate(
        val mutations: List<Mutation>? = null,
    )

    @Serializable
    data class Mutation(
        val entityKey: String? = null,
        val payload: Payload? = null,
    )

    @Serializable
    data class Payload(
        val commentEntityPayload: CommentEntity? = null,
        val engagementToolbarStateEntityPayload: ToolbarState? = null,
        /** The like/dislike/reply commands. Signed out they are empty and a prepareAccountCommand stands in. */
        val engagementToolbarSurfaceEntityPayload: JsonElement? = null,
    )

    @Serializable
    data class CommentEntity(
        val properties: Properties? = null,
        val author: Author? = null,
        val toolbar: Toolbar? = null,
    ) {
        @Serializable
        data class Properties(
            val commentId: String? = null,
            val content: Content? = null,
            val publishedTime: String? = null,
        )

        @Serializable
        data class Content(
            val content: String? = null,
        )

        @Serializable
        data class Author(
            val channelId: String? = null,
            val displayName: String? = null,
            val avatarThumbnailUrl: String? = null,
            val isVerified: Boolean? = null,
            val isCreator: Boolean? = null,
        )

        @Serializable
        data class Toolbar(
            val likeCountNotliked: String? = null,
            val likeCountLiked: String? = null,
        )
    }

    @Serializable
    data class ToolbarState(
        val likeState: String? = null,
        val heartState: String? = null,
    )
}
