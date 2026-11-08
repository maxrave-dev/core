package com.maxrave.domain.data.entities.analytics.query

import androidx.room.ColumnInfo

/**
 * A top artist with the time spent on them, not just the number of plays.
 *
 * Separate from [TopPlayedArtist] rather than a column added to it: `event_artist` alone carries no
 * seconds, so the queries that read that table on its own — the top-five list and the unbounded
 * per-artist counts behind the fingerprint — cannot select this column, and Room refuses a result
 * class holding a column the query does not return.
 *
 * [totalListeningTime] is time spent WITH this artist, so a track credited to several of them gives
 * its full `listenedSecond` to each. The column therefore does not sum to the period's total, and
 * that is intended — not a double-count to be fixed.
 */
data class TopPlayedArtistTime(
    @ColumnInfo(name = "channelId") val channelId: String,
    @ColumnInfo(name = "playCount") val playCount: Int,
    @ColumnInfo(name = "totalListeningTime") val totalListeningTime: Long,
)
