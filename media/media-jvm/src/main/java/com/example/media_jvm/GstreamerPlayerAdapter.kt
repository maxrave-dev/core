package com.example.media_jvm

import com.example.media_jvm.download.getDownloadPath
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Version
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.event.SeekType
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

private const val TAG = "GstreamerPlayerAdapter"
/**
 * GStreamer implementation of MediaPlayerInterface
 * Features:
 * - Queue management with auto-load for next track
 * - Precaching system for smooth transitions
 * - Thread-safe operations with dedicated GStreamer thread
 * - Hardware acceleration support
 * - Advanced audio pipeline
 * - Proper state machine like ExoPlayer
 */
class GstreamerPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {

    // Internal state enum for proper state machine
    private enum class InternalState {
        IDLE,           // No media loaded
        PREPARING,      // Loading media
        READY,          // Ready to play/paused
        PLAYING,        // Currently playing
        ENDED,          // Playback ended
        ERROR           // Error state
    }

    init {
        /**
         * Set up paths to native GStreamer libraries - see adjacent file.
         */
        configurePaths()

        /**
         * Initialize GStreamer. Always pass the lowest version you require -
         * Version.BASELINE is GStreamer 1.8. Use Version.of() for higher.
         * Features requiring later versions of GStreamer than passed here will
         * throw an exception in the bindings even if the actual native library
         * is a higher version.
         */
        Gst.init(Version.BASELINE, "FXPlayer")
    }

    // ========== Threading Model ==========
    // Single-threaded executor for ALL GStreamer operations (like ExoPlayer's internal playback thread
    // ========== State Management ==========
    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile private var currentPlayer: PlayBin? = null
    @Volatile private var internalState = InternalState.IDLE
    @Volatile private var internalPlayWhenReady = true
    @Volatile private var internalVolume = 1.0f
    @Volatile private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF
    @Volatile private var internalShuffleModeEnabled = false
    @Volatile private var internalPlaybackSpeed = 1.0f

    // Position tracking - updated periodically, not on every query
    @Volatile private var cachedPosition = 0L
    @Volatile private var cachedDuration = 0L
    @Volatile private var cachedBufferedPosition = 0L
    @Volatile private var cachedIsLoading = false
    private var positionUpdateJob: Job? = null

    // State transition debouncing to prevent flickering
    @Volatile private var lastStateChangeTime = 0L
    private val stateChangeDebounceMs = 100L
    @Volatile private var isTransitioning = false

    // Bus listener management
    private data class BusListeners(
        val eos: Bus.EOS,
        val durationChanged: Bus.DURATION_CHANGED,
        val error: Bus.ERROR,
        val warning: Bus.WARNING,
        val stateChanged: Bus.STATE_CHANGED,
        val buffering: Bus.BUFFERING,
        val asyncDone: Bus.ASYNC_DONE
    )
    private var activeBusListeners: BusListeners? = null

    // Precaching system
    private data class PrecachedPlayer(
        val player: PlayBin,
        val mediaItem: GenericMediaItem,
        val url: String
    )
    private val precachedPlayers = ConcurrentHashMap<Int, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Loading management
    private var currentLoadJob: Job? = null

    fun getCurrentPlayer(): PlayBin? = currentPlayer

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "▶️ play() called (current state: $internalState, playWhenReady: $internalPlayWhenReady)")
        coroutineScope.launch {
                when (internalState) {
                    InternalState.READY, InternalState.ENDED -> {
                        currentPlayer?.let { player ->
                            Logger.d(TAG, "▶️ Play: Setting GStreamer state to PLAYING")
                            isTransitioning = true
                            player.state = State.PLAYING
                            internalPlayWhenReady = true
                            // State change will be handled by stateChangedListener
                        } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                    }
                    InternalState.PREPARING -> {
                        // Just set playWhenReady, will auto-play when ready
                        cachedIsLoading = true
                        listeners.forEach { it.onIsLoadingChanged(true) }
                        Logger.d(TAG, "▶️ Play: During PREPARING - will auto-play when ready")
                    }
                    InternalState.PLAYING -> {
                        // Already playing, update flag
                        internalPlayWhenReady = true
                        cachedIsLoading = true
                        listeners.forEach { it.onIsLoadingChanged(true) }
                        Logger.d(TAG, "▶️ Play: Already playing")
                    }
                    else -> {
                        Logger.w(TAG, "▶️ Play: Called in invalid state: $internalState")
                    }
                }
        }
    }

    override fun pause() {
        Logger.d(TAG, "⏸️ pause() called (current state: $internalState, playWhenReady: $internalPlayWhenReady)")
        coroutineScope.launch {
                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            Logger.d(TAG, "⏸️ Pause: Setting GStreamer state to PAUSED")
                            isTransitioning = true
                            player.state = State.PAUSED
                            internalPlayWhenReady = false
                            // State change will be handled by stateChangedListener
                        }
                    }
                    InternalState.PREPARING -> {
                        // Just set playWhenReady to false
                        internalPlayWhenReady = false
                        Logger.d(TAG, "⏸️ Pause: During PREPARING - will not auto-play")
                    }
                    else -> {
                        Logger.w(TAG, "⏸️ Pause: Called in invalid state: $internalState")
                    }
                }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.state = State.NULL
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
                notifyEqualizerIntent(false)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        coroutineScope.launch {
            currentPlayer?.let { player ->
                try {
                    val seekResult = player.seek(positionMs, TimeUnit.MILLISECONDS)
                    if (seekResult) {
                        cachedPosition = positionMs
                        Logger.d(TAG, "Seeked to position: $positionMs")
                    } else {
                        Logger.w(TAG, "Seek failed to position: $positionMs")
                    }
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

            // Cancel any ongoing load
            currentLoadJob?.cancel()

            // Load the new track
            localCurrentMediaItemIndex = mediaItemIndex
            currentPlayer?.seek(0L, TimeUnit.MILLISECONDS)
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (cachedPosition - 10000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val newPosition = (cachedPosition + 10000).coerceAtMost(cachedDuration)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        if (hasNextMediaItem()) {
            val nextIndex = getNextMediaItemIndex()
            seekTo(nextIndex, 0)
        }
    }

    override fun seekToPrevious() {
        if (hasPreviousMediaItem()) {
            val prevIndex = getPreviousMediaItemIndex()
            seekTo(prevIndex, 0)
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
            // Cancel ongoing operations
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)

        if (playlist.size - 1 - currentMediaItemIndex <= maxPrecacheCount) {
            // If added item is within precache range, trigger precaching
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
            playlist.add(index, mediaItem)

            // Adjust current index if needed
            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
            if (index - 1 - currentMediaItemIndex <= maxPrecacheCount) {
                // If added item is within precache range, trigger precaching
                coroutineScope.launch {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        if (index !in playlist.indices) return

        coroutineScope.launch {
                playlist.removeAt(index)

                // Remove from precache
                precachedPlayers.remove(index)?.let { cached ->
                    cleanupPlayerInternal(cached.player)
                }

                when {
                    index < localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex--
                        // Rekey precache
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
                        // Index after current, just update precache
                        clearPrecacheExceptCurrentInternal()
                        triggerPrecachingInternal()
                    }
                }
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return

        coroutineScope.launch {
                val item = playlist.removeAt(fromIndex)
                playlist.add(toIndex, item)

                // Update current index
                localCurrentMediaItemIndex =
                    when {
                        localCurrentMediaItemIndex == fromIndex -> toIndex
                        fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex ->
                            localCurrentMediaItemIndex - 1

                        fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex ->
                            localCurrentMediaItemIndex + 1

                        else -> localCurrentMediaItemIndex
                    }

                // Clear and rebuild precache
                clearPrecacheExceptCurrentInternal()
                triggerPrecachingInternal()
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            localCurrentMediaItemIndex = -1

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

            // Remove from precache
            precachedPlayers.remove(index)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

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
            PlayerConstants.REPEAT_MODE_ONE -> localCurrentMediaItemIndex
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (localCurrentMediaItemIndex < playlist.size - 1) {
                    localCurrentMediaItemIndex + 1
                } else {
                    0
                }
            }

            else -> (localCurrentMediaItemIndex + 1).coerceAtMost(playlist.size - 1)
        }

    private fun getPreviousMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> localCurrentMediaItemIndex
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (localCurrentMediaItemIndex > 0) {
                    localCurrentMediaItemIndex - 1
                } else {
                    playlist.size - 1
                }
            }

            else -> (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
        }

    // ========== Playback Modes ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            internalShuffleModeEnabled = value
            // TODO: Implement shuffle logic with queue reordering
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            internalRepeatMode = value
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackSpeed)
        set(value) {
            internalPlaybackSpeed = value.speed
            currentPlayer?.let { player ->
                // GStreamer playback rate control via seek event with rate
                try {
                    val currentPos = currentPosition * 1000000 // Convert to nanoseconds
                    val rate = value.speed.toDouble()

                    // Use seek with rate parameter for playback speed control
                    val seekFlags =
                        java.util.EnumSet.of(
                            SeekFlags.FLUSH,
                            SeekFlags.ACCURATE,
                        )

                    player.seek(
                        rate,
                        Format.TIME,
                        seekFlags,
                        SeekType.SET,
                        currentPos,
                        SeekType.NONE,
                        -1,
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to set playback speed: ${e.message}")
                }
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = 0 // GStreamer doesn't provide audio session ID in the same way

    override var volume: Float
        get() = internalVolume
        set(value) {
            internalVolume = value.coerceIn(0f, 1f)
            currentPlayer?.setVolume(internalVolume.toDouble())
        }

    override var skipSilenceEnabled: Boolean = false
    // GStreamer doesn't natively support skip silence, would need custom pipeline

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        // Cancel all ongoing jobs
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()

        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()
    }

    // ========== Internal Methods ==========
    // NOTE: All internal methods MUST be called from coroutineScope unless otherwise noted

    /**
     * State transition helper - MUST be called within stateLock
     */
    private fun transitionToState(newState: InternalState) {
        if (internalState == newState) {
            Logger.d(TAG, "State transition ignored: already in $newState")
            return
        }

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "⚡ State transition: $oldState -> $newState (playWhenReady=$internalPlayWhenReady, transitioning=$isTransitioning)")

        // Notify listeners
        when (newState) {
            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }
            InternalState.PREPARING -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
                listeners.forEach { it.onIsLoadingChanged(true) }
            }
            InternalState.READY -> {
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                    listeners.forEach { it.onIsLoadingChanged(false) }
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }
            InternalState.PLAYING -> {
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
            }
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

        // Cancel previous load
        currentLoadJob?.cancel()

        currentLoadJob = coroutineScope.launch {
            try {
                transitionToState(InternalState.PREPARING)

                // Notify media item transition
                listeners.forEach {
                    it.onMediaItemTransition(
                        mediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    )
                }
                // Use precached player if available
                val cachedPlayer = precachedPlayers.remove(index)
                val player = if (cachedPlayer?.player != null) {
                    cachedPlayer.player
                } else {
                    // Extract URL outside GStreamer thread
                    val playableUrl = extractPlayableUrl(videoId)

                    if (playableUrl.isNullOrEmpty()) {
                        Logger.e(TAG, "Failed to extract playable URL for $videoId")
                        transitionToState(InternalState.ERROR)
                        return@launch
                    }
                    createMediaPlayerInternal(playableUrl)
                }

                // Cleanup current
                cleanupCurrentPlayerInternal()

                // Set as current
                currentPlayer = player
                setupPlayerListenersInternal(player)

                // Apply settings
                player.volume = internalVolume.toDouble()
                player["mute"] = false

                // Set to PAUSED to load pipeline
                player.state = State.PAUSED

                transitionToState(InternalState.READY)

                // Seek if needed
                if (startPositionMs > 0) {
                    player.seek(startPositionMs, TimeUnit.MILLISECONDS)
                    cachedPosition = startPositionMs
                }

                // Auto-play if requested
                if (shouldPlay) {
                    player.state = State.PLAYING
                } else {
                    player.state = State.READY
                }

                // Start position updates
                startPositionUpdates()

                // Trigger precaching
                triggerPrecachingInternal()
            } catch (e: Exception) {
                Logger.e(TAG, "Load track error: ${e.message}", e)
                transitionToState(InternalState.ERROR)
            }
        }
    }

    /**
     * Create player - MUST be called on gstreamerDispatcher
     */
    private fun createMediaPlayerInternal(uri: String): PlayBin {
        val player = PlayBin("player-${System.currentTimeMillis()}")
        player.setURI(URI(uri))

        // Disable video, enable audio
        try {
            val currentFlags = player["flags"] as? Int ?: 0x00000617
            val audioOnlyFlags = (currentFlags and 0x01.inv()) or 0x02 or 0x20
            player["flags"] = audioOnlyFlags
            Logger.d(TAG, "PlayBin flags set to audio-only: $audioOnlyFlags")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set playbin flags: ${e.message}", e)
        }

        // Disable video sink
        try {
            val fakeSink = ElementFactory.make("fakesink", "video-sink")
            if (fakeSink != null) {
                player.setVideoSink(fakeSink)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to disable video sink: ${e.message}", e)
        }

        // Configure audio sink
        try {
            val audioSink = ElementFactory.make("autoaudiosink", "audio-sink")
            if (audioSink != null) {
                player.setAudioSink(audioSink)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set audio sink: ${e.message}", e)
        }

        player.volume = 1.0
        player["mute"] = false

        return player
    }

    /**
     * Setup bus listeners - MUST be called on gstreamerDispatcher
     */
    private fun setupPlayerListenersInternal(player: PlayBin) {
        // Clean up old listeners first
        cleanupBusListenersInternal()

        val bus = player.bus

        // Create new listeners
        val eosListener = Bus.EOS { _ ->
            coroutineScope.launch {
                Logger.d(TAG, "End of stream reached")
                transitionToState(InternalState.ENDED)
                handleTrackEndInternal()
            }
        }

        val durationListener = Bus.DURATION_CHANGED { _ ->
            coroutineScope.launch {
                currentPlayer?.let { player ->
                    val dur = player.queryDuration(Format.TIME)
                    cachedDuration = if (dur != -1L) dur / 1000000 else 0L
                    Logger.d(TAG, "Duration updated: $cachedDuration ms")
                }
            }
        }

        val errorListener = Bus.ERROR { _, code, message ->
            coroutineScope.launch {
                val error = PlayerError(
                    errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                    errorCodeName = "GSTREAMER_ERROR",
                    message = message ?: "Playback error (code: $code)",
                )
                Logger.e(TAG, "Playback error: $message")
                listeners.forEach { it.onPlayerError(error) }
                transitionToState(InternalState.ERROR)
            }
        }

        val warningListener = Bus.WARNING { _, code, message ->
            Logger.w(TAG, "Warning (code: $code): $message")
        }

        val stateChangedListener = Bus.STATE_CHANGED { _, oldState, newState, pending ->
            // Filter out intermediate state transitions to prevent flickering
            // Only react to meaningful PAUSED <-> PLAYING transitions
            if (oldState == newState) return@STATE_CHANGED

            // Ignore transitions to/from READY state (intermediate)
            if (newState == State.READY || oldState == State.READY) return@STATE_CHANGED

            coroutineScope.launch {
                // Debounce rapid state changes
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastStateChangeTime < stateChangeDebounceMs) {
                    Logger.d(TAG, "State change debounced: $oldState -> $newState")
                    return@launch
                }
                lastStateChangeTime = currentTime

                Logger.d(TAG, "State changed: $oldState -> $newState (internal: $internalState)")

                when (newState) {
                    State.PLAYING -> {
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            notifyEqualizerIntent(true)
                        }
                        isTransitioning = false
                    }
                    State.PAUSED -> {
                        // Only transition to READY if we were actually playing
                        if (internalState == InternalState.PLAYING) {
                            transitionToState(InternalState.READY)
                            notifyEqualizerIntent(false)
                        }
                        isTransitioning = false
                    }
                    State.NULL -> {
                        notifyEqualizerIntent(false)
                        isTransitioning = false
                    }
                    else -> {
                        isTransitioning = false
                    }
                }
            }
        }

        val bufferingListener = Bus.BUFFERING { _, percent ->
            if (percent in 1..100) {
                Logger.d(TAG, "Buffering: $percent%")
                cachedBufferedPosition = (duration * percent / 100).coerceIn(0, duration)
            }
        }

        val asyncDoneListener = Bus.ASYNC_DONE { _ ->
            coroutineScope.launch {
                // Pipeline is ready, only auto-play if:
                // 1. We're in READY state (not already playing)
                // 2. playWhenReady is true
                // 3. We're not already transitioning
                if (internalState == InternalState.READY &&
                    internalPlayWhenReady &&
                    !isTransitioning) {
                    isTransitioning = true
                    Logger.d(TAG, "ASYNC_DONE: Auto-starting playback")
                    currentPlayer?.state = State.PLAYING
                    // isTransitioning will be reset by state change listener
                }
            }
        }

        // Connect listeners
        bus.connect(eosListener)
        bus.connect(errorListener)
        bus.connect(warningListener)
        bus.connect(stateChangedListener)
        bus.connect(bufferingListener)
        bus.connect(asyncDoneListener)
        bus.connect(durationListener)

        // Store references
        activeBusListeners = BusListeners(
            eos = eosListener,
            durationChanged = durationListener,
            error = errorListener,
            warning = warningListener,
            stateChanged = stateChangedListener,
            buffering = bufferingListener,
            asyncDone = asyncDoneListener
        )
    }

    /**
     * Clean up bus listeners
     */
    private fun cleanupBusListenersInternal() {
        activeBusListeners?.let { listeners ->
            currentPlayer?.bus?.let { bus ->
                try {
                    bus.disconnect(Bus.EOS::class.java, listeners.eos)
                    bus.disconnect(Bus.DURATION_CHANGED::class.java, listeners.durationChanged)
                    bus.disconnect(Bus.ERROR::class.java, listeners.error)
                    bus.disconnect(Bus.WARNING::class.java, listeners.warning)
                    bus.disconnect(Bus.STATE_CHANGED::class.java, listeners.stateChanged)
                    bus.disconnect(Bus.BUFFERING::class.java, listeners.buffering)
                    bus.disconnect(Bus.ASYNC_DONE::class.java, listeners.asyncDone)
                } catch (e: Exception) {
                    Logger.w(TAG, "Error disconnecting listeners: ${e.message}")
                }
            }
        }
        activeBusListeners = null
    }

    /**
     * Cleanup a player instance
     */
    private fun cleanupPlayerInternal(player: PlayBin) {
        try {
            player.state = State.NULL
            player.stop()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    /**
     * Cleanup current player
     */
    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupBusListenersInternal()
        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
    }

    /**
     * Handle track end
     */
    private fun handleTrackEndInternal() {
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
                } else {
                    notifyEqualizerIntent(false)
                }
            }
        }
    }

    /**
     * Start position updates (periodic background task)
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob = coroutineScope.launch {
            while (isActive && currentPlayer != null) {
                try {
                    // Skip position queries during transitions to prevent flicker
                    if (!isTransitioning) {
                        currentPlayer?.let { player ->
                            // Only query position when in PLAYING or READY states
                            if (internalState == InternalState.PLAYING ||
                                internalState == InternalState.READY) {
                                val pos = player.queryPosition(TimeUnit.MILLISECONDS)
                                val dur = player.queryDuration(TimeUnit.MILLISECONDS)

                                if (pos >= 0) cachedPosition = pos
                                if (dur >= 0) cachedDuration = dur
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore query errors - don't log to avoid spam
                }

                delay(200) // Update every 200ms
            }
        }
    }

    /**
     * Stop position updates
     */
    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Trigger precaching - with proper cancellation
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty()) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob = coroutineScope.launch {
            try {
                val indicesToPrecache = mutableListOf<Int>()

                val index = localCurrentMediaItemIndex
                for (i in 1..maxPrecacheCount) {
                    val nextIndex = when (internalRepeatMode) {
                        PlayerConstants.REPEAT_MODE_ALL -> (index + i) % playlist.size
                        else -> {
                            val next = index + i
                            if (next < playlist.size) next else break
                        }
                    }

                    if (nextIndex != localCurrentMediaItemIndex &&
                        !precachedPlayers.containsKey(nextIndex)
                    ) {
                        indicesToPrecache.add(nextIndex)
                    }
                }

                for (idx in indicesToPrecache) {
                    if (!isActive) break

                    val mediaItem = playlist.getOrNull(idx) ?: continue
                    val videoId = mediaItem.mediaId

                    val playableUrl = withContext(coroutineScope.coroutineContext) {
                        extractPlayableUrl(videoId)
                    }

                    if (!playableUrl.isNullOrEmpty()) {
                        try {
                            val player = createMediaPlayerInternal(playableUrl)
                            player.state = State.READY
                            precachedPlayers[idx] = PrecachedPlayer(player, mediaItem, playableUrl)
                            Logger.d(TAG, "Precached player for index $idx")
                        } catch (e: Exception) {
                            Logger.e(TAG, "Precaching error for $idx: ${e.message}")
                        }
                    }

                    delay(100)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Logger.e(TAG, "Precaching error: ${e.message}")
                }
            }
        }
    }

    /**
     * Cancel precaching
     */
    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    /**
     * Clear precache except current
     */
    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        precachedPlayers.entries.removeIf { (index, cached) ->
            if (index != localCurrentMediaItemIndex) {
                cleanupPlayerInternal(cached.player)
                true
            } else {
                false
            }
        }
    }

    /**
     * Clear all precache
     */
    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        precachedPlayers.values.forEach { cleanupPlayerInternal(it.player) }
        precachedPlayers.clear()
    }


    /**
     * Notify equalizer intent
     */
    private fun notifyEqualizerIntent(shouldOpen: Boolean) {
        listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(shouldOpen) }
    }

    /**
     * Enable or disable precaching
     */
    fun setPrecachingEnabled(enabled: Boolean) {
        precacheEnabled = enabled
        if (!enabled) {
            clearPrecacheExceptCurrentInternal()
        } else {
            triggerPrecachingInternal()
        }
    }

    /**
     * Set maximum number of tracks to precache
     */
    fun setMaxPrecacheCount(count: Int) {
        // maxPrecacheCount = count.coerceIn(0, 5)
        // Note: maxPrecacheCount is now val, but you can make it var if needed
    }

    private suspend fun extractPlayableUrl(videoId: String): String? {
        if (File(getDownloadPath()).listFiles().any {
            it.name.contains(videoId)
            }) {
            val file = File(getDownloadPath()).listFiles().first {
                it.name.contains(videoId)
            }
            return file.toURI().toString()
        } else {
            streamRepository.getNewFormat(videoId).lastOrNull()?.let {
                val videoUrl = it.audioUrl
                if (videoUrl != null && it.expiredTime > now()) {
                    Logger.d("Stream", videoUrl)
                    Logger.w("Stream", "Video from format")
                    val is403Url = streamRepository.is403Url(videoUrl).firstOrNull() != false
                    Logger.d("Stream", "is 403 $is403Url")
                    if (!is403Url) {
                        return videoUrl
                    }
                }
            }
            streamRepository
                .getStream(
                    dataStoreManager,
                    videoId,
                    false,
                ).lastOrNull()
                ?.let {
                    Logger.d("Stream", it)
                    Logger.w("Stream", "Audio")
                    return it
                }
            return null
        }
    }

    private fun configurePaths() {
        if (Platform.isWindows()) {
            val gstPath = System.getProperty("gstreamer.path", findWindowsLocation())
            if (!gstPath!!.isEmpty()) {
                val systemPath = System.getenv("PATH")
                if (systemPath == null || systemPath.trim { it <= ' ' }.isEmpty()) {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath)
                } else {
                    Kernel32.INSTANCE.SetEnvironmentVariable(
                        "PATH",
                        (
                            gstPath +
                                File.pathSeparator + systemPath
                        ),
                    )
                }
            }
        } else if (Platform.isMac()) {
            val gstPath =
                System.getProperty(
                    "gstreamer.path",
                    "/Library/Frameworks/GStreamer.framework/Libraries/",
                )
            if (!gstPath!!.isEmpty()) {
                val jnaPath = System.getProperty("jna.library.path", "").trim { it <= ' ' }
                if (jnaPath.isEmpty()) {
                    System.setProperty("jna.library.path", gstPath)
                } else {
                    System.setProperty("jna.library.path", jnaPath + File.pathSeparator + gstPath)
                }
            }
        }
    }

    /**
     * Query over a stream of possible environment variables for GStreamer
     * location, filtering on the first non-null result, and adding \bin\ to the
     * value.
     *
     * @return location or empty string
     */
    private fun findWindowsLocation(): String? {
        if (Platform.is64Bit()) {
            return Stream
                .of<String?>(
                    "GSTREAMER_1_0_ROOT_MSVC_X86_64",
                    "GSTREAMER_1_0_ROOT_MINGW_X86_64",
                    "GSTREAMER_1_0_ROOT_X86_64",
                ).map<String?> { name: String? -> System.getenv(name) }
                .filter { p: String? -> p != null }
                .map<String?> { p: String? -> if (p!!.endsWith("\\")) p + "bin\\" else p + "\\bin\\" }
                .findFirst()
                .orElse("")
        } else {
            return ""
        }
    }
}