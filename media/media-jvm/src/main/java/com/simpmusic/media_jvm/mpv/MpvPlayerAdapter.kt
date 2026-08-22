package com.simpmusic.media_jvm.mpv

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.simpmusic.media_jvm.download.getDownloadPath
import com.simpmusic.media_jvm.memory.MemoryTrimmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private const val TAG = "MpvPlayerAdapter"

/**
 * mpv (libmpv via JNA) implementation of [MediaPlayerInterface].
 *
 * Structural port of `VlcPlayerAdapter`: the queue, shuffle order, repeat modes, precaching,
 * crossfade stepping, the DJ/BPM/Camelot-key system, URL resolution and 403 retry handling are
 * carried over unchanged. Only the native-call layer differs — see [MpvPlayer] for the vlcj →
 * libmpv mapping, and [buildEdlUrl] for the `:input-slave` → `edl://` replacement.
 *
 * Differences from the VLC backend that are inherent to mpv rather than choices:
 *  - There is no factory object; each [MpvPlayer] owns its own `mpv_handle`, so there is nothing
 *    to release globally in [release].
 *  - Events are pulled on a per-handle pump thread instead of pushed from a native thread, so the
 *    "never call stop()/release() from inside a callback" hazard that VLC has does not exist here.
 *  - Video frames come from mpv's software render API on a thread this backend owns, rather than
 *    from a vlcj render callback — see [MpvVideoFrameSource].
 */
class MpvPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    // Internal state enum for proper state machine
    private enum class InternalState {
        IDLE,
        PREPARING,
        READY,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR,
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    init {
        if (MpvLibrary.INSTANCE == null) {
            Logger.e(TAG, "libmpv not available — playback will fail until mpv is installed.")
        }

        // Load crossfade settings
        coroutineScope.launch {
            dataStoreManager.crossfadeEnabled.collect { enabled ->
                crossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Crossfade enabled: $crossfadeEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeDuration.collect { duration ->
                crossfadeDurationMs = duration
                Logger.d(TAG, "Crossfade duration: $crossfadeDurationMs ms")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeDjMode.collect { enabled ->
                djCrossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ crossfade mode: $djCrossfadeEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.watchVideoInsteadOfPlayingAudio.collect { enabled ->
                watchVideoEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Watch video enabled: $watchVideoEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeSkipAlbum.collect { enabled ->
                skipCrossfadeInAlbum = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Skip crossfade inside album: $skipCrossfadeInAlbum")
            }
        }
    }

    // ========== State Management ==========
    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: MpvPlayer? = null

    // Tracks whether the current player is actually rendering video
    // (based on PlayableSource.isVideo, not GenericMediaItem metadata)
    @Volatile
    private var currentPlayerIsVideo = false

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var internalVolume = 1.0f

    /** Sleep-timer fade gain, kept apart from [internalVolume] so the user's level survives it. */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    /**
     * Pitch shift the user asked for, 1.0 = unchanged.
     *
     * Only applied while crossfade is off. The crossfade owns mpv's filter chain — it installs its
     * own rubberband entry for beatmatching and wipes the chain when a transition ends — so the two
     * cannot both drive it. The UI locks the pitch control whenever crossfade is enabled.
     */
    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    // Position tracking
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    // Position update job (fallback polling for crossfade detection)
    private var positionUpdateJob: Job? = null

    // Precaching system
    private data class PrecachedPlayer(
        val player: MpvPlayer,
        val mediaItem: GenericMediaItem,
        val source: PlayableSource,
    )

    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // Crossfade system
    @Volatile
    private var crossfadeEnabled = false

    /** See MediaPlayerInterface.crossfadeSuppressed — set while in a Listen Together room. */
    override var crossfadeSuppressed: Boolean = false

    @Volatile
    private var crossfadeDurationMs = 5000

    @Volatile
    private var djCrossfadeEnabled = false

    // Whether video content plays as video (watch-video setting) — the same condition
    // extractPlayableUrl uses to include the video stream in the edl:// merged source.
    @Volatile
    private var watchVideoEnabled = false

    /** User setting: leave transitions inside an album alone. */
    @Volatile
    private var skipCrossfadeInAlbum = false

    /** Set by the handler when an album is loaded; empty for every other kind of queue. */
    @Volatile
    private var internalAlbumTrackIds: Set<String> = emptySet()

    @Volatile
    private var secondaryPlayer: MpvPlayer? = null

    @Volatile
    private var crossfadeJob: Job? = null

    @Volatile
    private var isCrossfading = false

    /** Index we're crossfading from; used when cancelling to revert localCurrentMediaItemIndex. */
    @Volatile
    private var crossfadeFromIndex = -1

    /** Last index a crossfade was refused for, so the refusal is logged once instead of per poll. */
    private var lastBlockedCrossfadeIndex = -1

    /**
     * Set only while [commitIncomingAsCurrent] tears down a fade. [performCrossfade]'s
     * cancellation handler releases the incoming player by default; during a commit we are
     * promoting that very player, so it must be kept alive.
     */
    @Volatile
    private var committingIncomingPlayer = false

    // AutoMix metadata cache (in-memory, populated from Tidal via NewFormatEntity)
    private val audioMetaCache = ConcurrentHashMap<String, SongAudioMeta>()

    private fun setCrossfading(value: Boolean) {
        if (isCrossfading != value) {
            isCrossfading = value
            notifyListeners { onCrossfadeStateChanged(value) }
        }
    }

    // Retry system - mirrors Android CrossfadeExoPlayerAdapter retry logic
    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    // Loading management
    private var currentLoadJob: Job? = null

    fun getCurrentPlayer(): MpvPlayer? = currentPlayer

    // Video surface state - UI collects this to display video.
    private val _currentVideoFrames = MutableStateFlow<MpvVideoFrameSource?>(null)

    /** Frame source of the player whose video should be on screen, or null when the current track has no video. */
    val currentVideoFrames: StateFlow<MpvVideoFrameSource?> = _currentVideoFrames.asStateFlow()

    // ========== Playback Source ==========
    private data class PlayableSource(
        val isVideo: Boolean,
        val url: String,
        val audioSlaveUrl: String? = null, // For merging: audio URL, becomes a second edl:// stream
    )

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (current state: $internalState)")
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        // At the end of the queue the handle is parked at EOF, and telling mpv to
                        // play there does nothing — the press would look ignored. Rewind first so
                        // the last track replays, which is what the Play button is offering.
                        if (internalState == InternalState.ENDED) {
                            Logger.d(TAG, "Play: replaying from the start after end of queue")
                            player.seekTo(0L)
                            cachedPosition = 0L
                        }
                        Logger.d(TAG, "Play: calling mpv play")
                        player.play()
                        transitionToState(InternalState.PLAYING)
                        internalPlayWhenReady = true
                    } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                }

                InternalState.PREPARING -> {
                    if (!cachedIsLoading) {
                        cachedIsLoading = true
                        notifyListeners { onIsLoadingChanged(true) }
                    }
                    internalPlayWhenReady = true
                    Logger.d(TAG, "Play: During PREPARING - will auto-play when ready")
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                    cachedIsLoading = false
                }

                else -> {
                    Logger.w(TAG, "Play: Called in invalid state: $internalState")
                }
            }
        }
    }

    override fun pause() {
        Logger.d(TAG, "pause() called (current state: $internalState)")
        coroutineScope.launch {
            try {
                // Pausing mid-crossfade stays on A+1 — the track the UI already shows — and freezes
                // it in place via the when(internalState) block below. It does NOT jump back to A.
                if (isCrossfading) {
                    Logger.d(TAG, "Pause: committing incoming (A+1) and pausing in place")
                    commitIncomingAsCurrent()
                }

                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            Logger.d(TAG, "Pause: calling mpv pause")
                            player.pause()
                            transitionToState(InternalState.PAUSED)
                            internalPlayWhenReady = false
                        }
                    }

                    InternalState.PREPARING -> {
                        internalPlayWhenReady = false
                        Logger.d(TAG, "Pause: During PREPARING - will not auto-play")
                    }

                    else -> {
                        Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                    }
                }
            } finally {
                // Playback has genuinely stopped by this point, so a sleep-timer attenuation has
                // served its purpose and is cleared here rather than by the caller. The caller
                // cannot do it safely: pause() is asynchronous and this task suspends at
                // commitIncomingAsCurrent() above, so anything the caller queued afterwards would
                // run during that suspension and re-open the mixer over the last of the audio.
                //
                // In `finally` because that suspension point can also throw — committing a
                // crossfade joins a job that may be cancelled. Skipping this would leave the mixer
                // attenuated with nothing to ever restore it.
                if (internalSleepFadeFactor < 1f) {
                    internalSleepFadeFactor = 1f
                    forEachLiveHandle { it.setSleepFadeVolume(100) }
                }
            }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.stop()
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        // Updated here rather than inside the coroutine so the UI reflects the seek immediately.
        cachedPosition = positionMs
        // The mpv call hops to the player thread, same as the volume setters: reaching into a
        // handle from the caller's thread races the release that happens on the player thread, and
        // a handle destroyed between the isReleased check and the native call is a use-after-free.
        coroutineScope.launch {
            // Seeking mid-crossfade: commit the incoming track (A+1) as current first, the same way
            // pause() does. The progress bar the user just dragged belongs to A+1 — that is the
            // track the UI shows and the one position updates are read from during a crossfade.
            // Without this the seek lands on the *outgoing* track while the crossfade carries on,
            // so the old song keeps playing underneath and the seek appears to do nothing.
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: committing incoming (A+1) before seeking")
                commitIncomingAsCurrent()
            }
            currentPlayer?.let { player ->
                try {
                    player.seekTo(positionMs)
                    Logger.d(TAG, "Seeked to position: $positionMs")
                } catch (e: Exception) {
                    Logger.e(TAG, "Seek exception: ${e.message}", e)
                }
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return

        coroutineScope.launch {
            val shouldPlay = internalPlayWhenReady

            // Cancel any ongoing crossfade and await completion
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: Cancelling crossfade")
                cancelCrossfadeAndCleanup(revertIndex = false)
            }

            // Cancel any ongoing load
            currentLoadJob?.cancel()

            localCurrentMediaItemIndex = mediaItemIndex
            currentPlayer?.release()
            currentPlayer = null
            currentPlayerIsVideo = false
            _currentVideoFrames.value = null
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (cachedPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val newPosition = (cachedPosition + 5000).coerceAtMost(cachedDuration)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        coroutineScope.launch {
            // During crossfade A→A+1, "next" commits A+1 as current (the UI already shows A+1)
            // and then advances to A+2. Outside a crossfade it advances normally. hasNextMediaItem()
            // is evaluated AFTER the commit so it is relative to A+1, not A.
            val wasCrossfading = isCrossfading
            if (wasCrossfading) {
                Logger.d(TAG, "seekToNext: committing incoming (A+1), then advancing to A+2")
                commitIncomingAsCurrent()
            }
            if (hasNextMediaItem()) {
                seekTo(getNextMediaItemIndex(), 0)
            } else if (wasCrossfading) {
                // A+1 was the last track — stay on it; it is already promoted and announced.
                Logger.d(TAG, "seekToNext: A+1 was the last track — staying on it")
            }
        }
    }

    override fun seekToPrevious() {
        coroutineScope.launch {
            // Commit the incoming track (A+1) as current FIRST, so the 3-second rule below
            // evaluates against A+1's position and index rather than A's.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPrevious: committing incoming (A+1) first")
                commitIncomingAsCurrent()
            }

            // Standard music player behavior:
            // Position > 3s → seek to start of current track
            // Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            if (cachedPosition > positionThresholdMs) {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms > ${positionThresholdMs}ms — seeking to start")
                currentPlayer?.seekTo(0)
                cachedPosition = 0
            } else if (hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms <= ${positionThresholdMs}ms — going to previous track")
                val prevIndex = getPreviousMediaItemIndex()
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPrevious: No previous item, seeking to start")
                currentPlayer?.seekTo(0)
                cachedPosition = 0
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            // Mirror seekToPrevious(): commit the incoming track (A+1) as current first, so
            // "previous" means the track before A+1 (i.e. A), not the one before A.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPreviousMediaItem: committing incoming (A+1) first")
                commitIncomingAsCurrent()
            }

            // Always go to the previous track — skips the 3-second "seek to start"
            // rule used by seekToPrevious().
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    override fun prepare() {
        if (playlist.isNotEmpty() && localCurrentMediaItemIndex >= 0) {
            coroutineScope.launch {
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        coroutineScope.launch {
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)

        if (internalShuffleModeEnabled) {
            createShuffleOrder()
        }

        notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

        if (playlist.size - 1 - currentMediaItemIndex <= maxPrecacheCount) {
            coroutineScope.launch {
                clearPrecacheExceptCurrentInternal()
                triggerPrecachingInternal()
            }
        }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index in 0..playlist.size) {
            val currentIndexBeforeInsert = localCurrentMediaItemIndex

            playlist.add(index, mediaItem)

            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }

            if (internalShuffleModeEnabled) {
                if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                    insertIntoShuffleOrder(index, currentShufflePos)
                } else {
                    createShuffleOrder()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index - 1 - currentMediaItemIndex <= maxPrecacheCount) {
                coroutineScope.launch {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        coroutineScope.launch {
            // Bounds check MUST run inside the launch, on the same player thread/queue as removeAt.
            // If it sits outside (on the caller thread), another queued op (clearMediaItems/setMediaItem)
            // can empty the playlist between the check and removeAt → IndexOutOfBounds (issue #2156 /
            // SIMPMUSIC-DESKTOP-3Y: "Index 0 out of bounds for length 0").
            if (index !in playlist.indices) return@launch
            val track = playlist.removeAt(index)

            precachedPlayers.remove(track.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }

                index == localCurrentMediaItemIndex -> {
                    if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        cleanupCurrentPlayerInternal()
                    }
                }

                else -> {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        coroutineScope.launch {
            // Same reason as removeMediaItem (issue #2156): re-check bounds inside the launch, since the
            // playlist can be emptied by another queued op between an outside-launch guard and removeAt.
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return@launch
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> {
                        toIndex
                    }
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex - 1
                    }
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex + 1
                    }
                    else -> {
                        localCurrentMediaItemIndex
                    }
                }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index !in playlist.indices) return

        coroutineScope.launch {
            playlist[index] = mediaItem

            precachedPlayers.remove(mediaItem.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> =
        if (internalShuffleModeEnabled) {
            shuffleOrder.mapNotNull { shuffledIndex -> playlist.getOrNull(shuffledIndex) }
        } else {
            playlist.toList()
        }

    override fun getUnshuffledIndex(shuffledIndex: Int): Int =
        if (internalShuffleModeEnabled) {
            shuffleOrder.getOrNull(shuffledIndex) ?: -1
        } else {
            shuffledIndex
        }

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = internalState == InternalState.PLAYING

    override val currentPosition: Long
        get() = cachedPosition

    override val duration: Long
        get() = cachedDuration

    override val bufferedPosition: Long
        get() = cachedBufferedPosition

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            if (dur <= 0) return 0
            return ((bufferedPosition * 100) / dur).toInt().coerceIn(0, 100)
        }

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int
        get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = cachedPosition

    override val playbackState: Int
        get() =
            when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }

    // ========== Navigation ==========

    override fun hasNextMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex < playlist.size - 1
        }

    override fun hasPreviousMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex > 0
        }

    private fun getNextMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> {
                localCurrentMediaItemIndex
            }
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val nextShufflePos = (currentShufflePos + 1) % shuffleOrder.size
                    shuffleOrder.getOrNull(nextShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex < playlist.size - 1) {
                        localCurrentMediaItemIndex + 1
                    } else {
                        0
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val nextShufflePos = currentShufflePos + 1
                    if (nextShufflePos < shuffleOrder.size) {
                        shuffleOrder.getOrNull(nextShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex + 1).coerceAtMost(playlist.size - 1)
                }
            }
        }

    private fun getPreviousMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> {
                localCurrentMediaItemIndex
            }
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos =
                        if (currentShufflePos > 0) {
                            currentShufflePos - 1
                        } else {
                            shuffleOrder.size - 1
                        }
                    shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex > 0) {
                        localCurrentMediaItemIndex - 1
                    } else {
                        playlist.size - 1
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos = currentShufflePos - 1
                    if (prevShufflePos >= 0) {
                        shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
                }
            }
        }

    // ========== Playback Modes ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            if (internalShuffleModeEnabled == value) return

            internalShuffleModeEnabled = value

            if (value) {
                createShuffleOrder()
            } else {
                clearShuffleOrder()
            }

            val mediaItemList = getShuffledMediaItemList()
            notifyListeners { onShuffleModeEnabledChanged(value, mediaItemList) }
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            notifyListeners { onRepeatModeChanged(value) }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        set(value) {
            internalPlaybackSpeed = value.speed
            internalPlaybackPitch = value.pitch
            // Hopped to the player thread for the same reason as seekTo and the volume setters.
            coroutineScope.launch {
                // Every live handle, not just the current one: these are playback-wide settings, so
                // the precached handles have to carry them too or the next track starts back at 1.0x.
                forEachLiveHandle {
                    try {
                        it.setRate(value.speed)
                        it.applyPitch()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to set playback speed/pitch: ${e.message}")
                    }
                }
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = 0 // mpv doesn't provide an audio session ID

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            // Same hop as sleepFadeFactor below, and for the same reason: writing mpv properties
            // straight from the caller's thread races the player thread that releases handles, and
            // a handle destroyed between the isReleased check and the property write is a
            // use-after-free. The field itself is set synchronously so the getter reads back what
            // was just written; only the mpv calls are deferred.
            coroutineScope.launch {
                // mpv volume: 0-100 (100 = unattenuated). Map our 0.0-1.0 to 0-100.
                val percent = (internalVolume * 100).toInt()
                forEachLiveHandle { it.setMasterVolume(percent) }
            }
            notifyListeners { onVolumeChanged(internalVolume) }
        }

    override var albumTrackIds: Set<String>
        get() = internalAlbumTrackIds
        set(value) {
            internalAlbumTrackIds = value
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
            // Hop onto the player thread instead of writing from the caller's — the sleep timer
            // ramps this from Main. Three separate things depend on that hop:
            //  - [MpvPlayer.applyVolume] reads three fields and issues two native calls, so a
            //    second writer can interleave and publish a stale combination.
            //  - A handle can be released between its `isReleased` check and mpv_set_property,
            //    which is a use-after-free that has already produced a SIGSEGV here once (see the
            //    join comment in MpvPlayer.release). Releases happen on this same thread, so
            //    sharing it removes the window entirely.
            //  - pause() queues onto this same single-threaded scope. Going through it is what
            //    guarantees the timer's closing restore to 1f lands *after* playback has actually
            //    stopped, instead of briefly re-opening the mixer over the last of the audio.
            coroutineScope.launch {
                // Re-read rather than capture: if the queue backs up, the pending writes collapse
                // onto the newest value instead of replaying a stale ramp.
                val percent = (internalSleepFadeFactor * 100).toInt()
                forEachLiveHandle { it.setSleepFadeVolume(percent) }
            }
        }

    /**
     * Every handle that can currently reach the audio device.
     *
     * `ao-volume` is not per-handle: on Windows they all land in the process' default WASAPI
     * session (mpv passes a NULL AudioSessionGuid), so one ISimpleAudioVolume covers the lot, and
     * a handle left holding an older level re-asserts it on its own AUDIO_RECONFIG and overwrites
     * everyone else's. Missing one is therefore not "that handle stays wrong", it is "that handle
     * silently undoes the others".
     *
     * [secondaryPlayer] — the track fading in during a crossfade — is the easy one to miss: it is
     * removed from [precachedPlayers] before being promoted, so it belongs to neither collection.
     */
    private fun forEachLiveHandle(action: (MpvPlayer) -> Unit) {
        currentPlayer?.let(action)
        secondaryPlayer?.let(action)
        precachedPlayers.values.forEach { action(it.player) }
    }

    /**
     * Push the playback-wide settings onto a handle that is about to become audible.
     *
     * A fresh mpv handle starts at 100/100 volume and 1.0x speed, so anything already in force has
     * to be re-asserted here — otherwise a fade in progress jumps back to full volume mid-ramp, and
     * a track started while the user has speed turned up plays at normal speed instead.
     *
     * The two volume levels go down in one call: setting them separately would publish a
     * full-volume master onto the shared `ao-volume` before the fade is applied, which every other
     * handle hears.
     */
    private fun MpvPlayer.applyPlaybackLevels() {
        setVolumeLevels(
            master = (internalVolume * 100).toInt(),
            sleep = (internalSleepFadeFactor * 100).toInt(),
        )
        setRate(internalPlaybackSpeed)
        applyPitch()
        // A fresh handle starts with an empty filter chain, so the curve has to be re-applied or
        // the equalizer silently stops working at the next track — including on secondaryPlayer,
        // which belongs to neither collection while it is being promoted.
        if (internalEqualizerBands.isNotEmpty() || internalEqualizerPreamp != 0f) {
            setEqualizer(internalEqualizerBands, internalEqualizerPreamp)
        }
    }

    /**
     * Put the user's pitch shift on this handle, if pitch is available at all right now.
     *
     * Skipped entirely while crossfade is enabled: that feature owns the filter chain — it installs
     * its own rubberband entry for beatmatching and clears the chain when a transition ends — so
     * touching `af` here would either be wiped or would wipe the crossfade's own filters. The UI
     * locks the pitch control in that case, so the value stays at 1.0 anyway.
     */
    private fun MpvPlayer.applyPitch() {
        if (crossfadeEnabled) return
        if (internalPlaybackPitch == 1.0f) {
            clearAudioFilters()
        } else {
            // rubberband also absorbs the tempo change once present, so setRate keeps working.
            // Only drive the shift if mpv actually took the filter — a build without rubberband
            // rejects it, and af-command against a filter that is not in the chain fails every time.
            val chain = installCrossfadeChain(sweep = null, sweepStartHz = 0f, pitchShift = true)
            if (chain.pitchShift) {
                setPitchScale(internalPlaybackPitch)
            } else {
                Logger.w(TAG, "Pitch shift unavailable: mpv rejected the rubberband filter")
            }
        }
    }

    override var skipSilenceEnabled: Boolean = false

    /** Current curve, so a handle created later can be brought up to the same setting. */
    @Volatile
    private var internalEqualizerBands: List<Float> = emptyList()

    @Volatile
    private var internalEqualizerPreamp: Float = 0f

    override fun setEqualizer(
        bandsDb: List<Float>,
        preampDb: Float,
    ) {
        internalEqualizerBands = bandsDb
        internalEqualizerPreamp = preampDb
        // Same hop as volume and sleepFadeFactor: mpv properties are written on the player thread,
        // because a handle released between the isReleased check and the write is a use-after-free.
        coroutineScope.launch {
            forEachLiveHandle { it.setEqualizer(bandsDb, preampDb) }
        }
    }

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()
        crossfadeJob?.cancel()

        secondaryPlayer?.release()
        secondaryPlayer = null
        isCrossfading = false
        crossfadeFromIndex = -1

        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()

        // No factory to release: libmpv has no factory concept, each handle is destroyed by its
        // own MpvPlayer.release() via mpv_terminate_destroy().
    }

    // ========== Internal Methods ==========

    /**
     * Transition internal state and notify listeners.
     * Listeners are called on the service thread (not Main) because
     * JvmMediaPlayerHandlerImpl uses thread-safe StateFlow updates
     * and contains runBlocking calls that would deadlock on Main.
     */
    private fun transitionToState(newState: InternalState) {
        if (internalState == newState) return

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "State transition: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Query duration from mpv
        currentPlayer?.let { player ->
            val dur = player.length
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners on the service thread — safe because JvmMediaPlayerHandlerImpl
        // uses thread-safe StateFlow/MutableStateFlow for all state updates.
        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                trimNativeMemory("paused")
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                trimNativeMemory("idle")
            }

            InternalState.PREPARING -> {
                Logger.d(TAG, "transitionToState PREPARING -> isLoading=true")
                cachedIsLoading = true
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
                listeners.forEach { it.onIsLoadingChanged(true) }
            }

            InternalState.READY -> {
                Logger.d(TAG, "transitionToState READY -> isLoading=false")
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                Logger.d(TAG, "transitionToState PLAYING -> isLoading=false")
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            InternalState.ENDED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                listeners.forEach {
                    it.onPlayerError(
                        PlayerError(
                            errorCode = 403,
                            errorCodeName = "ERROR_UNKNOWN",
                            message = "Can not extract playable URL or playback error",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Give the C allocator's free pages back to the OS now that playback has gone quiet.
     *
     * Only ever called from the PAUSED / IDLE transitions: the underlying call walks the heap while
     * holding the allocator lock, so anything that mallocs — mpv's decoder threads included — stalls
     * until it returns. See [MemoryTrimmer] for the measurements that motivate this.
     *
     * Dispatched off the single service thread because that thread also drives the transport; a
     * heap walk there would show up as a laggy pause button. [MemoryTrimmer] throttles itself, so
     * firing this on every pause is cheap.
     */
    private fun trimNativeMemory(reason: String) {
        coroutineScope.launch(Dispatchers.IO) {
            MemoryTrimmer.trim(reason)
        }
    }

    /**
     * Load and play track - MUST run on coroutineScope
     */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId

        currentLoadJob?.cancel()

        currentLoadJob =
            coroutineScope.launch {
                try {
                    transitionToState(InternalState.PREPARING)

                    // Notify media item transition (fire-and-forget to avoid
                    // blocking the service thread with runBlocking inside handler)
                    notifyListeners {
                        onMediaItemTransition(
                            mediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    // Detach event listener from old player IMMEDIATELY before
                    // spending time on URL extraction. This prevents stale events
                    // (error/finished) from the old player seeing the updated
                    // playlist/index and interfering with the new track load.
                    cleanupEventListenerInternal()
                    stopPositionUpdates()

                    // Extract URL on IO thread (network), mpv native calls stay on service thread
                    val cachedPrecache = precachedPlayers.remove(videoId)
                    var resolvedSource: PlayableSource? = null
                    val player =
                        if (cachedPrecache?.player != null) {
                            cachedPrecache.player
                        } else {
                            var source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            if (source == null || source.url.isEmpty()) {
                                Logger.w(TAG, "First extract failed for $videoId, invalidating and retrying...")
                                withContext(Dispatchers.IO) {
                                    streamRepository.invalidateFormat(videoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                                }
                                source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            }
                            if (source == null || source.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract playable URL for $videoId after retry")
                                transitionToState(InternalState.ERROR)
                                return@launch
                            }
                            resolvedSource = source
                            createMediaPlayerInternal(source)
                        }

                    if (player == null) {
                        Logger.e(TAG, "Failed to create mpv player for $videoId")
                        transitionToState(InternalState.ERROR)
                        return@launch
                    }

                    // mpv native calls on the service thread
                    cleanupCurrentPlayerInternal()
                    currentPlayer = player
                    currentPlayerIsVideo = player.videoFrames != null
                    _currentVideoFrames.value = player.videoFrames
                    setupPlayerEventsInternal(player)
                    player.applyPlaybackLevels()

                    if (cachedPrecache != null) {
                        // The precached handle already has the file loaded and held paused;
                        // unpausing is all that is needed (VLC needed an explicit play() here too).
                        if (shouldPlay) {
                            player.play()
                        }
                        Logger.d(TAG, "Playing from precache for $videoId")
                    } else {
                        val source = resolvedSource
                        if (source != null) {
                            player.loadFile(buildPlaybackUrl(source), startPaused = !shouldPlay)
                        } else {
                            Logger.e(TAG, "resolvedSource is null — should not happen")
                            transitionToState(InternalState.ERROR)
                            return@launch
                        }
                    }

                    if (startPositionMs > 0) {
                        delay(100)
                        player.seekTo(startPositionMs)
                        cachedPosition = startPositionMs
                    }

                    // Always transition to READY first so UI receives
                    // isLoading=false via STATE_READY. The READY handler
                    // will auto-call play() when internalPlayWhenReady is true.
                    transitionToState(InternalState.READY)

                    // Start position updates
                    startPositionUpdates()

                    // Eagerly load audio metadata for auto crossfade / DJ mode calculations
                    if (crossfadeEnabled && (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO || djCrossfadeEnabled)) {
                        val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""
                        loadAudioMetaIfNeeded(currentVideoId)
                    }

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Load track error: ${e.message}", e)
                        transitionToState(InternalState.ERROR)
                    }
                }
            }
    }

    /**
     * Resolve the single URL handed to `loadfile`.
     *
     * Replaces VLC's `buildVlcOptions` + `:input-slave`. The rest of what that function did —
     * `:no-video`, `:network-caching`, `:http-reconnect` — are handle-level options in mpv and
     * live in [MpvPlayer.create] instead.
     *
     * When YouTube gives separate adaptive audio and video URLs they are merged into one
     * `edl://` URL so mpv plays both streams simultaneously, which is exactly what
     * `:input-slave` did for VLC and what MergingMediaSource does on Android.
     *
     * An audio-only source never carries a slave URL (see [extractPlayableUrl]), so audio playback
     * always resolves to a single audio URL and never opens a video stream.
     */
    private fun buildPlaybackUrl(source: PlayableSource): String {
        val audioSlave = source.audioSlaveUrl
        return if (audioSlave != null) buildEdlUrl(source.url, audioSlave) else source.url
    }

    /**
     * Create an mpv player instance for [source].
     *
     * Video sources get a handle with mpv's software render API attached — see
     * [MpvVideoFrameSource], the successor of `VlcVideoSurfacePanel`. Audio sources get a
     * headless handle with `vid=no`/`vo=null`, so no video stream is ever decoded or fetched.
     */
    private fun createMediaPlayerInternal(source: PlayableSource): MpvPlayer? {
        // VLC used --network-caching=10000 globally and :network-caching=15000 for video.
        val cacheSeconds = if (source.isVideo) 15 else 10
        return if (source.isVideo) {
            Logger.d(TAG, "Creating video player with software render surface")
            MpvPlayer.create(audioOnly = false, networkCacheSeconds = cacheSeconds)
        } else {
            Logger.d(TAG, "Creating audio-only player")
            MpvPlayer.create(audioOnly = true, networkCacheSeconds = cacheSeconds)
        }
    }

    /**
     * Setup mpv event listeners for a player.
     *
     * Unlike VLC — whose callbacks arrive on a native thread where calling stop()/release()
     * deadlocks — mpv events are pulled by [MpvPlayer]'s own pump thread. Work is still dispatched
     * to [coroutineScope] so the state machine stays serialized on one thread.
     */
    private fun setupPlayerEventsInternal(player: MpvPlayer) {
        // Remove old listener
        cleanupEventListenerInternal()

        val listener =
            object : MpvPlayerEventAdapter() {
                override fun finished(player: MpvPlayer) {
                    Logger.d(TAG, "End of stream reached")
                    coroutineScope.launch {
                        // Ignore events from a player that is no longer current
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring finished() from stale player")
                            return@launch
                        }
                        // During crossfade, the old player will naturally finish near the end.
                        // Ignore it - the crossfade is already handling the transition.
                        if (isCrossfading) {
                            Logger.d(TAG, "Ignoring finished() during crossfade (old player ended)")
                            return@launch
                        }
                        transitionToState(InternalState.ENDED)
                        handleTrackEndInternal()
                    }
                }

                override fun error(player: MpvPlayer) {
                    Logger.e(TAG, "mpv playback error")
                    coroutineScope.launch {
                        // Ignore errors from a player that is no longer current.
                        // This can happen when the old player fires error() after
                        // a new track has started loading (playlist/index already updated).
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring error() from stale player")
                            return@launch
                        }
                        // During crossfade, ignore errors from the old player -
                        // it's fading out and will be released soon anyway.
                        if (isCrossfading) {
                            Logger.w(TAG, "Ignoring error() during crossfade (old player error)")
                            return@launch
                        }
                        val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                        if (currentVideoId != null) {
                            // Reset retry count for new track
                            if (retryVideoId != currentVideoId) {
                                retryVideoId = currentVideoId
                                retryCount = 0
                            }
                            if (retryCount < maxRetryCount) {
                                retryCount++
                                Logger.w(TAG, "Retrying playback (attempt $retryCount/$maxRetryCount) for $currentVideoId")
                                try {
                                    // Invalidate cached format so fresh URL is fetched
                                    streamRepository.invalidateFormat(currentVideoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                    // Evict stale precache
                                    precachedPlayers.remove(currentVideoId)?.player?.release()
                                    // Reload the track
                                    loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0L, shouldPlay = true)
                                    return@launch
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                                }
                            }
                            Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                        }
                        val error =
                            PlayerError(
                                errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                                errorCodeName = "MPV_ERROR",
                                message = "Playback error",
                            )
                        listeners.forEach { it.onPlayerError(error) }
                        transitionToState(InternalState.ERROR)
                    }
                }

                override fun playing(player: MpvPlayer) {
                    coroutineScope.launch {
                        if (currentPlayer !== player) return@launch
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            // Reset retry counter on successful playback
                            retryCount = 0
                            retryVideoId = null
                        }
                    }
                }

                override fun paused(player: MpvPlayer) {
                    coroutineScope.launch {
                        if (currentPlayer !== player) return@launch
                        if (internalState == InternalState.PLAYING) {
                            transitionToState(InternalState.PAUSED)
                        }
                    }
                }

                override fun timeChanged(
                    player: MpvPlayer,
                    newTimeMs: Long,
                ) {
                    // During crossfade, this fires from the OLD player — ignore it.
                    // Position updates come from the poll loop using secondaryPlayer's time.
                    if (!isCrossfading) {
                        cachedPosition = newTimeMs
                    }
                }

                override fun lengthChanged(
                    player: MpvPlayer,
                    newLengthMs: Long,
                ) {
                    // During crossfade, this fires from the OLD player — ignore it.
                    if (!isCrossfading && newLengthMs > 0) {
                        cachedDuration = newLengthMs
                    }
                }

                override fun buffering(
                    player: MpvPlayer,
                    newCache: Float,
                ) {
                    // During crossfade, ignore buffering events from old player
                    if (isCrossfading) return

                    // Update buffered position first
                    if (cachedDuration > 0) {
                        cachedBufferedPosition = (cachedDuration * newCache / 100f).toLong()
                    }

                    // Only report loading when buffer is actually behind the playhead
                    // AND the player intends to play. Ignore buffering while paused
                    // to avoid showing a loading spinner when user has explicitly paused.
                    val isStalled = newCache < 100f && cachedBufferedPosition <= cachedPosition && internalPlayWhenReady
                    if (isStalled != cachedIsLoading) {
                        Logger.w(TAG, "isLoading changed: $cachedIsLoading -> $isStalled")
                        cachedIsLoading = isStalled
                        notifyListeners { onIsLoadingChanged(isStalled) }
                    }
                }

                override fun opening(player: MpvPlayer) {
                    Logger.d(TAG, "mpv opening media")
                }
            }

        player.setEventListener(listener)
    }

    /**
     * Clean up event listener from current player
     */
    private fun cleanupEventListenerInternal() {
        currentPlayer?.setEventListener(null)
    }

    /**
     * Cleanup a player instance
     */
    private fun cleanupPlayerInternal(player: MpvPlayer) {
        try {
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    /**
     * Cleanup current player
     */
    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupEventListenerInternal()

        crossfadeJob?.cancel()
        crossfadeJob = null
        setCrossfading(false)

        // The crossfade handle has to die with its job. This runs on the load path (next / prev /
        // picking another track), which never passes through cancelCrossfadeAndCleanup, so nothing
        // else would release it: the following crossfade overwrites `secondaryPlayer` and the old
        // handle loses its last reference while still holding a demuxer, a decoder, an audio output
        // and its event pump thread.
        secondaryPlayer?.let { cleanupPlayerInternal(it) }
        secondaryPlayer = null

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
        currentPlayerIsVideo = false
        _currentVideoFrames.value = null
    }

    /**
     * Crossfade is skipped when the NEXT track will play as a video (video content with
     * the watch-video setting on — the same condition [extractPlayableUrl] uses to include
     * the video stream). The merged edl:// source resolves two stream URLs and is
     * error-prone to prepare mid-fade, and a video should start from its first frame
     * instead of fading in under the outgoing song — so the transition takes the normal
     * (non-crossfade) path.
     */
    private fun isNextTrackVideo(): Boolean = watchVideoEnabled && playlist.getOrNull(getNextMediaItemIndex())?.isVideo() == true

    /** Same skip rule for the CURRENT track: a video should play out to its last frame instead of fading out under the incoming song. */
    private fun isCurrentTrackVideo(): Boolean = watchVideoEnabled && currentMediaItem?.isVideo() == true

    /**
     * Crossfade needs a track long enough that both sides of the blend are still worth hearing. At
     * the default 5s fade a 20s track would spend half its length fading in or out, and a longer
     * fade setting swallows it whole — so the bar scales with the fade rather than being fixed.
     *
     * Only the current track is measured: the next one has not been loaded yet, so its duration is
     * unknown until it becomes current.
     */
    private fun isCurrentTrackTooShortForCrossfade(): Boolean {
        val currentDuration = duration
        if (currentDuration <= 0L) return false
        val fadeMs =
            if (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO) {
                // Auto resolves to 20–45s, nowhere near the 5s default — measuring against the
                // default would let a 30s track through and then swallow it whole.
                resolveAutoCrossfadeDurationMs(
                    currentMediaItem?.mediaId ?: "",
                    playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: "",
                )
            } else {
                crossfadeDurationMs
            }
        return currentDuration < maxOf(MIN_CROSSFADE_TRACK_MS, fadeMs * 3L)
    }

    /**
     * True when both this track and the next came from the album loaded in the queue, and the user
     * asked for albums to play through uninterrupted.
     *
     * Requiring *both* sides is what keeps the edges intact: the last album track into the first
     * track endless queue appended still crossfades, because that one is not in the set.
     */
    private fun isWithinAlbum(): Boolean {
        if (!skipCrossfadeInAlbum) return false
        val ids = internalAlbumTrackIds
        if (ids.isEmpty()) return false
        val current = currentMediaItem?.mediaId ?: return false
        val next = playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: return false
        return current in ids && next in ids
    }

    /**
     * Handle track end
     */
    private fun handleTrackEndInternal() {
        // If crossfade is already in progress (triggered by position update before track ended),
        // don't interrupt it. The old player ending is expected — the crossfade will complete
        // and finalizeCrossfade() will handle the transition.
        if (isCrossfading) {
            Logger.d(TAG, "handleTrackEndInternal: crossfade in progress, ignoring track end")
            return
        }

        val shouldCrossfade =
            crossfadeEnabled &&
                !crossfadeSuppressed &&
                hasNextMediaItem() &&
                !isCurrentTrackVideo() &&
                !isNextTrackVideo() &&
                !isCurrentTrackTooShortForCrossfade() &&
                !isWithinAlbum()

        if (shouldCrossfade) {
            val nextIndex = getNextMediaItemIndex()
            triggerCrossfadeTransition(nextIndex)
        } else {
            when (internalRepeatMode) {
                PlayerConstants.REPEAT_MODE_ONE -> {
                    seekTo(localCurrentMediaItemIndex, 0)
                }

                PlayerConstants.REPEAT_MODE_ALL -> {
                    if (hasNextMediaItem()) {
                        seekToNext()
                    }
                }

                else -> {
                    if (localCurrentMediaItemIndex < playlist.size - 1) {
                        seekToNext()
                    }
                }
            }
        }
    }

    /**
     * Trigger crossfade to next track
     */
    private fun triggerCrossfadeTransition(nextIndex: Int) {
        if (nextIndex !in playlist.indices || isCrossfading) {
            // Says which of the two blocked it — an index the playlist no longer holds, or a
            // crossfading flag left set. Reported once per blocked index rather than on every poll:
            // the position loop reaches here every 200 ms for as long as the track stays inside the
            // trigger window, and a line repeating five times a second buries everything else.
            if (lastBlockedCrossfadeIndex != nextIndex) {
                lastBlockedCrossfadeIndex = nextIndex
                Logger.w(
                    TAG,
                    "Crossfade not started: nextIndex=$nextIndex, playlistSize=${playlist.size}, " +
                        "isCrossfading=$isCrossfading, currentIndex=$localCurrentMediaItemIndex",
                )
            }
            return
        }
        lastBlockedCrossfadeIndex = -1

        crossfadeJob =
            coroutineScope.launch {
                try {
                    setCrossfading(true)
                    val nextMediaItem = playlist[nextIndex]
                    val nextVideoId = nextMediaItem.mediaId

                    Logger.d(TAG, "Starting crossfade to track $nextIndex ($nextVideoId)")

                    // Extract URL on IO thread (network), mpv native calls stay on service thread
                    val cachedPrecache = precachedPlayers.remove(nextVideoId)
                    val nextPlayer: MpvPlayer? =
                        if (cachedPrecache?.player != null) {
                            Logger.d(TAG, "Using precached player for crossfade")
                            cachedPrecache.player
                        } else {
                            val nextSource = withContext(Dispatchers.IO) { extractPlayableUrl(nextMediaItem) }
                            if (nextSource == null || nextSource.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract URL for crossfade")
                                null
                            } else {
                                createMediaPlayerInternal(nextSource)?.also { newPlayer ->
                                    newPlayer.loadFile(buildPlaybackUrl(nextSource), startPaused = true)
                                }
                            }
                        }

                    if (nextPlayer != null) {
                        // Setup secondary player with its OWN listener.
                        // DO NOT call setupPlayerEventsInternal() here - that would remove
                        // the event listener from the current player (which is still playing).
                        secondaryPlayer = nextPlayer
                        nextPlayer.setEventListener(
                            object : MpvPlayerEventAdapter() {
                                override fun error(player: MpvPlayer) {
                                    Logger.e(TAG, "Secondary player error during crossfade")
                                    coroutineScope.launch {
                                        crossfadeJob?.cancel()
                                        secondaryPlayer?.release()
                                        secondaryPlayer = null
                                        setCrossfading(false)
                                        seekTo(nextIndex, 0)
                                    }
                                }
                            },
                        )
                        nextPlayer.setMute(true)
                        nextPlayer.applyPlaybackLevels()
                        nextPlayer.setFadeVolume(0)
                        nextPlayer.play()
                        delay(50)
                        nextPlayer.setMute(false)
                    }

                    if (nextPlayer == null) {
                        setCrossfading(false)
                        seekTo(nextIndex, 0)
                        return@launch
                    }

                    // Capture current index BEFORE advancing localCurrentMediaItemIndex
                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""
                    crossfadeFromIndex = localCurrentMediaItemIndex

                    // AutoMix: load metadata and calculate parameters
                    val isAutoMode = crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO
                    if (isAutoMode || djCrossfadeEnabled) {
                        loadAudioMetaIfNeeded(currentVideoId)
                        loadAudioMetaIfNeeded(nextVideoId)
                    }

                    // Update now playing and video frames immediately. Set unconditionally: the
                    // old null-guard kept the OUTGOING player's frames on screen when the incoming
                    // track has no video, leaving a dead surface from a soon-released player on
                    // screen (the "black video until next/prev" bug).
                    localCurrentMediaItemIndex = nextIndex
                    _currentVideoFrames.value = nextPlayer.videoFrames
                    notifyListeners {
                        onMediaItemTransition(
                            nextMediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    Logger.d(TAG, "Now playing updated to track $nextIndex during crossfade")

                    // AutoMix: resolve duration and BPM ratio
                    val resolvedConfigDurationMs =
                        if (isAutoMode) {
                            resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId)
                        } else {
                            crossfadeDurationMs
                        }
                    // AutoMix tempo/pitch match for the OUTGOING track. Gated on Auto mode exactly
                    // like Android: outside Auto mode the user picked a fixed crossfade length and
                    // gets a plain fade. Both helpers return 1.0f when the BPM / key metadata is
                    // missing, so a track with no Tidal analysis simply skips the ramp.
                    val bpmSpeedRatio = if (isAutoMode) calculateBpmSpeedRatio(currentVideoId, nextVideoId) else 1.0f
                    val keyPitchRatio = if (isAutoMode) calculateKeyPitchRatio(currentVideoId, nextVideoId) else 1.0f

                    // Calculate effective crossfade duration based on ACTUAL remaining time.
                    val actualTimeRemaining =
                        currentPlayer?.let { player ->
                            val dur = player.length
                            val pos = player.time
                            if (dur > 0 && pos >= 0) (dur - pos) else resolvedConfigDurationMs.toLong()
                        } ?: resolvedConfigDurationMs.toLong()

                    val effectiveCrossfadeDurationMs =
                        minOf(
                            resolvedConfigDurationMs.toLong(),
                            actualTimeRemaining,
                        ).coerceAtLeast(1000L).toInt()

                    Logger.d(
                        TAG,
                        "Crossfade duration: configured=${resolvedConfigDurationMs}ms (auto=$isAutoMode), " +
                            "bpmRatio=$bpmSpeedRatio, pitchRatio=$keyPitchRatio, " +
                            "actualRemaining=${actualTimeRemaining}ms, effective=${effectiveCrossfadeDurationMs}ms",
                    )

                    performCrossfade(
                        nextIndex,
                        nextPlayer,
                        effectiveCrossfadeDurationMs,
                        bpmSpeedRatio,
                        keyPitchRatio,
                    )
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Crossfade error: ${e.message}", e)
                        setCrossfading(false)
                        seekTo(nextIndex, 0)
                    }
                }
            }
    }

    /**
     * Cancel the ongoing crossfade, await its completion, and clean up state.
     * Uses [Job.cancelAndJoin] to ensure the crossfade coroutine's catch block
     * has finished before the caller proceeds, preventing race conditions.
     *
     * @param revertIndex If true, revert [localCurrentMediaItemIndex] to the track
     *   that was playing before the crossfade started.
     */
    private suspend fun cancelCrossfadeAndCleanup(revertIndex: Boolean) {
        val job = crossfadeJob
        crossfadeJob = null
        job?.cancel()
        job?.join()
        // secondaryPlayer may already be released by performCrossfade's catch block,
        // but with isReleased guard in MpvPlayer, this is safe.
        secondaryPlayer?.release()
        secondaryPlayer = null
        if (revertIndex && crossfadeFromIndex >= 0) {
            localCurrentMediaItemIndex = crossfadeFromIndex
            playlist.getOrNull(crossfadeFromIndex)?.let { mediaItem ->
                notifyListeners {
                    onMediaItemTransition(
                        mediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                    )
                }
            }
        }
        crossfadeFromIndex = -1
        setCrossfading(false)
    }

    /**
     * Abort an in-progress crossfade by committing the INCOMING track (A+1) as the new current
     * player — the mid-fade counterpart of [finalizeCrossfade]. Invoked when the user interacts
     * during a crossfade (next / previous / pause).
     *
     * Direction: "crossfade means we have moved to A+1", so A+1 is kept and A is dropped. This
     * mirrors CrossfadeExoPlayerAdapter.commitIncomingAsCurrentInternal() on Android — both
     * platforms must resolve mid-crossfade transport commands the same way.
     *
     * [localCurrentMediaItemIndex] already equals A+1 and its onMediaItemTransition was emitted
     * by [triggerCrossfadeTransition], so neither is touched here: the UI already shows A+1.
     *
     * Position polling is deliberately left running, unlike the Android counterpart. On this
     * adapter the poll loop also drives crossfade detection and [play] never restarts it, so
     * stopping it here would freeze progress and disable every later crossfade.
     *
     * Does NOT change [internalState] and does NOT seek — the caller decides what happens next
     * (pause in place, advance to A+2, go to previous, ...).
     */
    private suspend fun commitIncomingAsCurrent() {
        val incoming = secondaryPlayer
        if (incoming == null) {
            // The fade already collapsed (the secondary player errored out and was released).
            // Nothing to promote, so revert to A instead — that keeps index and player in sync.
            Logger.w(TAG, "commitIncomingAsCurrent: no incoming player, reverting to outgoing track")
            cancelCrossfadeAndCleanup(revertIndex = true)
            return
        }

        committingIncomingPlayer = true
        try {
            val job = crossfadeJob
            crossfadeJob = null
            job?.cancel()
            job?.join()
        } finally {
            committingIncomingPlayer = false
        }

        // Drop the outgoing track (A).
        currentPlayer?.let { outgoing ->
            try {
                outgoing.release()
            } catch (e: Exception) {
                Logger.w(TAG, "commitIncomingAsCurrent: error releasing outgoing player: ${e.message}")
            }
        }

        // Promote the incoming track (A+1).
        currentPlayer = incoming
        currentPlayerIsVideo = incoming.videoFrames != null
        _currentVideoFrames.value = incoming.videoFrames
        secondaryPlayer = null

        // Until now it only carried the minimal crossfade error listener (see
        // triggerCrossfadeTransition); give it the full one now that it is the current player.
        setupPlayerEventsInternal(incoming)

        // It was mid fade-in, so its ramp sits somewhere below full — open it back up. The master
        // volume is untouched by the crossfade and already holds whatever the user set.
        incoming.applyPlaybackLevels()
        incoming.setFadeVolume(100)

        crossfadeFromIndex = -1
        setCrossfading(false)
        Logger.d(TAG, "commitIncomingAsCurrent: committed track index $localCurrentMediaItemIndex")
    }

    /**
     * Perform the actual crossfade animation
     *
     * @param effectiveDurationMs The actual crossfade duration to use. May be shorter than
     *   the configured [crossfadeDurationMs] if URL resolution / buffering consumed
     *   part of the crossfade window.
     * @param targetSpeedRatio BPM-based speed ratio for the OUTGOING track (1.0 = no adjustment).
     * @param targetPitchRatio Key-based pitch ratio for the OUTGOING track (1.0 = no adjustment).
     */
    private suspend fun performCrossfade(
        nextIndex: Int,
        nextPlayer: MpvPlayer,
        effectiveDurationMs: Int,
        targetSpeedRatio: Float = 1.0f,
        targetPitchRatio: Float = 1.0f,
    ) {
        val steps = 50
        val delayPerStep = (effectiveDurationMs / steps).coerceAtLeast(20)
        // A pure 0..100 attenuation per handle: the user's level rides on the master volume and must
        // come out of the crossfade exactly as it went in.
        val targetVolume = 100
        val wantDjFilter = djCrossfadeEnabled
        val wantAutoMixRamp = targetSpeedRatio != 1.0f || targetPitchRatio != 1.0f
        val outgoingPlayer = currentPlayer

        // Arm the filter chains BEFORE the animation starts, so the 50 steps only have to retune
        // live filters (af-command) instead of rebuilding the chain each time. Both are armed at
        // their transparent starting cutoff, so arming is inaudible on its own.
        //
        // The outgoing handle carries the low-pass AND the pitch shift; the incoming one only ever
        // gets the high-pass, because Android adjusts speed/pitch on the outgoing player alone.
        val outgoingChain =
            if ((wantDjFilter || wantAutoMixRamp) && outgoingPlayer != null) {
                outgoingPlayer.installCrossfadeChain(
                    sweep = if (wantDjFilter) MpvCrossfadeFilter.LOW_PASS else null,
                    sweepStartHz = LPF_START_HZ,
                    pitchShift = wantAutoMixRamp,
                )
            } else {
                MpvPlayer.CrossfadeChain.NONE
            }
        val incomingChain =
            if (wantDjFilter) {
                nextPlayer.installCrossfadeChain(
                    sweep = MpvCrossfadeFilter.HIGH_PASS,
                    sweepStartHz = HPF_START_HZ,
                    pitchShift = false,
                )
            } else {
                MpvPlayer.CrossfadeChain.NONE
            }

        // Only drive what mpv actually installed. The speed ramp still runs without rubberband —
        // mpv falls back to its built-in scaletempo2, which stretches tempo with pitch preserved,
        // the same thing Media3's PlaybackParameters.speed does. Only the pitch match is lost.
        val sweepOutgoing = outgoingChain.sweep
        val sweepIncoming = incomingChain.sweep
        val rampSpeed = wantAutoMixRamp && outgoingPlayer != null
        val rampPitch = rampSpeed && outgoingChain.pitchShift

        Logger.d(
            TAG,
            "Crossfade animation: ${effectiveDurationMs}ms, $steps steps, ${delayPerStep}ms/step, " +
                "internalVolume=$internalVolume, dj=$wantDjFilter (out=$sweepOutgoing, in=$sweepIncoming), " +
                "autoMix=$wantAutoMixRamp (speed=$targetSpeedRatio ramp=$rampSpeed, " +
                "pitch=$targetPitchRatio ramp=$rampPitch)",
        )

        // Track the last quantized speed/pitch so we skip redundant native updates.
        var lastOutgoingSpeed = -1f
        var lastOutgoingPitch = -1f

        try {
            for (step in 0..steps) {
                currentCoroutineContext().ensureActive()

                val progress = step.toFloat() / steps
                val angle = progress * Math.PI / 2.0

                val fadeOutVolume = (targetVolume * kotlin.math.cos(angle)).toInt()
                currentPlayer?.setFadeVolume(fadeOutVolume)

                val fadeInVolume = (targetVolume * kotlin.math.sin(angle)).toInt()
                nextPlayer.setFadeVolume(fadeInVolume)

                // DJ-style filter sweep, alongside the volume fade.
                // S-curve (sigmoid) on the time axis holds both tracks near full spectrum at the
                // start and end and transitions steeply in the middle; exponential interpolation
                // on the frequency axis matches logarithmic hearing perception.
                if (sweepOutgoing || sweepIncoming) {
                    val filterProgress = sigmoid(progress)
                    if (sweepOutgoing) {
                        // Outgoing: LPF sweeps 20kHz → 200Hz
                        outgoingPlayer?.setCrossfadeCutoffHz(
                            MpvCrossfadeFilter.LOW_PASS,
                            exponentialInterpolate(LPF_START_HZ, LPF_END_HZ, filterProgress),
                        )
                    }
                    if (sweepIncoming) {
                        // Incoming: HPF sweeps 2kHz → 20Hz
                        nextPlayer.setCrossfadeCutoffHz(
                            MpvCrossfadeFilter.HIGH_PASS,
                            exponentialInterpolate(HPF_START_HZ, HPF_END_HZ, filterProgress),
                        )
                    }
                }

                // AutoMix: only the OUTGOING player is adjusted to match the incoming track; the
                // incoming one stays at natural speed and pitch.
                //
                // Front-loaded ramp: speed/pitch reach target within the first [BPM_RAMP_PORTION]
                // of the crossfade and then HOLD, so the bulk of the audible blend plays at matched
                // BPM instead of catching up only when the outgoing volume is already 0.
                if (rampSpeed) {
                    val linearRamp = (progress / BPM_RAMP_PORTION).coerceAtMost(1f)
                    // Smoothstep S-curve: slow→fast→slow (3t²−2t³)
                    val rampProgress = linearRamp * linearRamp * (3f - 2f * linearRamp)
                    val qOutSpeed = quantize(lerp(1.0f, targetSpeedRatio, rampProgress) * internalPlaybackSpeed)
                    val qOutPitch = quantize(lerp(1.0f, targetPitchRatio, rampProgress) * NATURAL_PITCH_SCALE)

                    if (qOutSpeed != lastOutgoingSpeed) {
                        outgoingPlayer.setRate(qOutSpeed)
                        lastOutgoingSpeed = qOutSpeed
                    }
                    if (rampPitch && qOutPitch != lastOutgoingPitch) {
                        outgoingPlayer.setPitchScale(qOutPitch)
                        lastOutgoingPitch = qOutPitch
                    }
                }

                delay(delayPerStep.toLong())
            }

            finalizeCrossfade(nextIndex, nextPlayer)
        } catch (e: CancellationException) {
            // Undo everything this fade did to the audio of BOTH handles. Whichever one survives
            // the cancel must not be left muffled or off-tempo; for the one that gets released it
            // is a harmless no-op (MpvPlayer guards on isReleased).
            outgoingPlayer?.endCrossfadeAudio()
            nextPlayer.endCrossfadeAudio()

            if (committingIncomingPlayer) {
                // commitIncomingAsCurrent() is promoting nextPlayer to current — releasing it
                // here would kill the track the user just chose to keep.
                Logger.d(TAG, "Crossfade cancelled to commit incoming track — keeping its player")
            } else {
                Logger.d(TAG, "Crossfade cancelled")
                nextPlayer.release()
                secondaryPlayer = null
                setCrossfading(false)
            }
        }
    }

    /**
     * Finalize crossfade: swap players and cleanup
     */
    private fun finalizeCrossfade(
        nextIndex: Int,
        nextPlayer: MpvPlayer,
    ) {
        Logger.d(TAG, "Crossfade complete, swapping players")

        stopPositionUpdates()

        // Cleanup old current player
        currentPlayer?.let { oldPlayer ->
            try {
                oldPlayer.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error cleaning up old player: ${e.message}")
            }
        }

        // Promote secondary to current
        currentPlayer = nextPlayer
        currentPlayerIsVideo = nextPlayer.videoFrames != null
        _currentVideoFrames.value = nextPlayer.videoFrames
        secondaryPlayer = null

        // Now set up the full event listener on the new current player
        // (replaces the minimal crossfade error listener)
        setupPlayerEventsInternal(nextPlayer)

        // Ensure correct volume, and drop the high-pass this player faded in under so it is back to
        // untouched playback. (Its speed/pitch were never moved — only the outgoing track is
        // adjusted — but restore them anyway, mirroring the Android finalize path.)
        currentPlayer?.applyPlaybackLevels()
        currentPlayer?.setFadeVolume(100)
        currentPlayer?.endCrossfadeAudio()

        // Reset state
        setCrossfading(false)
        crossfadeFromIndex = -1
        transitionToState(InternalState.PLAYING)

        // Start position tracking
        startPositionUpdates()

        // Trigger next precache
        triggerPrecachingInternal()
    }

    // ========== AutoMix / DJ metadata ==========

    data class SongAudioMeta(
        val bpm: Int?,
        val key: String?,
        val keyScale: String?,
    )

    private suspend fun loadAudioMetaIfNeeded(videoId: String) {
        if (videoId.isBlank() || audioMetaCache.containsKey(videoId)) return
        try {
            val format = streamRepository.getNewFormat(videoId).firstOrNull()
            if (format == null) {
                Logger.d(TAG, "AutoMix meta: no NewFormatEntity found for videoId=$videoId")
                return
            }
            if (format.bpm != null || format.musicKey != null) {
                audioMetaCache[videoId] = SongAudioMeta(format.bpm, format.musicKey, format.keyScale)
                Logger.d(TAG, "AutoMix meta loaded: videoId=$videoId, bpm=${format.bpm}, key=${format.musicKey} ${format.keyScale}")
            } else {
                Logger.d(TAG, "AutoMix meta: format exists but no bpm/key data for videoId=$videoId")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load AutoMix meta for $videoId: ${e.message}")
        }
    }

    private fun getAutoTargetDurationMs(bpm: Int): Double {
        val clampedBpm = bpm.coerceIn(70, 170)
        return 30000.0 - (clampedBpm - 70) * 230.0
    }

    private fun resolveAutoCrossfadeDurationMs(
        currentVideoId: String,
        nextVideoId: String,
    ): Int {
        val currentBpm = audioMetaCache[currentVideoId]?.bpm
        val nextBpm = audioMetaCache[nextVideoId]?.bpm
        if (currentBpm == null || nextBpm == null) return AUTO_FALLBACK_DURATION_MS
        if (currentBpm <= 0 || nextBpm <= 0) return AUTO_FALLBACK_DURATION_MS

        val beatMs = 60_000.0 / currentBpm
        val baseTargetMs = getAutoTargetDurationMs(currentBpm)

        val bpmGapFactor = calculateBpmGapDurationFactor(currentBpm, nextBpm)
        val keyGapFactor = calculateKeyGapDurationFactor(currentVideoId, nextVideoId)
        val adjustedTargetMs = baseTargetMs * bpmGapFactor * keyGapFactor

        val bestBeatCount =
            BEAT_COUNT_OPTIONS.minByOrNull { abs(it * beatMs - adjustedTargetMs) }
                ?: DEFAULT_BEAT_COUNT
        val duration = (bestBeatCount * beatMs).toInt()

        // Deliberately silent. This is a pure calculation, and the position poll calls it twice
        // every 200 ms — once through isCurrentTrackTooShortForCrossfade() and once to build the
        // trigger threshold — so logging here buried every other line in the terminal at ten a
        // second. The resolved value is logged where a crossfade actually starts instead.
        return duration.coerceIn(AUTO_MIN_DURATION_MS, AUTO_MAX_DURATION_MS)
    }

    private fun calculateBpmGapDurationFactor(
        currentBpm: Int,
        nextBpm: Int,
    ): Double {
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0
        var ratio = nextBpm.toDouble() / currentBpm.toDouble()
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.67) ratio *= 2.0
        val gapPercent = abs(1.0 - ratio)
        return 1.0 + gapPercent * BPM_GAP_DURATION_SCALE
    }

    private fun calculateKeyGapDurationFactor(
        currentVideoId: String,
        nextVideoId: String,
    ): Double {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentKey = currentMeta?.key ?: return UNKNOWN_GAP_DEFAULT_FACTOR
        val nextKey = nextMeta?.key ?: return UNKNOWN_GAP_DEFAULT_FACTOR

        // An unparseable key tells us nothing about compatibility — treat it like a missing
        // key instead of like a perfect match, otherwise it silently shortens the blend.
        val currentCamelot = keyToCamelot(currentKey, currentMeta.keyScale) ?: return UNKNOWN_GAP_DEFAULT_FACTOR
        val nextCamelot = keyToCamelot(nextKey, nextMeta.keyScale) ?: return UNKNOWN_GAP_DEFAULT_FACTOR

        val dist = camelotDistance(currentCamelot, nextCamelot)
        return when {
            dist <= 1 -> 1.0
            dist == 2 -> 1.1
            dist <= 4 -> 1.25
            else -> 1.4
        }
    }

    // ========== Camelot Wheel ==========

    private data class CamelotCode(
        val number: Int,
        val isMinor: Boolean,
    ) {
        override fun toString(): String = "$number${if (isMinor) "A" else "B"}"
    }

    private fun keyToCamelot(
        key: String,
        keyScale: String?,
    ): CamelotCode? {
        val semitone = keyToSemitone(key)
        if (semitone < 0) return null
        val isMinor = keyScale?.uppercase()?.contains("MIN") == true
        val minorCamelotByPitch = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val majorCamelotByPitch = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        val number = if (isMinor) minorCamelotByPitch[semitone] else majorCamelotByPitch[semitone]
        return CamelotCode(number, isMinor)
    }

    private fun camelotDistance(
        a: CamelotCode,
        b: CamelotCode,
    ): Int {
        val numberDiff = abs(a.number - b.number)
        val circularDist = minOf(numberDiff, 12 - numberDiff)
        val typeDiff = if (a.isMinor != b.isMinor) 1 else 0
        return circularDist + typeDiff
    }

    // Tidal spells accidentals out ("FSharp", "CSharp") instead of using symbols,
    // so the name is normalised before matching.
    private fun keyToSemitone(key: String): Int {
        val normalized =
            key
                .trim()
                .replace("Sharp", "#", ignoreCase = true)
                .replace("Flat", "b", ignoreCase = true)
                .replaceFirstChar { it.uppercaseChar() }
        return when (normalized) {
            "C" -> 0; "C#", "Db" -> 1; "D" -> 2; "D#", "Eb" -> 3
            "E" -> 4; "F" -> 5; "F#", "Gb" -> 6; "G" -> 7
            "G#", "Ab" -> 8; "A" -> 9; "A#", "Bb" -> 10; "B" -> 11
            else -> -1
        }
    }

    companion object {
        /** Floor for the shortest track worth crossfading, in ms. */
        private const val MIN_CROSSFADE_TRACK_MS = 20_000L

        private const val AUTO_FALLBACK_DURATION_MS = 30000
        private const val AUTO_MIN_DURATION_MS = 20000
        private const val AUTO_MAX_DURATION_MS = 45000
        private val BEAT_COUNT_OPTIONS = intArrayOf(8, 16, 24, 32, 40, 48, 64, 80, 96)
        private const val DEFAULT_BEAT_COUNT = 32
        private const val BPM_GAP_DURATION_SCALE = 2.0
        private const val UNKNOWN_GAP_DEFAULT_FACTOR = 1.25

        // DJ crossfade sigmoid steepness (higher = sharper S-curve transition)
        private const val DJ_FILTER_SIGMOID_K = 6f

        // DJ crossfade filter frequency bounds
        private const val LPF_START_HZ = 20000f // Low-pass starts wide open
        private const val LPF_END_HZ = 200f // Low-pass ends muffled (keeps bass thump like Pioneer DJM)
        private const val HPF_START_HZ = 2000f // High-pass starts lower — incoming track fills in faster
        private const val HPF_END_HZ = 20f // High-pass ends wide open

        private const val BPM_RATIO_MIN = 0.75f // Max 25% slower
        private const val BPM_RATIO_MAX = 1.25f // Max 25% faster

        // Quantization step for the speed/pitch ramp. On Android this keeps SonicAudioProcessor
        // from popping on micro-adjustments; here it keeps us from issuing 50 near-identical
        // af-command/speed updates per crossfade.
        private const val SPEED_PITCH_STEP = 0.02f

        // Front-loaded BPM/pitch ramp portion (fraction of crossfade duration).
        // Outgoing tempo reaches target within the first [BPM_RAMP_PORTION] of the
        // crossfade (smoothstep S-curve) and then holds for the remainder.
        // Must stay > 0 — the ramp divides by it.
        private const val BPM_RAMP_PORTION = 0.6f

        /**
         * mpv's neutral `pitch-scale`. The desktop backend exposes no user pitch control (see
         * [playbackParameters]), so unlike Android — which multiplies the AutoMix ratio by
         * `internalPlaybackPitch` — the baseline here is simply 1.0.
         */
        private const val NATURAL_PITCH_SCALE = 1.0f
    }

    // ========== DJ crossfade math (ported from CrossfadeExoPlayerAdapter) ==========

    /**
     * S-curve (sigmoid) function for DJ filter crossfade timing.
     * Keeps both tracks near full spectrum at the start and end, with a steep transition in the
     * middle — like a real DJ mixer crossfader.
     */
    private fun sigmoid(
        t: Float,
        k: Float = DJ_FILTER_SIGMOID_K,
    ): Float = 1.0f / (1.0f + exp(-k * (t - 0.5f)))

    /**
     * Exponential interpolation between two values.
     * Frequency perception is logarithmic, so this produces a natural-sounding sweep.
     */
    private fun exponentialInterpolate(
        start: Float,
        end: Float,
        t: Float,
    ): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t)
    }

    private fun lerp(
        start: Float,
        end: Float,
        t: Float,
    ): Float = start + (end - start) * t

    /** Quantize a speed/pitch value to the nearest [SPEED_PITCH_STEP] (2%). */
    private fun quantize(value: Float): Float = (value / SPEED_PITCH_STEP).roundToInt() * SPEED_PITCH_STEP

    /**
     * Speed ratio applied to the OUTGOING track so its effective tempo matches the incoming one.
     * Returns 1.0 (no ramp) whenever BPM data is missing for either side.
     */
    private fun calculateBpmSpeedRatio(
        currentVideoId: String,
        nextVideoId: String,
    ): Float {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentBpm = currentMeta?.bpm
        val nextBpm = nextMeta?.bpm

        if (currentBpm == null || nextBpm == null) {
            Logger.d(
                TAG,
                "AutoMix BPM: missing data - current=$currentBpm (cached=${currentMeta != null}), " +
                    "next=$nextBpm (cached=${nextMeta != null})",
            )
            return 1.0f
        }
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0f

        // Ratio is APPLIED to the outgoing player so its effective BPM matches the next track:
        //   outgoing_effective_BPM = currentBpm × ratio, want it to equal nextBpm
        //   → ratio = nextBpm / currentBpm
        var ratio = nextBpm.toFloat() / currentBpm.toFloat()

        // Normalize halftime/doubletime relationships (e.g. 140/70 → 1.0, 70/140 → 1.0)
        while (ratio > 1.5f) ratio /= 2f
        while (ratio < 0.67f) ratio *= 2f

        Logger.d(TAG, "AutoMix BPM: current=$currentBpm, next=$nextBpm, ratio=$ratio")

        // Only apply if the adjustment is within a safe range (avoids unnatural artifacts)
        return if (ratio in BPM_RATIO_MIN..BPM_RATIO_MAX) {
            quantize(ratio)
        } else {
            Logger.d(TAG, "AutoMix BPM: ratio $ratio outside safe range [$BPM_RATIO_MIN..$BPM_RATIO_MAX], skipping")
            1.0f
        }
    }

    /**
     * Pitch ratio applied to the OUTGOING track, using the Camelot Wheel for musically correct key
     * matching. Tries ±1 then ±2 semitone shifts and picks the smallest one that brings the Camelot
     * distance to ≤ 1. Returns 1.0 when the keys are already compatible, unknown, or no small shift
     * helps.
     */
    private fun calculateKeyPitchRatio(
        currentVideoId: String,
        nextVideoId: String,
    ): Float {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentKey = currentMeta?.key
        val nextKey = nextMeta?.key

        if (currentKey == null || nextKey == null) {
            Logger.d(
                TAG,
                "AutoMix Key: missing data - currentKey=$currentKey (cached=${currentMeta != null}), " +
                    "nextKey=$nextKey (cached=${nextMeta != null})",
            )
            return 1.0f
        }

        val currentCamelot = keyToCamelot(currentKey, currentMeta.keyScale)
        val nextCamelot = keyToCamelot(nextKey, nextMeta.keyScale)
        if (currentCamelot == null || nextCamelot == null) {
            Logger.d(
                TAG,
                "AutoMix Key: unknown key format - currentKey='$currentKey' ${currentMeta.keyScale}, " +
                    "nextKey='$nextKey' ${nextMeta.keyScale}",
            )
            return 1.0f
        }

        val dist = camelotDistance(currentCamelot, nextCamelot)
        Logger.d(
            TAG,
            "AutoMix Key: current=$currentKey ${currentMeta.keyScale} ($currentCamelot), " +
                "next=$nextKey ${nextMeta.keyScale} ($nextCamelot), camelotDist=$dist",
        )
        if (dist <= 1) {
            Logger.d(TAG, "AutoMix Key: compatible (dist=$dist), no shift")
            return 1.0f
        }

        val currentSemitone = keyToSemitone(currentKey)
        if (currentSemitone < 0) return 1.0f

        val isMinor = currentCamelot.isMinor
        //                                   C  C# D  D# E  F  F# G  G# A  A# B
        val minorCamelotByPitch = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val majorCamelotByPitch = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

        for (shift in intArrayOf(-1, 1, -2, 2)) {
            val shiftedSemitone = (currentSemitone + shift + 12) % 12
            val shiftedNumber =
                if (isMinor) minorCamelotByPitch[shiftedSemitone] else majorCamelotByPitch[shiftedSemitone]
            val shiftedCamelot = CamelotCode(shiftedNumber, isMinor)
            if (camelotDistance(shiftedCamelot, nextCamelot) <= 1) {
                val pitchRatio = exp(ln(2.0) * shift.toDouble() / 12.0).toFloat()
                Logger.d(
                    TAG,
                    "AutoMix Key: shift $shift semitones ($currentCamelot→$shiftedCamelot), ratio=$pitchRatio",
                )
                return pitchRatio
            }
        }

        Logger.d(TAG, "AutoMix Key: dist=$dist, no safe shift within ±2 semitones")
        return 1.0f
    }

    /**
     * Return a handle to untouched playback: no crossfade filters, natural speed. Clearing the
     * filter chain is also what drops the pitch shift (see [MpvPlayer.clearAudioFilters]).
     */
    private fun MpvPlayer.endCrossfadeAudio() {
        clearAudioFilters()
        setRate(internalPlaybackSpeed)
    }

    // ========== Position Updates ==========

    /**
     * Start position updates (periodic polling for crossfade detection).
     * mpv's observed `time-pos` handles position caching, but this loop is still needed
     * for crossfade trigger detection.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != null) {
                    try {
                        if (internalState == InternalState.PLAYING ||
                            internalState == InternalState.READY ||
                            internalState == InternalState.PAUSED
                        ) {
                            // During crossfade, show the incoming track's timeline exclusively
                            if (isCrossfading) {
                                val nextPlayer = secondaryPlayer
                                if (nextPlayer != null) {
                                    val pos = nextPlayer.time
                                    val dur = nextPlayer.length
                                    if (pos > 0) cachedPosition = pos
                                    if (dur > 0) cachedDuration = dur
                                }
                            } else {
                                val player = currentPlayer
                                if (player != null) {
                                    val pos = player.time
                                    val dur = player.length
                                    if (pos > 0) cachedPosition = pos
                                    if (dur > 0) cachedDuration = dur
                                }
                            }

                            // Check if should trigger crossfade.
                            // Use currentPlayer's time (not the timeline player) for trigger detection.
                            // Block when paused (internalPlayWhenReady=false) to prevent
                            // crossfade during queue restore.
                            if (crossfadeEnabled &&
                                !crossfadeSuppressed &&
                                !isCrossfading &&
                                internalPlayWhenReady &&
                                !isCurrentTrackVideo() &&
                                !isNextTrackVideo() &&
                                !isCurrentTrackTooShortForCrossfade() &&
                                !isWithinAlbum()
                            ) {
                                val player = currentPlayer
                                if (player != null) {
                                    val dur = player.length
                                    val pos = player.time
                                    if (dur > 0 && pos > 0) {
                                        val timeRemaining = dur - pos
                                        val nextVideoId = playlist.getOrNull(getNextMediaItemIndex())?.mediaId
                                        val isPrecached = nextVideoId != null && precachedPlayers.containsKey(nextVideoId)
                                        val preparationBufferMs = if (isPrecached) 0L else 3000L
                                        val resolvedDurationMs =
                                            if (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO) {
                                                val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""
                                                resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId ?: "")
                                            } else {
                                                crossfadeDurationMs
                                            }
                                        val triggerThreshold = resolvedDurationMs.toLong() + preparationBufferMs
                                        if (timeRemaining in 1..triggerThreshold) {
                                            if (hasNextMediaItem()) {
                                                val nextIndex = getNextMediaItemIndex()
                                                triggerCrossfadeTransition(nextIndex)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore query errors
                    }

                    delay(200) // Update every 200ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Trigger precaching
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty()) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    val indicesToPrecache = mutableListOf<Int>()

                    val index = localCurrentMediaItemIndex
                    for (i in 1..maxPrecacheCount) {
                        val nextIndex =
                            when (internalRepeatMode) {
                                PlayerConstants.REPEAT_MODE_ALL -> {
                                    (index + i) % playlist.size
                                }
                                else -> {
                                    val next = index + i
                                    if (next < playlist.size) next else break
                                }
                            }

                        if (nextIndex != localCurrentMediaItemIndex &&
                            !precachedPlayers.containsKey(playlist.getOrNull(nextIndex)?.mediaId)
                        ) {
                            indicesToPrecache.add(nextIndex)
                        }
                    }

                    for (idx in indicesToPrecache) {
                        if (!isActive) break

                        val mediaItem = playlist.getOrNull(idx) ?: continue

                        // Network extraction on IO, mpv native calls on the service thread
                        val source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }

                        if (source != null && source.url.isNotEmpty()) {
                            try {
                                // mpv has no separate "parse without playing" call like vlcj's
                                // media().prepare(); loading held at pause=yes is the equivalent,
                                // and additionally warms the demuxer cache.
                                val player = createMediaPlayerInternal(source)
                                if (player == null) {
                                    Logger.w(TAG, "Precaching skipped for $idx: could not create mpv player")
                                } else {
                                    player.applyPlaybackLevels()
                                    player.loadFile(buildPlaybackUrl(source), startPaused = true)
                                    precachedPlayers[mediaItem.mediaId] =
                                        PrecachedPlayer(player, mediaItem, source)
                                    Logger.d(TAG, "Precached player for index $idx")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Precaching error for $idx: ${e.message}")
                            }
                        }

                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Precaching error: ${e.message}")
                    }
                }
            }
    }

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        precachedPlayers.entries.removeIf { (videoId, cached) ->
            if (videoId != currentMediaItem?.mediaId) {
                cleanupPlayerInternal(cached.player)
                true
            } else {
                false
            }
        }
    }

    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        precachedPlayers.values.forEach { cleanupPlayerInternal(it.player) }
        precachedPlayers.clear()
    }

    /**
     * Dispatch listener notifications on the service thread.
     * Listeners (JvmMediaPlayerHandlerImpl) use thread-safe StateFlow updates
     * and contain runBlocking calls that would deadlock on Main/Swing EDT.
     */
    private fun notifyListeners(block: MediaPlayerListener.() -> Unit) {
        coroutineScope.launch {
            listeners.forEach(block)
        }
    }

    // ========== Shuffle Management ==========

    private fun createShuffleOrder() {
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }

        val indices = playlist.indices.toMutableList()
        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) {
            indices.removeAt(currentIndex)
        }

        indices.shuffle()

        if (currentIndex in playlist.indices) {
            indices.add(0, currentIndex)
        }

        shuffleOrder.clear()
        shuffleOrder.addAll(indices)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            shuffleIndices[originalIndex] = shuffledPos
        }

        Logger.d(TAG, "Created shuffle order: $shuffleOrder")
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) return

        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) {
                shuffleOrder[i]++
            }
        }

        val insertPos = (afterShufflePos + 1).coerceIn(0, shuffleOrder.size)
        shuffleOrder.add(insertPos, insertedOriginalIndex)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, origIndex ->
            if (origIndex < shuffleIndices.size) {
                shuffleIndices[origIndex] = shuffledPos
            }
        }
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        notifyListeners { onTimelineChanged(list, reason) }
    }

    fun setPrecachingEnabled(enabled: Boolean) {
        precacheEnabled = enabled
        if (!enabled) {
            clearPrecacheExceptCurrentInternal()
        } else {
            triggerPrecachingInternal()
        }
    }

    // ========== URL Extraction ==========

    /**
     * Extract playable URL for a media item.
     * Returns both video AND audio URLs when available; they are merged into a single
     * `edl://` URL by [buildPlaybackUrl] (mpv's equivalent of VLC's `--input-slave`
     * and Android's MergingMediaSource).
     */
    private suspend fun extractPlayableUrl(mediaItem: GenericMediaItem): PlayableSource? {
        Logger.w(TAG, "Extracting playable URL for ${mediaItem.mediaId}")
        val shouldFindVideo =
            mediaItem.isVideo() &&
                dataStoreManager.watchVideoInsteadOfPlayingAudio.first() == DataStoreManager.TRUE
        val videoId = mediaItem.mediaId

        // Check downloads first
        val downloadFiles =
            File(getDownloadPath()).listFiles()?.filter {
                it.name.contains(videoId)
            }
        if (!downloadFiles.isNullOrEmpty()) {
            val audioFile = downloadFiles.firstOrNull { !it.name.contains(MERGING_DATA_TYPE.VIDEO) }
            if (audioFile != null && audioFile.length() > 0 && audioFile.exists()) {
                // mpv accepts absolute file paths directly
                return PlayableSource(isVideo = false, url = audioFile.absolutePath)
            }
        }

        // Try new format API (returns both audio and video URLs)
        streamRepository.getNewFormat(videoId).lastOrNull()?.let { format ->
            val audioUrl = format.audioUrl
            val videoUrl = format.videoUrl

            if (shouldFindVideo && !videoUrl.isNullOrEmpty()) {
                val is403Video = streamRepository.is403Url(videoUrl).firstOrNull() != false
                if (!is403Video) {
                    // Return video URL with audio as a second EDL stream for merging
                    val audioSlave =
                        if (!audioUrl.isNullOrEmpty()) {
                            val is403Audio = streamRepository.is403Url(audioUrl).firstOrNull() != false
                            if (!is403Audio) audioUrl else null
                        } else {
                            null
                        }

                    Logger.w("Stream", "Video from format (with audio stream: ${audioSlave != null})")
                    return PlayableSource(
                        isVideo = true,
                        url = videoUrl,
                        audioSlaveUrl = audioSlave,
                    )
                }
            } else if (!shouldFindVideo && !audioUrl.isNullOrEmpty()) {
                val is403Url = streamRepository.is403Url(audioUrl).firstOrNull() != false
                if (!is403Url) {
                    Logger.w("Stream", "Audio from format")
                    return PlayableSource(isVideo = false, url = audioUrl)
                }
            }
        }

        // Fallback to stream extraction
        if (shouldFindVideo) {
            val videoUrl =
                streamRepository
                    .getStream(
                        dataStoreManager,
                        videoId,
                        isDownloading = false,
                        isVideo = true,
                        // NOT muxed — deliberately different from VlcPlayerAdapter.
                        //
                        // VLC cannot mux two streams into one source, so it has to ask for an HLS
                        // manifest (muxed=true), which returns the SAME url as both audioUrl and
                        // videoUrl. mpv can mux, via `edl://` + `!new_stream` — the very mechanism
                        // mpv's own ytdl_hook uses for YouTube's separate audio/video formats.
                        //
                        // Staying on the default (false) therefore gives us: real DASH streams
                        // instead of one manifest opened twice, free choice of itag rather than
                        // whatever the HLS variant offers, and the signed-in session preserved
                        // (StreamRepositoryImpl passes `noLogIn = muxed` straight to the player
                        // request, so muxed=true silently drops the login for that call).
                    ).lastOrNull()
            if (videoUrl != null) {
                // The stream above is video-ONLY. Dropping `muxed = true` traded the HLS
                // manifest (audio and video in one URL) for a real DASH stream, which carries
                // no audio track at all — so the audio URL has to be fetched separately and
                // merged back in, exactly as the format path above does. Without this the
                // player plays a silent video, with nothing in the logs to say why.
                val audioUrl =
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            videoId,
                            isDownloading = false,
                            isVideo = false,
                        ).lastOrNull()
                Logger.d(TAG, "Stream Video $videoUrl (with audio stream: ${audioUrl != null})")
                return PlayableSource(
                    isVideo = true,
                    url = videoUrl,
                    audioSlaveUrl = audioUrl,
                )
            }
        } else {
            val audioUrl =
                streamRepository
                    .getStream(
                        dataStoreManager,
                        videoId,
                        isDownloading = false,
                        isVideo = false,
                    ).lastOrNull()
            if (audioUrl != null) {
                Logger.d(TAG, "Stream Audio $audioUrl")
                return PlayableSource(isVideo = false, url = audioUrl)
            }
        }

        return null
    }
}
