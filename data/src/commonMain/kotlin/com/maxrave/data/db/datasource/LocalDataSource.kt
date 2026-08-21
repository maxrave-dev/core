package com.maxrave.data.db.datasource

import DatabaseDao
import com.maxrave.data.db.MusicDatabase
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.AutoEqCurveEntity
import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.entities.AutoEqIndexMetaEntity
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.FollowedArtistSingleAndAlbum
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.NotificationEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.extension.now
import com.maxrave.domain.utils.FilterState
import kotlinx.datetime.LocalDateTime

/** SQLite caps bind parameters per statement; stay well under it when deleting in bulk. */
private const val DELETE_BATCH_SIZE = 400

/**
 * The album name older builds stored when they could not find a real one.
 *
 * The playlist parser used to take the album's browse id out of the row's context menu, which
 * carries an id but no title, and filled the name in with this literal string. It then travelled
 * out to MediaSession metadata, where external scrobblers read it. The parser no longer does this,
 * but roughly half the rows already in users' databases still hold it — see [LocalDataSource.insertSong].
 */
private const val PLACEHOLDER_ALBUM_NAME = "Album"

internal class LocalDataSource(
    private val databaseDao: DatabaseDao,
    private val musicDatabase: MusicDatabase,
) {
    suspend fun checkpoint() = databaseDao.checkpoint()

    /** Goes through the database rather than the DAO — see [MusicDatabase.vacuum]. */
    suspend fun vacuum() = musicDatabase.vacuum()

    suspend fun getAllRecentData() = databaseDao.getAllRecentData()

    suspend fun getAllDownloadedPlaylist() = databaseDao.getAllDownloadedPlaylist()

    suspend fun getAllDownloadingPlaylist() = databaseDao.getAllDownloadingPlaylist()

    suspend fun getSearchHistory() = databaseDao.getSearchHistory()

    suspend fun deleteSearchHistory() = databaseDao.deleteSearchHistory()

    suspend fun insertSearchHistory(searchHistory: SearchHistory) = databaseDao.insertSearchHistory(searchHistory)

    suspend fun getAllSongs(limit: Int) = databaseDao.getAllSongs(limit)

    suspend fun getRecentSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getRecentSongs(limit, offset)

    suspend fun getSongByListVideoId(
        primaryKeyList: List<String>,
        offset: Int,
    ) = databaseDao.getSongByListVideoId(primaryKeyList, offset)

    suspend fun getCanvasSong(max: Int) = databaseDao.getCanvasSong(max)

    suspend fun getSongByListVideoIdFull(primaryKeyList: List<String>) = databaseDao.getSongByListVideoIdFull(primaryKeyList)

    suspend fun getDownloadedSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getDownloadedSongs(
        limit,
        offset,
    )

    fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>) = databaseDao.getDownloadedVideoIdByListVideoId(listVideoId)

    suspend fun getDownloadingSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getDownloadingSongs(
        limit,
        offset,
    )

    // ===== Clear listening history + drop orphaned songs =====

    suspend fun deleteAllPlaybackEvents() = databaseDao.deleteAllPlaybackEvents()

    suspend fun deleteUnfollowedArtists() = databaseDao.deleteUnfollowedArtists()

    suspend fun deleteNotificationsOfUnfollowedArtists() = databaseDao.deleteNotificationsOfUnfollowedArtists()

    suspend fun deleteFollowedArtistReleasesOfUnfollowedArtists() = databaseDao.deleteFollowedArtistReleasesOfUnfollowedArtists()

    suspend fun deleteUnfavoritedPodcasts() = databaseDao.deleteUnfavoritedPodcasts()

    suspend fun deleteUnreferencedAlbums() = databaseDao.deleteUnreferencedAlbums()

    suspend fun deleteUnreferencedPlaylists() = databaseDao.deleteUnreferencedPlaylists()

    suspend fun getOrphanedSongIds() = databaseDao.getOrphanedSongIds()

    /**
     * Drop the songs and everything that only existed to describe them.
     *
     * Batched because SQLite limits how many bind parameters a single statement may carry, and this
     * can run over a library with thousands of stale rows.
     *
     * @return how many songs were actually deleted, which can be fewer than were asked for — the
     * delete re-checks the orphan conditions, so anything the user touched in the meantime survives.
     */
    suspend fun deleteSongsAndRelatedData(videoIds: List<String>): Int =
        videoIds.chunked(DELETE_BATCH_SIZE).sumOf { batch ->
            // Songs first, so the follow-up deletes can tell which ids really went.
            val deleted = databaseDao.deleteSongsByIds(batch)
            databaseDao.deleteLyricsByIds(batch)
            databaseDao.deleteTranslatedLyricsByIds(batch)
            databaseDao.deleteNewFormatsByIds(batch)
            databaseDao.deleteSongInfoByIds(batch)
            deleted
        }

    /**
     * The same four tables again, but for rows whose song is already gone.
     *
     * [deleteSongsAndRelatedData] can only reach the ids it was handed, so these have been piling up
     * since long before this sweep existed. Unbatched on purpose — the statements bind nothing.
     *
     * @return how many rows went, across all four tables.
     */
    suspend fun deleteStaleSongSatellites(): Int =
        databaseDao.deleteStaleNewFormats() +
            databaseDao.deleteStaleLyrics() +
            databaseDao.deleteStaleTranslatedLyrics() +
            databaseDao.deleteStaleSongInfo()

    suspend fun getLikedSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getLikedSongs(
        limit,
        offset,
    )

    suspend fun getSong(videoId: String) = databaseDao.getSong(videoId)

    fun getSongAsFlow(videoId: String) = databaseDao.getSongAsFlow(videoId)

    /**
     * Every path that stores a track funnels through here — playback, playlist browsing, the
     * now-playing sheet, local playlist edits — which is why the self-repair below lives at this
     * level rather than at any one caller.
     *
     * The insert itself is IGNORE, so a track already in the database keeps whatever it was first
     * written with. That is usually right, but it also means a row saved with the old "Album"
     * placeholder can never learn its real album name, however many times it is seen again.
     *
     * Room returns -1 when IGNORE drops the row, which tells us the track already exists without
     * spending a read to ask. Only then, and only when this copy actually carries a real name, do
     * we let the database decide whether the stored row is stale — the WHERE clause in
     * [DatabaseDao.refreshAlbumIfPlaceholder] is what guarantees good data is never overwritten.
     *
     * The artist list is refreshed the same way. Rows written before the parser split the subtitle
     * column on " • " kept its trailing groups too, so the album name and the view count were
     * stored as extra artists ("JENNIE", "13M plays") and travelled out to MediaSession metadata.
     */
    suspend fun insertSong(song: SongEntity): Long {
        val rowId = databaseDao.insertSong(song)
        if (rowId == -1L) {
            val albumName = song.albumName
            if (!albumName.isNullOrBlank() && albumName != PLACEHOLDER_ALBUM_NAME) {
                databaseDao.refreshAlbumIfPlaceholder(
                    videoId = song.videoId,
                    albumName = albumName,
                    albumId = song.albumId,
                )
            }
            val artistName = song.artistName
            if (!artistName.isNullOrEmpty()) {
                databaseDao.refreshArtists(
                    videoId = song.videoId,
                    artistName = artistName,
                    artistId = song.artistId,
                )
            }
        }
        return rowId
    }

    /**
     * Batch counterpart of [insertSong], used by the importer.
     *
     * The IGNORE-and-self-repair policy above lives inside the DAO method here, because the
     * transaction has to wrap the whole batch and only a DAO method can be `@Transaction`.
     */
    suspend fun insertSongs(songs: List<SongEntity>) = databaseDao.insertSongs(songs)

    suspend fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ) = databaseDao.updateThumbnailsSongEntity(thumbnail, videoId)

    suspend fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ) = databaseDao.updateVideoTypeSongEntity(videoType, videoId)

    suspend fun updateListenCount(videoId: String) = databaseDao.updateTotalPlayTime(videoId)

    suspend fun resetTotalPlayTime(videoId: String) = databaseDao.resetTotalPlayTime(videoId)

    suspend fun updateCanvasUrl(
        videoId: String,
        canvasUrl: String,
    ) = databaseDao.updateCanvasUrl(videoId, canvasUrl)

    suspend fun updateCanvasThumbUrl(
        videoId: String,
        canvasThumbUrl: String,
    ) = databaseDao.updateCanvasThumbUrl(videoId, canvasThumbUrl)

    suspend fun updateLiked(
        liked: Int,
        videoId: String,
    ) = databaseDao.updateLiked(liked, videoId)

    suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    ) = databaseDao.updateDurationSeconds(durationSeconds, videoId)

    suspend fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ) = databaseDao.updateSongInLibrary(inLibrary, videoId)

    fun getMostPlayedSongs() = databaseDao.getMostPlayedSongs()

    suspend fun updateDownloadState(
        downloadState: Int,
        videoId: String,
    ) = databaseDao.updateDownloadState(downloadState, videoId)

    suspend fun getAllArtists(limit: Int) = databaseDao.getAllArtists(limit)

    suspend fun insertArtist(artist: ArtistEntity) = databaseDao.insertArtist(artist)

    suspend fun updateArtistImage(
        channelId: String,
        thumbnails: String,
    ) = databaseDao.updateArtistImage(channelId, thumbnails)

    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    ) = databaseDao.updateArtistNameLogo(channelId, nameLogoUrl, nameLogoColor)

    suspend fun updateFollowed(
        followed: Int,
        channelId: String,
    ) = databaseDao.updateFollowed(followed, channelId)

    suspend fun getArtist(channelId: String) = databaseDao.getArtist(channelId)

    suspend fun getFollowedArtists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getFollowedArtists(
        limit,
        offset,
    )

    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    ) = databaseDao.updateArtistInLibrary(inLibrary, channelId)

    suspend fun getAllAlbums(limit: Int) = databaseDao.getAllAlbums(limit)

    suspend fun insertAlbum(album: AlbumEntity) = databaseDao.insertAlbum(album)

    suspend fun updateAlbumLiked(
        liked: Int,
        albumId: String,
    ) = databaseDao.updateAlbumLiked(liked, albumId)

    suspend fun getAlbum(albumId: String) = databaseDao.getAlbum(albumId)

    fun getAlbumAsFlow(albumId: String) = databaseDao.getAlbumAsFlow(albumId)

    suspend fun getLikedAlbums(
        limit: Int,
        offset: Int,
    ) = databaseDao.getLikedAlbums(
        limit,
        offset,
    )

    suspend fun updateAlbumInLibrary(
        inLibrary: LocalDateTime,
        albumId: String,
    ) = databaseDao.updateAlbumInLibrary(inLibrary, albumId)

    suspend fun updateAlbumDownloadState(
        downloadState: Int,
        albumId: String,
    ) = databaseDao.updateAlbumDownloadState(downloadState, albumId)

    suspend fun getAllPlaylists(limit: Int) = databaseDao.getAllPlaylists(limit)

    suspend fun insertPlaylist(playlist: PlaylistEntity) = databaseDao.insertPlaylist(playlist)

    suspend fun insertAndReplacePlaylist(playlist: PlaylistEntity) = databaseDao.insertAndReplacePlaylist(playlist)

    suspend fun insertRadioPlaylist(playlist: PlaylistEntity) = databaseDao.insertRadioPlaylist(playlist)

    suspend fun updatePlaylistLiked(
        liked: Int,
        playlistId: String,
    ) = databaseDao.updatePlaylistLiked(liked, playlistId)

    suspend fun getPlaylist(playlistId: String) = databaseDao.getPlaylist(playlistId)

    suspend fun getLikedPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getLikedPlaylists(
        limit,
        offset,
    )

    suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    ) = databaseDao.updatePlaylistInLibrary(inLibrary, playlistId)

    suspend fun updatePlaylistDownloadState(
        downloadState: Int,
        playlistId: String,
    ) = databaseDao.updatePlaylistDownloadState(downloadState, playlistId)

    suspend fun getAllLocalPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getAllLocalPlaylists(
        limit,
        offset,
    )

    suspend fun getAllDownloadingLocalPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getAllDownloadingLocalPlaylists(
        limit,
        offset,
    )

    suspend fun getLocalPlaylist(id: Long) = databaseDao.getLocalPlaylist(id)

    suspend fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity) = databaseDao.insertLocalPlaylist(localPlaylist)

    suspend fun insertLocalPlaylistWithTracks(
        localPlaylist: LocalPlaylistEntity,
        videoIds: List<String>,
    ) = databaseDao.insertLocalPlaylistWithTracks(localPlaylist, videoIds)

    suspend fun deleteLocalPlaylist(id: Long) = databaseDao.deleteLocalPlaylist(id)

    suspend fun updateLocalPlaylistTitle(
        title: String,
        id: Long,
    ) = databaseDao.updateLocalPlaylistTitle(title, id)

    suspend fun updateLocalPlaylistThumbnail(
        thumbnail: String,
        id: Long,
    ) = databaseDao.updateLocalPlaylistThumbnail(thumbnail, id)

    suspend fun updateLocalPlaylistTracks(
        tracks: List<String>,
        id: Long,
    ) = databaseDao.updateLocalPlaylistTracks(tracks, id)

    suspend fun updateLocalPlaylistInLibrary(
        inLibrary: LocalDateTime,
        id: Long,
    ) = databaseDao.updateLocalPlaylistInLibrary(inLibrary, id)

    suspend fun updateLocalPlaylistDownloadState(
        downloadState: Int,
        id: Long,
    ) = databaseDao.updateLocalPlaylistDownloadState(downloadState, id)

    suspend fun getDownloadedLocalPlaylists(
        limit: Int,
        offset: Int,
    ) = databaseDao.getDownloadedLocalPlaylists(
        limit,
        offset,
    )

    suspend fun updateLocalPlaylistYouTubePlaylistId(
        id: Long,
        ytId: String?,
    ) = databaseDao.updateLocalPlaylistYouTubePlaylistId(id, ytId)

    suspend fun updateLocalPlaylistYouTubePlaylistSyncState(
        id: Long,
        syncState: Int,
    ) = databaseDao.updateLocalPlaylistYouTubePlaylistSyncState(id, syncState)

    fun getDownloadStateFlowOfLocalPlaylist(id: Long) = databaseDao.getDownloadStateFlowOfLocalPlaylist(id)

    fun getListTracksFlowOfLocalPlaylist(id: Long) = databaseDao.getListTracksFlowOfLocalPlaylist(id)

    suspend fun getSavedLyrics(videoId: String) = databaseDao.getLyrics(videoId)

    suspend fun insertLyrics(lyrics: LyricsEntity) = databaseDao.insertLyrics(lyrics)

    suspend fun getPreparingSongs(
        limit: Int,
        offset: Int,
    ) = databaseDao.getPreparingSongs(
        limit,
        offset,
    )

    suspend fun insertNewFormat(format: NewFormatEntity) = databaseDao.insertNewFormat(format)

    suspend fun getNewFormat(videoId: String) = databaseDao.getNewFormat(videoId)

    suspend fun updateNewFormat(newFormatEntity: NewFormatEntity) = databaseDao.updateNewFormat(newFormatEntity)

    suspend fun getNewFormatAsFlow(videoId: String) = databaseDao.getNewFormatAsFlow(videoId)

    suspend fun insertSongInfo(songInfo: SongInfoEntity) = databaseDao.insertSongInfo(songInfo)

    suspend fun getSongInfo(videoId: String) = databaseDao.getSongInfo(videoId)

    suspend fun recoverQueue(queueEntity: QueueEntity) = databaseDao.recoverQueue(queueEntity)

    suspend fun getQueue() = databaseDao.getQueue()

    suspend fun deleteQueue() = databaseDao.deleteQueue()

    suspend fun getLocalPlaylistByYoutubePlaylistId(playlistId: String) = databaseDao.getLocalPlaylistByYoutubePlaylistId(playlistId)

    suspend fun insertSetVideoId(setVideoIdEntity: SetVideoIdEntity) = databaseDao.insertSetVideoId(setVideoIdEntity)

    suspend fun getSetVideoId(videoId: String) = databaseDao.getSetVideoId(videoId)

    suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist) =
        databaseDao.insertPairSongLocalPlaylist(pairSongLocalPlaylist)

    suspend fun unsyncLocalPlaylist(id: Long) = databaseDao.unsyncLocalPlaylist(id)

    suspend fun getPlaylistPairOfSong(
        videoId: String,
        localPlaylistId: Long,
    ) = databaseDao.getPlaylistPairOfSong(videoId, localPlaylistId)

    suspend fun getPlaylistPairSong(
        playlistId: Long,
        limit: Int,
        offset: Int,
    ) = databaseDao.getPlaylistPairSong(
        playlistId,
        limit,
        offset,
    )

    suspend fun getPlaylistPairSongByListPosition(
        playlistId: Long,
        listPosition: List<Int>,
    ) = databaseDao.getPlaylistPairSongByListPosition(playlistId, listPosition)

    /**
     * Search one local playlist by title or artist.
     *
     * Escaping happens here rather than in the DAO because the term is a bind parameter, not a
     * column: Room passes the value through untouched, and LIKE still reads `%` and `_` inside it
     * as wildcards. A user typing `%` would otherwise get the whole playlist back.
     */
    suspend fun searchPlaylistPairSong(
        playlistId: Long,
        query: String,
        limit: Int,
    ) = databaseDao.searchPlaylistPairSong(
        playlistId = playlistId,
        query = "%" + query.escapeForLike() + "%",
        limit = limit,
    )

    suspend fun getPlaylistPairSongByOffset(
        playlistId: Long,
        offset: Int,
        filterState: FilterState,
    ) = if (filterState == FilterState.CustomOrder) {
        databaseDao.getPlaylistPairSongByOffset(
            playlistId,
            offset * 50,
        )
    } else if (filterState == FilterState.Title) {
        databaseDao.getPlaylistPairSongByTitle(
            playlistId,
            offset * 50,
        )
    } else {
        null
    }

    suspend fun getPlaylistPairSongByTime(
        playlistId: Long,
        filterState: FilterState,
        localDateTime: LocalDateTime,
    ) = if (filterState == FilterState.OlderFirst) {
        databaseDao.getPlaylistPairSongByOlderFirst(
            playlistId,
            localDateTime,
        )
    } else if (filterState == FilterState.NewerFirst) {
        databaseDao.getPlaylistPairSongByNewerFirst(
            playlistId,
            localDateTime,
        )
    } else {
        null
    }

    suspend fun getNewestPlaylistPairSong(playlistId: Long) = databaseDao.getNewestPlaylistPairSong(playlistId)

    suspend fun editPositionOfSongInPlaylist(
        playlistId: Long,
        videoId: String,
        newPosition: Int,
    ) = databaseDao.editPositionOfSongInPlaylist(
        playlistId,
        videoId,
        newPosition,
    )

    suspend fun getAllPlaylistPairSongByPosition(playlistId: Long) =
        databaseDao.getAllPlaylistPairSongByPosition(playlistId)

    suspend fun shiftPositionsForward(
        playlistId: Long,
        from: Int,
        to: Int,
    ) = databaseDao.shiftPositionsForward(playlistId, from, to)

    suspend fun shiftPositionsBackward(
        playlistId: Long,
        from: Int,
        to: Int,
    ) = databaseDao.shiftPositionsBackward(playlistId, from, to)

    suspend fun deletePairSongLocalPlaylist(
        playlistId: Long,
        videoId: String,
    ) = databaseDao.deletePairSongLocalPlaylist(playlistId, videoId)

    suspend fun getGoogleAccounts() = databaseDao.getAllGoogleAccount()

    suspend fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity) = databaseDao.insertGoogleAccount(googleAccountEntity)

    suspend fun getUsedGoogleAccount() = databaseDao.getUsedGoogleAccount()

    suspend fun deleteGoogleAccount(email: String) = databaseDao.deleteGoogleAccount(email)

    suspend fun updateGoogleAccountUsed(
        email: String,
        isUsed: Boolean,
    ) = databaseDao.updateGoogleAccountUsed(isUsed, email)

    suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    ) = databaseDao.setInLibrary(videoId, inLibrary)

    suspend fun insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum: FollowedArtistSingleAndAlbum) =
        databaseDao.insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum)

    suspend fun deleteFollowedArtistSingleAndAlbum(channelId: String) = databaseDao.deleteFollowedArtistSingleAndAlbum(channelId)

    suspend fun getFollowedArtistSingleAndAlbum(channelId: String) = databaseDao.getFollowedArtistSingleAndAlbum(channelId)

    suspend fun getAllFollowedArtistSingleAndAlbums(
        limit: Int,
        offset: Int,
    ) = databaseDao.getAllFollowedArtistSingleAndAlbum(
        limit,
        offset,
    )

    suspend fun insertNotification(notificationEntity: NotificationEntity) = databaseDao.insertNotification(notificationEntity)

    suspend fun getAllNotification() = databaseDao.getAllNotification()

    suspend fun countNotificationByLink(link: String) = databaseDao.countNotificationByLink(link)

    suspend fun deleteNotification(id: Long) = databaseDao.deleteNotification(id)

    suspend fun deleteNotificationsByChannelId(channelId: String) = databaseDao.deleteNotificationsByChannelId(channelId)

    suspend fun getTranslatedLyrics(
        videoId: String,
        language: String,
    ) = databaseDao.getTranslatedLyrics(videoId, language)

    suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    ) = databaseDao.removeTranslatedLyrics(videoId, language)

    suspend fun insertTranslatedLyrics(translatedLyricsEntity: TranslatedLyricsEntity) = databaseDao.insertTranslatedLyrics(translatedLyricsEntity)

    suspend fun insertPodcast(podcastsEntity: PodcastsEntity) = databaseDao.insertPodcast(podcastsEntity)

    suspend fun insertEpisodes(episodes: List<EpisodeEntity>) = databaseDao.insertEpisodes(episodes)

    suspend fun getPodcastWithEpisodes(podcastId: String) = databaseDao.getPodcastWithEpisodes(podcastId)

    suspend fun getAllPodcasts(limit: Int) = databaseDao.getAllPodcasts(limit)

    suspend fun getAllPodcastWithEpisodes(
        limit: Int,
        offset: Int,
    ) = databaseDao.getAllPodcastWithEpisodes(
        limit,
        offset,
    )

    suspend fun getPodcast(podcastId: String) = databaseDao.getPodcast(podcastId)

    suspend fun getFavoritePodcasts(
        limit: Int,
        offset: Int,
    ) = databaseDao.getFavoritePodcasts(
        limit,
        offset,
    )

    suspend fun getEpisode(videoId: String) = databaseDao.getEpisode(videoId)

    suspend fun deletePodcast(podcastId: String) = databaseDao.deletePodcast(podcastId)

    suspend fun favoritePodcast(
        podcastId: String,
        isFavorite: Boolean,
    ): Boolean {
        val podcast = databaseDao.getPodcast(podcastId)
        if (podcast != null) {
            val updatedPodcast =
                podcast.copy(
                    isFavorite = isFavorite,
                    favoriteTime = if (isFavorite) now() else null,
                )
            return databaseDao.insertPodcast(updatedPodcast) > 0
        } else {
            return false
        }
    }

    suspend fun getPodcastEpisodes(
        podcastId: String,
        limit: Int,
        offset: Int,
    ) = databaseDao.getPodcastEpisodes(
        podcastId,
        limit,
        offset,
    )

    suspend fun updatePodcastInLibraryNow(id: String) = databaseDao.updatePodcastInLibrary(id,
        now()
    )

    suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) =
        databaseDao.insertYourYouTubePlaylist(yourYouTubePlaylist)

    suspend fun getYourYouTubePlaylistList(
        emailPageId: String
    ): YourYouTubePlaylistList? =
        databaseDao.getYourYouTubePlaylistList(emailPageId)

    suspend fun deleteAllYourYouTubePlaylist() =
        databaseDao.deleteAllYourYouTubePlaylist()


    // ========== AutoEq ==========

    suspend fun getAutoEqEntryCount(): Int = databaseDao.getAutoEqEntryCount()

    suspend fun searchAutoEqEntries(
        query: String,
        limit: Int,
    ): List<AutoEqEntryEntity> =
        if (query.isBlank()) {
            databaseDao.getAutoEqEntries(limit)
        } else {
            databaseDao.searchAutoEqEntries(query.escapeForLike(), limit)
        }

    suspend fun getAutoEqCurve(path: String): AutoEqCurveEntity? = databaseDao.getAutoEqCurve(path)

    suspend fun insertAutoEqCurve(curve: AutoEqCurveEntity) = databaseDao.insertAutoEqCurve(curve)

    suspend fun getAutoEqCachedCurvePaths(): List<String> = databaseDao.getAutoEqCachedCurvePaths()

    suspend fun getAutoEqIndexMeta(): AutoEqIndexMetaEntity? = databaseDao.getAutoEqIndexMeta()

    /** Records that the cache was checked, without touching the rows it describes. */
    suspend fun updateAutoEqIndexMeta(meta: AutoEqIndexMetaEntity) = databaseDao.upsertAutoEqIndexMeta(meta)

    suspend fun replaceAutoEqIndex(
        entries: List<AutoEqEntryEntity>,
        meta: AutoEqIndexMetaEntity,
    ) = databaseDao.replaceAutoEqIndex(entries, meta)
}

/**
 * Escapes the LIKE wildcards in a user-typed term, for use with `ESCAPE '\\'`.
 *
 * The backslash goes first: escaping it after `_` and `%` would escape the backslashes this very
 * function just added, turning `\_` back into a literal backslash followed by a wildcard.
 */
private fun String.escapeForLike(): String =
    replace("\\", "\\\\")
        .replace("_", "\\_")
        .replace("%", "\\%")
