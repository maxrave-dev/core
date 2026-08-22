package com.maxrave.domain.mediaservice.player

import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.data.player.PlayerError

/**
 * Listener interface for media player events
 */
interface MediaPlayerListener {
    fun onPlaybackStateChanged(playbackState: Int) {}

    fun onIsPlayingChanged(isPlaying: Boolean) {}

    // Default no-op so non-emitting implementors (e.g. the JVM adapter) don't have to override it.
    fun onSeeked(positionMs: Long) {}

    fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) {}

    fun onTimelineChanged(
        list: List<GenericMediaItem>, reason: String
    ) {}

    fun onTracksChanged(tracks: GenericTracks) {}

    fun onPlayerError(error: PlayerError) {}

    fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean, list: List<GenericMediaItem>) {}

    fun onRepeatModeChanged(repeatMode: Int) {}

    fun onIsLoadingChanged(isLoading: Boolean) {}

    fun onCrossfadeStateChanged(isCrossfading: Boolean) {}

    fun onVolumeChanged(volume: Float) {}

    fun onCastStateChanged(castState: GenericCastState) {}
}