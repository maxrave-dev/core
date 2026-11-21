package com.maxrave.data.repository

import com.maxrave.data.parser.parseGenreObject
import com.maxrave.data.parser.parseMixedContent
import com.maxrave.data.parser.parseMoodsMomentObject
import com.maxrave.data.parser.parsePlaylist
import com.maxrave.data.parser.parseSongFlat
import com.maxrave.kotlinytmusicscraper.models.MusicDescriptionShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.NavigationEndpoint
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import com.maxrave.kotlinytmusicscraper.models.Tabs
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeParserHardeningTest {
    @Test
    fun parseMixedContentHandlesEmptyDescriptionRuns() {
        val row =
            SectionListRenderer.Content(
                musicCarouselShelfRenderer = null,
                musicShelfRenderer = null,
                musicCardShelfRenderer = null,
                musicPlaylistShelfRenderer = null,
                musicDescriptionShelfRenderer =
                    MusicDescriptionShelfRenderer(
                        header = Runs(runs = emptyList()),
                        subheader = null,
                        description = Runs(runs = emptyList()),
                        footer = null,
                    ),
                musicResponsiveHeaderRenderer = null,
                musicEditablePlaylistDetailHeaderRenderer = null,
                gridRenderer = null,
            )

        val result = parseMixedContent(listOf(row), viewString = "views", songString = "songs")
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseSongFlatHandlesEmptyFlexColumnsWithoutCrashing() {
        val renderer =
            MusicResponsiveListItemRenderer(
                badges = null,
                fixedColumns = null,
                flexColumns = emptyList(),
                thumbnail = null,
                menu = null,
                playlistItemData = null,
                overlay = null,
                navigationEndpoint = null,
            )

        val parsed = assertNotNull(parseSongFlat(renderer, viewString = "views"))
        assertEquals("", parsed.title)
        assertEquals("", parsed.videoId)
    }

    @Test
    fun parsePlaylistHandlesEmptyTitleRunsAndThreeRunSubtitle() {
        val renderer =
            MusicTwoRowItemRenderer(
                title = Runs(runs = emptyList()),
                subtitle =
                    Runs(
                        runs =
                            listOf(
                                Run(text = "Artist", navigationEndpoint = null),
                                Run(text = " • ", navigationEndpoint = null),
                                Run(text = "10 songs", navigationEndpoint = null),
                            ),
                    ),
                menu = null,
                thumbnailRenderer =
                    ThumbnailRenderer(
                        musicThumbnailRenderer =
                            ThumbnailRenderer.MusicThumbnailRenderer(
                                thumbnail = Thumbnails(thumbnails = emptyList()),
                                thumbnailCrop = null,
                                thumbnailScale = null,
                            ),
                        musicAnimatedThumbnailRenderer = null,
                        croppedSquareThumbnailRenderer = null,
                    ),
                navigationEndpoint = NavigationEndpoint(),
                thumbnailOverlay = null,
            )

        val parsed = parsePlaylist(renderer, viewString = "views")
        assertEquals("", parsed.title)
        assertEquals("Artist • 10 songs", parsed.description)
    }

    @Test
    fun parseMoodsAndGenreHandleEmptyTabsWithoutCrashing() {
        val emptyTabsResponse =
            BrowseResponse(
                contents =
                    BrowseResponse.Contents(
                        singleColumnBrowseResultsRenderer = Tabs(tabs = emptyList()),
                        twoColumnBrowseResultsRenderer = null,
                        sectionListRenderer = null,
                    ),
                continuationContents = null,
                header = null,
                microformat = null,
                responseContext = ResponseContext(serviceTrackingParams = null),
                background = null,
                onResponseReceivedActions = null,
            )

        val mood = assertNotNull(parseMoodsMomentObject(emptyTabsResponse))
        val genre = assertNotNull(parseGenreObject(emptyTabsResponse))
        assertTrue(mood.items.isEmpty())
        assertTrue(genre.itemsPlaylist.isEmpty())
        assertTrue(genre.itemsSong.isEmpty())
    }
}
