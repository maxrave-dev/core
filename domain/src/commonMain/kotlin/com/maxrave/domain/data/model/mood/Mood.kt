package com.maxrave.domain.data.model.mood

import kotlinx.serialization.Serializable

/**
 * "Moods & Genres" browse result, kept as the list of sections YouTube actually returned.
 *
 * Deliberately NOT split into fixed `moodsMoments` / `genres` fields: the number and order of
 * sections is not stable. A signed-in account gets an extra "For you" section pushed in front,
 * which shifted every index by one — the app then labelled "For you" as Moods, "Moods & moments"
 * as Genres, and dropped the real Genres section entirely. ytmusicapi keys its result by section
 * title for exactly this reason.
 */
@Serializable
data class Mood(
    val sections: List<MoodSection>,
)

@Serializable
data class MoodSection(
    /** Section heading exactly as YouTube localised it, e.g. "Genres" / "Dòng nhạc". */
    val title: String,
    val items: List<MoodItem>,
)

@Serializable
data class MoodItem(
    val title: String,
    val params: String,
    /** `solid.leftStripeColor` from the API — full ARGB, so it maps straight onto Compose Color. */
    val stripeColor: Long,
)
