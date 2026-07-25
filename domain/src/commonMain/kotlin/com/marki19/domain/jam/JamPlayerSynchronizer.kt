package com.marki19.domain.jam

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.domain.utils.toSongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Bridges the Jam session with the local media player.
 *
 * Responsibilities:
 * - Host: forwards local player events to [JamRepository.syncState]
 * - Guest: applies incoming [JamPlaybackState] changes to the local player
 * - All: dispatches incoming [JamCommand]s to the player (on the host side)
 */
@OptIn(ExperimentalTime::class)
class JamPlayerSynchronizer(
    private val jamRepository: JamRepository,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val songRepository: SongRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    /** Mutex to prevent state-sync feedback loops across concurrent flows. */
    private val syncMutex = Mutex()
    private val DRIFT_THRESHOLD_MS = 3_000L
    /** Tracks the last song ID successfully synced/loaded to prevent double-loads. */
    private var lastSyncedSongId: String? = null

    private suspend fun getOrCreateTrack(
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        durationMs: Long
    ): Track {
        val existing = mediaPlayerHandler.queueData.value?.data?.listTracks?.find { it.videoId.cleanId() == videoId.cleanId() }
        if (existing != null) return existing

        val entity = songRepository.getSongById(videoId).firstOrNull()
        if (entity != null) return entity.toTrack()

        val track = Track(
            album = null,
            artists = if (artist.isNotBlank()) listOf(Artist(name = artist, id = null)) else emptyList(),
            duration = null,
            durationSeconds = (durationMs / 1000L).toInt(),
            isAvailable = true,
            isExplicit = false,
            likeStatus = null,
            thumbnails = if (!thumbnailUrl.isNullOrBlank()) listOf(Thumbnail(url = thumbnailUrl, width = 544, height = 544)) else emptyList(),
            title = title,
            videoId = videoId,
            videoType = null,
            category = null,
            feedbackTokens = null,
            resultType = null
        )
        songRepository.insertSong(track.toSongEntity()).firstOrNull()
        return track
    }

    init {
        // ── 1. Guest & Host: apply incoming playback state / Clear queue on join ──
        scope.launch {
            var wasInSession = false
            var preJamQueueData: com.maxrave.domain.mediaservice.handler.QueueData.Data? = null
            jamRepository.sessionState.collectLatest { session ->
                if (session != null && !wasInSession) {
                    // Save pre-jam queue data
                    preJamQueueData = mediaPlayerHandler.queueData.value?.data
                    // Pause audio and wipe local queue for both Host and Guest to isolate Jam room cleanly
                    if (mediaPlayerHandler.controlState.value.isPlaying) {
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                    }
                    mediaPlayerHandler.clearMediaItems()
                } else if (session == null && wasInSession) {
                    // Jam session ended (manual end, timeout, server room closure, or disconnect):
                    mediaPlayerHandler.hardReset()
                    preJamQueueData = null
                    lastSyncedSongId = null
                }
                wasInSession = session != null

                if (session == null) return@collectLatest
                val pb = session.playbackState
                val currentSongId = pb.currentSongId

                if (!currentSongId.isNullOrBlank() && lastSyncedSongId != currentSongId) {
                    if (syncMutex.tryLock()) {
                        try {
                            lastSyncedSongId = currentSongId
                            val queueTrack = pb.queue.find { it.videoId.cleanId() == currentSongId.cleanId() }
                            val track = getOrCreateTrack(
                                videoId = currentSongId,
                                title = queueTrack?.title ?: "",
                                artist = queueTrack?.artist ?: "",
                                thumbnailUrl = queueTrack?.thumbnailUrl,
                                durationMs = queueTrack?.durationMs ?: 0L
                            )
                            mediaPlayerHandler.loadMoreCatalog(arrayListOf(track), isAddToQueue = false)
                            mediaPlayerHandler.playMediaItemInMediaSource(0)
                        } finally {
                            syncMutex.unlock()
                        }
                    }
                }

                if (!session.isHost) {
                    if (syncMutex.tryLock()) {
                        try {
                            val isLocalPlaying = mediaPlayerHandler.controlState.value.isPlaying
                            if (pb.isPlaying && !isLocalPlaying) {
                                mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                            } else if (!pb.isPlaying && isLocalPlaying) {
                                mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                            }

                            // Drift correction (position)
                            val currentPos = mediaPlayerHandler.getProgress()
                            val lagMs = if (pb.serverTimestampMs > 0)
                                (Clock.System.now().toEpochMilliseconds() - pb.serverTimestampMs).coerceAtLeast(0L)
                            else 0L
                            val expectedPos = if (pb.isPlaying) pb.playbackPositionMs + lagMs else pb.playbackPositionMs
                            if (kotlin.math.abs(currentPos - expectedPos) > DRIFT_THRESHOLD_MS) {
                                mediaPlayerHandler.onPlayerEvent(
                                    PlayerEvent.UpdateProgress(expectedPos.toFloat() / 1000f)
                                )
                            }
                        } finally {
                            syncMutex.unlock()
                        }
                    }
                }
            }
        }

        // ── 2. Host: broadcast state on every player/track state change ─────────
        scope.launch {
            kotlinx.coroutines.flow.combine(
                mediaPlayerHandler.controlState,
                mediaPlayerHandler.nowPlayingState
            ) { controlState, nowPlayingState ->
                controlState to nowPlayingState
            }.collectLatest { (controlState, nowPlayingState) ->
                val session = jamRepository.sessionState.value
                if (session == null || !session.isHost) return@collectLatest
                // Non-suspending check — if a local sync is in progress, skip this broadcast
                if (syncMutex.isLocked) return@collectLatest

                val rawNowPlayingId = nowPlayingState.track?.videoId ?: mediaPlayerHandler.nowPlaying.value?.mediaId
                val nowPlayingId = rawNowPlayingId?.cleanId()

                val playbackState = JamPlaybackState(
                    currentSongId = nowPlayingId,
                    isPlaying = controlState.isPlaying,
                    playbackPositionMs = mediaPlayerHandler.getProgress(),
                    queue = session.playbackState.queue,
                    shuffle = controlState.isShuffle,
                    repeatMode = when (controlState.repeatState) {
                        is com.maxrave.domain.mediaservice.handler.RepeatState.One -> JamRepeatMode.ONE
                        is com.maxrave.domain.mediaservice.handler.RepeatState.All -> JamRepeatMode.QUEUE
                        else -> JamRepeatMode.OFF
                    },
                    serverTimestampMs = Clock.System.now().toEpochMilliseconds(),
                )

                jamRepository.syncState(playbackState)
            }
        }

        // ── 3. Host: execute incoming commands from guests ────────────────────
        scope.launch {
            jamRepository.incomingCommands.collectLatest { command ->
                val session = jamRepository.sessionState.value
                if (session == null || !session.isHost) return@collectLatest
                handleCommand(command)
            }
        }
    }

    private suspend fun handleCommand(command: JamCommand) {
        when (command) {
            is JamCommand.Play ->
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
            is JamCommand.Pause ->
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
            is JamCommand.Seek ->
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateProgress(command.positionMs.toFloat() / 1000f))
            is JamCommand.Skip -> {
                if (command.direction > 0) mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next)
                else mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous)
            }
            is JamCommand.SkipTo ->
                mediaPlayerHandler.playMediaItemInMediaSource(command.index)
            is JamCommand.RemoveFromQueue ->
                mediaPlayerHandler.removeMediaItem(command.index)
            is JamCommand.RemoveQueueItem -> {
                val sessionQueue = jamRepository.sessionState.value?.playbackState?.queue
                val targetVideoId = command.videoId.ifBlank {
                    sessionQueue?.find { it.queueId == command.queueId }?.videoId ?: command.queueId
                }
                val targetCleanId = targetVideoId.cleanId()
                val commandCleanQueueId = command.queueId.cleanId()
                val index = mediaPlayerHandler.queueData.value?.data?.listTracks
                    ?.indexOfFirst {
                        val trackCleanId = it.videoId.cleanId()
                        trackCleanId == targetCleanId || trackCleanId == commandCleanQueueId
                    }
                if (index != null && index >= 0) {
                    mediaPlayerHandler.removeMediaItem(index)
                }
            }
            is JamCommand.MoveQueueItem -> {
                // Reorder the player queue using swap() steps
                val tracks = mediaPlayerHandler.queueData.value?.data?.listTracks ?: return
                val fromIndex = tracks.indexOfFirst { it.videoId == command.queueId }
                if (fromIndex >= 0) {
                    mediaPlayerHandler.swap(fromIndex, command.toIndex)
                }
            }
            is JamCommand.AddToQueue -> {
                val track = getOrCreateTrack(
                    videoId = command.videoId,
                    title = command.title,
                    artist = command.artist,
                    thumbnailUrl = command.thumbnailUrl,
                    durationMs = command.durationMs
                )
                mediaPlayerHandler.loadMoreCatalog(arrayListOf(track), isAddToQueue = true)
            }
            is JamCommand.PlayNow -> {
                if (syncMutex.tryLock()) {
                    try {
                        val track = getOrCreateTrack(
                            videoId = command.videoId,
                            title = command.title,
                            artist = command.artist,
                            thumbnailUrl = command.thumbnailUrl,
                            durationMs = command.durationMs
                        )
                        // Clear old player items so previous track does not linger in local queue
                        mediaPlayerHandler.clearMediaItems()
                        mediaPlayerHandler.loadMoreCatalog(arrayListOf(track), isAddToQueue = false)
                        mediaPlayerHandler.playMediaItemInMediaSource(0)
                        // Mark this videoId as synced so the sessionState observer doesn't double-load
                        lastSyncedSongId = command.videoId.cleanId()
                    } finally {
                        syncMutex.unlock()
                    }
                }
            }
            // Shuffle and Repeat are toggles — only fire if current state differs
            is JamCommand.SetShuffle -> {
                val current = mediaPlayerHandler.controlState.value.isShuffle
                if (current != command.enabled) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }
            is JamCommand.SetRepeat -> {
                // Cycle Repeat until it matches — PlayerEvent.Repeat is a toggle
                val currentRepeat = mediaPlayerHandler.controlState.value.repeatState
                val alreadyMatches = when (command.mode) {
                    JamRepeatMode.OFF -> currentRepeat is com.maxrave.domain.mediaservice.handler.RepeatState.None
                    JamRepeatMode.QUEUE -> currentRepeat is com.maxrave.domain.mediaservice.handler.RepeatState.All
                    JamRepeatMode.ONE -> currentRepeat is com.maxrave.domain.mediaservice.handler.RepeatState.One
                }
                if (!alreadyMatches) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Repeat)
                }
            }
            else -> { /* no-op for non-player commands */ }
        }
    }
}
