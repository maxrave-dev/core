package com.example.media_jvm

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream

/**
 * GStreamer implementation of MediaPlayerInterface
 * Features:
 * - Queue management with auto-load for next track
 * - Precaching system for smooth transitions
 * - Thread-safe operations
 * - Hardware acceleration support
 * - Advanced audio pipeline
 */
class GstreamerPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
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

    private val listeners = mutableListOf<MediaPlayerListener>()

    private var currentPlayer: PlayBin? = null
    private val positionQueryLock = Any()
    private val durationQueryLock = Any()

    // Precaching system
    private val precachedPlayers = ConcurrentHashMap<Int, PlayBin>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 1 // Precache current + next 2 tracks

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Playback state
    private var internalIsPlaying = AtomicBoolean(false)
    private var internalPlayWhenReady = AtomicBoolean(true)
    private var internalVolume = 1.0f
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF
    private var internalShuffleModeEnabled = false
    private var internalPlaybackSpeed = 1.0f

    // Position tracking
    private val lastKnownPosition = AtomicLong(0L)
    private val lastKnownDuration = AtomicLong(0L)

    // Bus for message handling
    private var currentBus: Bus? = null

    fun getCurrentPlayer(): PlayBin? = currentPlayer

    // ========== Playback Control ==========

    override fun play() {
        currentPlayer?.let { player ->
            Logger.d("GstreamerPlayerAdapter", "Play called - Current state: ${player.state}, Volume: ${player.volume}, Mute: ${player["mute"]}")
            player.play()
            internalIsPlaying.set(true)
            internalPlayWhenReady.set(true)
            listeners.forEach { it.onIsPlayingChanged(true) }
            notifyEqualizerIntent(true)
            Logger.d("GstreamerPlayerAdapter", "Play completed - State: ${player.state}")
        } ?: Logger.w("GstreamerPlayerAdapter", "Play called but currentPlayer is null")
    }

    override fun pause() {
        currentPlayer?.let { player ->
            player.pause()
            internalIsPlaying.set(false)
            internalPlayWhenReady.set(false)
            listeners.forEach { it.onIsPlayingChanged(false) }
            notifyEqualizerIntent(false)
        }
    }

    override fun stop() {
        currentPlayer?.let { player ->
            player.stop()
            internalIsPlaying.set(false)
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
            notifyEqualizerIntent(false)
        }
    }

    override fun seekTo(positionMs: Long) {
        currentPlayer?.let { player ->
            val seekResult = player.seek(positionMs, TimeUnit.MILLISECONDS)
            if (seekResult) {
                lastKnownPosition.set(positionMs)
            } else {
                Logger.w("GstreamerPlayerAdapter", "Seek failed to position: $positionMs")
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return

        val wasPlaying = internalIsPlaying.get()

        // Stop current player
        currentPlayer?.stop()

        // Load the new track
        localCurrentMediaItemIndex = mediaItemIndex
        loadAndPlayTrack(mediaItemIndex, positionMs, wasPlaying || internalPlayWhenReady.get())
    }

    override fun seekBack() {
        val newPosition = (currentPosition - 10000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val newPosition = (currentPosition + 10000).coerceAtMost(duration)
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
            loadAndPlayTrack(localCurrentMediaItemIndex, 0, false)
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        playlist.clear()
        precachedPlayers.clear()
        playlist.add(mediaItem)
        localCurrentMediaItemIndex = 0

        loadAndPlayTrack(0, 0, internalPlayWhenReady.get())
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)

        if (localCurrentMediaItemIndex == -1) {
            localCurrentMediaItemIndex = 0
            loadAndPlayTrack(0, 0, internalPlayWhenReady.get())
        } else {
            // Trigger precaching for newly added item if it's next
            triggerPrecaching()
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

            // Clear precache as indices have changed
            clearPrecacheExceptCurrent()
            triggerPrecaching()
        }
    }

    override fun removeMediaItem(index: Int) {
        if (index !in playlist.indices) return

        playlist.removeAt(index)

        // Remove from precache
        precachedPlayers.remove(index)?.stop()

        when {
            index < localCurrentMediaItemIndex -> {
                localCurrentMediaItemIndex--
                // Rekey precache
                clearPrecacheExceptCurrent()
                triggerPrecaching()
            }

            index == localCurrentMediaItemIndex -> {
                if (localCurrentMediaItemIndex >= playlist.size) {
                    localCurrentMediaItemIndex = playlist.size - 1
                }
                if (localCurrentMediaItemIndex >= 0) {
                    loadAndPlayTrack(localCurrentMediaItemIndex, 0, internalPlayWhenReady.get())
                } else {
                    currentPlayer?.stop()
                    currentPlayer = null
                }
            }

            else -> {
                // Index after current, just update precache
                clearPrecacheExceptCurrent()
                triggerPrecaching()
            }
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return

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
        clearPrecacheExceptCurrent()
        triggerPrecaching()
    }

    override fun clearMediaItems() {
        playlist.clear()
        localCurrentMediaItemIndex = -1

        currentPlayer?.stop()
        currentPlayer = null
        clearAllPrecache()
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index !in playlist.indices) return

        playlist[index] = mediaItem

        // Remove from precache
        precachedPlayers.remove(index)?.stop()

        if (index == localCurrentMediaItemIndex) {
            loadAndPlayTrack(index, 0, internalPlayWhenReady.get())
        } else {
            triggerPrecaching()
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = internalIsPlaying.get()

    override val currentPosition: Long
        get() {
            return currentPlayer?.let { player ->
                synchronized(positionQueryLock) {
                    try {
                        val pos = player.queryPosition(TimeUnit.MILLISECONDS)
                        if (pos >= 0) {
                            lastKnownPosition.set(pos)
                            pos
                        } else {
                            lastKnownPosition.get()
                        }
                    } catch (e: Exception) {
                        Logger.w("GstreamerPlayerAdapter", "Failed to query position: ${e.message}")
                        lastKnownPosition.get()
                    }
                }
            } ?: 0L
        }

    override val duration: Long
        get() {
            return currentPlayer?.let { player ->
                synchronized(durationQueryLock) {
                    try {
                        val dur = player.queryDuration(TimeUnit.MILLISECONDS)
                        if (dur >= 0) {
                            lastKnownDuration.set(dur)
                            dur
                        } else {
                            lastKnownDuration.get()
                        }
                    } catch (e: Exception) {
                        Logger.w("GstreamerPlayerAdapter", "Failed to query duration: ${e.message}")
                        lastKnownDuration.get()
                    }
                }
            } ?: 0L
        }

    override val bufferedPosition: Long
        get() {
            // GStreamer doesn't provide easy buffered position access
            // We can estimate based on buffering messages
            return currentPosition
        }

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
        get() = currentPosition

    override val playbackState: Int
        get() =
            when (currentPlayer?.state) {
                State.PLAYING -> PlayerConstants.STATE_READY
                State.PAUSED -> PlayerConstants.STATE_READY
                State.READY -> PlayerConstants.STATE_READY
                State.NULL -> PlayerConstants.STATE_IDLE
                else -> PlayerConstants.STATE_IDLE
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
        get() = internalPlayWhenReady.get()
        set(value) {
            internalPlayWhenReady.set(value)
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
                    Logger.e("GstreamerPlayerAdapter", "Failed to set playback speed: ${e.message}")
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
        currentPlayer?.stop()
        currentPlayer = null
        clearAllPrecache()
        listeners.clear()

        currentBus?.disconnect(Bus.EOS::class.java, eosListener)
        currentBus?.disconnect(Bus.ERROR::class.java, errorListener)
        currentBus?.disconnect(Bus.WARNING::class.java, warningListener)
        currentBus?.disconnect(Bus.STATE_CHANGED::class.java, stateChangedListener)
        currentBus?.disconnect(Bus.BUFFERING::class.java, bufferingListener)
        currentBus = null
    }

    // ========== Internal Methods ==========

    /**
     * Load and play a track at the specified index
     * This method handles switching from precached player or creating new one
     */
    private fun loadAndPlayTrack(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.uri ?: return

        coroutineScope.launch(Dispatchers.IO) {
            // Notify listeners
            listeners.forEach {
                it.onMediaItemTransition(
                    mediaItem,
                    PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                )
            }
            // Extract playable URL from videoId
            val playableUrl = extractPlayableUrl(videoId) ?: ""

            // GStreamer operations should NOT be on Dispatchers.Main
            // Check if we have a precached player for this index
            val player = precachedPlayers.remove(index) ?: createMediaPlayer(playableUrl)

            // Stop current player
            currentPlayer?.stop()
            currentPlayer = null

            // Set up the new player
            currentPlayer = player
            setupPlayerListeners(player)

            // Apply current settings and ensure audio is enabled
            player.volume = internalVolume.toDouble()
            player["mute"] = false

            Logger.d("GstreamerPlayerAdapter", "Player volume set to: ${player.volume}, mute: ${player["mute"]}")

            // Set state to PAUSED first (this loads the pipeline)
            player.state = State.PAUSED
            Logger.d("GstreamerPlayerAdapter", "Player state set to PAUSED (loading pipeline)")

            // Wait a bit for the pipeline to be ready
            delay(100)

            // Seek to start position if needed
            if (startPositionMs > 0) {
                player.seek(startPositionMs, TimeUnit.MILLISECONDS)
            }

            // Now set to PLAYING if needed
            if (shouldPlay) {
                Logger.d("GstreamerPlayerAdapter", "Starting playback...")
                player.state = State.PLAYING
                internalIsPlaying.set(true)
                Logger.d("GstreamerPlayerAdapter", "Playback started, state: ${player.state}")
            } else {
                // Keep in PAUSED state (ready to play)
                Logger.d("GstreamerPlayerAdapter", "Player ready in PAUSED state")
            }

            // Trigger precaching for upcoming tracks
            triggerPrecaching()
        }
    }

    /**
     * Create a new PlayBin instance
     */
    private fun createMediaPlayer(uri: String): PlayBin {
        val player = PlayBin("player")
        player.setURI(URI(uri))

        // Enable audio playback but DISABLE video in PlayBin flags
        // Flags: audio(0x02) + native-audio(0x20) but NOT video(0x01)
        try {
            // Start with default flags but explicitly disable video
            val currentFlags = player["flags"] as? Int ?: 0x00000617 // Default playbin flags
            // Remove video flag (0x01) and add audio flags
            val audioOnlyFlags = (currentFlags and 0x01.inv()) or 0x02 or 0x20 // Remove video, add audio + native-audio
            player["flags"] = audioOnlyFlags
            Logger.d(
                "GstreamerPlayerAdapter",
                "PlayBin flags set to: $audioOnlyFlags (0x${audioOnlyFlags.toString(16)}) - Video disabled, Audio enabled",
            )
        } catch (e: Exception) {
            Logger.e("GstreamerPlayerAdapter", "Failed to set playbin flags: ${e.message}", e)
        }

        // Explicitly disable video sink to prevent video rendering
        try {
            val fakeSink = ElementFactory.make("fakesink", "video-sink")
            if (fakeSink != null) {
                player.setVideoSink(fakeSink)
                Logger.d("GstreamerPlayerAdapter", "Video sink disabled with fakesink")
            }
        } catch (e: Exception) {
            Logger.e("GstreamerPlayerAdapter", "Failed to disable video sink: ${e.message}", e)
        }

        // Configure audio sink for better quality
        try {
            // Try to use autoaudiosink which automatically selects the best audio sink
            val audioSink = ElementFactory.make("autoaudiosink", "audio-sink")
            if (audioSink != null) {
                Logger.d("GstreamerPlayerAdapter", "Audio sink created successfully: ${audioSink.name}")
                player.setAudioSink(audioSink)
            } else {
                Logger.w("GstreamerPlayerAdapter", "Failed to create autoaudiosink, using default")
            }
        } catch (e: Exception) {
            Logger.e("GstreamerPlayerAdapter", "Failed to set audio sink: ${e.message}", e)
        }

        // Ensure volume is set to maximum
        player.volume = 1.0

        // Ensure audio is not muted
        player["mute"] = false

        Logger.d("GstreamerPlayerAdapter", "Created player for URI: $uri with volume: ${player.volume}, mute: ${player["mute"]}")

        return player
    }

    /**
     * Setup event listeners for a PlayBin
     */
    private fun setupPlayerListeners(player: PlayBin) {
        val bus = player.bus
        currentBus = bus

        // End of stream
        bus.connect(eosListener)

        // Error handling
        bus.connect(errorListener)

        // Warning handling (for debugging audio issues)
        bus.connect(warningListener)

        // State changes
        bus.connect(stateChangedListener)

        // Buffering
        bus.connect(bufferingListener)

        Logger.d("GstreamerPlayerAdapter", "Player listeners setup complete")
    }

    // Bus listeners
    private val eosListener =
        Bus.EOS { _ ->
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
            handleTrackEnd()
        }

    private val errorListener =
        Bus.ERROR { _, code, message ->
            val error =
                PlayerError(
                    errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                    errorCodeName = "GSTREAMER_ERROR",
                    message = message ?: "Playback error occurred (code: $code)",
                )
            Logger.e("GstreamerPlayerAdapter", "Playback error: $message")
            listeners.forEach { it.onPlayerError(error) }
        }

    private val warningListener =
        Bus.WARNING { _, code, message ->
            Logger.w("GstreamerPlayerAdapter", "Playback warning (code: $code): $message")
        }

    private val stateChangedListener =
        Bus.STATE_CHANGED { _, oldState, newState, _ ->
            when (newState) {
                State.PLAYING -> {
                    internalIsPlaying.set(true)
                    listeners.forEach { it.onIsPlayingChanged(true) }
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                    notifyEqualizerIntent(true)
                }

                State.PAUSED -> {
                    internalIsPlaying.set(false)
                    listeners.forEach { it.onIsPlayingChanged(false) }
                    notifyEqualizerIntent(false)
                }

                State.READY -> {
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                }

                State.NULL -> {
                    internalIsPlaying.set(false)
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                    notifyEqualizerIntent(false)
                }

                else -> {}
            }
        }

    private val bufferingListener =
        Bus.BUFFERING { _, percent ->
            val isLoading = percent < 100
            listeners.forEach { it.onIsLoadingChanged(isLoading) }
        }

    /**
     * Handle track end - auto-load next track based on repeat mode
     */
    private fun handleTrackEnd() {
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> {
                // Repeat current track
                seekTo(localCurrentMediaItemIndex, 0)
            }

            PlayerConstants.REPEAT_MODE_ALL -> {
                // Go to next track (wraps around)
                if (hasNextMediaItem()) {
                    seekToNext()
                }
            }

            else -> {
                // Normal mode - go to next if available
                if (localCurrentMediaItemIndex < playlist.size - 1) {
                    seekToNext()
                } else {
                    // End of playlist
                    notifyEqualizerIntent(false)
                }
            }
        }
    }

    /**
     * Trigger precaching for upcoming tracks
     */
    private fun triggerPrecaching() {
        if (!precacheEnabled || playlist.isEmpty()) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Determine which tracks to precache
                val indicesToPrecache = mutableListOf<Int>()

                // Precache next tracks
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
                        !precachedPlayers.containsKey(nextIndex)
                    ) {
                        indicesToPrecache.add(nextIndex)
                    }
                }

                // Precache the tracks
                for (idx in indicesToPrecache) {
                    val mediaItem = playlist.getOrNull(idx) ?: continue
                    val videoId = mediaItem.uri ?: continue

                    // Extract playable URL from videoId
                    val playableUrl = extractPlayableUrl(videoId) ?: ""

                    try {
                        val player = createMediaPlayer(playableUrl)
                        // Pre-load by setting to PAUSED state (this loads the pipeline without playing)
                        player.state = State.PAUSED
                        precachedPlayers[idx] = player
                        Logger.d("GstreamerPlayerAdapter", "Precached player for index $idx")
                    } catch (e: Exception) {
                        Logger.e("GstreamerPlayerAdapter", "Precaching error for index $idx: ${e.message}")
                    }

                    // Small delay between precaching
                    delay(100)
                }
            } catch (e: Exception) {
                Logger.e("GstreamerPlayerAdapter", "Precaching error: ${e.message}")
            }
        }
    }

    /**
     * Clear all precached players except current
     */
    private fun clearPrecacheExceptCurrent() {
        precachedPlayers.entries.removeIf { (index, player) ->
            if (index != localCurrentMediaItemIndex) {
                player.stop()
                true
            } else {
                false
            }
        }
    }

    /**
     * Clear all precached players
     */
    private fun clearAllPrecache() {
        precachedPlayers.values.forEach { it.stop() }
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
            clearPrecacheExceptCurrent()
        } else {
            triggerPrecaching()
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