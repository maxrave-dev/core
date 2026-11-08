package com.maxrave.domain.data.model.home

import com.maxrave.domain.data.model.mood.MoodItem

/**
 * The page a home section's "More" endpoint opens when YouTube does not name it an album,
 * playlist, artist or podcast. Every shelf is flattened into one list, as Metrolist's
 * YouTubeBrowseScreen does, so [contents] carries no section titles.
 */
data class BrowsePage(
    val title: String?,
    val contents: List<Content>,
    /** Mood & genre buttons (`musicNavigationButtonRenderer`) — all that `FEmusic_moods_and_genres` holds. */
    val moods: List<MoodItem>,
)
