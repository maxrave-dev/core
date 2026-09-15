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
import org.simpmusic.cast.getCurrentCastDeviceName
import org.simpmusic.cast.getCastDeviceVolume

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
    private var volumePollJob: Job? = null
    private var recoverJob: Job? = null

    @Volatile
    private var lastKnownRemotePositionMs = 0L

    /** Timestamp of the last local volume change; the poll skips reads within [VOLUME_LOCAL_DEBOUNCE_MS] to avoid overwriting the user's adjustment. */
    @Volatile
    private var lastLocalVolumeChangeMs = 0L

    private var retryMediaId: String? = null
    private var retryCount = 0

    /** Consecutive current-track resolve failures while casting; bounds auto-skip so a network outage can't spin the whole queue. */
    private var resolveFailureCount = 0

    private val playerListener =
        object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                Logger.d(TAG, "onDeviceInfoChanged: playbackType=${if (remote) "REMOTE" else "LOCAL"} (current=$isRemote)")
                if (remote == isRemote) return
                isRemote = remote
                if (remote) {
                    adapter.notifyConnecting(getCurrentCastDeviceName())
                    onCastConnected()
                } else {
                    onCastDisconnected()
                }
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
        adapter.castLocalVolumeChangeCallback = { notifyLocalVolumeChange() }
        sessionPlayer.addListener(playerListener)
        Logger.d(TAG, "Cast handoff manager started")
    }

    private fun onCastConnected() {
        // Snapshot BEFORE setCastActive: afterwards the adapter getters route to the receiver.
        val startIndex = adapter.currentMediaItemIndex
        val startPositionMs = adapter.currentPosition
        val playWhenReady = adapter.isPlaying || adapter.playWhenReady
        val deviceName = getCurrentCastDeviceName()
        Logger.w(TAG, "▶ CAST CONNECTED: device=$deviceName startIndex=$startIndex pos=${startPositionMs}ms")
        adapter.setCastActive(sessionPlayer, deviceName)
        lastKnownRemotePositionMs = startPositionMs
        // Sync the adapter's volume to the Cast device's actual volume (what Google Home shows).
        val deviceVolume = getCastDeviceVolume()
        Logger.d(TAG, "Volume sync on connect: device=$deviceVolume adapter=${adapter.volume}")
        deviceVolume?.let { adapter.updateVolumeFromDevice(it) }
        startPositionPolling()
        startVolumePolling()
        if (startIndex >= 0) {
            pushQueueWindow(startIndex, startPositionMs, playWhenReady)
        }
    }

    private fun onCastDisconnected() {
        Logger.w(TAG, "⏹ CAST DISCONNECTED: resuming local at index=${adapter.currentMediaItemIndex} pos=${lastKnownRemotePositionMs}ms")
        stopPositionPolling()
        stopVolumePolling()
        recoverJob?.cancel()
        recoverJob = null
        pushJob?.cancel()
        pushJob = null
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

                    // --- First track: resolve and push immediately for minimal latency ---
                    val firstItem = adapter.getMediaItemAt(startIndex) ?: return@launch
                    val firstStream = withContext(Dispatchers.IO) { resolver.resolve(firstItem.mediaId) }
                    if (firstStream == null) {
                        Logger.e(TAG, "Could not resolve a stream URL for index $startIndex — skipping ahead")
                        onResolveFailedWhileRemote(startIndex)
                        return@launch
                    }
                    resolveFailureCount = 0
                    remoteToPlaylist = listOf(startIndex)

                    sessionPlayer.repeatMode =
                        if (adapter.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    sessionPlayer.setMediaItems(listOf(firstItem.toCastMediaItem(firstStream)), 0, startPositionMs)
                    sessionPlayer.playWhenReady = playWhenReady
                    sessionPlayer.prepare()
                    adapter.notifyRemoteTransition(startIndex)
                    Logger.d(TAG, "pushQueueWindow: first track pushed immediately, resolving rest in background")

                    // --- Remaining tracks: resolve and append in background ---
                    if (!adapter.shuffleModeEnabled) {
                        for (offset in 1 until minOf(INITIAL_WINDOW_SIZE, itemCount - startIndex)) {
                            val idx = startIndex + offset
                            val item = adapter.getMediaItemAt(idx) ?: continue
                            launch(Dispatchers.IO) {
                                try {
                                    resolver.resolve(item.mediaId)?.let { stream ->
                                        if (!isRemote) return@let
                                        withContext(Dispatchers.Main) {
                                            if (!isRemote) return@withContext
                                            remoteToPlaylist = remoteToPlaylist + idx
                                            sessionPlayer.addMediaItem(item.toCastMediaItem(stream))
                                        }
                                        Logger.d(TAG, "pushQueueWindow: appended index=$idx in background")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(TAG, "pushQueueWindow: background resolve failed for index=$idx: ${e.message}")
                                }
                            }
                        }
                    }
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
        // Keep two resolved items ahead of the receiver for near-gapless auto-advance.
        if (remoteIndex >= remoteToPlaylist.lastIndex - 1 && !adapter.shuffleModeEnabled) {
            appendNextToRemoteQueue(playlistIndex + 1)
        }
    }

    private fun appendNextToRemoteQueue(playlistIndex: Int) {
        if (playlistIndex >= adapter.mediaItemCount) return
        Logger.d(TAG, "appendNextToRemoteQueue: playlistIndex=$playlistIndex (window look-ahead)")
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
     * the last known position. Uses exponential backoff (1s, 2s, 4s) with jitter.
     * After [MAX_STREAM_RETRIES] the track is skipped instead.
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
                    val backoffMs = (RETRY_BASE_DELAY_MS shl (retryCount - 1)) + (Math.random() * RETRY_JITTER_MS).toLong()
                    Logger.w(TAG, "Refreshing stream URL for $mediaId (attempt $retryCount/$MAX_STREAM_RETRIES, delay=${backoffMs}ms)")
                    delay(backoffMs)
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

    /**
     * Polls the Cast device volume every [VOLUME_POLL_INTERVAL_MS] and applies it to the adapter,
     * so the UI slider stays in sync when the user changes volume on Google Home or another controller.
     *
     * Reads within [VOLUME_LOCAL_DEBOUNCE_MS] of a local slider change are skipped to avoid
     * overwriting the user's adjustment with a stale cached value.
     */
    private fun startVolumePolling() {
        volumePollJob?.cancel()
        volumePollJob =
            coroutineScope.launch {
                while (isActive) {
                    delay(VOLUME_POLL_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    val timeSinceLocalChange = now - lastLocalVolumeChangeMs
                    if (timeSinceLocalChange < VOLUME_LOCAL_DEBOUNCE_MS) {
                        Logger.d(TAG, "Volume poll: SKIPPED (local change ${timeSinceLocalChange}ms ago, debounce=${VOLUME_LOCAL_DEBOUNCE_MS}ms)")
                        continue
                    }
                    val deviceVolume = getCastDeviceVolume() ?: continue
                    if (deviceVolume != adapter.volume) {
                        Logger.d(TAG, "Volume poll: device=${deviceVolume} adapter=${adapter.volume} — syncing")
                        adapter.updateVolumeFromDevice(deviceVolume)
                    }
                }
            }
    }

    private fun stopVolumePolling() {
        volumePollJob?.cancel()
        volumePollJob = null
    }

    /**
     * Called by the adapter when the user changes volume via the UI slider.
     * Records the timestamp so the volume poll knows to skip nearby reads.
     */
    internal fun notifyLocalVolumeChange() {
        lastLocalVolumeChangeMs = System.currentTimeMillis()
        Logger.d(TAG, "Local volume change recorded — poll debounce active for ${VOLUME_LOCAL_DEBOUNCE_MS}ms")
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
        private const val INITIAL_WINDOW_SIZE = 2
        private const val MAX_STREAM_RETRIES = 3
        private const val MAX_RESOLVE_FAILURES = 3
        private const val POSITION_POLL_INTERVAL_MS = 500L
        private const val VOLUME_POLL_INTERVAL_MS = 2000L
        private const val VOLUME_LOCAL_DEBOUNCE_MS = 3000L
        private const val RETRY_BASE_DELAY_MS = 1000L
        private const val RETRY_JITTER_MS = 500L
    }
}
