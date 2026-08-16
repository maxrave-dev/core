package com.maxrave.data.parser

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.kotlinytmusicscraper.models.ArtistItem
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.VideoItem
import com.maxrave.kotlinytmusicscraper.pages.ArtistPage
import com.maxrave.kotlinytmusicscraper.pages.ExplorePage
import com.maxrave.kotlinytmusicscraper.pages.RelatedPage
import com.maxrave.logger.Logger

internal fun parseMixedContent(
    data: List<SectionListRenderer.Content>?,
    viewString: String,
    songString: String,
): List<HomeItem> {
    val list = mutableListOf<HomeItem>()
    if (data != null) {
        for (row in data) {
            val results = row.musicDescriptionShelfRenderer
            if (results != null) {
                val title =
                    results.header
                        ?.runs
                        ?.get(0)
                        ?.text ?: ""
                val content =
                    results.description.runs
                        ?.get(0)
                        ?.text ?: ""
                if (title.isNotEmpty()) {
                    list.add(
                        HomeItem(
                            contents =
                                listOf(
                                    Content(
                                        album = null,
                                        artists = listOf(),
                                        description = content,
                                        isExplicit = null,
                                        playlistId = null,
                                        browseId = null,
                                        thumbnails = listOf(),
                                        title = content,
                                        videoId = null,
                                        views = null,
                                    ),
                                ),
                            title = title,
                        ),
                    )
                }
            } else {
                val results1 = row.musicCarouselShelfRenderer
                Logger.w("parse_mixed_content", results1.toString())
                val contentList = results1?.contents
                Logger.w("parse_mixed_content", results1?.contents?.size.toString())
                val title =
                    results1
                        ?.header
                        ?.musicCarouselShelfBasicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.get(0)
                        ?.text
                        ?: ""
                Logger.w("parse_mixed_content", title)
                if (title == "Your daily discover") {
                    Logger.w("parse_mixed_content", list.toString())
                }
                val subtitle =
                    results1
                        ?.header
                        ?.musicCarouselShelfBasicHeaderRenderer
                        ?.strapline
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                val thumbnail =
                    results1
                        ?.header
                        ?.musicCarouselShelfBasicHeaderRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.toListThumbnail()
                val artistChannelId =
                    results1
                        ?.header
                        ?.musicCarouselShelfBasicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.navigationEndpoint
                        ?.browseEndpoint
                        ?.browseId
                val listContent = mutableListOf<Content?>()
                if (!contentList.isNullOrEmpty()) {
                    for (result1 in contentList) {
                        val musicTwoRowItemRenderer = result1.musicTwoRowItemRenderer
                        if (musicTwoRowItemRenderer != null) {
                            //                        if (pageType == null) {
//                            if (result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.playlistId != null && result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.videoId == null){
//                                val content = parseWatchPlaylist(result1.musicTwoRowItemRenderer!!)
//                                listContent.add(content)
//                            }
//                            else if (result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.playlistId == null && result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.videoId != null){
//                                val content = parseSong(result1.musicTwoRowItemRenderer!!)
//                                listContent.add(content)
//                            }
//                        }
//                        else if (pageType == "MUSIC_PAGE_TYPE_ALBUM"){
//                            val content = parseAlbum(result1.musicTwoRowItemRenderer!!)
//                            listContent.add(content)
//                        }
//                        else if (pageType == "MUSIC_PAGE_TYPE_ARTIST"){
//                            val content = parseRelatedArtists(result1.musicTwoRowItemRenderer!!)
//                            listContent.add(content)
//                        }
//                        else if (pageType == "MUSIC_PAGE_TYPE_PLAYLIST") {
//                            if (result1.musicTwoRowItemRenderer!!.navigationEndpoint.browseEndpoint?.browseId?.startsWith("MPRE") == true) {
//                                val content = parseAlbum(result1.musicTwoRowItemRenderer!!)
//                                listContent.add(content)
//                            }
//                            else {
//                                val content = parsePlaylist(result1.musicTwoRowItemRenderer!!)
//                                listContent.add(content)
//                            }
//                        }
//                        when (result1.musicTwoRowItemRenderer!!.title.runs?.get(0)?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType) {
//                            "MUSIC_PAGE_TYPE_ALBUM" -> {
//                                val content = parseAlbum(result1.musicTwoRowItemRenderer!!)
//                                listContent.add(content)
//                            }
//                            "MUSIC_PAGE_TYPE_ARTIST" -> {
//                                val content = parseRelatedArtists(result1.musicTwoRowItemRenderer!!)
//                                listContent.add(content)
//                            }
//                            "MUSIC_PAGE_TYPE_PLAYLIST" -> {
//                                if (result1.musicTwoRowItemRenderer!!.navigationEndpoint.browseEndpoint?.browseId?.startsWith("MPRE") == true) {
//                                    val content = parseAlbum(result1.musicTwoRowItemRenderer!!)
//                                    listContent.add(content)
//                                }
//                                else {
//                                    val content = parsePlaylist(result1.musicTwoRowItemRenderer!!)
//                                    listContent.add(content)
//                                }
//                            }
//                            null -> {
//                                if (result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.playlistId != null && result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.videoId == null){
//                                    val content = parseWatchPlaylist(result1.musicTwoRowItemRenderer!!)
//                                    listContent.add(content)
//                                }
//                                else if (result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.playlistId == null && result1.musicTwoRowItemRenderer!!.navigationEndpoint.watchEndpoint?.videoId != null){
//                                    val content = parseSong(result1.musicTwoRowItemRenderer!!, context)
//                                    listContent.add(content)
//                                }
//                            }
//                        }
                            if (musicTwoRowItemRenderer.isSong) {
                                val ytItem =
                                    RelatedPage.fromMusicTwoRowItemRenderer(musicTwoRowItemRenderer, songString) as SongItem?
                                val artists =
                                    ytItem
                                        ?.artists
                                        ?.map {
                                            Artist(
                                                name = it.name,
                                                id = it.id,
                                            )
                                        }?.toMutableList()
                                if (artists?.lastOrNull()?.id == null &&
                                    artists?.lastOrNull()?.name?.contains(
                                        Regex("\\d"),
                                    ) == true
                                ) {
                                    runCatching { artists.removeAt(artists.lastIndex) }
                                        .onSuccess {
                                            Logger.i("parse_mixed_content", "Removed last artist")
                                        }.onFailure {
                                            Logger.e("parse_mixed_content", "Failed to remove last artist")
                                            it.printStackTrace()
                                        }
                                }
                                Logger.w("Song", ytItem.toString())
                                if (ytItem != null) {
                                    listContent.add(
                                        Content(
                                            album =
                                                ytItem.album?.let {
                                                    Album(
                                                        name = it.name,
                                                        id = it.id,
                                                    )
                                                },
                                            artists = artists,
                                            description = null,
                                            isExplicit = ytItem.explicit,
                                            playlistId = null,
                                            browseId = null,
                                            thumbnails =
                                                musicTwoRowItemRenderer.thumbnailRenderer
                                                    ?.musicThumbnailRenderer
                                                    ?.thumbnail
                                                    ?.thumbnails
                                                    ?.toListThumbnail()
                                                    ?: listOf(),
                                            title = ytItem.title,
                                            videoId = ytItem.id,
                                            views = null,
                                            durationSeconds = ytItem.duration,
                                            radio = null,
                                            videoType = ytItem.musicVideoType,
                                        ),
                                    )
                                }
                            } else if (musicTwoRowItemRenderer.isVideo) {
                                val ytItem =
                                    ArtistPage.fromMusicTwoRowItemRenderer(musicTwoRowItemRenderer) as VideoItem?
                                Logger.w("Video", ytItem.toString())
                                val artists =
                                    ytItem
                                        ?.artists
                                        ?.map {
                                            Artist(
                                                name = it.name,
                                                id = it.id,
                                            )
                                        }?.toMutableList()
                                if (artists?.lastOrNull()?.id == null &&
                                    artists?.lastOrNull()?.name?.contains(
                                        Regex("\\d"),
                                    ) == true
                                ) {
                                    runCatching { artists.removeAt(artists.lastIndex) }
                                        .onSuccess {
                                            Logger.i("parse_mixed_content", "Removed last artist")
                                        }.onFailure {
                                            Logger.e("parse_mixed_content", "Failed to remove last artist")
                                            it.printStackTrace()
                                        }
                                }
                                if (ytItem != null) {
                                    listContent.add(
                                        Content(
                                            album =
                                                ytItem.album?.let {
                                                    Album(
                                                        name = it.name,
                                                        id = it.id,
                                                    )
                                                },
                                            artists = artists,
                                            description = null,
                                            isExplicit = ytItem.explicit,
                                            playlistId = null,
                                            browseId = null,
                                            thumbnails =
                                                musicTwoRowItemRenderer.thumbnailRenderer
                                                    ?.musicThumbnailRenderer
                                                    ?.thumbnail
                                                    ?.thumbnails
                                                    ?.toListThumbnail()
                                                    ?: listOf(),
                                            title = ytItem.title,
                                            videoId = ytItem.id,
                                            views = ytItem.view,
                                            durationSeconds = ytItem.duration,
                                            radio = null,
                                            videoType = ytItem.musicVideoType,
                                        ),
                                    )
                                }
                            } else if (musicTwoRowItemRenderer.isArtist || musicTwoRowItemRenderer.isUserChannel) {
                                val ytItem =
                                    RelatedPage.fromMusicTwoRowItemRenderer(musicTwoRowItemRenderer) as ArtistItem?
                                Logger.w("Artists", ytItem.toString())
                                if (ytItem != null) {
                                    listContent.add(
                                        Content(
                                            album = null,
                                            artists = listOf(),
                                            description = null,
                                            isExplicit = null,
                                            playlistId = null,
                                            browseId = ytItem.id,
                                            thumbnails =
                                                musicTwoRowItemRenderer.thumbnailRenderer
                                                    ?.musicThumbnailRenderer
                                                    ?.thumbnail
                                                    ?.thumbnails
                                                    ?.toListThumbnail()
                                                    ?: listOf(),
                                            title = ytItem.title,
                                            videoId = null,
                                            views = null,
                                            radio = null,
                                        ),
                                    )
                                }
                            } else if (musicTwoRowItemRenderer.isAlbum) {
                                listContent.add(
                                    Content(
                                        album =
                                            Album(
                                                id =
                                                    musicTwoRowItemRenderer.navigationEndpoint?.browseEndpoint?.browseId
                                                        ?: "",
                                                name = title,
                                            ),
                                        // An album card's subtitle is "Album • Charli xcx": a type
                                        // specifier, a separator, then the artists. Keeping only the
                                        // runs that actually link somewhere drops the specifier
                                        // without having to recognise the word — which matters
                                        // because that word is localised, and "Single"/"EP" appear
                                        // there too. Leaving this list empty (as it was) made the UI
                                        // fall back to printing the literal string "Album" where the
                                        // artist should be.
                                        artists =
                                            musicTwoRowItemRenderer.subtitle
                                                ?.runs
                                                ?.mapNotNull { run ->
                                                    run.navigationEndpoint
                                                        ?.browseEndpoint
                                                        ?.browseId
                                                        ?.let { id -> Artist(id = id, name = run.text) }
                                                } ?: listOf(),
                                        description = null,
                                        isExplicit = false,
                                        playlistId = null,
                                        browseId = musicTwoRowItemRenderer.navigationEndpoint?.browseEndpoint?.browseId,
                                        thumbnails =
                                            musicTwoRowItemRenderer.thumbnailRenderer
                                                ?.musicThumbnailRenderer
                                                ?.thumbnail
                                                ?.thumbnails
                                                ?.toListThumbnail()
                                                ?: listOf(),
                                        title =
                                            musicTwoRowItemRenderer.title
                                                ?.runs
                                                ?.get(0)
                                                ?.text
                                                ?: "",
                                        videoId = "",
                                        views = "",
                                    ),
                                )
                            } else if (musicTwoRowItemRenderer.isPlaylist) {
                                val subtitle1 = musicTwoRowItemRenderer.subtitle
                                var description = ""
                                if (subtitle1 != null) {
                                    if (subtitle1.runs != null) {
                                        for (run in subtitle1.runs!!) {
                                            description += run.text
                                        }
                                    }
                                }
                                if (musicTwoRowItemRenderer.navigationEndpoint?.browseEndpoint?.browseId?.startsWith(
                                        "MPRE",
                                    ) == true
                                ) {
                                    listContent.add(
                                        Content(
                                            album =
                                                Album(
                                                    id =
                                                        musicTwoRowItemRenderer.navigationEndpoint?.browseEndpoint?.browseId
                                                            ?: "",
                                                    name = title,
                                                ),
                                            artists = listOf(),
                                            description = null,
                                            isExplicit = false,
                                            playlistId = null,
                                            browseId = musicTwoRowItemRenderer.navigationEndpoint?.browseEndpoint?.browseId,
                                            thumbnails =
                                                musicTwoRowItemRenderer.thumbnailRenderer
                                                    ?.musicThumbnailRenderer
                                                    ?.thumbnail
                                                    ?.thumbnails
                                                    ?.toListThumbnail()
                                                    ?: listOf(),
                                            title =
                                                musicTwoRowItemRenderer.title
                                                    ?.runs
                                                    ?.get(
                                                        0,
                                                    )?.text ?: "",
                                            videoId = "",
                                            views = "",
                                        ),
                                    )
                                } else {
                                    val ytItem1 =
                                        RelatedPage.fromMusicTwoRowItemRenderer(
                                            musicTwoRowItemRenderer,
                                        ) as PlaylistItem?
                                    ytItem1?.let { ytItem ->
                                        listContent.add(
                                            Content(
                                                album = null,
                                                artists =
                                                    listOf(
                                                        Artist(
                                                            id = ytItem.author?.id ?: "",
                                                            name = ytItem.author?.name ?: "",
                                                        ),
                                                    ),
                                                description = description,
                                                isExplicit = ytItem.explicit,
                                                playlistId = ytItem.id,
                                                browseId = ytItem.id,
                                                thumbnails =
                                                    musicTwoRowItemRenderer.thumbnailRenderer
                                                        ?.musicThumbnailRenderer
                                                        ?.thumbnail
                                                        ?.thumbnails
                                                        ?.toListThumbnail()
                                                        ?: listOf(),
                                                title = ytItem.title,
                                                videoId = null,
                                                views = null,
                                                radio = null,
                                            ),
                                        )
                                    }
                                }
                            } else if (musicTwoRowItemRenderer.isPodcast) {
                                listContent.add(
                                    Content(
                                        album = null,
                                        artists = listOf(),
                                        description =
                                            musicTwoRowItemRenderer.subtitle
                                                ?.runs
                                                ?.joinToString("") { it.text },
                                        isExplicit = null,
                                        playlistId = null,
                                        browseId = musicTwoRowItemRenderer.navigationEndpoint?.browseEndpoint?.browseId,
                                        thumbnails =
                                            musicTwoRowItemRenderer.thumbnailRenderer
                                                ?.musicThumbnailRenderer
                                                ?.thumbnail
                                                ?.thumbnails
                                                ?.toListThumbnail()
                                                ?: listOf(),
                                        title =
                                            musicTwoRowItemRenderer.title
                                                ?.runs
                                                ?.get(0)
                                                ?.text
                                                ?: "",
                                        videoId = null,
                                        views = null,
                                        radio = null,
                                    ),
                                )
                            } else {
                                continue
                            }
                        } else if (result1.musicResponsiveListItemRenderer != null) {
                            Logger.w(
                                "parse Song flat",
                                result1.musicResponsiveListItemRenderer.toString(),
                            )
                            val ytItem =
                                RelatedPage.fromMusicResponsiveListItemRenderer(result1.musicResponsiveListItemRenderer!!)
                            if (ytItem != null) {
                                val content =
                                    Content(
                                        album = ytItem.album?.let { Album(name = it.name, id = it.id) },
                                        artists =
                                            result1.musicResponsiveListItemRenderer!!.let { renderer ->
                                                parseSongArtistsAt(
                                                    renderer,
                                                    findSongColumns(renderer).artistIndex,
                                                )
                                            },
                                        description = null,
                                        isExplicit = ytItem.explicit,
                                        playlistId = null,
                                        browseId = null,
                                        thumbnails =
                                            result1.musicResponsiveListItemRenderer!!
                                                .thumbnail
                                                ?.musicThumbnailRenderer
                                                ?.thumbnail
                                                ?.thumbnails
                                                ?.toListThumbnail()
                                                ?: listOf(),
                                        title = ytItem.title,
                                        videoId = ytItem.id,
                                        views = "",
                                        radio = null,
                                    )
                                listContent.add(content)
                            }
                        } else if (result1.musicMultiRowListItemRenderer != null) {
                            val multiRow = result1.musicMultiRowListItemRenderer ?: break
                            val content =
                                Content(
                                    description =
                                        multiRow.description
                                            ?.runs
                                            ?.firstOrNull()
                                            ?.text,
                                    thumbnails =
                                        multiRow.thumbnail
                                            ?.musicThumbnailRenderer
                                            ?.thumbnail
                                            ?.thumbnails
                                            ?.toListThumbnail()
                                            ?: listOf(),
                                    title =
                                        multiRow.title
                                            ?.runs
                                            ?.get(0)
                                            ?.text ?: "",
                                    videoId = multiRow.onTap?.watchEndpoint?.videoId ?: "",
                                    album = null,
                                    artists = emptyList(),
                                    isExplicit = false,
                                    playlistId = null,
                                    browseId = null,
                                    views = null,
                                )
                            listContent.add(content)
                        } else {
                            break
                        }
                    }
                }
                if (title.isNotEmpty()) {
                    list.add(
                        HomeItem(
                            contents = listContent,
                            title = title,
                            subtitle = subtitle,
                            thumbnail = thumbnail,
                            channelId = if (artistChannelId?.contains("UC") == true) artistChannelId else null,
                        ),
                    )
                }
                Logger.w("parse_mixed_content", list.toString())
            }
        }
    }
    return list
}

internal fun parseSongFlat(
    data: MusicResponsiveListItemRenderer?,
    viewString: String,
): Content? {
    if (data?.flexColumns != null) {
        val column =
            mutableListOf<MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer?>()
        // `indices`, not `0..size`: the inclusive range ran one past the end on every call.
        for (i in data.flexColumns.indices) {
            column.add(getFlexColumnItem(data, i))
        }
        // Identify the columns from each one's own pageType instead of assuming an order.
        // Column 2 is not always the album: on a row with no album it holds the view count, which
        // has text but no browseEndpoint — and the previous `browseId!!` threw on exactly that.
        val columns = findSongColumns(data)
        return Content(
            album = parseSongAlbumAt(data, columns.albumIndex),
            artists = parseSongArtistsAt(data, columns.artistIndex),
            description = null,
            isExplicit = null,
            playlistId = null,
            browseId = null,
            thumbnails =
                data.thumbnail
                    ?.musicThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.toListThumbnail()
                    ?: listOf(),
            title =
                column[0]
                    ?.text
                    ?.runs
                    ?.get(0)
                    ?.text ?: "",
            videoId =
                column[0]
                    ?.text
                    ?.runs
                    ?.get(0)
                    ?.navigationEndpoint
                    ?.watchEndpoint
                    ?.videoId
                    ?: "",
            // A row shows either an album or a view count, never both — so a row that resolved an
            // album column reports no views, exactly as before. Only the detection changed: the
            // count is now recognised by its shape rather than by "whatever the last run of
            // column 1 happens to be", which picked up the album name on rows laid out differently.
            views = if (columns.albumIndex != null) null else parseSongViews(data) ?: "",
        )
    } else {
        return null
    }
}

fun getFlexColumnItem(
    data: MusicResponsiveListItemRenderer,
    index: Int,
): MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer? =
    if (data.flexColumns.size <= index ||
        data.flexColumns[index].musicResponsiveListItemFlexColumnRenderer.text == null ||
        data.flexColumns[index]
            .musicResponsiveListItemFlexColumnRenderer.text
            ?.runs == null
    ) {
        null
    } else {
        data.flexColumns[index].musicResponsiveListItemFlexColumnRenderer
    }

internal fun parsePlaylist(
    data: MusicTwoRowItemRenderer,
    viewString: String,
): Content {
    val subtitle = data.subtitle
    var description = ""
    var count = ""
    val author: MutableList<Artist> = mutableListOf()
    val thumbnails =
        data.thumbnailRenderer
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
    if (subtitle != null) {
        if (subtitle.runs != null) {
            for (run in subtitle.runs!!) {
                description += run.text
            }
            if (subtitle.runs!!.size == 3) {
                if (data.subtitle!!
                        .runs
                        ?.get(2)
                        ?.text
                        ?.split(" ")
                        ?.get(0) != null
                ) {
                    count +=
                        data.subtitle!!
                            .runs
                            ?.get(2)
                            ?.text
                            ?.split(" ")
                            ?.get(0)
                }
                author.addAll(parseSongArtistsRuns(subtitle.runs!!.take(1)))
            }
        }
    }
    Logger.w("parse_playlist", description)
    return Content(
        album = null,
        artists = author,
        description = description,
        isExplicit = false,
        playlistId =
            data.title
                ?.runs
                ?.get(0)
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.browseId,
        browseId = null,
        thumbnails = thumbnails?.toListThumbnail() ?: listOf(),
        title =
            data.title
                ?.runs
                ?.get(0)
                ?.text ?: "",
        videoId = null,
        views = null,
    )
}

/**
 * Picks the artists out of a run list, skipping the " • " separators at odd positions.
 *
 * Runs that are really a duration, a release year or a view count are dropped by
 * [classifySongRun], which decides from structure and shape rather than by matching a localised
 * word. The previous approach compared each run against the caller's localised "views" string with
 * its first five characters chopped off — that mismatched on any locale it was not written for and
 * let "1.2M plays" through as an artist name, and it threw outright when the string was shorter
 * than five characters.
 */
internal fun parseSongArtistsRuns(runs: List<Run>): List<Artist> {
    val artists = mutableListOf<Artist>()
    // Step over separators. `indices step 2` also fixes an out-of-bounds read the old
    // `0..(runs.size / 2)` bound produced whenever the list had an even number of runs.
    for (i in runs.indices step 2) {
        val run = runs[i]
        if (classifySongRun(run) != SongRunType.ARTIST) continue
        artists.add(
            Artist(
                name = run.text,
                id = run.navigationEndpoint?.browseEndpoint?.browseId,
            ),
        )
    }
    Logger.d("artists_log", artists.toString())
    return artists
}

fun Thumbnail.toThumbnail(): com.maxrave.domain.data.model.searchResult.songs.Thumbnail =
    com.maxrave.domain.data.model.searchResult.songs.Thumbnail(
        height = this.height ?: 0,
        url = this.url,
        width = this.width ?: 0,
    )

fun List<Thumbnail>.toListThumbnail(): List<com.maxrave.domain.data.model.searchResult.songs.Thumbnail> {
    val list = mutableListOf<com.maxrave.domain.data.model.searchResult.songs.Thumbnail>()
    this.forEach {
        list.add(it.toThumbnail())
    }
    return list
}

internal fun parseNewRelease(
    explore: ExplorePage,
    newReleaseString: String,
    musicVideoString: String,
): ArrayList<HomeItem> {
    val result = arrayListOf<HomeItem>()
    result.add(
        HomeItem(
            title = newReleaseString,
            contents =
                explore.released.map {
                    Content(
                        album = null,
                        artists =
                            it.artists?.map { artist ->
                                Artist(
                                    id = artist.id ?: "",
                                    name = artist.name,
                                )
                            } ?: listOf(),
                        description = it.artists?.firstOrNull()?.name ?: "YouTube Music",
                        isExplicit = it.explicit,
                        playlistId = null,
                        browseId = it.browseId,
                        thumbnails =
                            listOf(
                                com.maxrave.domain.data.model.searchResult.songs.Thumbnail(
                                    522,
                                    it.thumbnail,
                                    522,
                                ),
                            ),
                        title = it.title,
                        videoId = null,
                        views = null,
                        radio = null,
                    )
                },
        ),
    )
    result.add(
        HomeItem(
            title = musicVideoString,
            contents =
                explore.musicVideo.map { videoItem ->
                    val artists =
                        videoItem.artists
                            .map {
                                Artist(
                                    name = it.name,
                                    id = it.id,
                                )
                            }.toMutableList()
                    if (artists.lastOrNull()?.id == null &&
                        artists.lastOrNull()?.name?.contains(
                            Regex("\\d"),
                        ) == true
                    ) {
                        runCatching { artists.removeAt(artists.lastIndex) }
                            .onSuccess {
                                Logger.i("parse_mixed_content", "Removed last artist")
                            }.onFailure {
                                Logger.e("parse_mixed_content", "Failed to remove last artist")
                                it.printStackTrace()
                            }
                    }
                    Content(
                        album =
                            videoItem.album?.let {
                                Album(
                                    name = it.name,
                                    id = it.id,
                                )
                            },
                        artists = artists,
                        description = null,
                        isExplicit = videoItem.explicit,
                        playlistId = null,
                        browseId = null,
                        thumbnails =
                            listOf(
                                com.maxrave.domain.data.model.searchResult.songs.Thumbnail(
                                    522,
                                    videoItem.thumbnail,
                                    1080,
                                ),
                            ),
                        title = videoItem.title,
                        videoId = videoItem.id,
                        views = videoItem.view,
                        durationSeconds = videoItem.duration,
                        radio = null,
                    )
                },
        ),
    )
    return result
}