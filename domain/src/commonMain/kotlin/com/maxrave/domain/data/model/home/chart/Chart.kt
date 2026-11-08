package com.maxrave.domain.data.model.home.chart

data class Chart(
    val artists: Artists,
    val countries: Countries?,
    val listChartItem: List<ChartItemPlaylist>,
    // Null where the country's chart carries no podcast shelf (only the US one does, so far).
    val podcasts: ChartPodcasts? = null,
)

/**
 * A ranked shelf of podcast shows — the US chart's "Weekly top podcast shows". Its rows have the
 * artist chart's shape (rank, title, thumbnail, browseId), so they reuse [ItemArtist]; there,
 * [ItemArtist.subscribers] carries the show's author instead of a subscriber count.
 */
data class ChartPodcasts(
    val title: String,
    val shows: List<ItemArtist>,
)
