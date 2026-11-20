package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
    suspend fun insertNewFormat(newFormat: NewFormatEntity)

    fun getNewFormat(videoId: String): Flow<NewFormatEntity?>

    suspend fun getFormatFlow(videoId: String): Flow<NewFormatEntity?>

    suspend fun updateFormat(videoId: String)

    fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean = false, // m3u8 or mp4 (both contain audio and video)
    ): Flow<String?>

    fun initPlayback(
        playback: String,
        atr: String,
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Pair<Int, Float>>

    fun updateWatchTimeFull(
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Int>

    fun updateWatchTime(
        playbackTrackingVideostatsWatchtimeUrl: String,
        watchTimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Flow<Int>

    fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>>

    fun getFullMetadata(videoId: String): Flow<Resource<Track>>

    fun is403Url(url: String): Flow<Boolean>

    suspend fun invalidateFormat(videoId: String)

    /**
     * Forget every stored stream format, so the next play of anything re-resolves its
     * stream from scratch. Used when the audio-quality setting changes: a stored format
     * still points at the stream resolved under the old quality, and the resolver serves
     * it for as long as it has not expired. A default no-op keeps the other
     * implementations (desktop, tests) compiling unchanged.
     */
    suspend fun invalidateAllFormats() {}

    /**
     * Same as [invalidateAllFormats], preserving entries for [exceptVideoIds] so an
     * actively playing track is not invalidated mid-stream.
     */
    suspend fun invalidateAllFormatsExcept(exceptVideoIds: Set<String>) {
        invalidateAllFormats()
    }

    /**
     * Returns and clears the last unrecoverable resolution reason (such as a YouTube refusal)
     * for [videoId], so the player retry policy can fail fast instead of burning outage retries.
     */
    fun consumeLastUnrecoverableError(videoId: String): String? = null

    /** Notifies the extractor that a resolved stream for [videoId] failed playback, revoking HEAD trust. */
    fun onStreamRejected(videoId: String) {}

    /**
     * Warm the stream decipher pipeline (current player id, remote player table, player
     * script, QuickJS solver) ahead of the first play, so the first resolution does not
     * pay that one-time setup inside the play path. A default no-op keeps the other
     * implementations (desktop, tests) compiling unchanged.
     */
    suspend fun warmUpDecoder() {}

    /**
     * Which extractor and cipher decoder produced this video's stream URLs, for the info sheet.
     *
     * Null until the video has actually been extracted in this run — it is not persisted, because it
     * describes one extraction rather than the format row, which outlives it in the cache.
     */
    fun getExtractSource(videoId: String): String?
}