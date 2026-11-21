package com.maxrave.data.repository

import com.maxrave.data.parser.parseArtistChart
import com.maxrave.kotlinytmusicscraper.models.MusicCarouselShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChartParserTest {
    @Test
    fun parseArtistChartHandlesMissingFlexColumnsAndEmptyRuns() {
        val emptyRow =
            MusicCarouselShelfRenderer.Content(
                musicTwoRowItemRenderer = null,
                musicResponsiveListItemRenderer =
                    MusicResponsiveListItemRenderer(
                        badges = null,
                        fixedColumns = null,
                        flexColumns = emptyList(),
                        thumbnail = null,
                        menu = null,
                        playlistItemData = null,
                        overlay = null,
                        navigationEndpoint = null,
                    ),
                musicNavigationButtonRenderer = null,
                musicMultiRowListItemRenderer = null,
            )

        val singleColumnEmptyRunsRow =
            MusicCarouselShelfRenderer.Content(
                musicTwoRowItemRenderer = null,
                musicResponsiveListItemRenderer =
                    MusicResponsiveListItemRenderer(
                        badges = null,
                        fixedColumns = null,
                        flexColumns =
                            listOf(
                                MusicResponsiveListItemRenderer.FlexColumn(
                                    musicResponsiveListItemFlexColumnRenderer =
                                        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                                            text = Runs(runs = emptyList()),
                                        ),
                                ),
                            ),
                        thumbnail = null,
                        menu = null,
                        playlistItemData = null,
                        overlay = null,
                        navigationEndpoint = null,
                    ),
                musicNavigationButtonRenderer = null,
                musicMultiRowListItemRenderer = null,
            )

        val normalRow =
            MusicCarouselShelfRenderer.Content(
                musicTwoRowItemRenderer = null,
                musicResponsiveListItemRenderer =
                    MusicResponsiveListItemRenderer(
                        badges = null,
                        fixedColumns = null,
                        flexColumns =
                            listOf(
                                MusicResponsiveListItemRenderer.FlexColumn(
                                    musicResponsiveListItemFlexColumnRenderer =
                                        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                                            text = Runs(runs = listOf(Run(text = "Artist Name", navigationEndpoint = null))),
                                        ),
                                ),
                                MusicResponsiveListItemRenderer.FlexColumn(
                                    musicResponsiveListItemFlexColumnRenderer =
                                        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                                            text = Runs(runs = listOf(Run(text = "1M subscribers", navigationEndpoint = null))),
                                        ),
                                ),
                            ),
                        thumbnail = null,
                        menu = null,
                        playlistItemData = null,
                        overlay = null,
                        navigationEndpoint = null,
                    ),
                musicNavigationButtonRenderer = null,
                musicMultiRowListItemRenderer = null,
            )

        val artists = assertNotNull(parseArtistChart(listOf(emptyRow, singleColumnEmptyRunsRow, normalRow)))
        assertEquals(3, artists.size)
        assertEquals("", artists[0].title)
        assertEquals("", artists[0].subscribers)
        assertEquals("", artists[1].title)
        assertEquals("", artists[1].subscribers)
        assertEquals("Artist Name", artists[2].title)
        assertEquals("1M subscribers", artists[2].subscribers)
        assertEquals("3", artists[2].rank)
    }
}
