import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Update
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
import com.maxrave.domain.data.entities.PodcastWithEpisodes
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.entities.analytics.EventArtistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.analytics.query.DecadeCount
import com.maxrave.domain.data.entities.analytics.query.PlaybackSample
import com.maxrave.domain.data.entities.analytics.query.TopPlayedAlbum
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtist
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtistTime
import com.maxrave.domain.data.entities.analytics.query.TopPlayedTracks
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.extension.now
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

/**
 * The album name older builds stored when they could not find a real one.
 *
 * Kept in step with the copy in [com.maxrave.data.db.datasource.LocalDataSource] and with the
 * literal baked into [DatabaseDao.refreshAlbumIfPlaceholder]'s WHERE clause.
 */
private const val PLACEHOLDER_ALBUM_NAME = "Album"

@Dao
interface DatabaseDao {
    // Transaction request with multiple queries
    @Transaction
    suspend fun getAllRecentData(): List<RecentlyType> {
        val a = mutableListOf<RecentlyType>()
        a.addAll(getAllSongs(30))
        a.addAll(getAllArtists(30))
        a.addAll(getAllAlbums(30))
        a.addAll(getAllPlaylists(30))
        a.addAll(getAllPodcasts(30))
        val sortedList =
            a.sortedWith<RecentlyType>(
                Comparator { p0, p1 ->
                    val timeP0: LocalDateTime? =
                        when (p0) {
                            is SongEntity -> p0.inLibrary
                            is ArtistEntity -> p0.inLibrary
                            is AlbumEntity -> p0.inLibrary
                            is PlaylistEntity -> p0.inLibrary
                            is PodcastsEntity -> p0.inLibrary
                            else -> null
                        }
                    val timeP1: LocalDateTime? =
                        when (p1) {
                            is SongEntity -> p1.inLibrary
                            is ArtistEntity -> p1.inLibrary
                            is AlbumEntity -> p1.inLibrary
                            is PlaylistEntity -> p1.inLibrary
                            is PodcastsEntity -> p1.inLibrary
                            else -> null
                        }
                    if (timeP0 == null || timeP1 == null) {
                        return@Comparator if (timeP0 == null && timeP1 == null) {
                            0
                        } else if (timeP0 == null) {
                            -1
                        } else {
                            1
                        }
                    }
                    timeP0.compareTo(timeP1) // Sort in descending order by inLibrary time
                },
            )
        return sortedList.takeLast(30)
    }

    @Transaction
    suspend fun getAllDownloadedPlaylist(): List<PlaylistType> {
        val a = mutableListOf<PlaylistType>()
        var shouldContinue = true to 0
        while (shouldContinue.first) {
            val fetched =
                getDownloadedAlbums(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinue =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinue.second + 100
                }
        }
        var shouldContinuePlaylist = true to 0
        while (shouldContinuePlaylist.first) {
            val fetched =
                getDownloadedPlaylists(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinuePlaylist =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinuePlaylist.second + 100
                }
        }
        val sortedList =
            a.sortedWith<PlaylistType>(
                Comparator { p0, p1 ->
                    val timeP0: LocalDateTime? =
                        when (p0) {
                            is AlbumEntity -> p0.downloadedAt ?: p0.inLibrary
                            is PlaylistEntity -> p0.downloadedAt ?: p0.inLibrary
                            else -> null
                        }
                    val timeP1: LocalDateTime? =
                        when (p1) {
                            is AlbumEntity -> p1.downloadedAt ?: p1.inLibrary
                            is PlaylistEntity -> p1.downloadedAt ?: p1.inLibrary
                            else -> null
                        }
                    if (timeP0 == null || timeP1 == null) {
                        return@Comparator if (timeP0 == null && timeP1 == null) {
                            0
                        } else if (timeP0 == null) {
                            -1
                        } else {
                            1
                        }
                    }
                    timeP0.compareTo(timeP1) // Sort in descending order by inLibrary time
                },
            )
        return sortedList
    }

    @Transaction
    suspend fun getAllDownloadingPlaylist(): List<PlaylistType> {
        val a = mutableListOf<PlaylistType>()
        var shouldContinue = true to 0
        while (shouldContinue.first) {
            val fetched =
                getDownloadingAlbums(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinue =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinue.second + 100
                }
        }
        var shouldContinuePlaylist = true to 0
        while (shouldContinuePlaylist.first) {
            val fetched =
                getDownloadingPlaylists(
                    limit = 100,
                    offset = shouldContinue.second,
                )
            a.addAll(
                fetched,
            )
            shouldContinuePlaylist =
                if (fetched.size < 100) {
                    false to 0
                } else {
                    true to shouldContinuePlaylist.second + 100
                }
        }
        return a
    }

    // Get search history
    @Query("SELECT * FROM search_history")
    suspend fun getSearchHistory(): List<SearchHistory>

    @Query("DELETE FROM search_history")
    suspend fun deleteSearchHistory()

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertSearchHistory(searchHistory: SearchHistory): Long

    // Song
    @Query("SELECT * FROM song ORDER BY inLibrary DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecentSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllSongs(limit: Int): List<SongEntity>

    @Query("SELECT * FROM song WHERE liked = 1 ORDER BY favoriteAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getLikedSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE videoId = :videoId")
    suspend fun getSong(videoId: String): SongEntity?

    @Query("SELECT * FROM song WHERE videoId = :videoId")
    fun getSongAsFlow(videoId: String): Flow<SongEntity?>

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + 1 WHERE videoId = :videoId")
    suspend fun updateTotalPlayTime(videoId: String)

    @Query("UPDATE song SET totalPlayTime = 0 WHERE videoId = :videoId")
    suspend fun resetTotalPlayTime(videoId: String)

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE videoId = :videoId")
    suspend fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Int

    @Query("UPDATE song SET liked = :liked, favoriteAt = :favoriteAt WHERE videoId = :videoId")
    suspend fun updateLiked(
        liked: Int,
        videoId: String,
        favoriteAt: LocalDateTime? = if (liked == 1) now() else null,
    )

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertSong(song: SongEntity): Long

    /**
     * Stores a batch of tracks in one transaction.
     *
     * Every other path inserts one song at a time through
     * [com.maxrave.data.db.datasource.LocalDataSource.insertSong], which is right when a track
     * arrives on its own but costs a commit each time. An import writes thousands in a row, so the
     * batch collapses them into a handful of commits instead.
     *
     * The per-row semantics are deliberately identical to that single-song path: the insert is
     * IGNORE, and a row that was dropped because it already exists (rowId -1) gets the same two
     * self-repair passes — see [refreshAlbumIfPlaceholder] and [refreshArtists] for why they exist
     * and why their WHERE clauses are what keep good data from being overwritten.
     */
    @Transaction
    suspend fun insertSongs(songs: List<SongEntity>) {
        songs.forEach { song ->
            val rowId = insertSong(song)
            if (rowId == -1L) {
                val albumName = song.albumName
                if (!albumName.isNullOrBlank() && albumName != PLACEHOLDER_ALBUM_NAME) {
                    refreshAlbumIfPlaceholder(
                        videoId = song.videoId,
                        albumName = albumName,
                        albumId = song.albumId,
                    )
                }
                val artistName = song.artistName
                if (!artistName.isNullOrEmpty()) {
                    refreshArtists(
                        videoId = song.videoId,
                        artistName = artistName,
                        artistId = song.artistId,
                    )
                }
            }
        }
    }

    /**
     * Fills in an album name that an older parse never had.
     *
     * Rows written before the playlist parser read the album column carry the literal string
     * "Album" — a placeholder invented because the old code took the album's browse id from the
     * context menu, which has an id but no name. Those rows are never refreshed on their own:
     * [insertSong] is IGNORE, so every later encounter with the same track is discarded.
     *
     * The WHERE clause is the safety catch, not just a filter. A row that already holds a real
     * album name is untouched no matter who calls this, so a caller holding a sparser entity —
     * the playback path, say — cannot overwrite good data with worse.
     */
    @Query(
        "UPDATE song SET albumName = :albumName, albumId = :albumId " +
            "WHERE videoId = :videoId AND (albumName IS NULL OR albumName = '' OR albumName = 'Album')",
    )
    suspend fun refreshAlbumIfPlaceholder(
        videoId: String,
        albumName: String,
        albumId: String?,
    )

    /**
     * Replaces an artist list that an older parse polluted with the row's trailing metadata.
     *
     * The parser used to split the subtitle column on " • " and keep every group, so a row could
     * be stored as `["JENNIE", "13M plays"]` — the view count read as a second artist. It now
     * keeps only the first group, but [insertSong] is IGNORE, so a row already in the database
     * never learns the corrected list on its own.
     *
     * The WHERE clause is the safety catch, exactly as in [refreshAlbumIfPlaceholder]: the update
     * only lands when the stored list actually differs from the fresh one, so repeatedly seeing
     * the same track costs nothing and a caller cannot rewrite a row with what it already holds.
     */
    @Query(
        "UPDATE song SET artistName = :artistName, artistId = :artistId " +
            "WHERE videoId = :videoId AND artistName IS NOT :artistName",
    )
    suspend fun refreshArtists(
        videoId: String,
        artistName: List<String>,
        artistId: List<String>?,
    )

    @Query("UPDATE song SET canvasUrl = :canvasUrl WHERE videoId = :videoId")
    suspend fun updateCanvasUrl(
        videoId: String,
        canvasUrl: String,
    )

    @Query("UPDATE song SET canvasThumbUrl = :canvasThumbUrl WHERE videoId = :videoId")
    suspend fun updateCanvasThumbUrl(
        videoId: String,
        canvasThumbUrl: String,
    )

    @Query("UPDATE song SET thumbnails = :thumbnails WHERE videoId = :videoId")
    suspend fun updateThumbnailsSongEntity(
        thumbnails: String,
        videoId: String,
    ): Int

    @Query("UPDATE song SET videoType = :videoType WHERE videoId = :videoId")
    suspend fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Int

    @Query("SELECT * FROM song WHERE totalPlayTime > 1 ORDER BY totalPlayTime DESC LIMIT 50")
    fun getMostPlayedSongs(): Flow<List<SongEntity>>

    @Query("UPDATE song SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE videoId = :videoId")
    suspend fun updateDownloadState(
        downloadState: Int,
        videoId: String,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("UPDATE song SET durationSeconds = :durationSeconds WHERE videoId = :videoId")
    suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    )

    @Query("SELECT * FROM song WHERE downloadState = 3 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE downloadState = 1 OR downloadState = 2 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadingSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE videoId IN (:primaryKeyList) LIMIT 1000")
    suspend fun getSongByListVideoIdFull(primaryKeyList: List<String>): List<SongEntity>

    @Query("SELECT * FROM song WHERE videoId IN (:primaryKeyList) LIMIT 50 OFFSET :offset")
    suspend fun getSongByListVideoId(
        primaryKeyList: List<String>,
        offset: Int,
    ): List<SongEntity>

    @Query("SELECT * FROM song WHERE canvasThumbUrl IS NOT NULL ORDER BY totalPlayTime DESC LIMIT :max")
    suspend fun getCanvasSong(max: Int): List<SongEntity>

    @Query("SELECT videoId FROM song WHERE videoId IN (:primaryKeyList) AND downloadState = 3")
    fun getDownloadedVideoIdByListVideoId(primaryKeyList: List<String>): Flow<List<String>>

    // Artist
    @Query("SELECT * FROM artist ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllArtists(limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM artist WHERE channelId = :channelId")
    suspend fun getArtist(channelId: String): ArtistEntity?

    @Query("SELECT * FROM artist WHERE followed = 1 ORDER BY followedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getFollowedArtists(
        limit: Int,
        offset: Int,
    ): List<ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertArtist(artist: ArtistEntity)

    @Query("UPDATE artist SET thumbnails = :thumbnails WHERE channelId = :channelId")
    suspend fun updateArtistImage(
        channelId: String,
        thumbnails: String,
    )

    @Query("UPDATE artist SET nameLogoUrl = :nameLogoUrl, nameLogoColor = :nameLogoColor WHERE channelId = :channelId")
    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    )

    @Query("UPDATE artist SET followed = :followed, followedAt = :followedAt WHERE channelId = :channelId")
    suspend fun updateFollowed(
        followed: Int,
        channelId: String,
        followedAt: LocalDateTime? = if (followed == 1) now() else null,
    )

    @Query("UPDATE artist SET inLibrary = :inLibrary WHERE channelId = :channelId")
    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    )

    // Album
    @Query("SELECT * FROM album ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllAlbums(limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM album WHERE browseId = :browseId")
    suspend fun getAlbum(browseId: String): AlbumEntity?

    @Query("SELECT * FROM album WHERE browseId = :browseId")
    fun getAlbumAsFlow(browseId: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM album WHERE liked = 1 ORDER BY favoriteAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getLikedAlbums(
        limit: Int,
        offset: Int,
    ): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Query("UPDATE album SET liked = :liked, favoriteAt = :favoriteAt  WHERE browseId = :browseId")
    suspend fun updateAlbumLiked(
        liked: Int,
        browseId: String,
        favoriteAt: LocalDateTime? = if (liked == 1) now() else null,
    )

    @Query("UPDATE album SET inLibrary = :inLibrary WHERE browseId = :browseId")
    suspend fun updateAlbumInLibrary(
        inLibrary: LocalDateTime,
        browseId: String,
    )

    @Query("UPDATE album SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE browseId = :browseId")
    suspend fun updateAlbumDownloadState(
        downloadState: Int,
        browseId: String,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("SELECT * FROM album WHERE downloadState = 3 ORDER BY downloadedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedAlbums(
        limit: Int,
        offset: Int,
    ): List<AlbumEntity>

    @Query("SELECT * FROM album WHERE downloadState = 2 OR downloadState = 1 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadingAlbums(
        limit: Int,
        offset: Int,
    ): List<AlbumEntity>

    // Playlist
    @Query("SELECT * FROM playlist ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllPlaylists(limit: Int): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :playlistId")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE liked = 1 ORDER BY favoriteAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getLikedPlaylists(
        limit: Int,
        offset: Int,
    ): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertAndReplacePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertRadioPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlist SET liked = :liked, favoriteAt = :favoriteAt WHERE id = :playlistId")
    suspend fun updatePlaylistLiked(
        liked: Int,
        playlistId: String,
        favoriteAt: LocalDateTime? = if (liked == 1) now() else null,
    )

    @Query("UPDATE playlist SET inLibrary = :inLibrary WHERE id = :playlistId")
    suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    )

    @Query("UPDATE playlist SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE id = :playlistId")
    suspend fun updatePlaylistDownloadState(
        downloadState: Int,
        playlistId: String,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("SELECT * FROM playlist WHERE downloadState = 3 ORDER BY downloadedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedPlaylists(
        limit: Int,
        offset: Int,
    ): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE downloadState = 1 OR downloadState = 2 LIMIT :limit OFFSET :offset")
    suspend fun getDownloadingPlaylists(
        limit: Int,
        offset: Int,
    ): List<PlaylistEntity>

    // Local Playlist
    @Query("SELECT * FROM local_playlist ORDER BY inLibrary DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllLocalPlaylists(
        limit: Int,
        offset: Int,
    ): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlist WHERE downloadState = 1 OR downloadState = 2 LIMIT :limit OFFSET :offset")
    suspend fun getAllDownloadingLocalPlaylists(
        limit: Int,
        offset: Int,
    ): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlist WHERE id = :id")
    suspend fun getLocalPlaylist(id: Long): LocalPlaylistEntity?

    /**
     * Returns the generated `local_playlist.id`, or -1 when IGNORE dropped the row.
     *
     * `id` is the autoGenerate primary key, so the rowId Room hands back *is* the playlist id.
     * Callers that only create a playlist can keep ignoring the value.
     */
    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity): Long

    /**
     * Creates a playlist and its `pair_song_local_playlist` rows in one transaction.
     *
     * [videoIds] must already be filtered down to ids that have a `song` row — the pair table has a
     * foreign key to `song.videoId` and the insert fails otherwise. Position is the index in
     * [videoIds], so the caller is responsible for handing over a contiguous list.
     *
     * @return the new playlist id, or -1 when the playlist row was not written.
     */
    @Transaction
    suspend fun insertLocalPlaylistWithTracks(
        localPlaylist: LocalPlaylistEntity,
        videoIds: List<String>,
    ): Long {
        val playlistId = insertLocalPlaylist(localPlaylist)
        if (playlistId == -1L) return -1L
        val addedAt = now()
        videoIds.forEachIndexed { index, videoId ->
            insertPairSongLocalPlaylist(
                PairSongLocalPlaylist(
                    playlistId = playlistId,
                    songId = videoId,
                    position = index,
                    inPlaylist = addedAt,
                ),
            )
        }
        return playlistId
    }

    @Query("DELETE FROM local_playlist WHERE id = :id")
    suspend fun deleteLocalPlaylist(id: Long)

    @Query("UPDATE local_playlist SET title = :title WHERE id = :id")
    suspend fun updateLocalPlaylistTitle(
        title: String,
        id: Long,
    )

    @Query("UPDATE local_playlist SET tracks = :tracks WHERE id = :id")
    suspend fun updateLocalPlaylistTracks(
        tracks: List<String>,
        id: Long,
    )

    @Query("UPDATE local_playlist SET thumbnail = :thumbnail WHERE id = :id")
    suspend fun updateLocalPlaylistThumbnail(
        thumbnail: String,
        id: Long,
    )

    @Query("UPDATE local_playlist SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun updateLocalPlaylistInLibrary(
        inLibrary: LocalDateTime,
        id: Long,
    )

    @Query("UPDATE local_playlist SET downloadState = :downloadState, downloadedAt = :downloadedAt WHERE id = :id")
    suspend fun updateLocalPlaylistDownloadState(
        downloadState: Int,
        id: Long,
        downloadedAt: LocalDateTime? = if (downloadState == 3) now() else null,
    )

    @Query("SELECT * FROM local_playlist WHERE downloadState = 3 ORDER BY downloadedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadedLocalPlaylists(
        limit: Int,
        offset: Int,
    ): List<LocalPlaylistEntity>

    @Query("UPDATE local_playlist SET youtubePlaylistId = :youtubePlaylistId WHERE id = :id")
    suspend fun updateLocalPlaylistYouTubePlaylistId(
        id: Long,
        youtubePlaylistId: String?,
    )

    @Query("UPDATE local_playlist SET youtube_sync_state = :state WHERE id = :id")
    suspend fun updateLocalPlaylistYouTubePlaylistSyncState(
        id: Long,
        state: Int,
    )

    @Query("UPDATE local_playlist SET youtube_sync_state = 0, youtubePlaylistId = NULL WHERE id = :id")
    suspend fun unsyncLocalPlaylist(id: Long)

    @Query("SELECT downloadState FROM local_playlist WHERE id = :id")
    fun getDownloadStateFlowOfLocalPlaylist(id: Long): Flow<Int>

    @Query("SELECT tracks FROM local_playlist WHERE id = :id")
    fun getListTracksFlowOfLocalPlaylist(id: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId")
    suspend fun getLyrics(videoId: String): LyricsEntity?

    @RawQuery
    suspend fun raw(supportSQLiteQuery: RoomRawQuery): Int

    suspend fun checkpoint() {
        raw(RoomRawQuery("pragma wal_checkpoint(full)"))
    }

    // VACUUM deliberately does NOT live here: [raw] is generated as a read-only statement, and
    // Room's reader connections run under `PRAGMA query_only = 1`, which rejects it. See
    // `MusicDatabase.vacuum()`.

    @Query("SELECT * FROM song WHERE downloadState = 1 OR downloadState = 2 ORDER BY downloadedAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getPreparingSongs(
        limit: Int,
        offset: Int,
    ): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertNewFormat(format: NewFormatEntity)

    @Update
    suspend fun updateNewFormat(format: NewFormatEntity)

    @Query("SELECT * FROM new_format WHERE videoId = :videoId")
    suspend fun getNewFormat(videoId: String): NewFormatEntity?

    @Query("SELECT * FROM new_format WHERE videoId = :videoId")
    fun getNewFormatAsFlow(videoId: String): Flow<NewFormatEntity?>

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertSongInfo(songInfo: SongInfoEntity)

    @Query("SELECT * FROM song_info WHERE videoId = :videoId")
    suspend fun getSongInfo(videoId: String): SongInfoEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun recoverQueue(queue: QueueEntity)

    @Query("DELETE FROM queue")
    suspend fun deleteQueue()

    @Query("SELECT * FROM queue LIMIT 1 OFFSET 0")
    suspend fun getQueue(): List<QueueEntity>

    @Query("SELECT * FROM local_playlist WHERE youtubePlaylistId = :youtubePlaylistId")
    suspend fun getLocalPlaylistByYoutubePlaylistId(youtubePlaylistId: String): LocalPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertSetVideoId(setVideoIdEntity: SetVideoIdEntity)

    @Query("SELECT * FROM set_video_id WHERE videoId = :videoId")
    suspend fun getSetVideoId(videoId: String): SetVideoIdEntity?

    // PairSongLocalPlaylist
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist)

    @Query("SELECT * FROM pair_song_local_playlist WHERE songId = :videoId AND playlistId = :localPlaylistId LIMIT 1")
    suspend fun getPlaylistPairOfSong(
        videoId: String,
        localPlaylistId: Long,
    ): PairSongLocalPlaylist

    @Query("SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position ASC LIMIT :limit OFFSET :offset")
    suspend fun getPlaylistPairSong(
        playlistId: Long,
        limit: Int,
        offset: Int,
    ): List<PairSongLocalPlaylist>

    @Query("SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND position in (:positionList)")
    suspend fun getPlaylistPairSongByListPosition(
        playlistId: Long,
        positionList: List<Int>,
    ): List<PairSongLocalPlaylist>

    /**
     * Songs of one local playlist matching a search term, by title or artist.
     *
     * Joined rather than filtered in Kotlin: the paged reader walks
     * `pair_song_local_playlist` fifty rows at a time and only then looks the songs up, so a
     * filter applied to what it returned would search whatever the user had already scrolled
     * past and nothing else.
     *
     * [query] arrives ready to use — wildcards already wrapped around it and its own `%`, `_`
     * and `\\` already escaped by the caller, which is what ESCAPE refers to. A term typed as
     * `%` would otherwise match the entire playlist.
     *
     * `artistName` is a converted `List<String>` held as a JSON array, so this matches inside
     * that text. Good enough for a search box — a name is a name wherever it sits in the array —
     * unlike the id lookups elsewhere in this file, which have to match quoted tokens exactly.
     */
    @Query(
        "SELECT p.* FROM pair_song_local_playlist p JOIN song s ON s.videoId = p.songId " +
            "WHERE p.playlistId = :playlistId AND " +
            "(s.title LIKE :query ESCAPE '\\' OR s.artistName LIKE :query ESCAPE '\\') " +
            "ORDER BY p.position ASC LIMIT :limit",
    )
    suspend fun searchPlaylistPairSong(
        playlistId: Long,
        query: String,
        limit: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position " +
            "ASC LIMIT 50 OFFSET :offset",
    )
    suspend fun getPlaylistPairSongByOffset(
        playlistId: Long,
        offset: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND inPlaylist > :cutPoint ORDER BY position " +
            "ASC LIMIT 50",
    )
    suspend fun getPlaylistPairSongByOlderFirst(
        playlistId: Long,
        cutPoint: LocalDateTime,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND inPlaylist < :cutPoint ORDER BY position " +
            "DESC LIMIT 50",
    )
    suspend fun getPlaylistPairSongByNewerFirst(
        playlistId: Long,
        cutPoint: LocalDateTime,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position DESC LIMIT 1",
    )
    suspend fun getNewestPlaylistPairSong(playlistId: Long): PairSongLocalPlaylist?

    @Query(
        "UPDATE pair_song_local_playlist SET position = :newPosition WHERE playlistId = :playlistId AND songId = :videoId",
    )
    suspend fun editPositionOfSongInPlaylist(
        playlistId: Long,
        videoId: String,
        newPosition: Int,
    )

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position ASC",
    )
    suspend fun getAllPlaylistPairSongByPosition(playlistId: Long): List<PairSongLocalPlaylist>

    @Query(
        "UPDATE pair_song_local_playlist SET position = position + 1 WHERE playlistId = :playlistId AND position >= :from AND position < :to",
    )
    suspend fun shiftPositionsForward(
        playlistId: Long,
        from: Int,
        to: Int,
    )

    @Query(
        "UPDATE pair_song_local_playlist SET position = position - 1 WHERE playlistId = :playlistId AND position > :from AND position <= :to",
    )
    suspend fun shiftPositionsBackward(
        playlistId: Long,
        from: Int,
        to: Int,
    )

    @Query(
        "SELECT * FROM pair_song_local_playlist WHERE playlistId = :playlistId AND position >= :from AND position < :to ORDER BY position " +
            "LIMIT 50",
    )
    suspend fun getPlaylistPairSongByFromToDesc(
        playlistId: Long,
        from: Int,
        to: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "SELECT p.* FROM pair_song_local_playlist p JOIN song s ON p.songId = s.videoId WHERE" +
            " p.playlistId = :playlistId ORDER BY s.title ASC LIMIT 50 OFFSET :offset",
    )
    suspend fun getPlaylistPairSongByTitle(
        playlistId: Long,
        offset: Int,
    ): List<PairSongLocalPlaylist>

    @Query(
        "DELETE FROM pair_song_local_playlist WHERE songId = :videoId AND playlistId = :playlistId",
    )
    suspend fun deletePairSongLocalPlaylist(
        playlistId: Long,
        videoId: String,
    )

    // GoogleAccountEntity
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity): Long

    @Query("SELECT * FROM googleaccountentity")
    suspend fun getAllGoogleAccount(): List<GoogleAccountEntity>

    @Query("SELECT * FROM googleaccountentity WHERE isUsed = 1")
    suspend fun getUsedGoogleAccount(): GoogleAccountEntity?

    @Query("UPDATE googleaccountentity SET isUsed = :isUsed WHERE email = :email")
    suspend fun updateGoogleAccountUsed(
        isUsed: Boolean,
        email: String,
    ): Int

    @Query("DELETE FROM googleaccountentity WHERE email = :email")
    suspend fun deleteGoogleAccount(email: String)

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE videoId = :videoId")
    suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    )

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum: FollowedArtistSingleAndAlbum)

    @Query("SELECT * FROM followed_artist_single_and_album WHERE channelId = :channelId")
    suspend fun getFollowedArtistSingleAndAlbum(channelId: String): FollowedArtistSingleAndAlbum?

    @Query("DELETE FROM followed_artist_single_and_album WHERE channelId = :channelId")
    suspend fun deleteFollowedArtistSingleAndAlbum(channelId: String)

    @Query("SELECT * FROM followed_artist_single_and_album LIMIT :limit OFFSET :offset")
    suspend fun getAllFollowedArtistSingleAndAlbum(
        limit: Int,
        offset: Int,
    ): List<FollowedArtistSingleAndAlbum>

    @Insert
    suspend fun insertNotification(notificationEntity: NotificationEntity)

    @Query("SELECT * FROM notification ORDER BY time DESC LIMIT 100")
    suspend fun getAllNotification(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notification WHERE link = :link")
    suspend fun countNotificationByLink(link: String): Int

    @Query("DELETE FROM notification WHERE id = :id")
    suspend fun deleteNotification(id: Long)

    @Query("DELETE FROM notification WHERE channelId = :channelId")
    suspend fun deleteNotificationsByChannelId(channelId: String)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertTranslatedLyrics(translatedLyricsEntity: TranslatedLyricsEntity)

    @Query("SELECT * FROM translated_lyrics WHERE videoId = :videoId AND language = :language")
    suspend fun getTranslatedLyrics(
        videoId: String,
        language: String = "en",
    ): TranslatedLyricsEntity?

    @Query("DELETE FROM translated_lyrics WHERE videoId = :videoId AND language = :language")
    suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    )

    // Insert methods
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertPodcast(podcast: PodcastsEntity): Long

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>): List<Long>

    // Get methods
    @Transaction
    @Query("SELECT * FROM podcast_table WHERE podcastId = :podcastId")
    suspend fun getPodcastWithEpisodes(podcastId: String): PodcastWithEpisodes?

    @Transaction
    @Query("SELECT * FROM podcast_table ORDER BY inLibrary DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllPodcastWithEpisodes(
        limit: Int,
        offset: Int,
    ): List<PodcastWithEpisodes>

    @Query("SELECT * FROM podcast_table ORDER BY inLibrary DESC LIMIT :limit OFFSET 0")
    suspend fun getAllPodcasts(limit: Int): List<PodcastsEntity>

    @Query("SELECT * FROM podcast_table WHERE podcastId = :podcastId")
    suspend fun getPodcast(podcastId: String): PodcastsEntity?

    @Query("SELECT * FROM podcast_episode_table WHERE podcastId = :podcastId LIMIT :limit OFFSET :offset")
    suspend fun getPodcastEpisodes(
        podcastId: String,
        limit: Int,
        offset: Int,
    ): List<EpisodeEntity>

    @Query("SELECT * FROM podcast_episode_table WHERE videoId = :videoId")
    suspend fun getEpisode(videoId: String): EpisodeEntity?

    @Query("SELECT * FROM podcast_table WHERE isFavorite = 1 ORDER BY favoriteTime DESC LIMIT :limit OFFSET :offset")
    suspend fun getFavoritePodcasts(
        limit: Int,
        offset: Int,
    ): List<PodcastsEntity>

    @Query("UPDATE podcast_table SET inLibrary = :inLibrary WHERE podcastId = :id")
    suspend fun updatePodcastInLibrary(
        id: String,
        inLibrary: LocalDateTime,
    ): Int

    // Delete methods
    @Query("DELETE FROM podcast_table WHERE podcastId = :podcastId")
    suspend fun deletePodcast(podcastId: String): Int

    // Your YouTube Playlist list
    @Insert(onConflict = REPLACE)
    suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList)

    @Query("SELECT * FROM your_youtube_playlist_list WHERE emailPageId = :emailPageId LIMIT 1")
    suspend fun getYourYouTubePlaylistList(emailPageId: String): YourYouTubePlaylistList?

    @Query("DELETE FROM your_youtube_playlist_list")
    suspend fun deleteAllYourYouTubePlaylist()

    // Playback Event
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaybackEvent(playbackEventEntity: PlaybackEventEntity): Long

    @Insert
    suspend fun insertEventArtist(eventArtist: EventArtistEntity)

    @Transaction
    suspend fun insertPlaybackWithArtists(
        videoId: String,
        channelIds: List<String>,
        albumBrowseId: String?,
        durationSecond: Long,
        listenedSecond: Long,
    ): Long {
        val timestamp = now()

        val eventId =
            insertPlaybackEvent(
                PlaybackEventEntity(
                    timestamp = timestamp,
                    videoId = videoId,
                    albumBrowseId = albumBrowseId,
                    durationSecond = durationSecond,
                    listenedSecond = listenedSecond,
                ),
            )

        channelIds.forEach { channelId ->
            insertEventArtist(
                EventArtistEntity(
                    eventId = eventId,
                    channelId = channelId,
                    timestamp = timestamp,
                ),
            )
        }
        return eventId
    }

    @Query("SELECT * FROM playback_event ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPlaybackEventsByOffset(
        offset: Int,
        limit: Int,
    ): List<PlaybackEventEntity>

    @Query("SELECT * FROM playback_event WHERE timestamp > :cutoffTimestamp ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPlaybackEventsByOffsetAndTimestamp(
        offset: Int,
        limit: Int,
        cutoffTimestamp: LocalDateTime,
    ): List<PlaybackEventEntity>

    @Query("DELETE FROM playback_event WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime)

    // ===== Clear listening history + drop orphaned songs =====
    //
    // "Orphaned" means the videoId appears nowhere else at all. Three of the referencing tables key
    // on the id directly (pair_song_local_playlist.songId, set_video_id.videoId,
    // podcast_episode_table.videoId); the other five keep ids inside a JSON array column
    // (album.tracks, playlist.tracks, local_playlist.tracks, podcast_table.listEpisodes,
    // queue.listTrack) and are matched as quoted tokens in that text.
    //
    // Which is why the containers are swept FIRST — artists, podcasts, albums, playlists — and the
    // songs only after. Cached playlists alone were keeping eleven thousand songs alive, so the
    // song sweep on its own deleted nothing at all.
    //
    // Every id interpolated into a LIKE goes through replace() and the pattern declares ESCAPE '\'.
    // `_` and `%` are wildcards there, and over a thousand videoIds contain `_`, so an unescaped
    // `abc_def` also matches `abcXdef` and protects a row it has nothing to do with. Room cannot
    // escape a value that comes from a column rather than a bind parameter, hence the replace().
    //
    // Room cannot return a converted list column from a single-column SELECT — it reads the outer
    // List as "the rows" and then has no way to build the inner one — so pulling these lists into
    // Kotlin to filter them is not an option anyway.

    @Query("DELETE FROM playback_event")
    suspend fun deleteAllPlaybackEvents()

    /**
     * Artists cached only because they turned up while browsing.
     *
     * `song` carries its own `artistName`/`artistId`, so no track loses the name it displays, and the
     * artist page refetches everything it shows anyway — except that the artist a kept song belongs
     * to is spared regardless. Deleting one takes `nameLogoUrl`/`nameLogoColor` with it, which
     * `updateArtistNameLogo` builds once and no refetch reproduces, and it leaves the page of a song
     * still in the library with nothing at all to draw offline.
     *
     * `song.artistId` is a converted `List<String>`, stored as a JSON array, so the channel id is
     * matched as a quoted token the same way the `tracks` columns are — with the `replace()` +
     * ESCAPE '\' treatment, because over a thousand ids contain `_` and it is a LIKE wildcard.
     */
    @Query(
        "DELETE FROM artist WHERE followed = 0 AND " +
            "NOT EXISTS (SELECT 1 FROM song s WHERE (s.liked = 1 OR s.downloadState != 0) AND s.artistId LIKE " +
            "'%\"' || replace(replace(replace(artist.channelId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteUnfollowedArtists(): Int

    /**
     * Notifications left behind by an unfollow.
     *
     * Unfollowing only flipped `artist.followed` until the delete was added to
     * `ArtistRepositoryImpl.updateFollowedStatus`, so databases that predate it still carry these —
     * and after [deleteUnfollowedArtists] most no longer have an artist row to point back at. What
     * decides it here is the follow, not the row: the artists that statement spares are unfollowed
     * too, so their notifications go with the rest.
     * `artist.channelId` is NOT NULL, so the subquery cannot smuggle a NULL into the NOT IN.
     */
    @Query("DELETE FROM notification WHERE channelId NOT IN (SELECT channelId FROM artist WHERE followed = 1)")
    suspend fun deleteNotificationsOfUnfollowedArtists(): Int

    /** The new-releases tracking row for an artist the user no longer follows. See [deleteNotificationsOfUnfollowedArtists]. */
    @Query("DELETE FROM followed_artist_single_and_album WHERE channelId NOT IN (SELECT channelId FROM artist WHERE followed = 1)")
    suspend fun deleteFollowedArtistReleasesOfUnfollowedArtists(): Int

    /**
     * Podcasts the user never favorited.
     *
     * `podcast_episode_table` has a CASCADE foreign key onto this table, so the episodes go with
     * them — which is the point, since an episode row is what keeps its song out of the orphan set.
     */
    @Query("DELETE FROM podcast_table WHERE isFavorite = 0")
    suspend fun deleteUnfavoritedPodcasts(): Int

    /**
     * Cached albums the user neither liked nor downloaded.
     *
     * `followed_artist_single_and_album` is the new-releases feed for followed artists and stores
     * browse ids inside its `album`/`single` JSON, so an album still listed there is one the user is
     * being shown — deleting it would blank a row of that feed.
     *
     * An album that a kept song points at through `song.albumId` is spared too. The song survives
     * either way, but its album page would lose the cached track list and thumbnails and could no
     * longer render offline. Unlike the columns around it `albumId` holds one plain id rather than
     * JSON, so it is compared directly; where it is NULL the comparison is NULL and the album is
     * simply not protected, which is the wanted answer.
     */
    @Query(
        "DELETE FROM album WHERE liked = 0 AND downloadState = 0 AND " +
            "NOT EXISTS (SELECT 1 FROM song s WHERE s.albumId = album.browseId AND (s.liked = 1 OR s.downloadState != 0)) AND " +
            "NOT EXISTS (SELECT 1 FROM followed_artist_single_and_album f WHERE f.album LIKE " +
            "'%\"' || replace(replace(replace(album.browseId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\' " +
            "OR f.single LIKE " +
            "'%\"' || replace(replace(replace(album.browseId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteUnreferencedAlbums(): Int

    /**
     * Cached YouTube playlists the user neither liked nor downloaded.
     *
     * A row in `set_video_id` or a `local_playlist.youtubePlaylistId` means the playlist is synced
     * with one the user owns, and `your_youtube_playlist_list` is the cached index of their YouTube
     * library — all three are references, not state.
     *
     * `local_playlist.youtubePlaylistId` is nullable and mostly NULL, and `NOT IN` over a subquery
     * that yields a single NULL is NULL for **every** row: without the IS NOT NULL guard this
     * statement matches nothing whatsoever. The other two columns are NOT NULL and need no guard.
     */
    @Query(
        "DELETE FROM playlist WHERE liked = 0 AND downloadState = 0 AND " +
            "id NOT IN (SELECT youtubePlaylistId FROM set_video_id) AND " +
            "id NOT IN (SELECT youtubePlaylistId FROM local_playlist WHERE youtubePlaylistId IS NOT NULL) AND " +
            "NOT EXISTS (SELECT 1 FROM your_youtube_playlist_list y WHERE y.listBrowseIds LIKE " +
            "'%\"' || replace(replace(replace(playlist.id, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteUnreferencedPlaylists(): Int

    /**
     * Every song the rest of the database has stopped pointing at.
     *
     * `set_video_id` counts as a reference on purpose: a row there means the song sits in one of the
     * user's YouTube playlists, and it carries the `setVideoId` needed to reorder or remove it
     * there. That holds even for playlists never saved locally, so the `tracks` columns alone would
     * not catch it.
     *
     * `liked` and `downloadState` are not references at all — they are state on the song row itself,
     * and they are the ONLY storage the Favorites and Downloaded libraries have. A song liked or
     * downloaded straight from search belongs to no playlist, so without these two predicates the
     * literal "referenced by nothing" reading would wipe out both libraries. `downloadState = 0`
     * also covers a download still in flight, which would otherwise be deleted mid-transfer.
     *
     * The last five conditions search columns that hold a JSON array rather than an id. Matching the
     * id **with its surrounding quotes** is what makes that safe: ids live in the JSON as `"abc123"`,
     * so the quotes pin the match to a whole token and it cannot land inside a longer id. It is a
     * table scan, but those tables hold tens of rows, not thousands — and it replaces deserialising
     * every playlist and the entire saved queue into objects just to read one field back out.
     */
    @Query(
        "SELECT videoId FROM song WHERE " +
            "liked = 0 AND downloadState = 0 AND " +
            "videoId NOT IN (SELECT songId FROM pair_song_local_playlist) AND " +
            "videoId NOT IN (SELECT videoId FROM set_video_id) AND " +
            "videoId NOT IN (SELECT videoId FROM podcast_episode_table) AND " +
            "NOT EXISTS (SELECT 1 FROM album WHERE album.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM playlist WHERE playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM local_playlist WHERE local_playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM podcast_table WHERE podcast_table.listEpisodes LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM queue WHERE queue.listTrack LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun getOrphanedSongIds(): List<String>

    /**
     * Deleted in batches by the caller: SQLite caps how many bind parameters one statement takes.
     *
     * The orphan conditions from [getOrphanedSongIds] are repeated here rather than trusting the ids
     * handed in, because the user keeps playing and browsing between the two statements. Re-checking
     * closes that window, and it matters most for `pair_song_local_playlist`: it cascades on song
     * deletion, so a song added to a local playlist mid-sweep would otherwise be pulled straight
     * back out of it.
     */
    @Query(
        "DELETE FROM song WHERE videoId IN (:videoIds) AND " +
            "liked = 0 AND downloadState = 0 AND " +
            "videoId NOT IN (SELECT songId FROM pair_song_local_playlist) AND " +
            "videoId NOT IN (SELECT videoId FROM set_video_id) AND " +
            "videoId NOT IN (SELECT videoId FROM podcast_episode_table) AND " +
            "NOT EXISTS (SELECT 1 FROM album WHERE album.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM playlist WHERE playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM local_playlist WHERE local_playlist.tracks LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM podcast_table WHERE podcast_table.listEpisodes LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\') AND " +
            "NOT EXISTS (SELECT 1 FROM queue WHERE queue.listTrack LIKE " +
            "'%\"' || replace(replace(replace(song.videoId, '\\', '\\\\'), '_', '\\_'), '%', '\\%') || '\"%' ESCAPE '\\')",
    )
    suspend fun deleteSongsByIds(videoIds: List<String>): Int

    // Run AFTER the song delete, and only for ids that actually went: the statement above may spare
    // a song, and that song must keep its lyrics.
    @Query("DELETE FROM lyrics WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteLyricsByIds(videoIds: List<String>)

    @Query("DELETE FROM translated_lyrics WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteTranslatedLyricsByIds(videoIds: List<String>)

    @Query("DELETE FROM new_format WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteNewFormatsByIds(videoIds: List<String>)

    @Query("DELETE FROM song_info WHERE videoId IN (:videoIds) AND videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteSongInfoByIds(videoIds: List<String>)

    // The four deletes above only reach ids that are in flight, so a row whose song vanished in some
    // earlier build — or never had one — is unreachable to them and has accumulated ever since.
    // `song.videoId` is the primary key and NOT NULL, so these NOT INs cannot go NULL.

    @Query("DELETE FROM new_format WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleNewFormats(): Int

    @Query("DELETE FROM lyrics WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleLyrics(): Int

    @Query("DELETE FROM translated_lyrics WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleTranslatedLyrics(): Int

    @Query("DELETE FROM song_info WHERE videoId NOT IN (SELECT videoId FROM song)")
    suspend fun deleteStaleSongInfo(): Int

    // Query event
    @Query(
        "SELECT \n" +
            "  videoId,\n" +
            "  COUNT(*) AS playCount,\n" +
            "  SUM(listenedSecond) AS totalListeningTime\n" +
            "FROM playback_event\n" +
            "WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp\n" +
            "GROUP BY videoId\n" +
            "ORDER BY playCount DESC\n" +
            "LIMIT 100",
    )
    suspend fun queryTopPlayedSongsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<TopPlayedTracks>

    @Query(
        "SELECT channelId, COUNT(*) AS playCount FROM event_artist" +
            " WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp" +
            " GROUP BY channelId" + " ORDER BY playCount DESC" +
            " LIMIT 100",
    )
    suspend fun queryTopArtistsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<TopPlayedArtist>

    /**
     * The same ranking as [queryTopArtistsInRange], carrying the seconds as well.
     *
     * `event_artist` holds one row per artist per play and no duration at all, so the time can only
     * come from the join back to `playback_event`. A track credited to several artists hands its
     * full `listenedSecond` to each of them — the figure means "time spent with this artist", so a
     * duet counts as time with both and the column deliberately does not sum to the period total.
     */
    @Query(
        "SELECT ea.channelId, COUNT(*) AS playCount, SUM(pe.listenedSecond) AS totalListeningTime" +
            " FROM event_artist ea JOIN playback_event pe ON pe.eventId = ea.eventId" +
            " WHERE ea.timestamp BETWEEN :startTimestamp AND :endTimestamp" +
            " GROUP BY ea.channelId" + " ORDER BY playCount DESC" +
            " LIMIT 100",
    )
    suspend fun queryTopArtistsWithTimeInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<TopPlayedArtistTime>

    @Query(
        "SELECT \n" +
            "  albumBrowseId,\n" +
            "  COUNT(*) AS playCount\n" +
            "FROM playback_event\n" +
            "WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp\n" +
            "AND albumBrowseId IS NOT NULL " +
            "GROUP BY albumBrowseId\n" +
            "ORDER BY playCount DESC\n" +
            "LIMIT 100",
    )
    suspend fun queryTopAlbumsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<TopPlayedAlbum>

    @Query("SELECT COUNT(*) FROM playback_event")
    suspend fun getTotalPlaybackEventCount(): Long

    @Query("SELECT COUNT(*) FROM event_artist")
    suspend fun getTotalEventArtistCount(): Long

    @Query("SELECT SUM(listenedSecond) FROM playback_event")
    suspend fun getTotalListeningTimeInSeconds(): Long

    @Query("SELECT COUNT(*) FROM playback_event WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp")
    suspend fun getPlaybackEventCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Long

    /**
     * Every play in the range, as timestamp + listened seconds.
     *
     * One pass feeds the listening clock, the busiest day, the plays-per-day average and the
     * consistency axis — all of which are LOCAL-time questions over the same rows. Doing them as
     * four SQL aggregates would mean four scans and `'localtime'` in each, whose answer depends on
     * the process timezone; doing them in Kotlin costs one scan and is timezone-correct.
     */
    @Query(
        "SELECT timestamp, listenedSecond FROM playback_event" +
            " WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp",
    )
    suspend fun getPlaybackSamplesInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<PlaybackSample>

    @Query(
        "SELECT COUNT(DISTINCT videoId) FROM playback_event" +
            " WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp",
    )
    suspend fun getDistinctTrackCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT albumBrowseId) FROM playback_event" +
            " WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp AND albumBrowseId IS NOT NULL",
    )
    suspend fun getDistinctAlbumCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT channelId) FROM event_artist" +
            " WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp",
    )
    suspend fun getDistinctArtistCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Int

    /**
     * Artists heard for the FIRST time inside the range.
     *
     * `MIN(timestamp)` is taken over the artist's whole history, not over the range, so an artist
     * played last year and again this week is not new — grouping inside the window instead would
     * call every artist new, which is the obvious wrong answer this guards against.
     */
    @Query(
        "SELECT COUNT(*) FROM (SELECT channelId FROM event_artist GROUP BY channelId" +
            " HAVING MIN(timestamp) BETWEEN :startTimestamp AND :endTimestamp)",
    )
    suspend fun getNewArtistCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Int

    /**
     * Play counts for EVERY artist in the range, unbounded.
     *
     * [queryTopArtistsInRange] caps at 100, which is right for a top-five list and wrong here: the
     * concentration and diversity axes are shares of the whole, so a cut-off tail would inflate
     * both.
     */
    @Query(
        "SELECT channelId, COUNT(*) AS playCount FROM event_artist" +
            " WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp" +
            " GROUP BY channelId ORDER BY playCount DESC",
    )
    suspend fun getArtistPlayCountsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<TopPlayedArtist>

    /**
     * Plays bucketed by the release decade of the album they belong to.
     *
     * `album.year` is whatever YouTube returned — usually "2020", sometimes absent, occasionally
     * something else — so the row is only counted when the first four characters parse to a
     * plausible year.
     */
    @Query(
        "SELECT (CAST(SUBSTR(a.year, 1, 4) AS INTEGER) / 10) * 10 AS decade, COUNT(*) AS playCount" +
            " FROM playback_event p JOIN album a ON a.browseId = p.albumBrowseId" +
            " WHERE p.timestamp BETWEEN :startTimestamp AND :endTimestamp" +
            " AND a.year IS NOT NULL AND CAST(SUBSTR(a.year, 1, 4) AS INTEGER) > 1900" +
            " GROUP BY decade ORDER BY decade",
    )
    suspend fun getDecadeCountsInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): List<DecadeCount>

    /** How many plays in the range could be dated at all — the denominator for the decade chart. */
    @Query(
        "SELECT COUNT(*) FROM playback_event p JOIN album a ON a.browseId = p.albumBrowseId" +
            " WHERE p.timestamp BETWEEN :startTimestamp AND :endTimestamp" +
            " AND a.year IS NOT NULL AND CAST(SUBSTR(a.year, 1, 4) AS INTEGER) > 1900",
    )
    suspend fun getDatedPlayCountInRange(
        startTimestamp: LocalDateTime,
        endTimestamp: LocalDateTime,
    ): Int

    // ========== AutoEq ==========

    @Query("SELECT COUNT(*) FROM autoeq_entry")
    suspend fun getAutoEqEntryCount(): Int

    @Query("SELECT * FROM autoeq_entry ORDER BY name LIMIT :limit")
    suspend fun getAutoEqEntries(limit: Int): List<AutoEqEntryEntity>

    /**
     * Name search over the cached index.
     *
     * `ESCAPE` is declared and the caller escapes the query, because `_` and `%` are LIKE
     * wildcards: a user typing either would otherwise silently match far more than they asked for.
     */
    @Query(
        "SELECT * FROM autoeq_entry WHERE name LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY name LIMIT :limit",
    )
    suspend fun searchAutoEqEntries(
        query: String,
        limit: Int,
    ): List<AutoEqEntryEntity>

    @Query("SELECT * FROM autoeq_curve WHERE path = :path")
    suspend fun getAutoEqCurve(path: String): AutoEqCurveEntity?

    @Insert(onConflict = REPLACE)
    suspend fun insertAutoEqCurve(curve: AutoEqCurveEntity)

    /** Only the keys: the picker needs to know which rows are usable offline, not their gains. */
    @Query("SELECT path FROM autoeq_curve")
    suspend fun getAutoEqCachedCurvePaths(): List<String>

    @Query("SELECT * FROM autoeq_index_meta WHERE id = 0")
    suspend fun getAutoEqIndexMeta(): AutoEqIndexMetaEntity?

    @Insert(onConflict = REPLACE)
    suspend fun insertAutoEqEntries(entries: List<AutoEqEntryEntity>)

    @Insert(onConflict = REPLACE)
    suspend fun upsertAutoEqIndexMeta(meta: AutoEqIndexMetaEntity)

    @Query("DELETE FROM autoeq_entry")
    suspend fun deleteAllAutoEqEntries()

    /**
     * Swap in a freshly downloaded index.
     *
     * The rows and the ETag that describes them are written together, so there is no window in
     * which the stored ETag claims a version of the index the table does not actually hold.
     */
    @Transaction
    suspend fun replaceAutoEqIndex(
        entries: List<AutoEqEntryEntity>,
        meta: AutoEqIndexMetaEntity,
    ) {
        deleteAllAutoEqEntries()
        insertAutoEqEntries(entries)
        upsertAutoEqIndexMeta(meta)
    }
}
