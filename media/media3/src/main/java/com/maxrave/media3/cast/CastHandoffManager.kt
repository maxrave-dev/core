package com.maxrave.media3.cast

import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.logger.Logger
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter
import com.maxrave.media3.exoplayer.toMedia3MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.simpmusic.cast.currentCastDeviceName

/**
 * Owns the local ↔ Cast-receiver handoff.
 *
 * The adapter keeps every ExoPlayer at a single-item timeline and simulates the playlist
 * itself, so Media3's default state transfer cannot populate the remote queue. Instead this
 * manager watches the session player's [DeviceInfo]: on remote it snapshots the adapter,
 * silences local playback and pushes a small window of resolved-URL items to the receiver;
 * on return to local it restores the adapter at the last remote position. While remote,
 * the adapter routes transport calls to the session player and playback-start requests to
 * [CrossfadeExoPlayerAdapter.castPlaybackRouter], which lands in [pushQueueWindow].
 */
@UnstableApi
internal class CastHandoffManager(
    private val adapter: CrossfadeExoPlayerAdapter,
    private val sessionPlayer: Player,
    private val resolver: CastStreamResolver,
    private val coroutineScope: CoroutineScope,
) {
    private var isRemote = false

    /** Receiver queue position -> playlist index in the adapter. */
    private var remoteToPlaylist = listOf<Int>()
    private var pushJob: Job? = null
    private var positionPollJob: Job? = null
    private var recoverJob: Job? = null

    @Volatile
    private var lastKnownRemotePositionMs = 0L

    private var retryMediaId: String? = null
    private var retryCount = 0

    /** Consecutive current-track resolve failures while casting; bounds auto-skip so a network outage can't spin the whole queue. */
    private var resolveFailureCount = 0

    private val playerListener =
        object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (remote == isRemote) return
                isRemote = remote
                if (remote) onCastConnected() else onCastDisconnected()
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                if (!isRemote || mediaItem == null) return
                onRemoteTransition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isRemote) return
                adapter.notifyRemoteIsPlaying(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isRemote) return
                adapter.notifyRemotePlaybackState(playbackState)
                if (playbackState == Player.STATE_ENDED && adapter.hasNextMediaItem()) {
                    // Single-item window (shuffle) or exhausted queue: advance through the
                    // adapter so shuffle/repeat logic stays the single source of truth.
                    Logger.d(TAG, "Receiver queue ended — advancing via adapter")
                    adapter.seekToNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isRemote) return
                Logger.e(TAG, "Remote playback error: ${error.errorCodeName}")
                recoverFromRemoteError()
            }
        }

    fun start() {
        if (sessionPlayer === adapter.forwardingPlayer) {
            Logger.d(TAG, "Cast unavailable (FOSS build or no GMS) — handoff disabled")
            return
        }
        adapter.castPlaybackRouter = { index, positionMs, playWhenReady ->
            pushQueueWindow(index, positionMs, playWhenReady)
        }
        sessionPlayer.addListener(playerListener)
        Logger.d(TAG, "Cast handoff manager started")
    }

    private fun onCastConnected() {
        // Snapshot BEFORE setCastActive: afterwards the adapter getters route to the receiver.
        val startIndex = adapter.currentMediaItemIndex
        val startPositionMs = adapter.currentPosition
        val playWhenReady = adapter.isPlaying || adapter.playWhenReady
        val deviceName = currentCastDeviceName()
        Logger.w(TAG, "Cast connected to ${deviceName ?: "unknown"} — handing off index=$startIndex pos=${startPositionMs}ms")
        adapter.setCastActive(sessionPlayer, deviceName)
        lastKnownRemotePositionMs = startPositionMs
        startPositionPolling()
        if (startIndex >= 0) {
            pushQueueWindow(startIndex, startPositionMs, playWhenReady)
        }
    }

    private fun onCastDisconnected() {
        Logger.w(TAG, "Cast disconnected — resuming locally at ${lastKnownRemotePositionMs}ms")
        stopPositionPolling()
        pushJob?.cancel()
        pushJob = null
        recoverJob?.cancel()
        recoverJob = null
        remoteToPlaylist = emptyList()
        retryMediaId = null
        retryCount = 0
        resolveFailureCount = 0
        val resumeIndex = adapter.currentMediaItemIndex
        val resumePositionMs = lastKnownRemotePositionMs
        // Clear remote routing first so the seek below starts the local machinery again;
        // seekTo() resumes with the adapter's playWhenReady, which tracked the remote state.
        adapter.setCastActive(null, null)
        if (resumeIndex >= 0) {
            adapter.seekTo(resumeIndex, resumePositionMs)
        }
    }

    /**
     * Resolve URLs and (re)load the receiver queue starting at [startIndex].
     * With shuffle on, only the current item is pushed — "next" stays adapter-driven.
     */
    private fun pushQueueWindow(
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        pushJob?.cancel()
        pushJob =
            coroutineScope.launch {
                try {
                    Logger.d(TAG, "pushQueueWindow start=$startIndex pos=${startPositionMs}ms playWhenReady=$playWhenReady shuffle=${adapter.shuffleModeEnabled}")
                    val itemCount = adapter.mediaItemCount
                    if (startIndex !in 0 until itemCount) return@launch
                    val windowIndices =
                        if (adapter.shuffleModeEnabled) {
                            listOf(startIndex)
                        } else {
                            (startIndex until minOf(startIndex + INITIAL_WINDOW_SIZE, itemCount)).toList()
                        }
                    // Snapshot the items here (main thread) before going to IO: the adapter's playlist
                    // is a plain ArrayList mutated on the main thread, so reading it from
                    // Dispatchers.IO races with queue edits.
                    val window = windowIndices.mapNotNull { index -> adapter.getMediaItemAt(index)?.let { index to it } }
                    val resolved =
                        window
                            .map { (index, item) ->
                                async(Dispatchers.IO) {
                                    resolver.resolve(item.mediaId)?.let { stream ->
                                        index to item.toCastMediaItem(stream)
                                    }
                                }
                            }.awaitAll()
                            .filterNotNull()
                    if (resolved.isEmpty() || resolved.first().first != startIndex) {
                        Logger.e(TAG, "Could not resolve a stream URL for index $startIndex — skipping ahead")
                        onResolveFailedWhileRemote(startIndex)
                        return@launch
                    }
                    resolveFailureCount = 0
                    Logger.d(TAG, "pushQueueWindow: resolved ${resolved.size}/${window.size} items, playlistIndices=${resolved.map { it.first }}")
                    remoteToPlaylist = resolved.map { it.first }
                    // The receiver only ever holds this small window, so forwarding REPEAT_ALL would
                    // loop the window forever instead of the real playlist. Map it to OFF and let
                    // STATE_ENDED bubble back to the adapter, which owns shuffle/repeat. REPEAT_ONE is
                    // safe to forward — it loops the current item, which is exactly the intent.
                    // PlayerConstants repeat values match Player.REPEAT_MODE_* 1:1.
                    sessionPlayer.repeatMode =
                        if (adapter.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    sessionPlayer.setMediaItems(resolved.map { it.second }, 0, startPositionMs)
                    sessionPlayer.playWhenReady = playWhenReady
                    sessionPlayer.prepare()
                    adapter.notifyRemoteTransition(startIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "Pushing queue window failed: ${e.message}", e)
                }
            }
    }

    /**
     * The current track's stream URL could not be resolved while casting. Instead of leaving the
     * receiver idle, advance to the next track — bounded by [MAX_RESOLVE_FAILURES] consecutive
     * misses so a network-wide outage (or REPEAT_ONE on a dead track) can't spin the whole queue.
     * After the cap, surface the end state so the UI doesn't freeze mid-track.
     */
    private fun onResolveFailedWhileRemote(failedIndex: Int) {
        resolveFailureCount++
        if (resolveFailureCount <= MAX_RESOLVE_FAILURES && adapter.hasNextMediaItem()) {
            Logger.w(TAG, "Resolve failed at index $failedIndex ($resolveFailureCount/$MAX_RESOLVE_FAILURES) — skipping to next")
            adapter.seekToNext()
        } else {
            Logger.e(TAG, "Resolve failed at index $failedIndex with no recoverable next — ending remote playback")
            resolveFailureCount = 0
            adapter.notifyRemotePlaybackState(Player.STATE_ENDED)
        }
    }

    private fun onRemoteTransition() {
        val remoteIndex = sessionPlayer.currentMediaItemIndex
        val playlistIndex = remoteToPlaylist.getOrNull(remoteIndex) ?: return
        Logger.d(TAG, "onRemoteTransition remoteIndex=$remoteIndex -> playlistIndex=$playlistIndex")
        adapter.notifyRemoteTransition(playlistIndex)
        retryMediaId = null
        retryCount = 0
        resolveFailureCount = 0
        // Keep one resolved item ahead of the receiver for near-gapless auto-advance.
        if (remoteIndex == remoteToPlaylist.lastIndex && !adapter.shuffleModeEnabled) {
            appendNextToRemoteQueue(playlistIndex + 1)
        }
    }

    private fun appendNextToRemoteQueue(playlistIndex: Int) {
        if (playlistIndex >= adapter.mediaItemCount) return
        coroutineScope.launch {
            try {
                val item = adapter.getMediaItemAt(playlistIndex) ?: return@launch
                val stream = withContext(Dispatchers.IO) { resolver.resolve(item.mediaId) } ?: return@launch
                if (!isRemote) return@launch
                sessionPlayer.addMediaItem(item.toCastMediaItem(stream))
                remoteToPlaylist = remoteToPlaylist + playlistIndex
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Appending next item failed: ${e.message}", e)
            }
        }
    }

    /**
     * Remote 403/expiry recovery: invalidate the cached format, re-resolve and re-push at
     * the last known position. After [MAX_STREAM_RETRIES] the track is skipped instead.
     */
    private fun recoverFromRemoteError() {
        val remoteIndex = sessionPlayer.currentMediaItemIndex
        val playlistIndex = remoteToPlaylist.getOrNull(remoteIndex) ?: adapter.currentMediaItemIndex
        val mediaId = adapter.getMediaItemAt(playlistIndex)?.mediaId ?: return
        if (retryMediaId != mediaId) {
            retryMediaId = mediaId
            retryCount = 0
        }
        recoverJob?.cancel()
        recoverJob =
            coroutineScope.launch {
                if (!isRemote) return@launch
                if (retryCount < MAX_STREAM_RETRIES) {
                    retryCount++
                    Logger.w(TAG, "Refreshing stream URL for $mediaId (attempt $retryCount/$MAX_STREAM_RETRIES)")
                    withContext(Dispatchers.IO) { resolver.invalidate(mediaId) }
                    // The session can end while invalidate() suspends above; without this re-check the
                    // push below would land a resolved-URL queue on the *local* player and break the
                    // adapter's single-item-timeline invariant.
                    if (!isRemote) return@launch
                    pushQueueWindow(playlistIndex, lastKnownRemotePositionMs, true)
                } else if (adapter.hasNextMediaItem()) {
                    Logger.w(TAG, "Giving up on $mediaId — skipping to next track")
                    adapter.seekToNext()
                } else {
                    // No retries left and nothing to skip to — surface the end state instead of
                    // leaving the receiver (and the UI) frozen mid-track.
                    Logger.e(TAG, "Giving up on $mediaId — no next track, ending remote playback")
                    adapter.notifyRemotePlaybackState(Player.STATE_ENDED)
                }
            }
    }

    private fun startPositionPolling() {
        positionPollJob?.cancel()
        positionPollJob =
            coroutineScope.launch {
                // The CastPlayer's position resets once the session ends, so the last remote
                // position must be sampled continuously to restore local playback later.
                while (isActive) {
                    // Only sample once the receiver actually holds our queue: until pushQueueWindow
                    // lands, the CastPlayer reports position 0, which would wipe the snapshot taken in
                    // onCastConnected() and resume local playback from the start of the track.
                    if (sessionPlayer.mediaItemCount > 0 && sessionPlayer.playbackState != Player.STATE_IDLE) {
                        lastKnownRemotePositionMs = sessionPlayer.currentPosition
                    }
                    delay(POSITION_POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopPositionPolling() {
        positionPollJob?.cancel()
        positionPollJob = null
    }

    private fun GenericMediaItem.toCastMediaItem(stream: CastStreamResolver.ResolvedStream): MediaItem {
        val base = toMedia3MediaItem()
        return base
            .buildUpon()
            .setUri(stream.url)
            .setMimeType(stream.mimeType)
            .setMediaMetadata(
                base.mediaMetadata
                    .buildUpon()
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }

    companion object {
        private const val TAG = "CastHandoffManager"
        private const val INITIAL_WINDOW_SIZE = 3
        private const val MAX_STREAM_RETRIES = 2
        private const val MAX_RESOLVE_FAILURES = 3
        private const val POSITION_POLL_INTERVAL_MS = 1000L
    }
}
