package com.marki19.domain.jam

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.domain.utils.toSongEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.extension.toGenericMediaItem
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Bridges the Jam session with the local media player.
 *
 * Responsibilities:
 * - Host: forwards local player events to [JamRepository.syncState]
 * - Guest: applies incoming [JamPlaybackState] changes to the local player
 * - All: dispatches incoming [JamCommand]s to the player (on the host side)
 *
 * KEY DESIGN PRINCIPLES:
 * 1. The Host NEVER consumes incoming commands from the server (those are for Guests and echoes).
 * 2. The Host's local echo is needed for instant UI response, but the synchronizer must ignore
 *    server-echoed state updates to prevent feedback loops.
 * 3. Guests sync their player to the server's authoritative playback state.
 * 4. The syncMutex prevents concurrent mutations to the player state.
 */
@OptIn(ExperimentalTime::class)
class JamPlayerSynchronizer(
    private val jamRepository: JamRepository,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val songRepository: SongRepository,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("JamPlayerSynchronizer Unhandled coroutine exception: ${throwable.message}")
        throwable.printStackTrace()
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    
    /** Mutex to prevent concurrent player mutations. */
    private val syncMutex = Mutex()
    
    private val DRIFT_THRESHOLD_MS = 3_000L
    
    /** Tracks the last song ID the synchronizer loaded to prevent double-loads on state updates. */
    private var lastSyncedSongId: String? = null

    /**
     * Gets an existing track from player queue or DB, or builds one from metadata.
     * Inserts to DB in background to avoid blocking the caller.
     */
    private suspend fun getOrCreateTrack(
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        durationMs: Long
    ): Track {
        // 1. Check local player queue first (fastest path)
        val existing = mediaPlayerHandler.queueData.value?.data?.listTracks
            ?.find { it.videoId.cleanId() == videoId.cleanId() }
        if (existing != null) return existing

        // 2. Check local database
        val entity = songRepository.getSongById(videoId).firstOrNull()
        if (entity != null) return entity.toTrack()

        // 3. Build a minimal track from the Jam metadata
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
        // Fire-and-forget DB insert — never block the sync path on I/O
        scope.launch {
            try {
                songRepository.insertSong(track.toSongEntity()).firstOrNull()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return track
    }

    init {
        // ── SECTION 1: React to session lifecycle changes and sync Guest player state ──
        scope.launch {
            var wasInSession = false
            // MUST use collect (not collectLatest) here.
            // collectLatest would cancel the block mid-execution whenever sessionState emits a new value
            // (which happens constantly via STATE_SYNC). This cancels track loads and play commands
            // mid-flight, leaving ExoPlayer in a broken half-initialized state.
            jamRepository.sessionState.collect { session ->

                // ─── Session Started ───
                if (session != null && !wasInSession) {
                    lastSyncedSongId = null
                    // Guests pause & clear their local player to start fresh with the room's state
                    if (!session.isHost) {
                        if (mediaPlayerHandler.controlState.value.isPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                        }
                        mediaPlayerHandler.clearMediaItems()
                    }
                }

                // ─── Session Ended ───
                if (session == null && wasInSession) {
                    // Do NOT call hardReset() here. leaveSession() is async and includes a 150ms
                    // network-flush delay, so by the time sessionState emits null the user may
                    // have already navigated away and started playing a new song. hardReset() would
                    // wipe that new track's nowPlayingState, making the MiniPlayer invisible.
                    // Simply reset the sync cursor — the player keeps whatever it was doing.
                    lastSyncedSongId = null
                }

                wasInSession = session != null
                if (session == null) return@collect

                // ─── Guest-only: Sync player to authoritative server state ───
                // The Host manages its own player directly; it must NOT apply server state to itself
                // or it creates an infinite feedback loop.
                if (!session.isHost) {
                    val pb = session.playbackState
                    syncMutex.withLock {
                        // Load the current song if it has changed
                        val currentSongId = pb.currentSongId
                        val isNewTrack = !currentSongId.isNullOrBlank() &&
                            (lastSyncedSongId?.cleanId() != currentSongId.cleanId() ||
                             mediaPlayerHandler.queueData.value?.data?.listTracks.isNullOrEmpty())

                        if (isNewTrack && !currentSongId.isNullOrBlank()) {
                            lastSyncedSongId = currentSongId
                            val queueTrack = pb.queue.find { it.videoId.cleanId() == currentSongId.cleanId() }
                            val track = getOrCreateTrack(
                                videoId = currentSongId,
                                title = queueTrack?.title ?: "",
                                artist = queueTrack?.artist ?: "",
                                thumbnailUrl = queueTrack?.thumbnailUrl,
                                durationMs = queueTrack?.durationMs ?: 0L
                            )
                            mediaPlayerHandler.addMediaItem(track.toGenericMediaItem(), playWhenReady = pb.isPlaying)

                            // For slow connections: calculate expected position considering network transit lag
                            val lagMs = if (pb.serverTimestampMs > 0)
                                (Clock.System.now().toEpochMilliseconds() - pb.serverTimestampMs).coerceAtLeast(0L)
                            else 0L
                            val initialTargetPos = if (pb.isPlaying) pb.playbackPositionMs + lagMs else pb.playbackPositionMs
                            if (initialTargetPos > 1000L) {
                                mediaPlayerHandler.player.seekTo(initialTargetPos)
                            }
                        }

                        // Sync play/pause state.
                        // IMPORTANT: Use (isPlaying || playWhenReady) to avoid pausing while ExoPlayer is buffering.
                        val isLocalPlaying = mediaPlayerHandler.controlState.value.isPlaying || mediaPlayerHandler.player.playWhenReady
                        if (pb.isPlaying && !isLocalPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.Play)
                        } else if (!pb.isPlaying && isLocalPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.Pause)
                        }

                        // Drift correction for ongoing playback
                        if (!isNewTrack) {
                            val lagMs = if (pb.serverTimestampMs > 0)
                                (Clock.System.now().toEpochMilliseconds() - pb.serverTimestampMs).coerceAtLeast(0L)
                            else 0L
                            val expectedPos = if (pb.isPlaying) pb.playbackPositionMs + lagMs else pb.playbackPositionMs
                            val currentPos = mediaPlayerHandler.getProgress()
                            if (kotlin.math.abs(currentPos - expectedPos) > DRIFT_THRESHOLD_MS) {
                                mediaPlayerHandler.player.seekTo(expectedPos)
                            }
                        }
                    }
                }
            }
        }

        // ── SECTION 2: Host broadcasts its player state to the server ────────────
        scope.launch {
            kotlinx.coroutines.flow.combine(
                mediaPlayerHandler.controlState,
                mediaPlayerHandler.nowPlayingState
            ) { controlState, nowPlayingState ->
                controlState to nowPlayingState
            }.collectLatest { (controlState, nowPlayingState) ->
                val session = jamRepository.sessionState.value
                // Only the Host broadcasts; also skip while a sync lock is held
                if (session == null || !session.isHost) return@collectLatest
                if (syncMutex.isLocked) return@collectLatest

                val rawNowPlayingId = nowPlayingState.track?.videoId
                    ?: mediaPlayerHandler.nowPlaying.value?.mediaId
                val nowPlayingId = rawNowPlayingId?.cleanId()

                // Consider player intended state (playWhenReady) so buffering isn't reported as paused
                val isHostPlaying = controlState.isPlaying || mediaPlayerHandler.player.playWhenReady

                val playbackState = JamPlaybackState(
                    currentSongId = nowPlayingId,
                    isPlaying = isHostPlaying,
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

        // ── SECTION 3: Host executes commands from Guests ─────────────────────────
        // This flow ONLY processes commands when we ARE the Host.
        // The local echo from sendCommand() also routes here, which is correct:
        // the Host needs to act on its own PlayNow/Play/Pause commands immediately.
        scope.launch {
            jamRepository.incomingCommands.collect { command ->
                val session = jamRepository.sessionState.value
                // CRITICAL: Only the Host should execute commands.
                // Guests should NOT execute commands — their state comes from Section 1 (server state).
                if (session == null || !session.isHost) return@collect
                handleCommand(command)
            }
        }
    }

    private suspend fun handleCommand(command: JamCommand) {
        when (command) {
            is JamCommand.Play ->
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.Play)
            is JamCommand.Pause ->
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.Pause)
            is JamCommand.Seek ->
                mediaPlayerHandler.player.seekTo(command.positionMs)
            is JamCommand.Skip -> {
                val session = jamRepository.sessionState.value
                val queue = session?.playbackState?.queue ?: emptyList()
                val currentSongId = session?.playbackState?.currentSongId?.cleanId()

                if (command.direction > 0) {
                    val upcomingQueue = queue.filter { it.videoId.cleanId() != currentSongId }
                    val nextItem = upcomingQueue.firstOrNull { !it.isRecommendation } ?: upcomingQueue.firstOrNull()
                    if (nextItem != null) {
                        handleCommand(
                            JamCommand.PlayNow(
                                videoId = nextItem.videoId,
                                title = nextItem.title,
                                artist = nextItem.artist,
                                thumbnailUrl = nextItem.thumbnailUrl,
                                durationMs = nextItem.durationMs
                            )
                        )
                        jamRepository.sendCommand(JamCommand.RemoveQueueItem(nextItem.queueId, nextItem.videoId))
                    }
                    // Do nothing if there's no next queued song
                } else {
                    val currentPos = mediaPlayerHandler.getProgress()
                    if (currentPos > 5_000L) {
                        mediaPlayerHandler.player.seekTo(0L)
                    } else {
                        if (mediaPlayerHandler.player.hasPreviousMediaItem()) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.SkipToPrevious)
                        } else {
                            mediaPlayerHandler.player.seekTo(0L)
                        }
                    }
                }
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
                val currentMediaId = mediaPlayerHandler.nowPlayingState.value.track?.videoId?.cleanId()
                    ?: mediaPlayerHandler.nowPlayingState.value.mediaItem.mediaId.cleanId()
                    ?: mediaPlayerHandler.nowPlaying.value?.mediaId?.cleanId()
                val isPlayerEmpty = currentMediaId.isNullOrBlank() || mediaPlayerHandler.player.mediaItemCount == 0

                if (isPlayerEmpty) {
                    mediaPlayerHandler.addMediaItem(track.toGenericMediaItem(), playWhenReady = true)
                    lastSyncedSongId = command.videoId.cleanId()
                    val controlState = mediaPlayerHandler.controlState.value
                    jamRepository.syncState(
                        JamPlaybackState(
                            currentSongId = command.videoId.cleanId(),
                            isPlaying = true,
                            playbackPositionMs = 0L,
                            queue = jamRepository.sessionState.value?.playbackState?.queue ?: emptyList(),
                            shuffle = controlState.isShuffle,
                            repeatMode = when (controlState.repeatState) {
                                is com.maxrave.domain.mediaservice.handler.RepeatState.One -> JamRepeatMode.ONE
                                is com.maxrave.domain.mediaservice.handler.RepeatState.All -> JamRepeatMode.QUEUE
                                else -> JamRepeatMode.OFF
                            },
                            serverTimestampMs = Clock.System.now().toEpochMilliseconds(),
                        )
                    )
                } else {
                    mediaPlayerHandler.loadMoreCatalog(arrayListOf(track), isAddToQueue = true)
                }
            }
            is JamCommand.PlayNow -> {
                syncMutex.withLock {
                    val currentPlayingId = mediaPlayerHandler.nowPlayingState.value.track?.videoId?.cleanId()
                        ?: mediaPlayerHandler.nowPlaying.value?.mediaId?.cleanId()

                    if (currentPlayingId == command.videoId.cleanId()) {
                        // The track is already playing! Purge the rest of the queue safely without stopping playback.
                        while (mediaPlayerHandler.player.mediaItemCount > 1) {
                            val currentIdx = mediaPlayerHandler.player.currentMediaItemIndex
                            if (currentIdx > 0) {
                                mediaPlayerHandler.removeMediaItem(0)
                            } else {
                                mediaPlayerHandler.removeMediaItem(mediaPlayerHandler.player.mediaItemCount - 1)
                            }
                        }

                        if (!mediaPlayerHandler.controlState.value.isPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                        } else {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.Play)
                        }
                        lastSyncedSongId = command.videoId.cleanId()
                        val controlState = mediaPlayerHandler.controlState.value
                        jamRepository.syncState(
                            JamPlaybackState(
                                currentSongId = currentPlayingId,
                                isPlaying = true,
                                playbackPositionMs = mediaPlayerHandler.getProgress(),
                                queue = jamRepository.sessionState.value?.playbackState?.queue ?: emptyList(),
                                shuffle = controlState.isShuffle,
                                repeatMode = when (controlState.repeatState) {
                                    is com.maxrave.domain.mediaservice.handler.RepeatState.One -> JamRepeatMode.ONE
                                    is com.maxrave.domain.mediaservice.handler.RepeatState.All -> JamRepeatMode.QUEUE
                                    else -> JamRepeatMode.OFF
                                },
                                serverTimestampMs = Clock.System.now().toEpochMilliseconds(),
                            )
                        )
                    } else {
                        // Load and play the new track, purging any pre-existing local queue items
                        val track = getOrCreateTrack(
                            videoId = command.videoId,
                            title = command.title,
                            artist = command.artist,
                            thumbnailUrl = command.thumbnailUrl,
                            durationMs = command.durationMs
                        )
                        mediaPlayerHandler.addMediaItem(track.toGenericMediaItem(), playWhenReady = true)
                        if (!mediaPlayerHandler.controlState.value.isPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                        } else {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.Play)
                        }
                        lastSyncedSongId = command.videoId.cleanId()

                        val controlState = mediaPlayerHandler.controlState.value
                        jamRepository.syncState(
                            JamPlaybackState(
                                currentSongId = command.videoId.cleanId(),
                                isPlaying = true,
                                playbackPositionMs = 0L,
                                queue = jamRepository.sessionState.value?.playbackState?.queue ?: emptyList(),
                                shuffle = controlState.isShuffle,
                                repeatMode = when (controlState.repeatState) {
                                    is com.maxrave.domain.mediaservice.handler.RepeatState.One -> JamRepeatMode.ONE
                                    is com.maxrave.domain.mediaservice.handler.RepeatState.All -> JamRepeatMode.QUEUE
                                    else -> JamRepeatMode.OFF
                                },
                                serverTimestampMs = Clock.System.now().toEpochMilliseconds(),
                            )
                        )
                    }
                }
            }
            is JamCommand.SetShuffle -> {
                val current = mediaPlayerHandler.controlState.value.isShuffle
                if (current != command.enabled) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }
            is JamCommand.SetRepeat -> {
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
