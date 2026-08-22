package com.maxrave.domain.data.entities.analytics.query

import androidx.room.ColumnInfo
import kotlinx.datetime.LocalDateTime

/**
 * One play, reduced to the two columns every derived statistic needs.
 *
 * [timestamp] is a `LocalDateTime` so that Room's converter decodes it, and nothing here has to
 * know how the column is encoded. That is not a detail worth re-deriving: `Converters` writes every
 * LocalDateTime with `toInstant(TimeZone.UTC)`, so the column holds the local WALL CLOCK dressed up
 * as UTC — reading it as a raw `Long` and decoding it with the machine's own zone applies the
 * offset a second time, which is a silent seven-hour error in Vietnam and a silent zero-hour one in
 * London. Asking for the type the converter understands removes the choice, and the mistake with it.
 */
data class PlaybackSample(
    @ColumnInfo(name = "timestamp") val timestamp: LocalDateTime,
    @ColumnInfo(name = "listenedSecond") val listenedSecond: Long,
)

/**
 * Plays whose album carries a release year, bucketed by decade.
 *
 * Only plays that have an `albumBrowseId` AND an album row with a parseable year can be counted —
 * radio and stray videos have neither — so this is always a partial view. The count of plays that
 * could be dated is reported alongside it, because a distribution that silently omits an unknown
 * share of its input is not a distribution.
 */
data class DecadeCount(
    @ColumnInfo(name = "decade") val decade: Int,
    @ColumnInfo(name = "playCount") val playCount: Int,
)
