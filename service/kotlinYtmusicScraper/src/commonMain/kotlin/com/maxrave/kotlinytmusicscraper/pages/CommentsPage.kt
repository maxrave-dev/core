package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.CommentsResponse
import com.maxrave.kotlinytmusicscraper.models.response.NextResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class CommentLikeState { NONE, LIKED, DISLIKED }

/**
 * One comment, or one reply. The four action tokens and [replyParams] are null when YouTube gave this
 * request nothing to act with: signed out, or signed in to an account without a channel.
 */
data class CommentItem(
    val id: String,
    val author: String,
    val authorAvatar: String?,
    val authorChannelId: String?,
    val isVerified: Boolean,
    val isCreator: Boolean,
    val text: String,
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

data class CommentsHeader(
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
data class CommentsPage(
    val header: CommentsHeader?,
    val comments: List<CommentItem>,
    val nextToken: String?,
    val nextLabel: String?,
)

object CommentsPageParser {
    /**
     * The token behind the watch page's Comments tab, or null when it has none (comments turned off,
     * or a live stream that only has its chat). The tab is told apart by type, never by its title,
     * which is translated: Lyrics and Related carry a browse endpoint with their page type, Up next a
     * queue, and Comments is the only one holding a section list that reloads from a continuation.
     */
    fun commentsTabToken(next: NextResponse): String? =
        next.contents.singleColumnMusicWatchNextResultsRenderer
            ?.tabbedRenderer
            ?.watchNextTabbedResultsRenderer
            ?.tabs
            ?.firstNotNullOfOrNull { tab ->
                tab.tabRenderer.content
                    ?.sectionListRenderer
                    ?.continuations
                    ?.firstNotNullOfOrNull { it.reloadContinuationData?.continuation }
            }

    fun parse(response: CommentsResponse): CommentsPage {
        val entities = response.entities()
        var header: CommentsHeader? = null
        var nextToken: String? = null
        var nextLabel: String? = null
        val comments = mutableListOf<CommentItem>()
        response.onResponseReceivedEndpoints
            .orEmpty()
            .flatMap { (it.reloadContinuationItemsCommand ?: it.appendContinuationItemsAction)?.continuationItems.orEmpty() }
            .forEach { item ->
                item.commentsHeaderRenderer?.let { header = it.toHeader() }
                item.commentThreadRenderer?.let { thread ->
                    val replies = thread.replies?.commentRepliesRenderer
                    thread.commentViewModel?.commentViewModel
                        ?.toComment(
                            entities = entities,
                            repliesLabel = replies?.viewReplies.findObject("text").runsText(),
                            // Inside a thread every continuation is its replies; only a top-level one is the next page.
                            repliesToken =
                                (replies?.subThreads.orEmpty() + replies?.contents.orEmpty())
                                    .firstNotNullOfOrNull { it.continuationItemRenderer?.token() },
                        )?.let(comments::add)
                }
                item.commentViewModel?.toComment(entities, repliesLabel = null, repliesToken = null)?.let(comments::add)
                item.continuationItemRenderer?.let {
                    nextToken = it.token()
                    nextLabel = it.button.findObject("text").runsText()
                }
            }
        return CommentsPage(header = header, comments = comments, nextToken = nextToken, nextLabel = nextLabel)
    }

    /**
     * The comment `comment/create_comment*` just made. Its keys sit somewhere under `actions` and its
     * data in the mutations; when the keys cannot be found, the first comment in the mutations is it.
     */
    fun parseCreated(response: CommentsResponse): CommentItem? {
        val entities = response.entities()
        val keys =
            response.actions
                ?.firstNotNullOfOrNull { it.findObjectWith("commentKey") }
                ?.let {
                    CommentsResponse.CommentViewModel(
                        commentId = it.string("commentId"),
                        commentKey = it.string("commentKey"),
                        toolbarStateKey = it.string("toolbarStateKey"),
                        toolbarSurfaceKey = it.string("toolbarSurfaceKey"),
                    )
                } ?: entities.entries
                .firstOrNull { it.value.commentEntityPayload != null }
                ?.let { CommentsResponse.CommentViewModel(commentKey = it.key) }
        return keys?.toComment(entities, repliesLabel = null, repliesToken = null)
    }

    private fun CommentsResponse.entities(): Map<String, CommentsResponse.Payload> =
        frameworkUpdates
            ?.entityBatchUpdate
            ?.mutations
            .orEmpty()
            .mapNotNull { mutation -> mutation.entityKey?.let { key -> mutation.payload?.let { key to it } } }
            .toMap()

    private fun CommentsResponse.HeaderRenderer.toHeader(): CommentsHeader {
        val sorts = sortMenu?.sortFilterSubMenuRenderer?.subMenuItems.orEmpty()
        val box = createRenderer?.commentSimpleboxRenderer
        return CommentsHeader(
            countText = countText?.runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() },
            countShort = commentsCount?.runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() },
            topLabel = sorts.getOrNull(0)?.title,
            topToken = sorts.getOrNull(0)?.serviceEndpoint.findString("token"),
            newestLabel = sorts.getOrNull(1)?.title,
            newestToken = sorts.getOrNull(1)?.serviceEndpoint.findString("token"),
            createParams = box?.submitButton.findString("createCommentParams"),
            placeholder = box?.placeholderText?.runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() },
            userAvatar = box?.authorThumbnail?.thumbnails?.lastOrNull()?.url,
        )
    }

    private fun CommentsResponse.CommentViewModel.toComment(
        entities: Map<String, CommentsResponse.Payload>,
        repliesLabel: String?,
        repliesToken: String?,
    ): CommentItem? {
        val entity = commentKey?.let { entities[it]?.commentEntityPayload } ?: return null
        val id = commentId ?: entity.properties?.commentId ?: return null
        val state = toolbarStateKey?.let { entities[it]?.engagementToolbarStateEntityPayload }
        val surface = toolbarSurfaceKey?.let { entities[it]?.engagementToolbarSurfaceEntityPayload } as? JsonObject
        val likeCount = entity.toolbar?.likeCountNotliked.orEmpty()
        return CommentItem(
            id = id,
            author = entity.author?.displayName.orEmpty(),
            authorAvatar = entity.author?.avatarThumbnailUrl,
            authorChannelId = entity.author?.channelId,
            isVerified = entity.author?.isVerified == true,
            isCreator = entity.author?.isCreator == true,
            text = entity.properties?.content?.content.orEmpty(),
            publishedTime = entity.properties?.publishedTime.orEmpty(),
            likeCount = likeCount,
            likeCountLiked = entity.toolbar?.likeCountLiked ?: likeCount,
            likeState =
                when (state?.likeState) {
                    "TOOLBAR_LIKE_STATE_LIKED" -> CommentLikeState.LIKED
                    "TOOLBAR_LIKE_STATE_DISLIKED" -> CommentLikeState.DISLIKED
                    else -> CommentLikeState.NONE
                },
            isHearted = state?.heartState == "TOOLBAR_HEART_STATE_HEARTED",
            isPinned = pinnedText != null,
            repliesLabel = repliesLabel,
            repliesToken = repliesToken,
            likeAction = surface?.get("likeCommand").commentAction(),
            unlikeAction = surface?.get("unlikeCommand").commentAction(),
            dislikeAction = surface?.get("dislikeCommand").commentAction(),
            undislikeAction = surface?.get("undislikeCommand").commentAction(),
            replyParams = surface?.get("replyCommand").findString("createReplyParams"),
            replyPlaceholder = surface?.get("replyCommand").findObject("placeholderText").runsText(),
        )
    }

    private fun CommentsResponse.ContinuationItemRenderer.token(): String? =
        continuationEndpoint.findString("token") ?: button.findString("token")

    private fun JsonElement?.commentAction(): String? = findObject("performCommentActionEndpoint").findString("action")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** "runs" joined, or "simpleText". */
    private fun JsonObject?.runsText(): String? {
        if (this == null) return null
        (this["simpleText"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        return (this["runs"] as? JsonArray)
            ?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
            ?.takeIf { it.isNotBlank() }
    }

    /** The first string under [key], at any depth. */
    private fun JsonElement?.findString(key: String): String? {
        when (this) {
            is JsonObject -> {
                (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
                values.forEach { value -> value.findString(key)?.let { return it } }
            }
            is JsonArray -> forEach { value -> value.findString(key)?.let { return it } }
            else -> Unit
        }
        return null
    }

    /** The first object under [key], at any depth. */
    private fun JsonElement?.findObject(key: String): JsonObject? {
        when (this) {
            is JsonObject -> {
                (this[key] as? JsonObject)?.let { return it }
                values.forEach { value -> value.findObject(key)?.let { return it } }
            }
            is JsonArray -> forEach { value -> value.findObject(key)?.let { return it } }
            else -> Unit
        }
        return null
    }

    /** The first object that has [key] itself, at any depth. */
    private fun JsonElement?.findObjectWith(key: String): JsonObject? {
        when (this) {
            is JsonObject -> {
                if (key in this) return this
                values.forEach { value -> value.findObjectWith(key)?.let { return it } }
            }
            is JsonArray -> forEach { value -> value.findObjectWith(key)?.let { return it } }
            else -> Unit
        }
        return null
    }
}
