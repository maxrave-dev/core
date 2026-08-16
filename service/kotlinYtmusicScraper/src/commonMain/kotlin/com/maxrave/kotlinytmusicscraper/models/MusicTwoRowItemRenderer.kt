package com.maxrave.kotlinytmusicscraper.models

import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import kotlinx.serialization.Serializable

/**
 * Two row: a big thumbnail, a title, and a subtitle
 * Used in [GridRenderer] and [MusicCarouselShelfRenderer]
 * Item type: song, video, album, playlist, artist
 */
@Serializable
data class MusicTwoRowItemRenderer(
    val title: Runs,
    val subtitle: Runs?,
    val subtitleBadges: List<Badges>? = null,
    val menu: Menu?,
    val thumbnailRenderer: ThumbnailRenderer,
    val navigationEndpoint: NavigationEndpoint,
    val thumbnailOverlay: MusicResponsiveListItemRenderer.Overlay?,
    val aspectRatio: String? = null,
) {
    val isSong: Boolean
        get() =
            navigationEndpoint?.endpoint is WatchEndpoint &&
                (
                    if (aspectRatio != null) {
                        aspectRatio != "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9"
                    } else {
                        val thumbnail =
                            thumbnailRenderer
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.firstOrNull()
                        thumbnail != null && thumbnail.height == thumbnail.width
                    }
                )
    val isPlaylist: Boolean
        get() =
            navigationEndpoint
                ?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_PLAYLIST &&
                aspectRatio != "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9"
    val isAlbum: Boolean
        get() =
            navigationEndpoint
                ?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_ALBUM ||
                navigationEndpoint
                    ?.browseEndpoint
                    ?.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig
                    ?.pageType ==
                MUSIC_PAGE_TYPE_AUDIOBOOK
    val isArtist: Boolean
        get() =
            navigationEndpoint
                ?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_ARTIST

    /** A YouTube user channel card (e.g. "Music channels you may like"); shown as an artist. */
    val isUserChannel: Boolean
        get() =
            navigationEndpoint
                ?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_USER_CHANNEL

    /** A podcast show card (e.g. the Home "Shows for you" shelf); browseId starts with "MPSP". */
    val isPodcast: Boolean
        get() =
            navigationEndpoint
                ?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
    val isVideo: Boolean
        get() =
            navigationEndpoint?.endpoint is WatchEndpoint &&
                (
                    if (aspectRatio != null) {
                        aspectRatio == "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9"
                    } else {
                        val thumbnail =
                            thumbnailRenderer
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.firstOrNull()
                        thumbnail != null && thumbnail.height != thumbnail.width
                    }
                )

    /**
     * What YouTube says this card actually is. [isSong]/[isVideo] above answer a different
     * question — they read the thumbnail aspect ratio to pick a card layout, which is why they
     * stay as they are; this is the API's own classification of the track behind the card.
     */
    val musicVideoType: String?
        get() =
            navigationEndpoint
                ?.watchEndpoint
                ?.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType
                ?: thumbnailOverlay
                    ?.musicItemThumbnailOverlayRenderer
                    ?.content
                    ?.musicPlayButtonRenderer
                    ?.playNavigationEndpoint
                    ?.watchEndpoint
                    ?.watchEndpointMusicSupportedConfigs
                    ?.watchEndpointMusicConfig
                    ?.musicVideoType

    /** Album/playlist id from the card play button; play endpoint switched watchPlaylistEndpoint -> watchEndpoint (2026 web). */
    val playlistId: String?
        get() =
            thumbnailOverlay
                ?.musicItemThumbnailOverlayRenderer
                ?.content
                ?.musicPlayButtonRenderer
                ?.playNavigationEndpoint
                ?.let { it.watchPlaylistEndpoint?.playlistId ?: it.watchEndpoint?.playlistId }
}