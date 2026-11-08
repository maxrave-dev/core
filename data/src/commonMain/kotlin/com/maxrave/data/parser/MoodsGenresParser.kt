package com.maxrave.data.parser

import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.genre.ItemsPlaylist
import com.maxrave.domain.data.model.mood.genre.ItemsSong
import com.maxrave.domain.data.model.mood.genre.Title
import com.maxrave.domain.data.model.mood.moodmoments.Content
import com.maxrave.domain.data.model.mood.moodmoments.Item
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.domain.data.model.mood.genre.Content as GenreContent

internal fun parseMoodsMomentObject(data: BrowseResponse?): MoodsMomentObject? {
    if (data != null) {
        val title =
            data.header
                ?.musicHeaderRenderer
                ?.title
                ?.runs
                ?.get(0)
                ?.text ?: ""
        val items =
            data.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.get(0)
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
        val listItem: MutableList<Item> = mutableListOf()
        if (items != null) {
            for (item in items) {
                if (item.musicCarouselShelfRenderer != null) {
                    val contents = item.musicCarouselShelfRenderer?.contents
                    val header =
                        item.musicCarouselShelfRenderer
                            ?.header
                            ?.musicCarouselShelfBasicHeaderRenderer
                            ?.title
                            ?.runs
                            ?.get(
                                0,
                            )?.text
                    val listContent: MutableList<Content> = mutableListOf()
                    if (!contents.isNullOrEmpty()) {
                        for (content in contents) {
                            if (content.musicResponsiveListItemRenderer != null) {
                                // Song — the "Songs" shelf uses this renderer, not the
                                // musicTwoRowItemRenderer every other shelf uses. This branch used
                                // to be empty, so the shelf rendered as a bare "Songs" heading.
                                val renderer = content.musicResponsiveListItemRenderer
                                val songTitle =
                                    renderer
                                        ?.flexColumns
                                        ?.getOrNull(0)
                                        ?.musicResponsiveListItemFlexColumnRenderer
                                        ?.text
                                        ?.runs
                                        ?.firstOrNull()
                                        ?.text
                                val videoId =
                                    renderer
                                        ?.flexColumns
                                        ?.getOrNull(0)
                                        ?.musicResponsiveListItemFlexColumnRenderer
                                        ?.text
                                        ?.runs
                                        ?.firstOrNull()
                                        ?.navigationEndpoint
                                        ?.watchEndpoint
                                        ?.videoId
                                // The artist column is `<artist> • <n> views`, where only the
                                // artist runs carry a browseEndpoint — the separators and the
                                // view count do not. Filtering on that keeps every artist of a
                                // multi-artist track and drops the view count, which joining all
                                // runs would have glued onto the name.
                                val artistRuns =
                                    renderer
                                        ?.flexColumns
                                        ?.getOrNull(1)
                                        ?.musicResponsiveListItemFlexColumnRenderer
                                        ?.text
                                        ?.runs
                                val songSubtitle =
                                    artistRuns
                                        ?.filter { it.navigationEndpoint?.browseEndpoint != null }
                                        ?.joinToString(", ") { it.text }
                                        ?.takeIf { it.isNotBlank() }
                                        ?: artistRuns?.firstOrNull()?.text
                                // Song rows ship 60px/120px art because YouTube lists them small;
                                // SimpMusic draws them as full-size cards. Same w120 -> w544 bump
                                // used by Track.toGenericMediaItem and toSongEntity.
                                val songThumbnails =
                                    renderer
                                        ?.thumbnail
                                        ?.musicThumbnailRenderer
                                        ?.thumbnail
                                        ?.thumbnails
                                        ?.toListThumbnail()
                                        ?.map { thumb ->
                                            thumb.copy(url = Regex("([wh])120").replace(thumb.url, "$1544"))
                                        }
                                if (videoId != null && songTitle != null) {
                                    listContent.add(
                                        Content(
                                            playlistBrowseId = "",
                                            subtitle = songSubtitle ?: "",
                                            thumbnails = songThumbnails ?: listOf(),
                                            title = songTitle,
                                            videoId = videoId,
                                        ),
                                    )
                                }
                            } else if (content.musicTwoRowItemRenderer != null) {
                                // Playlist
                                val thumbnails =
                                    content.musicTwoRowItemRenderer
                                        ?.thumbnailRenderer
                                        ?.musicThumbnailRenderer
                                        ?.thumbnail
                                        ?.thumbnails
                                        ?.toListThumbnail()
                                var subtitle = ""
                                val runs = content.musicTwoRowItemRenderer?.subtitle?.runs
                                if (runs != null) {
                                    for (i in runs.indices) {
                                        subtitle += runs[i].text
                                    }
                                }
                                val contentTitle =
                                    content.musicTwoRowItemRenderer
                                        ?.title
                                        ?.runs
                                        ?.get(0)
                                        ?.text
                                val playlistBrowseId =
                                    content.musicTwoRowItemRenderer
                                        ?.navigationEndpoint
                                        ?.browseEndpoint
                                        ?.browseId
                                listContent.add(
                                    Content(
                                        playlistBrowseId = playlistBrowseId ?: "",
                                        subtitle = subtitle,
                                        thumbnails = thumbnails ?: listOf(),
                                        title = contentTitle ?: "",
                                    ),
                                )
                            }
                        }
                    }
                    listItem.add(Item(contents = listContent, header = header ?: ""))
                } else if (item.gridRenderer != null) {
                    val contents = item.gridRenderer?.items
                    val header =
                        item.gridRenderer
                            ?.header
                            ?.gridHeaderRenderer
                            ?.title
                            ?.runs
                            ?.get(0)
                            ?.text
                    val listContent: MutableList<Content> = mutableListOf()
                    if (!contents.isNullOrEmpty()) {
                        for (content in contents) {
                            if (content.musicTwoRowItemRenderer != null) {
                                // Playlist
                                val thumbnails =
                                    content.musicTwoRowItemRenderer
                                        ?.thumbnailRenderer
                                        ?.musicThumbnailRenderer
                                        ?.thumbnail
                                        ?.thumbnails
                                        ?.toListThumbnail()
                                var subtitle = ""
                                val runs = content.musicTwoRowItemRenderer?.subtitle?.runs
                                if (runs != null) {
                                    for (i in runs.indices) {
                                        subtitle += runs[i].text
                                    }
                                }
                                val contentTitle =
                                    content.musicTwoRowItemRenderer
                                        ?.title
                                        ?.runs
                                        ?.get(0)
                                        ?.text
                                val playlistBrowseId =
                                    content.musicTwoRowItemRenderer
                                        ?.navigationEndpoint
                                        ?.browseEndpoint
                                        ?.browseId
                                listContent.add(
                                    Content(
                                        playlistBrowseId = playlistBrowseId ?: "",
                                        subtitle = subtitle,
                                        thumbnails = thumbnails ?: listOf(),
                                        title = contentTitle ?: "",
                                    ),
                                )
                            }
                        }
                    }
                    listItem.add(Item(contents = listContent, header = header ?: ""))
                }
            }
        }
        return MoodsMomentObject(endpoint = "FEmusic_moods_and_genres_category", header = title, items = listItem, params = "")
    } else {
        return null
    }
}

internal fun parseGenreObject(data: BrowseResponse?): GenreObject? {
    if (data != null) {
        val title =
            data.header
                ?.musicHeaderRenderer
                ?.title
                ?.runs
                ?.get(0)
                ?.text ?: ""
        val items =
            data.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.get(0)
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
        val listItemsPlaylist: MutableList<ItemsPlaylist> = mutableListOf()
        val listItemsSong: MutableList<ItemsSong> = mutableListOf()
        if (items != null) {
            for (item in items) {
                if (item.musicCarouselShelfRenderer != null) {
                    val contents = item.musicCarouselShelfRenderer?.contents
                    val header =
                        item.musicCarouselShelfRenderer
                            ?.header
                            ?.musicCarouselShelfBasicHeaderRenderer
                            ?.title
                            ?.runs
                            ?.get(
                                0,
                            )?.text
                    val listContent: MutableList<GenreContent> = mutableListOf()
                    if (!contents.isNullOrEmpty()) {
                        for (content in contents) {
                            if (content.musicResponsiveListItemRenderer != null) {
                                // Song
                                val renderer = content.musicResponsiveListItemRenderer
                                val songName =
                                    renderer
                                        ?.flexColumns
                                        ?.getOrNull(0)
                                        ?.musicResponsiveListItemFlexColumnRenderer
                                        ?.text
                                        ?.runs
                                        ?.firstOrNull()
                                        ?.text
                                // Only the artist runs carry a browseEndpoint; the separators and
                                // the trailing "<n> views" do not. Taking runs[0] alone would drop
                                // every artist but the first on a collaboration.
                                val songArtists =
                                    renderer
                                        ?.flexColumns
                                        ?.getOrNull(1)
                                        ?.musicResponsiveListItemFlexColumnRenderer
                                        ?.text
                                        ?.runs
                                        ?.filter { it.navigationEndpoint?.browseEndpoint != null }
                                        ?.map { run ->
                                            Artist(
                                                id = run.navigationEndpoint?.browseEndpoint?.browseId,
                                                name = run.text,
                                            )
                                        }?.takeIf { it.isNotEmpty() }
                                val videoId =
                                    renderer
                                        ?.flexColumns
                                        ?.getOrNull(0)
                                        ?.musicResponsiveListItemFlexColumnRenderer
                                        ?.text
                                        ?.runs
                                        ?.firstOrNull()
                                        ?.navigationEndpoint
                                        ?.watchEndpoint
                                        ?.videoId
                                listItemsSong.add(
                                    ItemsSong(
                                        title = songName ?: "",
                                        artist = songArtists ?: listOf(),
                                        videoId = videoId ?: "",
                                    ),
                                )
                            } else if (content.musicTwoRowItemRenderer != null) {
                                // Playlist
                                val thumbnails =
                                    content.musicTwoRowItemRenderer
                                        ?.thumbnailRenderer
                                        ?.musicThumbnailRenderer
                                        ?.thumbnail
                                        ?.thumbnails
                                        ?.toListThumbnail()
                                var subtitle = ""
                                val runs = content.musicTwoRowItemRenderer?.subtitle?.runs
                                if (runs != null) {
                                    for (i in runs.indices) {
                                        subtitle += runs[i].text
                                    }
                                }
                                val contentTitle =
                                    content.musicTwoRowItemRenderer
                                        ?.title
                                        ?.runs
                                        ?.get(0)
                                        ?.text
                                val playlistBrowseId =
                                    content.musicTwoRowItemRenderer
                                        ?.navigationEndpoint
                                        ?.browseEndpoint
                                        ?.browseId
                                listContent.add(
                                    GenreContent(
                                        playlistBrowseId = playlistBrowseId ?: "",
                                        thumbnail = thumbnails ?: listOf(),
                                        title =
                                            Title(
                                                subtitle = subtitle,
                                                title = contentTitle ?: "",
                                            ),
                                    ),
                                )
                            }
                        }
                    }
                    listItemsPlaylist.add(ItemsPlaylist(contents = listContent, header = header ?: "", type = "playlist"))
                } else if (item.gridRenderer != null) {
                    val contents = item.gridRenderer?.items
                    val header =
                        item.gridRenderer
                            ?.header
                            ?.gridHeaderRenderer
                            ?.title
                            ?.runs
                            ?.get(0)
                            ?.text
                    val listContent: MutableList<GenreContent> = mutableListOf()
                    if (!contents.isNullOrEmpty()) {
                        for (content in contents) {
                            if (content.musicTwoRowItemRenderer != null) {
                                // Playlist
                                val thumbnails =
                                    content.musicTwoRowItemRenderer
                                        ?.thumbnailRenderer
                                        ?.musicThumbnailRenderer
                                        ?.thumbnail
                                        ?.thumbnails
                                        ?.toListThumbnail()
                                var subtitle = ""
                                val runs = content.musicTwoRowItemRenderer?.subtitle?.runs
                                if (runs != null) {
                                    for (i in runs.indices) {
                                        subtitle += runs[i].text
                                    }
                                }
                                val contentTitle =
                                    content.musicTwoRowItemRenderer
                                        ?.title
                                        ?.runs
                                        ?.get(0)
                                        ?.text
                                val playlistBrowseId =
                                    content.musicTwoRowItemRenderer
                                        ?.navigationEndpoint
                                        ?.browseEndpoint
                                        ?.browseId
                                listContent.add(
                                    GenreContent(
                                        playlistBrowseId = playlistBrowseId ?: "",
                                        thumbnail = thumbnails ?: listOf(),
                                        title =
                                            Title(
                                                subtitle = subtitle,
                                                title = contentTitle ?: "",
                                            ),
                                    ),
                                )
                            }
                        }
                    }
                    listItemsPlaylist.add(ItemsPlaylist(contents = listContent, header = header ?: "", type = "playlist"))
                }
            }
        }
        return GenreObject(header = title, itemsPlaylist = listItemsPlaylist, itemsSong = listItemsSong)
    } else {
        return null
    }
}