package com.maxrave.domain.mediaservice.player

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters

/**
 * Abstract interface for media player implementations
 */
interface MediaPlayerInterface {
    // Playback control
    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    )

    fun seekBack()

    fun seekForward()

    fun seekToNext()

    fun seekToPrevious()

    /**
     * Always advances to the previous media item, regardless of the current playback
     * position. This is the version used by UI affordances that should NOT exhibit
     * the "tap once to restart, tap again to go back" behaviour of [seekToPrevious]
     * (e.g. swiping the artwork pager). Implementations must skip the 3-second
     * "seek to start" threshold and go straight to the previous track.
     */
    fun seekToPreviousMediaItem()

    fun prepare()

    // Media item management
    fun setMediaItem(mediaItem: GenericMediaItem)

    fun addMediaItem(mediaItem: GenericMediaItem)

    fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    )

    fun removeMediaItem(index: Int)

    fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    )

    fun clearMediaItems()

    fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    )

    fun getMediaItemAt(index: Int): GenericMediaItem?

    fun getCurrentMediaTimeLine(): List<GenericMediaItem>

    fun getUnshuffledIndex(shuffledIndex: Int): Int

    // Playback state properties
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val bufferedPosition: Long
    val bufferedPercentage: Int
    val currentMediaItem: GenericMediaItem?
    val currentMediaItemIndex: Int
    val mediaItemCount: Int
    val contentPosition: Long
    val playbackState: Int

    // Navigation
    fun hasNextMediaItem(): Boolean

    fun hasPreviousMediaItem(): Boolean

    // Playback modes
    var shuffleModeEnabled: Boolean
    var repeatMode: Int
    var playWhenReady: Boolean
    var playbackParameters: GenericPlaybackParameters

    // Audio settings
    val audioSessionId: Int
    var volume: Float

    /**
     * Attenuation applied on top of [volume] while the sleep timer fades playback out,
     * in `0f..1f` — `1f` meaning no attenuation.
     *
     * Deliberately separate from [volume]: that one is the *user's* level, and it is
     * reported back through [MediaPlayerListener.onVolumeChanged], so ramping it would
     * drag the volume slider down in the UI and — if the process died mid-fade — leave
     * the user with a silent app. Same reasoning as the ducking factor applied on audio
     * focus loss: every owner keeps its own gain, and they are only multiplied together
     * at the point the value reaches a player.
     */
    var sleepFadeFactor: Float

    /**
     * Suppresses crossfade without touching the user's setting.
     *
     * Listen Together needs every device in a room to change track at the same instant; a fade
     * overlaps two tracks for seconds and drifts the room apart. Overwriting `crossfadeEnabled` in
     * DataStore instead would lose the user's real preference if the process died mid-room.
     */
    var crossfadeSuppressed: Boolean

    /**
     * `mediaId`s of the tracks that came from the album currently loaded in the queue, or empty
     * when the queue is not an album.
     *
     * Playback uses it to leave the transitions *inside* an album alone while still crossfading at
     * its edges — the last album track into the first radio track that endless queue appended, for
     * instance. A set of ids rather than a count because shuffle reorders the queue, radio included,
     * so position tells you nothing about which tracks belonged to the album.
     */
    var albumTrackIds: Set<String>
    var skipSilenceEnabled: Boolean

    /**
     * Apply a ten-band equalizer, in dB per band plus a preamp.
     *
     * Bands are the ISO octave centres 31 Hz to 16 kHz, the spacing AutoEq profiles are published
     * at. A shorter list leaves the remaining bands flat; a longer one is truncated.
     *
     * [preampDb] is normally negative and is what keeps a boosted curve from clipping — the sum of
     * several boosted bands can exceed full scale long before any single band looks extreme.
     *
     * Default no-op so a backend without an equalizer simply ignores it rather than every
     * implementation having to carry an empty override.
     */
    fun setEqualizer(
        bandsDb: List<Float>,
        preampDb: Float,
    ) = Unit

    // Listener management
    fun addListener(listener: MediaPlayerListener)

    fun removeListener(listener: MediaPlayerListener)

    // Release resources
    fun release()
}