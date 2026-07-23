package com.marki19.domain.jam

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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
    /** Prevents feedback loops when we ourselves trigger a state change. */
    private var isSyncing = false
    private val DRIFT_THRESHOLD_MS = 3_000L

    init {
        // ── 1. Guest & Host: apply incoming playback state / Clear queue on join ──
        scope.launch {
            var wasInSession = false
            var preJamQueueData: com.maxrave.domain.mediaservice.handler.QueueData.Data? = null
            jamRepository.sessionState.collectLatest { session ->
                if (session != null && !wasInSession) {
                    // Save pre-jam queue data
                    preJamQueueData = mediaPlayerHandler.queueData.value?.data
                    // Wipe local queue for both Host and Guest to isolate Jam room cleanly
                    if (mediaPlayerHandler.controlState.value.isPlaying) {
                        mediaPlayerHandler.onPlayerEvent(com.maxrave.domain.mediaservice.handler.PlayerEvent.PlayPause)
                    }
                    mediaPlayerHandler.clearMediaItems()
                } else if (session == null && wasInSession) {
                    // Jam session ended: wipe Jam room catalog and restore pre-jam queue
                    if (mediaPlayerHandler.controlState.value.isPlaying) {
                        mediaPlayerHandler.onPlayerEvent(com.maxrave.domain.mediaservice.handler.PlayerEvent.PlayPause)
                    }
                    mediaPlayerHandler.clearMediaItems()
                    preJamQueueData?.let { savedData ->
                        if (savedData.listTracks.isNotEmpty()) {
                            mediaPlayerHandler.loadMoreCatalog(ArrayList(savedData.listTracks), isAddToQueue = false)
                        }
                    }
                    preJamQueueData = null
                }
                wasInSession = session != null

                if (session == null || session.isHost || isSyncing) return@collectLatest
                val pb = session.playbackState
                isSyncing = true

                // Play / Pause
                val isLocalPlaying = mediaPlayerHandler.controlState.value.isPlaying
                if (pb.isPlaying && !isLocalPlaying) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                } else if (!pb.isPlaying && isLocalPlaying) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                }

                // Drift correction (position)
                val currentPos = mediaPlayerHandler.getProgress()
                // Adjust for transmission latency using serverTimestampMs
                val lagMs = if (pb.serverTimestampMs > 0)
                    (Clock.System.now().toEpochMilliseconds() - pb.serverTimestampMs).coerceAtLeast(0L)
                else 0L
                val expectedPos = if (pb.isPlaying) pb.playbackPositionMs + lagMs else pb.playbackPositionMs
                if (kotlin.math.abs(currentPos - expectedPos) > DRIFT_THRESHOLD_MS) {
                    mediaPlayerHandler.onPlayerEvent(
                        PlayerEvent.UpdateProgress(expectedPos.toFloat() / 1000f)
                    )
                }

                isSyncing = false
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
                if (session == null || !session.isHost || isSyncing) return@collectLatest

                val rawNowPlayingId = nowPlayingState.track?.videoId ?: mediaPlayerHandler.nowPlaying.value?.mediaId
                val nowPlayingId = rawNowPlayingId?.substringAfterLast('/')

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
                // Find by videoId (server queueId = videoId for host-originated items)
                val index = mediaPlayerHandler.queueData.value?.data?.listTracks
                    ?.indexOfFirst { it.videoId == command.queueId }
                if (index != null && index >= 0) mediaPlayerHandler.removeMediaItem(index)
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
                val songEntity = songRepository.getSongById(command.videoId).firstOrNull()
                val track = songEntity?.toTrack() ?: Track(
                    album = null,
                    artists = if (command.artist.isNotBlank()) listOf(Artist(name = command.artist, id = null)) else emptyList(),
                    duration = null,
                    durationSeconds = (command.durationMs / 1000).toInt(),
                    isAvailable = true,
                    isExplicit = false,
                    likeStatus = null,
                    thumbnails = if (!command.thumbnailUrl.isNullOrBlank()) listOf(Thumbnail(url = command.thumbnailUrl, width = 0, height = 0)) else emptyList(),
                    title = command.title,
                    videoId = command.videoId,
                    videoType = null,
                    category = null,
                    feedbackTokens = null,
                    resultType = null
                )
                mediaPlayerHandler.loadMoreCatalog(arrayListOf(track), isAddToQueue = true)
            }
            is JamCommand.PlayNow -> {
                val songEntity = songRepository.getSongById(command.videoId).firstOrNull()
                val track = songEntity?.toTrack() ?: Track(
                    album = null,
                    artists = if (command.artist.isNotBlank()) listOf(Artist(name = command.artist, id = null)) else emptyList(),
                    duration = null,
                    durationSeconds = (command.durationMs / 1000).toInt(),
                    isAvailable = true,
                    isExplicit = false,
                    likeStatus = null,
                    thumbnails = if (!command.thumbnailUrl.isNullOrBlank()) listOf(Thumbnail(url = command.thumbnailUrl, width = 0, height = 0)) else emptyList(),
                    title = command.title,
                    videoId = command.videoId,
                    videoType = null,
                    category = null,
                    feedbackTokens = null,
                    resultType = null
                )
                mediaPlayerHandler.playNext(track)
                mediaPlayerHandler.onPlayerEvent(com.maxrave.domain.mediaservice.handler.PlayerEvent.Next)
                if (!mediaPlayerHandler.controlState.value.isPlaying) {
                    mediaPlayerHandler.onPlayerEvent(com.maxrave.domain.mediaservice.handler.PlayerEvent.Play)
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
