package com.maxrave.media3.cast

import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
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
    private var appendJob: Job? = null
    private var positionPollJob: Job? = null
    private var recoverJob: Job? = null
    private var queueGeneration = 0L

    @Volatile
    private var lastKnownRemotePositionMs = 0L

    private var retryMediaId: String? = null
    private var retryCount = 0

    /** Consecutive current-track resolve or playback failures while casting; bounds auto-skip across REPEAT_ALL. */
    private var resolveFailureCount = 0

    private val adapterListener =
        object : MediaPlayerListener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                if (!isRemote) return
                val mapped =
                    if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                if (sessionPlayer.repeatMode != mapped) {
                    sessionPlayer.repeatMode = mapped
                }
            }

            override fun onShuffleModeEnabledChanged(
                shuffleModeEnabled: Boolean,
                list: List<GenericMediaItem>,
            ) {
                if (!isRemote) return
                val idx = adapter.currentMediaItemIndex
                if (idx >= 0) {
                    pushQueueWindow(idx, lastKnownRemotePositionMs, sessionPlayer.playWhenReady)
                }
            }
        }

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
                adapter.notifyRemoteIsPlaying(isPlaying, sessionPlayer.playWhenReady)
                if (isPlaying) {
                    resolveFailureCount = 0
                }
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (!isRemote) return
                adapter.notifyRemotePlayWhenReady(playWhenReady)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isRemote) return
                adapter.notifyRemotePlaybackState(playbackState)
                if (playbackState == Player.STATE_READY && sessionPlayer.isPlaying) {
                    resolveFailureCount = 0
                }
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
        adapter.addListener(adapterListener)
        sessionPlayer.addListener(playerListener)
        if (sessionPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE && !isRemote) {
            isRemote = true
            onCastConnected()
        }
        Logger.d(TAG, "Cast handoff manager started")
    }

    private fun onCastConnected() {
        // Snapshot BEFORE setCastActive: afterwards the adapter getters route to the receiver.
        val startIndex = adapter.currentMediaItemIndex
        val startPositionMs = adapter.currentPosition.coerceAtLeast(0L)
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
        queueGeneration++
        stopPositionPolling()
        pushJob?.cancel()
        pushJob = null
        appendJob?.cancel()
        appendJob = null
        recoverJob?.cancel()
        recoverJob = null
        remoteToPlaylist = emptyList()
        retryMediaId = null
        retryCount = 0
        resolveFailureCount = 0
        val resumeIndex = adapter.currentMediaItemIndex
        val resumePositionMs = lastKnownRemotePositionMs.coerceAtLeast(0L)
        // Clear remote routing first so the seek below starts the local machinery again.
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
        val generation = ++queueGeneration
        pushJob?.cancel()
        appendJob?.cancel()
        appendJob = null
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
                    if (!isRemote || generation != queueGeneration) return@launch
                    if (resolved.isEmpty() || resolved.first().first != startIndex) {
                        Logger.e(TAG, "Could not resolve a stream URL for index $startIndex — skipping ahead")
                        onResolveFailedWhileRemote(startIndex)
                        return@launch
                    }
                    resolveFailureCount = 0
                    Logger.d(TAG, "pushQueueWindow: resolved ${resolved.size}/${window.size} items, playlistIndices=${resolved.map { it.first }}")
                    remoteToPlaylist = resolved.map { it.first }
                    sessionPlayer.repeatMode =
                        if (adapter.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    sessionPlayer.setMediaItems(resolved.map { it.second }, 0, startPositionMs.coerceAtLeast(0L))
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
        val generation = queueGeneration
        appendJob?.cancel()
        appendJob =
            coroutineScope.launch {
                try {
                    val item = adapter.getMediaItemAt(playlistIndex) ?: return@launch
                    val stream = withContext(Dispatchers.IO) { resolver.resolve(item.mediaId) } ?: return@launch
                    if (!isRemote || generation != queueGeneration) return@launch
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
     * the last known position. After [MAX_STREAM_RETRIES] the track is skipped instead (bounded
     * by [MAX_RESOLVE_FAILURES] consecutive failed tracks so REPEAT_ALL cannot spin forever).
     */
    private fun recoverFromRemoteError() {
        val remoteIndex = sessionPlayer.currentMediaItemIndex
        val playlistIndex = remoteToPlaylist.getOrNull(remoteIndex) ?: adapter.currentMediaItemIndex
        val mediaId = adapter.getMediaItemAt(playlistIndex)?.mediaId ?: return
        if (retryMediaId != mediaId) {
            retryMediaId = mediaId
            retryCount = 0
        }
        appendJob?.cancel()
        appendJob = null
        recoverJob?.cancel()
        recoverJob =
            coroutineScope.launch {
                if (!isRemote) return@launch
                if (retryCount < MAX_STREAM_RETRIES) {
                    retryCount++
                    Logger.w(TAG, "Refreshing stream URL for $mediaId (attempt $retryCount/$MAX_STREAM_RETRIES)")
                    withContext(Dispatchers.IO) { resolver.invalidate(mediaId) }
                    if (!isRemote) return@launch
                    pushQueueWindow(playlistIndex, lastKnownRemotePositionMs, true)
                } else {
                    resolveFailureCount++
                    if (resolveFailureCount <= MAX_RESOLVE_FAILURES && adapter.hasNextMediaItem()) {
                        Logger.w(TAG, "Giving up on $mediaId ($resolveFailureCount/$MAX_RESOLVE_FAILURES) — skipping to next track")
                        adapter.seekToNext()
                    } else {
                        Logger.e(TAG, "Giving up on $mediaId — recovery cap reached, ending remote playback")
                        resolveFailureCount = 0
                        adapter.notifyRemotePlaybackState(Player.STATE_ENDED)
                    }
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
                    val state = sessionPlayer.playbackState
                    val pos = sessionPlayer.currentPosition
                    if (sessionPlayer.mediaItemCount > 0 &&
                        (state == Player.STATE_READY || (state == Player.STATE_BUFFERING && pos > 0L))
                    ) {
                        lastKnownRemotePositionMs = pos
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
