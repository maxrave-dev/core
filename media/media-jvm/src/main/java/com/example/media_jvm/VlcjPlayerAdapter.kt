package com.example.media_jvm

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import uk.co.caprica.vlcj.media.MediaRef
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State

/**
 * VLCJ implementation of MediaPlayerInterface
 * Handles all VLCJ-specific logic and conversions
 */
class VlcjPlayerAdapter(
    private val vlcPlayer: MediaPlayer,
) : MediaPlayerInterface {
    private val listeners = mutableListOf<MediaPlayerListener>()
    private val vlcjPlayerListener = VlcjPlayerListenerImpl()

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    init {
        vlcPlayer.events().addMediaPlayerEventListener(vlcjPlayerListener)
    }

    // Playback control
    override fun play() = vlcPlayer.controls().play()

    override fun pause() = vlcPlayer.controls().pause()

    override fun stop() = vlcPlayer.controls().stop()

    override fun seekTo(positionMs: Long) {
        vlcPlayer.controls().setTime(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex in playlist.indices) {
            localCurrentMediaItemIndex = mediaItemIndex
            val item = playlist[mediaItemIndex]
            item.uri?.let {
                vlcPlayer.media().play(it)
                vlcPlayer.controls().setTime(positionMs)
            }
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
            val item = playlist[localCurrentMediaItemIndex]
            item.uri?.let { vlcPlayer.media().prepare(it) }
        }
    }

    // Media item management
    override fun setMediaItem(mediaItem: GenericMediaItem) {
        playlist.clear()
        playlist.add(mediaItem)
        localCurrentMediaItemIndex = 0
        mediaItem.uri?.let { vlcPlayer.media().play(it) }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)
        if (localCurrentMediaItemIndex == -1) {
            localCurrentMediaItemIndex = 0
            mediaItem.uri?.let { vlcPlayer.media().play(it) }
        }
    }

    override fun addMediaItem(index: Int, mediaItem: GenericMediaItem) {
        if (index in 0..playlist.size) {
            playlist.add(index, mediaItem)
            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        if (index in playlist.indices) {
            playlist.removeAt(index)
            when {
                index < localCurrentMediaItemIndex -> localCurrentMediaItemIndex--
                index == localCurrentMediaItemIndex -> {
                    if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    if (localCurrentMediaItemIndex >= 0) {
                        playlist[localCurrentMediaItemIndex].uri?.let {
                            vlcPlayer.media().play(it)
                        }
                    }
                }
            }
        }
    }

    override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex in playlist.indices && toIndex in playlist.indices) {
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            localCurrentMediaItemIndex = when {
                localCurrentMediaItemIndex == fromIndex -> toIndex
                fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> localCurrentMediaItemIndex - 1
                fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> localCurrentMediaItemIndex + 1
                else -> localCurrentMediaItemIndex
            }
        }
    }

    override fun clearMediaItems() {
        playlist.clear()
        localCurrentMediaItemIndex = -1
        vlcPlayer.controls().stop()
    }

    override fun replaceMediaItem(index: Int, mediaItem: GenericMediaItem) {
        if (index in playlist.indices) {
            playlist[index] = mediaItem
            if (index == localCurrentMediaItemIndex) {
                mediaItem.uri?.let { vlcPlayer.media().play(it) }
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? {
        return playlist.getOrNull(index)
    }

    // Playback state properties
    override val isPlaying: Boolean get() = vlcPlayer.status().isPlaying

    override val currentPosition: Long get() = vlcPlayer.status().time()

    override val duration: Long get() = vlcPlayer.status().length()

    override val bufferedPosition: Long get() = duration // VLCJ doesn't provide direct buffered position

    override val bufferedPercentage: Int get() = 100 // VLCJ doesn't provide buffered percentage

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int get() = playlist.size

    override val contentPosition: Long get() = currentPosition

    override val playbackState: Int
        get() = when (vlcPlayer.status().state()) {
            State.NOTHING_SPECIAL, State.STOPPED -> PlayerConstants.STATE_IDLE
            State.ENDED -> PlayerConstants.STATE_ENDED
            State.PLAYING, State.PAUSED -> PlayerConstants.STATE_READY
            else -> PlayerConstants.STATE_IDLE
        }

    // Navigation
    override fun hasNextMediaItem(): Boolean {
        return when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex < playlist.size - 1
        }
    }

    override fun hasPreviousMediaItem(): Boolean {
        return when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex > 0
        }
    }

    private fun getNextMediaItemIndex(): Int {
        return when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> localCurrentMediaItemIndex
            PlayerConstants.REPEAT_MODE_ALL -> if (localCurrentMediaItemIndex < playlist.size - 1) {
                localCurrentMediaItemIndex + 1
            } else {
                0
            }
            else -> (localCurrentMediaItemIndex + 1).coerceAtMost(playlist.size - 1)
        }
    }

    private fun getPreviousMediaItemIndex(): Int {
        return when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> localCurrentMediaItemIndex
            PlayerConstants.REPEAT_MODE_ALL -> if (localCurrentMediaItemIndex > 0) {
                localCurrentMediaItemIndex - 1
            } else {
                playlist.size - 1
            }
            else -> (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
        }
    }

    // Playback modes
    override var shuffleModeEnabled: Boolean = false
        set(value) {
            field = value
            // Shuffle logic would need to be implemented
        }

    override var repeatMode: Int = PlayerConstants.REPEAT_MODE_OFF

    override var playWhenReady: Boolean = false
        set(value) {
            field = value
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters = GenericPlaybackParameters(1.0f, 1.0f)
        get() = GenericPlaybackParameters(field.speed, field.pitch)
        set(value) {
            field = value
            vlcPlayer.controls().setRate(value.speed)
        }

    // Audio settings
    override val audioSessionId: Int get() = 0 // VLCJ doesn't have audio session ID

    override var volume: Float
        get() = vlcPlayer.audio().volume() / 100.0f
        set(value) {
            vlcPlayer.audio().setVolume((value * 100).toInt())
        }

    override var skipSilenceEnabled: Boolean = false // VLCJ doesn't support skip silence

    // Listener management
    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // Release resources
    override fun release() {
        vlcPlayer.events().removeMediaPlayerEventListener(vlcjPlayerListener)
        listeners.clear()
        vlcPlayer.release()
    }

    // Internal VLCJ listener that converts events to generic events
    private inner class VlcjPlayerListenerImpl : MediaPlayerEventAdapter() {

        override fun playing(mediaPlayer: MediaPlayer) {
            listeners.forEach { it.onIsPlayingChanged(true) }
            notifyEqualizerIntent(true)
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            listeners.forEach { it.onIsPlayingChanged(false) }
            notifyEqualizerIntent(false)
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
            notifyEqualizerIntent(false)
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }

            // Handle repeat/next item
            when (repeatMode) {
                PlayerConstants.REPEAT_MODE_ONE -> seekTo(localCurrentMediaItemIndex, 0)
                else -> if (hasNextMediaItem()) seekToNext()
            }

            notifyEqualizerIntent(false)
        }

        override fun error(mediaPlayer: MediaPlayer) {
            val error = PlayerError(
                errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                errorCodeName = "VLCJ_ERROR",
                message = "Playback error occurred"
            )
            listeners.forEach { it.onPlayerError(error) }
        }

        override fun mediaChanged(mediaPlayer: MediaPlayer, media: MediaRef) {
            val currentItem = currentMediaItem
            listeners.forEach {
                it.onMediaItemTransition(
                    currentItem,
                    PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
            }
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            val isLoading = newCache < 100.0f
            listeners.forEach { it.onIsLoadingChanged(isLoading) }
        }

        private fun notifyEqualizerIntent(shouldOpen: Boolean) {
            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(shouldOpen) }
        }
    }
}