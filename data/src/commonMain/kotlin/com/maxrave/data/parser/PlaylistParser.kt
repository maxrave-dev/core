package com.maxrave.data.parser

import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.podcast.PodcastBrowse
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.extension.now
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.SearchResponse
import com.maxrave.kotlinytmusicscraper.pages.PodcastItem
import com.maxrave.logger.Logger
import kotlinx.datetime.LocalDateTime

internal fun parsePlaylistData(
    header: Any?,
    listContent: List<MusicShelfRenderer.Content>,
    playlistId: String,
    viewString: String,
): PlaylistBrowse? {
    if (header != null) {
        var title = ""
        val listAuthor: ArrayList<Author> = arrayListOf()
        var duration = ""
        var description = ""
        val listThumbnails: ArrayList<Thumbnail> = arrayListOf()
        var year = now().year.toString()
        var trackCount = 0
        when (header) {
            is BrowseResponse.Header.MusicDetailHeaderRenderer -> {
                title +=
                    header.title.runs
                        ?.get(0)
                        ?.text
                Logger.d("PlaylistParser", "title: $title")
                if (!header.subtitle.runs.isNullOrEmpty() && header.subtitle.runs?.size!! > 2) {
                    val author =
                        Author(
                            id =
                                header.subtitle.runs
                                    ?.get(2)
                                    ?.navigationEndpoint
                                    ?.browseEndpoint
                                    ?.browseId ?: "",
                            name =
                                header.subtitle.runs
                                    ?.get(2)
                                    ?.text ?: "",
                        )
                    listAuthor.add(author)
                    Logger.d("PlaylistParser", "author: $author")
                }
                val secondSubtitle = header.secondSubtitle.runs
                Logger.w("PlaylistParser", "secondSubtitle: $secondSubtitle")
                if (!secondSubtitle.isNullOrEmpty() && secondSubtitle.size > 2) {
                    duration += secondSubtitle.getOrNull(2)?.text
                }
                Logger.d("PlaylistParser", "duration: $duration")
                if (!header.description?.runs.isNullOrEmpty()) {
                    for (run in header.description?.runs!!) {
                        description += (run.text)
                    }
                }
                if (!header.subtitle.runs.isNullOrEmpty() && header.subtitle.runs?.size!! > 4) {
                    year =
                        header.subtitle.runs
                            ?.get(4)
                            ?.text ?: now().year.toString()
                }
                header.thumbnail.croppedSquareThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.toListThumbnail()
                    ?.let { listThumbnails.addAll(it) }
            }

            is BrowseResponse.Header.MusicEditablePlaylistDetailHeaderRenderer? -> {
                title +=
                    header.header.musicDetailHeaderRenderer
                        ?.title
                        ?.runs
                        ?.get(0)
                        ?.text
                Logger.d("PlaylistParser", "title: $title")
                val author =
                    Author(
                        id =
                            header.header.musicDetailHeaderRenderer
                                ?.subtitle
                                ?.runs
                                ?.get(2)
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId
                                ?: "",
                        name =
                            header.header.musicDetailHeaderRenderer
                                ?.subtitle
                                ?.runs
                                ?.get(2)
                                ?.text ?: "",
                    )
                listAuthor.add(author)
                Logger.d("PlaylistParser", "author: $author")
                if (header.header.musicDetailHeaderRenderer
                        ?.secondSubtitle
                        ?.runs
                        ?.size!! > 4
                ) {
                    duration +=
                        header.header.musicDetailHeaderRenderer
                            ?.secondSubtitle
                            ?.runs
                            ?.get(4)
                            ?.text
                } else if (header.header.musicDetailHeaderRenderer
                        ?.secondSubtitle
                        ?.runs
                        ?.size!! == 3
                ) {
                    duration +=
                        header.header.musicDetailHeaderRenderer
                            ?.secondSubtitle
                            ?.runs
                            ?.get(2)
                            ?.text
                }
                Logger.d("PlaylistParser", "duration: $duration")
                if (!header.header.musicDetailHeaderRenderer
                        ?.description
                        ?.runs
                        .isNullOrEmpty()
                ) {
                    for (run in header.header.musicDetailHeaderRenderer
                        ?.description
                        ?.runs!!) {
                        description += (run.text)
                    }
                }
                header.header.musicDetailHeaderRenderer
                    ?.thumbnail
                    ?.croppedSquareThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.toListThumbnail()
                    ?.let { listThumbnails.addAll(it) }
            }

            is SectionListRenderer.Content.MusicResponsiveHeaderRenderer? -> {
                title +=
                    header.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                Logger.d("PlaylistParser", "title: $title")
                val secondSubtitle = header.secondSubtitle?.runs
                if (!secondSubtitle.isNullOrEmpty()) {
                    /**
                     * Fuck Kotlin
                     * Ref: https://stackoverflow.com/q/48379981/20605098
                     */
                    trackCount =
                        try {
                            if (secondSubtitle.size >= 5) {
                                secondSubtitle
                                    .getOrNull(2)
                                    ?.text
                                    ?.split("\\s".toRegex())
                                    ?.firstOrNull()
                                    ?.toInt() ?: 0
                            } else {
                                secondSubtitle
                                    .firstOrNull()
                                    ?.text
                                    ?.split("\\s".toRegex())
                                    ?.firstOrNull()
                                    ?.toInt() ?: 0
                            }
                        } catch (e: Exception) {
                            Logger.e("PlaylistParser", "Error parsing track count: ${e.message}")
                            e.printStackTrace()
                            0
                        }
                }
                year =
                    header.subtitle
                        ?.runs
                        ?.lastOrNull()
                        ?.text
                        ?: now().year.toString()
                val author =
                    Author(
                        id =
                            header.straplineTextOne
                                ?.runs
                                ?.get(0)
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId
                                ?: header.facepile
                                    ?.avatarStackViewModel
                                    ?.rendererContext
                                    ?.commandContext
                                    ?.onTap
                                    ?.innertubeCommand
                                    ?.browseEndpoint
                                    ?.browseId
                                ?: "",
                        name =
                            header.straplineTextOne
                                ?.runs
                                ?.get(0)
                                ?.text
                                ?: header.facepile
                                    ?.avatarStackViewModel
                                    ?.text
                                    ?.content
                                ?: "YouTube Music",
                    )
                listAuthor.add(author)
                Logger.d("PlaylistParser", "author: $author")
                Logger.w("PlaylistParser", "secondSubtitle: $secondSubtitle")
                if (!secondSubtitle.isNullOrEmpty() && secondSubtitle.size > 4) {
                    duration += secondSubtitle.getOrNull(4)?.text
                } else if (!secondSubtitle.isNullOrEmpty() && secondSubtitle.size == 3) {
                    duration += secondSubtitle.getOrNull(2)?.text
                }
                Logger.d("PlaylistParser", "duration: $duration")
                if (!header.description
                        ?.musicDescriptionShelfRenderer
                        ?.description
                        ?.runs
                        .isNullOrEmpty()
                ) {
                    for (run in header.description
                        ?.musicDescriptionShelfRenderer
                        ?.description
                        ?.runs!!) {
                        description += (run.text)
                    }
                }
                header.thumbnail
                    ?.musicThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.toListThumbnail()
                    ?.let { listThumbnails.addAll(it) }
            }
        }
        Logger.d("PlaylistParser", "description: $description")
        val listTrack: MutableList<Track> = arrayListOf()
        for (content in listContent) {
            val renderer = content.musicResponsiveListItemRenderer
            // Identify the columns from each one's own pageType instead of assuming an order —
            // see SongRunParser. This is what makes the album name real rather than a placeholder,
            // and what keeps the play count out of the artist list.
            val columns = renderer?.let { findSongColumns(it) }
            // `firstOrNull`, not `get(0)`: fixedColumns is absent on rows YouTube marks unavailable,
            // and indexing an empty list there threw.
            val durationText =
                renderer
                    ?.fixedColumns
                    ?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val track =
                Track(
                    album =
                        renderer?.let { parseSongAlbumAt(it, columns?.albumIndex) }
                            // No album column: the row still links one from its context menu, but a
                            // menu entry carries only the browse id. Keep the id so navigation works
                            // and leave the name empty — inventing one would put a fake title into
                            // MediaSession metadata, which is what external scrobblers read.
                            ?: renderer
                                ?.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "ALBUM" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId
                                ?.let { Album(id = it, name = "") },
                    artists =
                        renderer?.let { parseSongArtistsAt(it, columns?.artistIndex) }
                            ?: listOf(),
                    duration = durationText.orEmpty(),
                    durationSeconds = parseDurationSeconds(durationText),
                    isAvailable = false,
                    // YouTube only emits this badge on explicit rows, so its absence is the normal
                    // case rather than missing data. Same shape the search and album parsers
                    // already read, and the same field ytmusicapi reads for playlist items.
                    isExplicit =
                        renderer?.badges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true,
                    likeStatus = "INDIFFERENT",
                    thumbnails =
                        content.musicResponsiveListItemRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.thumbnail
                            ?.thumbnails
                            ?.toListThumbnail()
                            ?: listOf(),
                    title =
                        renderer
                            ?.flexColumns
                            ?.getOrNull(columns?.titleIndex ?: 0)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: "",
                    // The model already resolves this from playlistItemData, then the overlay play
                    // button, then the title column — YouTube does not always populate all three.
                    videoId = renderer?.videoId ?: "",
                    // Resolved from the same three endpoints as videoId above; null when this row
                    // carried no music config at all.
                    videoType = renderer?.musicVideoType,
                    category = null,
                    feedbackTokens = null,
                    resultType = null,
                    year = null,
                )
            if (track.videoId != "") {
                listTrack.add(track)
            }
        }
        Logger.d("PlaylistParser", "description: $description")
        Logger.d("PlaylistParser", "trackCount: $trackCount")
        Logger.d("PlaylistParser", "year: $year")
        return PlaylistBrowse(
            author = listAuthor.firstOrNull() ?: Author("", "YouTube Music"),
            description = description,
            duration = duration,
            durationSeconds = 0,
            id = playlistId,
            privacy = "PUBLIC",
            thumbnails = listThumbnails,
            title = title,
            trackCount = if (trackCount == 0) listContent.size else trackCount,
            tracks = listTrack,
            year = year,
        )
    } else {
        return null
    }
}

internal fun parseSetVideoId(
    playlistId: String,
    listContent: List<MusicShelfRenderer.Content>,
): ArrayList<SetVideoIdEntity> {
    val listSetVideoId: ArrayList<SetVideoIdEntity> = arrayListOf()
    for (content in listContent) {
        val videoId = content.musicResponsiveListItemRenderer?.playlistItemData?.videoId
        val setVideoId =
            content.musicResponsiveListItemRenderer
                ?.menu
                ?.menuRenderer
                ?.items
                ?.find { it.menuServiceItemRenderer?.icon?.iconType == "REMOVE_FROM_PLAYLIST" }
                ?.menuServiceItemRenderer
                ?.serviceEndpoint
                ?.playlistEditEndpoint
                ?.actions
                ?.get(
                    0,
                )?.setVideoId
        if (videoId != null && setVideoId != null) {
            listSetVideoId.add(SetVideoIdEntity(videoId, setVideoId, playlistId))
        } else {
            Logger.d("PlaylistParser", "videoId or setVideoId is null")
        }
    }
    Logger.w("PlaylistParser", "listSetVideoId: $listSetVideoId")
    return listSetVideoId
}

internal fun parsePodcast(list: List<PodcastItem>): ArrayList<PlaylistsResult> {
    val listPlaylist: ArrayList<PlaylistsResult> = arrayListOf()
    for (item in list) {
        listPlaylist.add(
            PlaylistsResult(
                author = item.author.name,
                browseId = item.id,
                category = "podcast",
                itemCount = "",
                resultType = "Podcast",
                thumbnails = item.thumbnail.thumbnails.toListThumbnail(),
                title = item.title,
            ),
        )
    }
    return listPlaylist
}

internal fun parsePodcastData(
    listContent: List<MusicShelfRenderer.Content>?,
    author: Artist?,
): List<PodcastBrowse.EpisodeItem> {
    if (listContent == null || author == null) {
        return emptyList()
    } else {
        val listEpisode: ArrayList<PodcastBrowse.EpisodeItem> = arrayListOf()
        listContent.forEach { content ->
            listEpisode.add(
                PodcastBrowse.EpisodeItem(
                    title =
                        content.musicMultiRowListItemRenderer
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: "",
                    author = author,
                    description =
                        content.musicMultiRowListItemRenderer?.description?.runs?.joinToString(
                            separator = "",
                        ) { it.text } ?: "",
                    thumbnail =
                        content.musicMultiRowListItemRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.thumbnail
                            ?.thumbnails
                            ?.toListThumbnail()
                            ?: emptyList<Thumbnail>(),
                    createdDay =
                        content.musicMultiRowListItemRenderer
                            ?.subtitle
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: "",
                    durationString =
                        content.musicMultiRowListItemRenderer
                            ?.playbackProgress
                            ?.musicPlaybackProgressRenderer
                            ?.durationText
                            ?.runs
                            ?.lastOrNull()
                            ?.text ?: "",
                    videoId =
                        content.musicMultiRowListItemRenderer
                            ?.onTap
                            ?.watchEndpoint
                            ?.videoId
                            ?: "",
                ),
            )
        }

        return listEpisode
    }
}

internal fun parsePodcastContinueData(
    listContent: List<SearchResponse.ContinuationContents.MusicShelfContinuation.Content>?,
    author: Artist?,
): List<PodcastBrowse.EpisodeItem> {
    if (listContent == null || author == null) {
        return emptyList()
    } else {
        val listEpisode: ArrayList<PodcastBrowse.EpisodeItem> = arrayListOf()
        listContent.forEach { content ->
            listEpisode.add(
                PodcastBrowse.EpisodeItem(
                    title =
                        content.musicMultiRowListItemRenderer
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: "",
                    author = author,
                    description =
                        content.musicMultiRowListItemRenderer?.description?.runs?.joinToString(
                            separator = "",
                        ) { it.text } ?: "",
                    thumbnail =
                        content.musicMultiRowListItemRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.thumbnail
                            ?.thumbnails
                            ?.toListThumbnail()
                            ?: emptyList<Thumbnail>(),
                    createdDay =
                        content.musicMultiRowListItemRenderer
                            ?.subtitle
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: "",
                    durationString =
                        content.musicMultiRowListItemRenderer
                            ?.subtitle
                            ?.runs
                            ?.lastOrNull()
                            ?.text
                            ?: "",
                    videoId =
                        content.musicMultiRowListItemRenderer
                            ?.onTap
                            ?.watchEndpoint
                            ?.videoId
                            ?: "",
                ),
            )
        }

        return listEpisode
    }
}