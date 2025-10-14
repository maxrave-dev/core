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
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * JavaFX MediaPlayer implementation of MediaPlayerInterface
 * Features:
 * - Queue management with auto-load for next track
 * - Precaching system for smooth transitions
 * - Thread-safe operations
 */
class JavaFxPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    private val listeners = mutableListOf<MediaPlayerListener>()

    private var currentPlayer: MediaPlayer? = null

    fun getCurrentPlayer(): MediaPlayer? = currentPlayer

    // Precaching system
    private val precachedPlayers = ConcurrentHashMap<Int, MediaPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2 // Precache current + next 2 tracks

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Playback state
    private var internalIsPlaying = false
    private var internalPlayWhenReady = false
    private var internalVolume = 1.0f
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF
    private var internalShuffleModeEnabled = false
    private var internalPlaybackSpeed = 1.0f

    init {
        // Ensure JavaFX is initialized
        try {
            Platform.startup { }
        } catch (e: IllegalStateException) {
            // Already initialized
        }
    }

    // ========== Playback Control ==========

    override fun play() {
        Platform.runLater {
            currentPlayer?.let { player ->
                player.play()
                internalIsPlaying = true
                internalPlayWhenReady = true
                listeners.forEach { it.onIsPlayingChanged(true) }
                notifyEqualizerIntent(true)
            }
        }
    }

    override fun pause() {
        Platform.runLater {
            currentPlayer?.let { player ->
                player.pause()
                internalIsPlaying = false
                internalPlayWhenReady = false
                listeners.forEach { it.onIsPlayingChanged(false) }
                notifyEqualizerIntent(false)
            }
        }
    }

    override fun stop() {
        Platform.runLater {
            currentPlayer?.let { player ->
                player.stop()
                internalIsPlaying = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                notifyEqualizerIntent(false)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        Platform.runLater {
            currentPlayer?.seek(Duration.millis(positionMs.toDouble()))
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex !in playlist.indices) return

        Platform.runLater {
            val wasPlaying = internalIsPlaying

            // Stop current player
            currentPlayer?.stop()

            // Load the new track
            localCurrentMediaItemIndex = mediaItemIndex
            loadAndPlayTrack(mediaItemIndex, positionMs, wasPlaying || internalPlayWhenReady)
        }
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
            Platform.runLater {
                loadAndPlayTrack(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        playlist.clear()
        precachedPlayers.clear()
        playlist.add(mediaItem)
        localCurrentMediaItemIndex = 0

        Platform.runLater {
            loadAndPlayTrack(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)

        if (localCurrentMediaItemIndex == -1) {
            localCurrentMediaItemIndex = 0
            Platform.runLater {
                loadAndPlayTrack(0, 0, internalPlayWhenReady)
            }
        } else {
            // Trigger precaching for newly added item if it's next
            triggerPrecaching()
        }
    }

    override fun addMediaItem(index: Int, mediaItem: GenericMediaItem) {
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
        precachedPlayers.remove(index)

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
                    Platform.runLater {
                        loadAndPlayTrack(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    }
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

    override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return

        val item = playlist.removeAt(fromIndex)
        playlist.add(toIndex, item)

        // Update current index
        localCurrentMediaItemIndex = when {
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

        Platform.runLater {
            currentPlayer?.stop()
            currentPlayer = null
            clearAllPrecache()
        }
    }

    override fun replaceMediaItem(index: Int, mediaItem: GenericMediaItem) {
        if (index !in playlist.indices) return

        playlist[index] = mediaItem

        // Remove from precache
        precachedPlayers.remove(index)

        if (index == localCurrentMediaItemIndex) {
            Platform.runLater {
                loadAndPlayTrack(index, 0, internalPlayWhenReady)
            }
        } else {
            triggerPrecaching()
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? {
        return playlist.getOrNull(index)
    }

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = internalIsPlaying

    override val currentPosition: Long
        get() = currentPlayer?.currentTime?.toMillis()?.toLong() ?: 0L

    override val duration: Long
        get() = currentPlayer?.totalDuration?.toMillis()?.toLong() ?: 0L

    override val bufferedPosition: Long
        get() = currentPlayer?.bufferProgressTime?.toMillis()?.toLong() ?: 0L

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
        get() = when (currentPlayer?.status) {
            MediaPlayer.Status.READY, MediaPlayer.Status.PAUSED, MediaPlayer.Status.PLAYING ->
                PlayerConstants.STATE_READY
            MediaPlayer.Status.STOPPED -> PlayerConstants.STATE_ENDED
            MediaPlayer.Status.DISPOSED -> PlayerConstants.STATE_IDLE
            else -> PlayerConstants.STATE_IDLE
        }

    // ========== Navigation ==========

    override fun hasNextMediaItem(): Boolean {
        return when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex < playlist.size - 1
        }
    }

    override fun hasPreviousMediaItem(): Boolean {
        return when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex > 0
        }
    }

    private fun getNextMediaItemIndex(): Int {
        return when (internalRepeatMode) {
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
    }

    private fun getPreviousMediaItemIndex(): Int {
        return when (internalRepeatMode) {
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
            Platform.runLater {
                currentPlayer?.rate = value.speed.toDouble()
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = 0 // JavaFX doesn't provide audio session ID

    override var volume: Float
        get() = internalVolume
        set(value) {
            internalVolume = value.coerceIn(0f, 1f)
            Platform.runLater {
                currentPlayer?.volume = internalVolume.toDouble()
            }
        }

    override var skipSilenceEnabled: Boolean = false
        // JavaFX doesn't support skip silence

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        Platform.runLater {
            currentPlayer?.dispose()
            currentPlayer = null
            clearAllPrecache()
        }

        listeners.clear()
    }

    // ========== Internal Methods ==========

    /**
     * Load and play a track at the specified index
     * This method handles switching from precached player or creating new one
     */
    private fun loadAndPlayTrack(index: Int, startPositionMs: Long, shouldPlay: Boolean) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.uri ?: return

        coroutineScope.launch(Dispatchers.IO) {

            // Extract playable URL from videoId (will be implemented)
            // For now, using videoId directly - TODO: implement extractPlayableUrl
            val playableUrl = extractPlayableUrl(videoId) ?: return@launch // TODO: Replace with: runBlocking { extractPlayableUrl(videoId) ?: return }

            // Check if we have a precached player for this index
            val player = precachedPlayers.remove(index) ?: createMediaPlayer(playableUrl)

            // Stop and dispose current player
            currentPlayer?.stop()
            currentPlayer?.dispose()

            // Set up the new player
            currentPlayer = player
            setupPlayerListeners(player)

            // Apply current settings
            player.volume = internalVolume.toDouble()
            player.rate = internalPlaybackSpeed.toDouble()

            // Seek to start position if needed
            if (startPositionMs > 0) {
                player.seek(Duration.millis(startPositionMs.toDouble()))
            }

            // Play if needed
            if (shouldPlay) {
                player.play()
                internalIsPlaying = true
            }

            // Notify listeners
            listeners.forEach {
                it.onMediaItemTransition(
                    mediaItem,
                    PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
            }

            // Trigger precaching for upcoming tracks
            triggerPrecaching()
        }
    }

    /**
     * Create a new MediaPlayer instance
     */
    private fun createMediaPlayer(uri: String): MediaPlayer {
        val media = Media(uri)
        val player = MediaPlayer(media)

        // Set to not auto-play
        player.setAutoPlay(false)

        return player
    }

    /**
     * Setup event listeners for a MediaPlayer
     */
    private fun setupPlayerListeners(player: MediaPlayer) {
        player.setOnReady {
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
        }

        player.setOnPlaying {
            internalIsPlaying = true
            listeners.forEach { it.onIsPlayingChanged(true) }
            notifyEqualizerIntent(true)
        }

        player.setOnPaused {
            internalIsPlaying = false
            listeners.forEach { it.onIsPlayingChanged(false) }
            notifyEqualizerIntent(false)
        }

        player.setOnStopped {
            internalIsPlaying = false
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
            notifyEqualizerIntent(false)
        }

        player.setOnEndOfMedia {
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }

            // Auto-load next track
            handleTrackEnd()
        }

        player.setOnError {
            val error = PlayerError(
                errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                errorCodeName = "JAVAFX_MEDIA_ERROR",
                message = player.error?.message ?: "Playback error occurred"
            )
            Logger.e("JavaFxPlayerAdapter", "Playback error: ${player.error}")
            listeners.forEach { it.onPlayerError(error) }
        }

        // Buffer progress monitoring
        player.bufferProgressTimeProperty().addListener { _, _, newValue ->
            val bufferedMs = newValue.toMillis().toLong()
            val durationMs = duration
            if (durationMs > 0) {
                val isLoading = bufferedMs < durationMs
                listeners.forEach { it.onIsLoadingChanged(isLoading) }
            }
        }
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
                    val nextIndex = when (internalRepeatMode) {
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
                    val playableUrl = extractPlayableUrl(videoId) ?: continue

                    withContext(Dispatchers.Main) {
                        try {
                            val player = createMediaPlayer(playableUrl)
                            precachedPlayers[idx] = player
                        } catch (e: Exception) {
                            Logger.e("JavaFxPlayerAdapter", "Precaching error for index $idx: ${e.message}")
                        }
                    }

                    // Small delay between precaching
                    delay(100)
                }
            } catch (e: Exception) {
                Logger.e("JavaFxPlayerAdapter", "Precaching error: ${e.message}")
            }
        }
    }

    /**
     * Clear all precached players except current
     */
    private fun clearPrecacheExceptCurrent() {
        Platform.runLater {
            precachedPlayers.entries.removeIf { (index, player) ->
                if (index != localCurrentMediaItemIndex) {
                    player.dispose()
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Clear all precached players
     */
    private fun clearAllPrecache() {
        precachedPlayers.values.forEach { it.dispose() }
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
            val videoUrl = it.videoUrl
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
                Logger.w("Stream", "Video")
                return it
            }
        return null
    }
}
