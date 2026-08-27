package com.maxrave.data.repository

import com.maxrave.data.db.datasource.AnalyticsDatasource
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.analytics.query.TopPlayedAlbum
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtist
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtistTime
import com.maxrave.domain.data.entities.analytics.query.TopPlayedTracks
import com.maxrave.domain.data.model.analytics.AnalyticsPeriodStats
import com.maxrave.domain.data.model.analytics.DecadePlays
import com.maxrave.domain.data.model.analytics.ListeningFingerprint
import com.maxrave.domain.repository.AnalyticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime

private const val TAG = "AnalyticsRepositoryImpl"

internal class AnalyticsRepositoryImpl(
    private val analyticsDatasource: AnalyticsDatasource,
) : AnalyticsRepository {
    override suspend fun insertPlaybackEvent(
        videoId: String,
        channelIds: List<String>,
        albumBrowseId: String?,
        durationSecond: Long,
        listenedSecond: Long,
    ): Flow<Long> =
        flow {
            emit(
                analyticsDatasource.insertPlaybackEvent(
                    videoId,
                    channelIds,
                    albumBrowseId,
                    durationSecond,
                    listenedSecond,
                ),
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun getPlaybackEventsByOffset(
        offset: Int,
        limit: Int,
    ): Flow<List<PlaybackEventEntity>> =
        flow {
            emit(analyticsDatasource.getPlaybackEventsByOffset(offset, limit))
        }.flowOn(Dispatchers.IO)

    override suspend fun getPlaybackEventsByOffsetAndTimestamp(
        offset: Int,
        limit: Int,
        cutoffTimestamp: LocalDateTime,
    ): Flow<List<PlaybackEventEntity>> =
        flow {
            emit(analyticsDatasource.getPlaybackEventsByOffsetAndTimestamp(offset, limit, cutoffTimestamp))
        }.flowOn(Dispatchers.IO)

    override suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime) =
        withContext(Dispatchers.IO) {
            analyticsDatasource.deleteOldPlaybackEvents(cutoffTimestamp)
        }

    // Query methods for analytics reports

    override suspend fun queryTopPlayedSongsLastXDays(x: Int): Flow<List<TopPlayedTracks>> =
        flow {
            emit(analyticsDatasource.queryTopPlayedSongsLastXDays(x))
        }.flowOn(Dispatchers.IO)

    override suspend fun queryTopPlayedSongsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedTracks>> =
        flow {
            emit(
                analyticsDatasource.queryTopPlayedSongsInRange(
                    startTimestamp,
                    endTimestamp,
                ),
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun queryTopArtistsLastXDays(x: Int): Flow<List<TopPlayedArtist>> =
        flow {
            emit(analyticsDatasource.queryTopArtistsLastXDays(x))
        }.flowOn(Dispatchers.IO)

    override suspend fun queryTopArtistsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedArtist>> =
        flow {
            emit(
                analyticsDatasource.queryTopArtistsInRange(
                    startTimestamp,
                    endTimestamp,
                ),
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun queryTopArtistsWithTimeInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedArtistTime>> =
        flow {
            emit(
                analyticsDatasource.queryTopArtistsWithTimeInRange(
                    startTimestamp,
                    endTimestamp,
                ),
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun queryTopAlbumsLastXDays(x: Int): Flow<List<TopPlayedAlbum>> =
        flow {
            emit(analyticsDatasource.queryTopAlbumsLastXDays(x))
        }.flowOn(Dispatchers.IO)

    override suspend fun queryTopAlbumsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<List<TopPlayedAlbum>> =
        flow {
            emit(
                analyticsDatasource.queryTopAlbumsInRange(
                    startTimestamp,
                    endTimestamp,
                ),
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun getTotalPlaybackEventCount(): Flow<Long> =
        flow {
            emit(analyticsDatasource.getTotalPlaybackEventCount())
        }.flowOn(Dispatchers.IO)

    override suspend fun getTotalEventArtistCount(): Flow<Long> =
        flow {
            emit(analyticsDatasource.getTotalEventArtistCount())
        }.flowOn(Dispatchers.IO)

    override suspend fun getTotalListeningTimeInSeconds(): Flow<Long> =
        flow {
            emit(analyticsDatasource.getTotalListeningTimeInSeconds())
        }.flowOn(Dispatchers.IO)

    override suspend fun getPlaybackEventCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Flow<Long> =
        flow {
            emit(
                analyticsDatasource.getPlaybackEventCountInRange(
                    startTimestamp,
                    endTimestamp,
                ),
            )
        }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalTime::class)
    override suspend fun getPeriodStats(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): AnalyticsPeriodStats =
        withContext(Dispatchers.IO) {
            val samples = analyticsDatasource.getPlaybackSamplesInRange(startTimestamp, endTimestamp)
            if (samples.isEmpty()) return@withContext AnalyticsPeriodStats()

            // No timezone arithmetic here on purpose. PlaybackSample.timestamp is a LocalDateTime,
            // so Room's converter has already handed back the wall clock the listener saw, and the
            // hour and the date read straight off it. Decoding the raw column by hand is what put
            // this screen's busiest hour at 03:00 on a UTC+7 machine.
            val hours = IntArray(24)
            val perDay = mutableMapOf<LocalDate, Int>()
            var listened = 0L
            samples.forEach { sample ->
                hours[sample.timestamp.hour]++
                perDay[sample.timestamp.date] = (perDay[sample.timestamp.date] ?: 0) + 1
                listened += sample.listenedSecond
            }
            val busiest = perDay.maxByOrNull { it.value }

            val distinctTracks = analyticsDatasource.getDistinctTrackCountInRange(startTimestamp, endTimestamp)
            val distinctAlbums = analyticsDatasource.getDistinctAlbumCountInRange(startTimestamp, endTimestamp)
            val distinctArtists = analyticsDatasource.getDistinctArtistCountInRange(startTimestamp, endTimestamp)
            val newArtists = analyticsDatasource.getNewArtistCountInRange(startTimestamp, endTimestamp)
            val artistPlays = analyticsDatasource.getArtistPlayCountsInRange(startTimestamp, endTimestamp)
            val decades = analyticsDatasource.getDecadeCountsInRange(startTimestamp, endTimestamp)
            val datedPlays = analyticsDatasource.getDatedPlayCountInRange(startTimestamp, endTimestamp)

            AnalyticsPeriodStats(
                plays = samples.size.toLong(),
                listenedSeconds = listened,
                distinctTracks = distinctTracks,
                distinctAlbums = distinctAlbums,
                distinctArtists = distinctArtists,
                newArtists = newArtists,
                activeDays = perDay.size,
                busiestDay = busiest?.key,
                busiestDayPlays = busiest?.value ?: 0,
                playsByHour = hours.toList(),
                decades = decades.map { DecadePlays(it.decade, it.playCount) },
                datedPlays = datedPlays,
                fingerprint =
                    fingerprintOf(
                        dailyCounts = perDay.values.toList(),
                        artistPlays = artistPlays.map { it.playCount },
                        newArtists = newArtists,
                        distinctArtists = distinctArtists,
                        distinctTracks = distinctTracks,
                        plays = samples.size,
                    ),
            )
        }

    /**
     * The five behaviour axes, each bounded 0..1 by construction so no external scale is needed.
     *
     * Every one of them divides by something that can be zero on a thin period, so each guards its
     * own denominator rather than relying on the caller to have enough data.
     */
    private fun fingerprintOf(
        dailyCounts: List<Int>,
        artistPlays: List<Int>,
        newArtists: Int,
        distinctArtists: Int,
        distinctTracks: Int,
        plays: Int,
    ): ListeningFingerprint {
        // Relative spread of the daily counts, inverted: listening a little every day scores high,
        // one big binge and six silent days scores low.
        val consistency =
            if (dailyCounts.size < 2) {
                0f
            } else {
                val mean = dailyCounts.average()
                if (mean <= 0.0) {
                    0f
                } else {
                    val variance = dailyCounts.sumOf { (it - mean) * (it - mean) } / dailyCounts.size
                    (1.0 - sqrt(variance) / mean).coerceIn(0.0, 1.0).toFloat()
                }
            }

        val artistTotal = artistPlays.sum()
        // Normalised Shannon entropy. ln(n) is the entropy of a perfectly even spread over the same
        // number of artists, so dividing by it asks "how even is this, for its size" rather than
        // "how many artists" — otherwise a bigger library would always look more diverse.
        val diversity =
            if (artistTotal <= 0 || artistPlays.size < 2) {
                0f
            } else {
                val entropy =
                    artistPlays.sumOf { count ->
                        val p = count.toDouble() / artistTotal
                        if (p > 0.0) -p * ln(p) else 0.0
                    }
                (entropy / ln(artistPlays.size.toDouble())).coerceIn(0.0, 1.0).toFloat()
            }

        val concentration =
            if (artistTotal <= 0) 0f else (artistPlays.take(5).sum().toFloat() / artistTotal).coerceIn(0f, 1f)

        return ListeningFingerprint(
            consistency = consistency,
            discovery = if (distinctArtists <= 0) 0f else (newArtists.toFloat() / distinctArtists).coerceIn(0f, 1f),
            diversity = diversity,
            concentration = concentration,
            replay = if (plays <= 0) 0f else (1f - distinctTracks.toFloat() / plays).coerceIn(0f, 1f),
        )
    }
}