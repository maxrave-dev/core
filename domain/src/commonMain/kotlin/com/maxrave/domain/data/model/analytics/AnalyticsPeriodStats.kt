package com.maxrave.domain.data.model.analytics

import kotlinx.datetime.LocalDate

/**
 * Everything the Analytics screen needs about ONE span of time.
 *
 * Returned whole rather than as a dozen separate flows, because the screen never wants one of
 * these numbers on its own: it wants this period beside the one before it, and two coherent
 * snapshots are what make a delta meaningful. A dozen flows arriving independently would let the
 * screen render a count from this week against a total from last.
 */
data class AnalyticsPeriodStats(
    val plays: Long = 0,
    val listenedSeconds: Long = 0,
    val distinctTracks: Int = 0,
    val distinctAlbums: Int = 0,
    val distinctArtists: Int = 0,
    /** Artists heard for the first time ever inside this span. */
    val newArtists: Int = 0,
    /** Days in the span that had at least one play — the denominator people actually mean. */
    val activeDays: Int = 0,
    val busiestDay: LocalDate? = null,
    val busiestDayPlays: Int = 0,
    /** 24 entries, local hours, index 0 = midnight. */
    val playsByHour: List<Int> = List(24) { 0 },
    val decades: List<DecadePlays> = emptyList(),
    /** Plays whose album carried a usable year — the decade chart's real denominator. */
    val datedPlays: Int = 0,
    val fingerprint: ListeningFingerprint = ListeningFingerprint(),
) {
    /** Plays per day over the days that had any, not over the calendar span. */
    val playsPerActiveDay: Int
        get() = if (activeDays > 0) (plays / activeDays).toInt() else 0

    /** True when there is nothing here at all, so the UI can skip the whole period. */
    val isEmpty: Boolean get() = plays == 0L
}

data class DecadePlays(
    val decade: Int,
    val plays: Int,
)

/**
 * Five readings of listening *behaviour*, each bounded 0..1 by construction.
 *
 * Last.fm draws the same shape against a global average; we have no global corpus, so the screen
 * compares it against the same span one period earlier instead. That comparison is not decoration:
 * every axis here is self-normalised, so a lone polygon says almost nothing — "consistency 0.62"
 * is neither high nor low until there is something to be high or low against.
 */
data class ListeningFingerprint(
    /** 1 − relative spread of daily play counts. Even listening every day scores high. */
    val consistency: Float = 0f,
    /** New artists as a share of the artists heard. */
    val discovery: Float = 0f,
    /** Normalised entropy over per-artist play counts. Many artists, evenly, scores high. */
    val diversity: Float = 0f,
    /** Share of plays landing on the top five artists. */
    val concentration: Float = 0f,
    /** 1 − distinct tracks / plays. Replaying the same songs scores high. */
    val replay: Float = 0f,
) {
    /** In the order the radar draws them, clockwise from the top. */
    val axes: List<Float>
        get() = listOf(consistency, discovery, diversity, concentration, replay)
}
