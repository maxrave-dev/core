package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.CommentsResponse
import com.maxrave.kotlinytmusicscraper.models.response.NextResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Shapes copied from YouTube Music's Comments tab (WEB_REMIX, 2026-10-17); names and tokens are made up.
private val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private const val WATCH_TABS = """
{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
  {"tabRenderer":{"title":"Lyrics"}},
  {"tabRenderer":{"title":"Bình luận","content":{"sectionListRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"COMMENTS_TAB"}}],"targetId":"vid"}}}},
  {"tabRenderer":{"title":"Related"}}
]}}}}}
"""

private const val WATCH_TABS_TURNED_OFF = """
{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
  {"tabRenderer":{"title":"Lyrics"}},
  {"tabRenderer":{"title":"Comments","content":{"messageRenderer":{"text":{"runs":[{"text":"Comments are turned off. "}]}}},"unselectable":true}},
  {"tabRenderer":{"title":"Related"}}
]}}}}}
"""

// Signed out: the comment box offers only to sign in, and the toolbar carries no commands.
private const val FIRST_PAGE = """
{"onResponseReceivedEndpoints":[
 {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER","continuationItems":[
  {"commentsHeaderRenderer":{
   "countText":{"runs":[{"text":"1,234"},{"text":" Comments"}]},
   "commentsCount":{"runs":[{"text":"1.2K"}]},
   "sortMenu":{"sortFilterSubMenuRenderer":{"subMenuItems":[
    {"title":"Top","serviceEndpoint":{"clickTrackingParams":"x","continuationCommand":{"token":"SORT_TOP"}}},
    {"title":"Newest","serviceEndpoint":{"continuationCommand":{"token":"SORT_NEWEST"}}}]}},
   "createRenderer":{"commentSimpleboxRenderer":{"placeholderText":{"runs":[{"text":"Add a comment..."}]},"prepareAccountEndpoint":{"modalEndpoint":{}}}}}}]}},
 {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","continuationItems":[
  {"commentThreadRenderer":{
   "commentViewModel":{"commentViewModel":{"commentId":"c1","commentKey":"k1","toolbarStateKey":"s1","toolbarSurfaceKey":"t1"}},
   "replies":{"commentRepliesRenderer":{
    "viewReplies":{"buttonRenderer":{"text":{"runs":[{"text":"3 replies"}]}}},
    "subThreads":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLIES_C1"}}}}]}}}},
  {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentId":"missing","commentKey":"no-such-key"}}}},
  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"PAGE_2"}}}}]}}],
 "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
  {"entityKey":"k1","payload":{"commentEntityPayload":{
   "properties":{"commentId":"c1","content":{"content":"Chorus at 1:02 hits hard"},"publishedTime":"2 days ago"},
   "author":{"channelId":"UC1","displayName":"@listener","avatarThumbnailUrl":"https://example.com/a.jpg","isVerified":true,"isCreator":false},
   "toolbar":{"likeCountNotliked":"4","likeCountLiked":"5","replyCount":"3"}}}},
  {"entityKey":"s1","payload":{"engagementToolbarStateEntityPayload":{"likeState":"TOOLBAR_LIKE_STATE_INDIFFERENT","heartState":"TOOLBAR_HEART_STATE_HEARTED"}}},
  {"entityKey":"t1","payload":{"engagementToolbarSurfaceEntityPayload":{"likeCommand":{"clickTrackingParams":"x"},"prepareAccountCommand":{"modalEndpoint":{}}}}}]}}}
"""

// Signed in: every command is there, one of them wrapped the way YouTube sometimes wraps them.
private const val SIGNED_IN_PAGE = """
{"onResponseReceivedEndpoints":[
 {"appendContinuationItemsAction":{"continuationItems":[
  {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentId":"c2","commentKey":"k2","toolbarStateKey":"s2","toolbarSurfaceKey":"t2"}}}}]}}],
 "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
  {"entityKey":"k2","payload":{"commentEntityPayload":{"properties":{"content":{"content":"Second"},"publishedTime":"1 hour ago"},"author":{"displayName":"@me"},"toolbar":{"likeCountNotliked":"9","likeCountLiked":"10"}}}},
  {"entityKey":"s2","payload":{"engagementToolbarStateEntityPayload":{"likeState":"TOOLBAR_LIKE_STATE_LIKED"}}},
  {"entityKey":"t2","payload":{"engagementToolbarSurfaceEntityPayload":{
   "likeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"A_LIKE"}}},
   "unlikeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"A_UNLIKE"}}},
   "dislikeCommand":{"innertubeCommand":{"commandExecutorCommand":{"commands":[{"performCommentActionEndpoint":{"action":"A_DISLIKE"}}]}}},
   "undislikeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"A_UNDISLIKE"}}},
   "replyCommand":{"innertubeCommand":{"createCommentReplyDialogEndpoint":{"dialog":{"commentReplyDialogRenderer":{
    "placeholderText":{"runs":[{"text":"Add a reply..."}]},
    "replyButton":{"buttonRenderer":{"serviceEndpoint":{"createCommentReplyEndpoint":{"createReplyParams":"REPLY_PARAMS"}}}}}}}}}}}}]}}}
"""

// A replies page, then "Show more replies". YouTube Music lists each reply as a thread; the WEB
// client lists the bare view model. Both are here.
private const val REPLIES_PAGE = """
{"onResponseReceivedEndpoints":[
 {"appendContinuationItemsAction":{"continuationItems":[
  {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentId":"r0","commentKey":"rk0"}}}},
  {"commentViewModel":{"commentId":"r1","commentKey":"rk1"}},
  {"continuationItemRenderer":{"button":{"buttonRenderer":{"text":{"runs":[{"text":"Show more replies"}]},"command":{"continuationCommand":{"token":"MORE_REPLIES"}}}}}}]}}],
 "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
  {"entityKey":"rk0","payload":{"commentEntityPayload":{"properties":{"content":{"content":"Same"},"publishedTime":"2 days ago"},"author":{"displayName":"@first"},"toolbar":{"likeCountNotliked":"2"}}}},
  {"entityKey":"rk1","payload":{"commentEntityPayload":{"properties":{"content":{"content":"Agreed"},"publishedTime":"1 day ago"},"author":{"displayName":"@reply"},"toolbar":{"likeCountNotliked":"1"}}}}]}}}
"""

// What comment/create_comment answers with: the new comment's keys under actions, its data in the mutations.
private const val CREATED = """
{"actions":[{"runAttestationCommand":{"engagementType":"ENGAGEMENT_TYPE_COMMENT_POST"}},
 {"createCommentAction":{"contents":{"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentId":"new","commentKey":"nk","toolbarStateKey":"ns","toolbarSurfaceKey":"nt"}}}}}}],
 "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
  {"entityKey":"nk","payload":{"commentEntityPayload":{"properties":{"content":{"content":"Posted from SimpMusic"},"publishedTime":"now"},"author":{"displayName":"@me"},"toolbar":{"likeCountNotliked":""}}}},
  {"entityKey":"nt","payload":{"engagementToolbarSurfaceEntityPayload":{"likeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"NEW_LIKE"}}}}}}]}}}
"""

class CommentsPageParserTest {
    private fun comments(raw: String) = CommentsPageParser.parse(json.decodeFromString<CommentsResponse>(raw))

    @Test
    fun commentsTabIsFoundByTypeNotTitle() {
        assertEquals("COMMENTS_TAB", CommentsPageParser.commentsTabToken(json.decodeFromString<NextResponse>(WATCH_TABS)))
    }

    @Test
    fun turnedOffCommentsTabHasNoToken() {
        assertNull(CommentsPageParser.commentsTabToken(json.decodeFromString<NextResponse>(WATCH_TABS_TURNED_OFF)))
    }

    @Test
    fun firstPageCarriesHeaderCommentsAndNextPage() {
        val page = comments(FIRST_PAGE)
        val header = assertNotNull(page.header)
        assertEquals("1,234 Comments", header.countText)
        assertEquals("1.2K", header.countShort)
        assertEquals("Top", header.topLabel)
        assertEquals("SORT_TOP", header.topToken)
        assertEquals("Newest", header.newestLabel)
        assertEquals("SORT_NEWEST", header.newestToken)
        assertEquals("Add a comment...", header.placeholder)
        // Signed out: no comment box to post with.
        assertNull(header.createParams)
        // The thread whose data never arrived is dropped, not the page.
        assertEquals(1, page.comments.size)
        assertEquals("PAGE_2", page.nextToken)
    }

    @Test
    fun threadIsJoinedWithItsMutations() {
        val comment = comments(FIRST_PAGE).comments.single()
        assertEquals("c1", comment.id)
        assertEquals("@listener", comment.author)
        assertEquals("https://example.com/a.jpg", comment.authorAvatar)
        assertTrue(comment.isVerified)
        assertFalse(comment.isCreator)
        assertEquals("Chorus at 1:02 hits hard", comment.text)
        assertEquals("2 days ago", comment.publishedTime)
        assertEquals("4", comment.likeCount)
        assertEquals("5", comment.likeCountLiked)
        assertEquals(CommentLikeState.NONE, comment.likeState)
        assertTrue(comment.isHearted)
        assertEquals("3 replies", comment.repliesLabel)
        assertEquals("REPLIES_C1", comment.repliesToken)
        // Signed out: nothing to act with.
        assertNull(comment.likeAction)
        assertNull(comment.replyParams)
    }

    @Test
    fun signedInCommentCarriesEveryAction() {
        val comment = comments(SIGNED_IN_PAGE).comments.single()
        assertEquals("c2", comment.id)
        assertEquals(CommentLikeState.LIKED, comment.likeState)
        assertEquals("A_LIKE", comment.likeAction)
        assertEquals("A_UNLIKE", comment.unlikeAction)
        assertEquals("A_DISLIKE", comment.dislikeAction)
        assertEquals("A_UNDISLIKE", comment.undislikeAction)
        assertEquals("REPLY_PARAMS", comment.replyParams)
        assertEquals("Add a reply...", comment.replyPlaceholder)
    }

    @Test
    fun repliesPageReadsRepliesAndShowMore() {
        val page = comments(REPLIES_PAGE)
        assertNull(page.header)
        assertEquals(listOf("Same", "Agreed"), page.comments.map { it.text })
        assertEquals("MORE_REPLIES", page.nextToken)
        assertEquals("Show more replies", page.nextLabel)
    }

    @Test
    fun createdCommentIsReadFromItsKeys() {
        val created = assertNotNull(CommentsPageParser.parseCreated(json.decodeFromString<CommentsResponse>(CREATED)))
        assertEquals("new", created.id)
        assertEquals("Posted from SimpMusic", created.text)
        assertEquals("NEW_LIKE", created.likeAction)
    }
}
