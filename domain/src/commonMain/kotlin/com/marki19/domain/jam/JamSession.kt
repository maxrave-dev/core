package com.marki19.domain.jam

import kotlinx.serialization.Serializable

// ────────────────────────────────────────────────────────────────────────────
//  Rich queue item – replaces bare video-ID strings
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class JamQueueItem(
    /** Stable, server-assigned id — never rely on list index for mutations. */
    val queueId: String,
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    /** Display name of the user who added this song. */
    val addedBy: String = "",
    val addedTimestamp: Long = 0L,
    val voteCount: Int = 0,
    /** Set of user-IDs that have already voted (client-side for UI gating). */
    val voterIds: Set<String> = emptySet(),
    val orderWeight: Double = 0.0,
    val isPlaying: Boolean = false,
    /** True for auto-recommendations, false for manually queued songs. */
    val isRecommendation: Boolean = false,
)

// ────────────────────────────────────────────────────────────────────────────
//  Session state
// ────────────────────────────────────────────────────────────────────────────

data class JamParticipant(
    val userId: String,
    val name: String,
    val imageUrl: String,
    val online: Boolean = true
)

data class JamSessionState(
    val roomId: String,
    val isHost: Boolean,
    val hostId: String,
    /** Display names / IDs of all participants currently in the room. */
    val participants: List<JamParticipant> = emptyList(),
    val permissions: JamPermissions = JamPermissions(),
    val playbackState: JamPlaybackState = JamPlaybackState(),
    /** Each participant's top-song taste list used for recommendations. */
    val guestTastes: Map<String, List<String>> = emptyMap(),
    /** True while a sync round-trip is in flight. */
    val isSyncing: Boolean = false,
    /** When non-null, show HOST_TRANSFER notification. */
    val newHostNotice: String? = null,
    val recommendationsEnabled: Boolean = false,
)

// ────────────────────────────────────────────────────────────────────────────
//  Expanded permissions (7 granular fields)
// ────────────────────────────────────────────────────────────────────────────

data class JamPermissions(
    val allowAddSongs: Boolean = true,
    val allowRemoveSongs: Boolean = true,
    val allowReorder: Boolean = false,
    val allowPause: Boolean = true,
    val allowSkip: Boolean = true,
    val allowSeek: Boolean = false,
    val allowVoting: Boolean = true,
)

// ────────────────────────────────────────────────────────────────────────────
//  Playback state (now with shuffle / repeat + rich queue)
// ────────────────────────────────────────────────────────────────────────────

enum class JamRepeatMode { OFF, QUEUE, ONE }

data class JamPlaybackState(
    val currentSongId: String? = null,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val queue: List<JamQueueItem> = emptyList(),
    val shuffle: Boolean = false,
    val repeatMode: JamRepeatMode = JamRepeatMode.OFF,
    /** Server-side epoch millis when this snapshot was taken — used for drift correction. */
    val serverTimestampMs: Long = 0L,
)

// ────────────────────────────────────────────────────────────────────────────
//  Commands
// ────────────────────────────────────────────────────────────────────────────

sealed class JamCommand {
    // Playback controls
    object Play : JamCommand()
    object Pause : JamCommand()
    data class Seek(val positionMs: Long) : JamCommand()
    data class Skip(val direction: Int = 1) : JamCommand()
    data class SkipTo(val index: Int) : JamCommand()

    // Queue mutations (use queueId, not index, wherever possible)
    data class AddToQueue(val videoId: String) : JamCommand()
    data class RemoveQueueItem(val queueId: String) : JamCommand()
    /** Legacy index-based remove — kept for JamPlayerSynchronizer compatibility. */
    data class RemoveFromQueue(val index: Int) : JamCommand()
    data class MoveQueueItem(val queueId: String, val toIndex: Int) : JamCommand()
    data class PlayNow(val videoId: String) : JamCommand()

    // Voting
    data class Vote(val queueId: String) : JamCommand()

    // Recommendations
    data class EnableRecommendations(val enabled: Boolean) : JamCommand()
    object RefreshRecommendations : JamCommand()

    // Shuffle / Repeat
    data class SetShuffle(val enabled: Boolean) : JamCommand()
    data class SetRepeat(val mode: JamRepeatMode) : JamCommand()

    // Sync / admin
    data class SyncState(val state: JamPlaybackState) : JamCommand()
    data class ShareTaste(val tracks: List<String>) : JamCommand()
    object UnshareTaste : JamCommand()

    // Social
    data class ChatMessage(val senderId: String, val text: String, val timestamp: Long) : JamCommand()
    object Ping : JamCommand()
}
