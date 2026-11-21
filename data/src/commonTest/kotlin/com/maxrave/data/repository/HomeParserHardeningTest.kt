package com.maxrave.data.repository

import com.maxrave.data.parser.parseGenreObject
import com.maxrave.data.parser.parseMixedContent
import com.maxrave.data.parser.parseMoodsMomentObject
import com.maxrave.data.parser.parsePlaylist
import com.maxrave.data.parser.parseSongFlat
import com.maxrave.kotlinytmusicscraper.models.Badges
import com.maxrave.kotlinytmusicscraper.models.MusicCarouselShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicDescriptionShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.NavigationEndpoint
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import com.maxrave.kotlinytmusicscraper.models.Tabs
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
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
        assertTrue(genre.itemsSong.isNullOrEmpty())
    }

    @Test
    fun parseMixedContentParsesLiveStationFallbackAndContinuesPastUnknownCard() {
        val liveBadge =
            listOf(
                Badges(
                    musicInlineBadgeRenderer = null,
                    liveBadgeRenderer = Badges.LiveBadgeRenderer(label = Runs(listOf(Run(text = "LIVE", navigationEndpoint = null)))),
                ),
            )
        val liveStationCard =
            MusicTwoRowItemRenderer(
                title = Runs(listOf(Run(text = "A Matter of Time, All The Time", navigationEndpoint = null))),
                subtitle = Runs(listOf(Run(text = "Laufey", navigationEndpoint = null))),
                subtitleBadges = null,
                badges = liveBadge,
                menu = null,
                thumbnailRenderer =
                    ThumbnailRenderer(
                        musicThumbnailRenderer =
                            ThumbnailRenderer.MusicThumbnailRenderer(
                                thumbnail = Thumbnails(listOf(Thumbnail(url = "https://example.com/thumb.jpg", width = 320, height = 180))),
                                thumbnailCrop = null,
                                thumbnailScale = null,
                            ),
                        musicAnimatedThumbnailRenderer = null,
                        croppedSquareThumbnailRenderer = null,
                    ),
                navigationEndpoint =
                    NavigationEndpoint(
                        watchPlaylistEndpoint = WatchEndpoint(videoId = "live123", playlistId = "RDAMVMlive123"),
                    ),
                thumbnailOverlay = null,
                aspectRatio = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
            )
        val unknownItem =
            MusicCarouselShelfRenderer.Content(
                musicTwoRowItemRenderer = null,
                musicResponsiveListItemRenderer = null,
                musicNavigationButtonRenderer = null,
                musicMultiRowListItemRenderer = null,
            )
        val row =
            SectionListRenderer.Content(
                musicCarouselShelfRenderer =
                    MusicCarouselShelfRenderer(
                        header =
                            MusicCarouselShelfRenderer.Header(
                                musicCarouselShelfBasicHeaderRenderer =
                                    MusicCarouselShelfRenderer.Header.MusicCarouselShelfBasicHeaderRenderer(
                                        strapline = null,
                                        title = Runs(listOf(Run(text = "Ao vivo agora", navigationEndpoint = null))),
                                        thumbnail = null,
                                        moreContentButton = null,
                                    ),
                            ),
                        contents =
                            listOf(
                                unknownItem,
                                MusicCarouselShelfRenderer.Content(
                                    musicTwoRowItemRenderer = liveStationCard,
                                    musicResponsiveListItemRenderer = null,
                                    musicNavigationButtonRenderer = null,
                                    musicMultiRowListItemRenderer = null,
                                ),
                            ),
                        itemSize = "MRLIR",
                        numItemsPerColumn = null,
                    ),
                musicShelfRenderer = null,
                musicCardShelfRenderer = null,
                musicPlaylistShelfRenderer = null,
                musicDescriptionShelfRenderer = null,
                musicResponsiveHeaderRenderer = null,
                musicEditablePlaylistDetailHeaderRenderer = null,
                gridRenderer = null,
            )

        val result = parseMixedContent(listOf(row), viewString = "views", songString = "songs")
        assertEquals(1, result.size)
        assertEquals("Ao vivo agora", result[0].title)
        assertEquals(1, result[0].contents.size)
        val parsed = assertNotNull(result[0].contents[0])
        assertEquals("live123", parsed.videoId)
        assertEquals("A Matter of Time, All The Time", parsed.title)
        assertTrue(parsed.isLive)
    }

    @Test
    fun parseMixedContentParsesMusicShelfRendererWithLiveItem() {
        val liveRow =
            MusicResponsiveListItemRenderer(
                badges =
                    listOf(
                        Badges(
                            musicInlineBadgeRenderer = null,
                            liveBadgeRenderer = Badges.LiveBadgeRenderer(label = Runs(listOf(Run(text = "LIVE", navigationEndpoint = null)))),
                        ),
                    ),
                fixedColumns = null,
                flexColumns =
                    listOf(
                        MusicResponsiveListItemRenderer.FlexColumn(
                            musicResponsiveListItemFlexColumnRenderer =
                                MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                                    text =
                                        Runs(
                                            listOf(
                                                Run(
                                                    text = "Lofi Girl Radio",
                                                    navigationEndpoint = NavigationEndpoint(watchEndpoint = WatchEndpoint(videoId = "jfKfPfyJRdk")),
                                                ),
                                            ),
                                        ),
                                ),
                        ),
                    ),
                thumbnail = null,
                menu = null,
                playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(playlistSetVideoId = null, videoId = "jfKfPfyJRdk"),
                overlay = null,
                navigationEndpoint = null,
            )
        val row =
            SectionListRenderer.Content(
                musicCarouselShelfRenderer = null,
                musicShelfRenderer =
                    MusicShelfRenderer(
                        title = Runs(listOf(Run(text = "Transmissões ao vivo", navigationEndpoint = null))),
                        contents =
                            listOf(
                                MusicShelfRenderer.Content(
                                    musicResponsiveListItemRenderer = liveRow,
                                    musicMultiRowListItemRenderer = null,
                                    continuationItemRenderer = null,
                                ),
                            ),
                        bottomEndpoint = null,
                        moreContentButton = null,
                        continuations = null,
                    ),
                musicCardShelfRenderer = null,
                musicPlaylistShelfRenderer = null,
                musicDescriptionShelfRenderer = null,
                musicResponsiveHeaderRenderer = null,
                musicEditablePlaylistDetailHeaderRenderer = null,
                gridRenderer = null,
            )

        val result = parseMixedContent(listOf(row), viewString = "views", songString = "songs")
        assertEquals(1, result.size)
        assertEquals("Transmissões ao vivo", result[0].title)
        assertEquals(1, result[0].contents.size)
        val parsed = assertNotNull(result[0].contents[0])
        assertEquals("jfKfPfyJRdk", parsed.videoId)
        assertEquals("Lofi Girl Radio", parsed.title)
        assertTrue(parsed.isLive)
    }
}
