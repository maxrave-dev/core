package com.maxrave.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.maxrave.data.db.Converters
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.isAlbumItem
import com.maxrave.domain.utils.FilterState
import com.maxrave.logger.Logger

/** Prefix of the synthetic [SongEntity.videoId] used to render an album entry as a list row. */
internal const val ALBUM_ITEM_VIDEO_ID_PREFIX = "album:"

/**
 * An album entry has no song, so it is given a synthetic one carrying only what its row draws.
 * The pair beside it is the source of truth — callers tell an album from a song with
 * [isAlbumItem], never by looking at this placeholder.
 */
private fun synthesizedAlbumSong(pair: PairSongLocalPlaylist): SongEntity =
    SongEntity(
        videoId = ALBUM_ITEM_VIDEO_ID_PREFIX + (pair.albumBrowseId ?: pair.id.toString()),
        albumId = pair.albumBrowseId,
        albumName = pair.title,
        title = pair.title ?: "",
        thumbnails = pair.thumbnail,
        artistName = pair.artistName?.let { listOf(it) },
        duration = "",
        durationSeconds = 0,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "",
        videoType = "",
        category = null,
        resultType = null,
    )

private fun PairSongLocalPlaylist.toPagingItem(songsById: Map<String, SongEntity>): Pair<SongEntity, PairSongLocalPlaylist>? =
    when {
        isAlbumItem -> synthesizedAlbumSong(this) to this
        songId != null -> songsById[songId]?.let { it to this }
        else -> null
    }

internal class LocalPlaylistPagingSource(
    private val playlistId: Long,
    private val filter: FilterState,
    private val localDataSource: LocalDataSource,
) : PagingSource<Int, Pair<SongEntity, PairSongLocalPlaylist>>() {
    override fun getRefreshKey(state: PagingState<Int, Pair<SongEntity, PairSongLocalPlaylist>>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Pair<SongEntity, PairSongLocalPlaylist>> {
        return try {
            val currentPage = params.key ?: 0
            val pairs =
                localDataSource.getPlaylistPairSongByOffset(
                    playlistId = playlistId,
                    filterState = filter,
                    offset = currentPage,
                )
            Logger.d("LocalPlaylistPagingSource", "load: $pairs")
            val songs =
                localDataSource
                    .getSongByListVideoIdFull(
                        pairs?.mapNotNull { it.songId } ?: emptyList(),
                    )
            val idValue = songs.associateBy { it.videoId }
            val sorted = (pairs ?: emptyList()).mapNotNull { it.toPagingItem(idValue) }
            Logger.d("LocalPlaylistPagingSource", "load: $songs")
            return LoadResult.Page(
                data = sorted,
                prevKey = if (currentPage == 0) null else currentPage - 1,
                nextKey = if (pairs.isNullOrEmpty()) null else currentPage + 1,
            )
        } catch (e: Exception) {
            Logger.e("LocalPlaylistPagingSource", "load: ${e.printStackTrace()}")
            LoadResult.Error(e)
        }
    }
}

internal class LocalPlaylistTimeBasedPagingSource(
    private val playlistId: Long,
    private val filter: FilterState,
    private val localDataSource: LocalDataSource,
) : PagingSource<Long, Pair<SongEntity, PairSongLocalPlaylist>>() {
    val converter = Converters()

    override fun getRefreshKey(state: PagingState<Long, Pair<SongEntity, PairSongLocalPlaylist>>): Long? =
        state.anchorPosition?.let { anchor ->
            state.closestItemToPosition(anchor)?.let { (_, pair) ->
                converter.dateToTimestamp(pair.inPlaylist)
            }
        }

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Pair<SongEntity, PairSongLocalPlaylist>> {
        return try {
            val currentPage = params.key ?: 0L
            val timestamp =
                if (currentPage == 0L && filter == FilterState.NewerFirst) {
                    val newestPair =
                        localDataSource.getNewestPlaylistPairSong(playlistId = playlistId)
                    newestPair?.inPlaylist
                } else {
                    converter.fromTimestamp(currentPage)
                }
            val pairs =
                localDataSource
                    .getPlaylistPairSongByTime(
                        playlistId = playlistId,
                        filterState = filter,
                        localDateTime = timestamp ?: throw Exception("Invalid timestamp"),
                    ).let {
                        if (currentPage == 0L && filter == FilterState.NewerFirst) {
                            val newestPair =
                                listOfNotNull(
                                    localDataSource.getNewestPlaylistPairSong(playlistId = playlistId),
                                )
                            newestPair + (it ?: emptyList())
                        } else {
                            it
                        }
                    }
            Logger.d("LocalPlaylistPagingSource", "load: $pairs")
            val songs =
                localDataSource
                    .getSongByListVideoIdFull(
                        pairs?.mapNotNull { it.songId } ?: emptyList(),
                    )
            val idValue = songs.associateBy { it.videoId }
            val sorted = (pairs ?: emptyList()).mapNotNull { it.toPagingItem(idValue) }
            Logger.d("LocalPlaylistPagingSource", "load: $songs")
            val nextKey =
                pairs?.lastOrNull()?.inPlaylist.let {
                    converter.dateToTimestamp(it)
                }
            return LoadResult.Page(
                data = sorted,
                prevKey = null,
                nextKey =
                    if (pairs.isNullOrEmpty()) {
                        null
                    } else if (nextKey == currentPage) {
                        null
                    } else {
                        nextKey
                    },
            )
        } catch (e: Exception) {
            Logger.e("LocalPlaylistPagingSource", "load: ${e.printStackTrace()}")
            LoadResult.Error(e)
        }
    }
}
