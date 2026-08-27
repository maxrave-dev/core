package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.model.analytics.AnalyticsPeriodStats
import com.maxrave.domain.data.entities.analytics.query.TopPlayedAlbum
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtist
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtistTime
import com.maxrave.domain.data.entities.analytics.query.TopPlayedTracks
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface AnalyticsRepository {
    suspend fun insertPlaybackEvent(
        videoId: String,
        channelIds: List<String>,
        albumBrowseId: String?,
        durationSecond: Long,
        listenedSecond: Long,
    ): Flow<Long>

    suspend fun getPlaybackEventsByOffset(
        offset: Int,
        limit: Int,
    ): Flow<List<PlaybackEventEntity>>

    suspend fun getPlaybackEventsByOffsetAndTimestamp(
        offset: Int,
        limit: Int,
        cutoffTimestamp: LocalDateTime,
    ): Flow<List<PlaybackEventEntity>>

    suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime)

    // Query methods for analytics reports
    suspend fun queryTopPlayedSongsLastXDays(x: Int): Flow<List<TopPlayedTracks>>

    suspend fun queryTopPlayedSongsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedTracks>>

    suspend fun queryTopArtistsLastXDays(x: Int): Flow<List<TopPlayedArtist>>

    suspend fun queryTopArtistsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedArtist>>

    /**
     * The same ranking as [queryTopArtistsInRange], with the seconds spent on each artist.
     *
     * Separate because the seconds cost a join back to `playback_event`, which the top-five list
     * and the fingerprint's per-artist counts have no use for. Time is credited in full to every
     * artist on a track, so it means "time spent with this artist" and does not sum to the period.
     */
    suspend fun queryTopArtistsWithTimeInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedArtistTime>>

    suspend fun queryTopAlbumsLastXDays(x: Int): Flow<List<TopPlayedAlbum>>

    suspend fun queryTopAlbumsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedAlbum>>

    suspend fun getTotalPlaybackEventCount(): Flow<Long>

    suspend fun getTotalEventArtistCount(): Flow<Long>

    suspend fun getTotalListeningTimeInSeconds(): Flow<Long>

    suspend fun getPlaybackEventCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<Long>

    /**
     * One coherent snapshot of a span, rather than a dozen flows the caller has to line up.
     *
     * Suspending, not a Flow: the screen asks for exactly two of these — the period on screen and
     * the one before it — and needs them as a matched pair. Ten independent flows would let a
     * count from this week render against a total from last.
     */
    suspend fun getPeriodStats(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): AnalyticsPeriodStats
}