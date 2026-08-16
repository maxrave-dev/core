package com.maxrave.data.repository

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toSongItemForDownload
import com.maxrave.data.mapping.toWatchEndpoint
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.models.response.LikeStatus
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import org.koin.mp.KoinPlatform.getKoin

private const val TAG = "SongRepositoryImpl"

internal class SongRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : SongRepository {
    override fun getAllSongs(limit: Int): Flow<List<SongEntity>> =
        flow {
            emit(localDataSource.getAllSongs(limit))
        }.flowOn(Dispatchers.IO)

    override suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    ) = withContext(Dispatchers.IO) { localDataSource.setInLibrary(videoId, inLibrary) }

    override fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>> =
        flow {
            // SQLite's `WHERE videoId IN (:list)` does NOT preserve the input list order
            // (it returns rows in table/rowid order). Album tracks, local playlist items,
            // and queue snapshots all rely on caller-defined order, so we restore it here.
            val songs = localDataSource.getSongByListVideoIdFull(listVideoId)
            val byId = songs.associateBy { it.videoId }
            emit(listVideoId.mapNotNull { byId[it] })
        }.flowOn(Dispatchers.IO)

    override fun getDownloadedSongs(): Flow<List<SongEntity>?> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getDownloadedSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getDownloadingSongs(): Flow<List<SongEntity>?> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getDownloadingSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getPreparingSongs(): Flow<List<SongEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getPreparingSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>) =
        localDataSource.getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId)

    override fun getLikedSongs(): Flow<List<SongEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun downloadAllLikedSongs(): Int =
        withContext(Dispatchers.IO) {
            val pending =
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedSongs(limit, offset)
                }.filter { it.downloadState != DownloadState.STATE_DOWNLOADED }
            Logger.d(TAG, "Auto-download: queueing ${pending.size} liked songs")
            pending.forEach { song ->
                downloadHandler.downloadTrack(song.videoId, song.title, song.thumbnails.orEmpty())
            }
            pending.size
        }

    /**
     * The order below is the whole feature.
     *
     * A song is orphaned only once nothing points at it, so every cached container has to go first —
     * sweeping songs on their own found zero to delete, because eleven thousand of them were held
     * alive by playlists nobody prunes. Artists lead so the two tables keyed on `channelId` can be
     * measured against what is still followed; podcasts, albums and playlists follow; songs last,
     * then the satellite rows that were already orphaned before any of this ran.
     */
    override suspend fun clearHistoryAndOrphanedSongs(): Int =
        withContext(Dispatchers.IO) {
            val pinnedQueue = persistLiveQueueBeforeSweep()
            localDataSource.deleteAllPlaybackEvents()
            val artists = localDataSource.deleteUnfollowedArtists()
            val notifications = localDataSource.deleteNotificationsOfUnfollowedArtists()
            val artistReleases = localDataSource.deleteFollowedArtistReleasesOfUnfollowedArtists()
            val podcasts = localDataSource.deleteUnfavoritedPodcasts()
            val albums = localDataSource.deleteUnreferencedAlbums()
            val playlists = localDataSource.deleteUnreferencedPlaylists()
            val orphans = localDataSource.getOrphanedSongIds()
            val removed = localDataSource.deleteSongsAndRelatedData(orphans)
            val satellites = localDataSource.deleteStaleSongSatellites()
            Logger.d(
                TAG,
                "Clear history: removed $removed of ${orphans.size} candidate songs, " +
                    "$artists artists, $notifications notifications, $artistReleases artist releases, " +
                    "$podcasts podcasts, $albums albums, $playlists playlists, $satellites stale rows",
            )
            // Last, and outside every statement above: SQLite refuses VACUUM inside a transaction.
            // Without it the file stays fragmented and less space comes back than the user expects.
            // The checkpoint first folds the WAL — which every delete above just filled — back into
            // the main file, so VACUUM actually has those pages to reclaim.
            //
            // Failure here is not failure of the operation: every delete has already committed, so
            // letting it throw would show an error toast for work that actually succeeded. It only
            // costs some disk space, and the next run reclaims it.
            runCatching {
                localDataSource.checkpoint()
                localDataSource.vacuum()
            }.onFailure { Logger.e(TAG, "VACUUM after clearing history failed: ${it.message}") }
            unpinLiveQueueAfterSweep(pinnedQueue)
            removed
        }

    /**
     * Write whatever is playing right now into the `queue` table, so the sweep cannot delete it.
     *
     * The sweep spares songs found in `queue.listTrack`, but that table is only written on pause,
     * on track change, and on exit — and only when the user has "save recent song and queue" on.
     * Two ways that loses the current session: with the setting off the table is empty, and even
     * with it on, the endless queue keeps appending tracks that no save has caught up with yet.
     * Either way the song playing through the speakers looks orphaned and goes.
     *
     * Deliberately ignores that setting: this is not "remember my queue for next launch", it is
     * "do not delete what is on screen". Writing nothing when the queue is empty matters too —
     * overwriting a previously saved queue with an empty one would strip the protection instead of
     * adding it.
     *
     * @return whether a row was actually written, which is what [unpinLiveQueueAfterSweep] needs in
     * order to know the `queue` table is this function's doing and not the user's own saved queue.
     */
    private suspend fun persistLiveQueueBeforeSweep(): Boolean {
        val liveQueue = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        if (liveQueue.isEmpty()) return false
        Logger.d(TAG, "Clear history: pinning ${liveQueue.size} queued tracks before the sweep")
        localDataSource.recoverQueue(QueueEntity(listTrack = liveQueue))
        return true
    }

    /**
     * Take the pin back out once the sweep no longer needs it.
     *
     * [persistLiveQueueBeforeSweep] writes the queue whether or not the user asked for their queue
     * to be saved, so leaving it there does two unwanted things: a user who deliberately turned that
     * setting off ends up with their queue on disk anyway, and the row goes on protecting those
     * songs from every future sweep — the next one would find them still referenced and spare them
     * again, however long ago they stopped playing.
     *
     * Only when the setting is off, and only when this run is what created the row: with the setting
     * on, the row is the user's queue being saved as normal and is none of the sweep's business.
     */
    private suspend fun unpinLiveQueueAfterSweep(pinned: Boolean) {
        if (!pinned) return
        if (dataStoreManager.saveRecentSongAndQueue.first() == TRUE) return
        Logger.d(TAG, "Clear history: removing the pinned queue, saving the queue is turned off")
        localDataSource.deleteQueue()
    }

    override fun getCanvasSong(max: Int): Flow<List<SongEntity>> =
        flow {
            emit(localDataSource.getCanvasSong(max))
        }.flowOn(Dispatchers.IO)

    override fun getSongById(id: String): Flow<SongEntity?> =
        flow {
            emit(localDataSource.getSong(id))
        }.flowOn(Dispatchers.IO)

    override fun getSongAsFlow(id: String) = localDataSource.getSongAsFlow(id)

    override fun insertSong(songEntity: SongEntity): Flow<Long> = flow<Long> { emit(localDataSource.insertSong(songEntity)) }.flowOn(Dispatchers.IO)

    override fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateThumbnailsSongEntity(thumbnail, videoId)) }.flowOn(Dispatchers.IO)

    override fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateVideoTypeSongEntity(videoType, videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun updateListenCount(videoId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.updateListenCount(videoId)
        }

    override suspend fun resetTotalPlayTime(videoId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.resetTotalPlayTime(videoId)
        }

    /**
     * Resolved lazily rather than taken in the constructor: the download handler is implemented in
     * the platform media modules, and pulling it in at construction time would make this repository
     * depend on them being built first.
     */
    private val downloadHandler: DownloadHandler by lazy { getKoin().get() }

    /**
     * Lazy for the same reason as [downloadHandler], and here it also breaks a cycle: the handler
     * already depends on this repository (it calls [recoverQueue] whenever it saves the queue).
     */
    private val mediaPlayerHandler: MediaPlayerHandler by lazy { getKoin().get() }

    /**
     * Note the side effect: liking a song can start a download, when the user has asked for that.
     *
     * Deliberately one-way. It fires only on the way *in* (`likeStatus == 1`) and only for songs
     * not already offline, so re-liking something you already have does not queue it twice, and
     * unliking never removes a file — the user downloaded it, only they should undo that.
     */
    override suspend fun updateLikeStatus(
        videoId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateLiked(likeStatus, videoId)
        if (likeStatus == 1 && dataStoreManager.autoDownloadLikedSongs.first() == TRUE) {
            val song = localDataSource.getSong(videoId)
            if (song != null && song.downloadState != DownloadState.STATE_DOWNLOADED) {
                Logger.d(TAG, "Auto-downloading liked song: $videoId")
                downloadHandler.downloadTrack(videoId, song.title, song.thumbnails.orEmpty())
            }
        }
//        if (dataStoreManager.combineLocalAndYouTubeLiked.first() == TRUE) {
//            if (likeStatus == 1) {
//                addToYouTubeLiked(videoId).collect { result ->
//                    Logger.d(TAG, "updateLikeStatus -> addToYouTubeLiked: $result")
//                }
//            } else {
//                removeFromYouTubeLiked(videoId).collect { result ->
//                    Logger.d(TAG, "updateLikeStatus -> removeFromYouTubeLiked: $result")
//                }
//            }
//        }
    }

    override fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateSongInLibrary(inLibrary, videoId)) }

    override suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateDurationSeconds(
            durationSeconds,
            videoId,
        )
    }

    override fun getMostPlayedSongs(): Flow<List<SongEntity>> = localDataSource.getMostPlayedSongs()

    override suspend fun updateDownloadState(
        videoId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateDownloadState(
            downloadState,
            videoId,
        )
    }

    override suspend fun getRecentSong(
        limit: Int,
        offset: Int,
    ) = localDataSource.getRecentSongs(limit, offset)

    override suspend fun insertSongInfo(songInfo: SongInfoEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertSongInfo(songInfo)
        }

    override suspend fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?> =
        flow { emit(localDataSource.getSongInfo(videoId)) }.flowOn(Dispatchers.Main)

    override suspend fun recoverQueue(temp: List<Track>) {
        val queueEntity = QueueEntity(listTrack = temp)
        withContext(Dispatchers.IO) { localDataSource.recoverQueue(queueEntity) }
    }

    override suspend fun removeQueue() {
        withContext(Dispatchers.IO) { localDataSource.deleteQueue() }
    }

    override suspend fun getSavedQueue(): Flow<List<QueueEntity>?> =
        flow {
            emit(localDataSource.getQueue())
        }.flowOn(Dispatchers.IO)

    /**
     * Drops the video entries YouTube mixes into a radio queue, when the user asked radios to stay
     * audio-only. [isRadio] gates it because the setting is deliberately radio-scoped: a playlist
     * or album the user picked themselves must still play exactly what it contains.
     *
     * Only entries YouTube *named* as a video are dropped. A null `musicVideoType` means the
     * response never said, which is not a claim of "audio" — those are kept rather than guessed at
     * (see [MusicVideoType]).
     *
     * Dropping is the only option here; substituting the audio version is not available. Measured
     * against a live logged-in radio (197 entries over four pages), every video that reached the
     * queue was `MUSIC_VIDEO_TYPE_UGC` — a fan remix or mashup that exists only as a video and
     * ships no `counterpart` to swap in. Official music videos never arrive as the primary
     * rendition at all: YouTube already demotes those to the counterpart of the audio track, which
     * is what [com.maxrave.kotlinytmusicscraper.models.PlaylistPanelRenderer.Content.track] reads.
     */
    private suspend fun List<SongItem>.dropVideosWhenRadioAudioOnly(isRadio: Boolean): List<SongItem> {
        if (!isRadio) return this
        if (dataStoreManager.radioAudioOnly.first() != TRUE) return this
        return filterNot { MusicVideoType.isVideoSong(it.musicVideoType) }
    }

    override fun getContinueTrack(
        playlistId: String,
        continuation: String,
        fromPlaylist: Boolean,
    ): Flow<Pair<ArrayList<Track>?, String?>> =
        flow {
            runCatching {
                var newContinuation: String? = null
                Logger.d(TAG, "getContinueTrack -> playlistId: $playlistId")
                Logger.d(TAG, "getContinueTrack -> continuation: $continuation")
                if (!fromPlaylist) {
                    youTube
                        .next(
                            if (playlistId.startsWith("RRDAMVM")) {
                                WatchEndpoint(videoId = playlistId.removePrefix("RRDAMVM"))
                            } else {
                                WatchEndpoint(playlistId = playlistId)
                            },
                            continuation = continuation,
                        ).onSuccess { next ->
                            val data: ArrayList<SongItem> = arrayListOf()
                            // Only this branch can be a radio — the `else` below continues a real
                            // playlist. `RRDAMVM…` counts as one too: it is YouTube's other
                            // spelling for the radio of a single video, which `isRadioQueueId`
                            // deliberately does not match because it never appears as a queue's
                            // own playlistId.
                            val isRadio =
                                playlistId.startsWith("RRDAMVM") || playlistId.isRadioQueueId()
                            data.addAll(next.items.dropVideosWhenRadioAudioOnly(isRadio))
                            newContinuation = next.continuation
                            emit(Pair(data.toListTrack(), newContinuation))
                        }.onFailure { exception ->
                            exception.printStackTrace()
                            emit(Pair(null, null))
                        }
                } else {
                    youTube
                        .customQuery(
                            browseId = null,
                            continuation = continuation,
                            setLogin = true,
                        ).onSuccess { values ->
                            Logger.d(TAG, "getPlaylistData -> continue: $continuation")
                            Logger.d(TAG, "getPlaylistData -> values: ${values.onResponseReceivedActions}")
                            val dataMore: List<SongItem> =
                                values.onResponseReceivedActions
                                    ?.firstOrNull()
                                    ?.appendContinuationItemsAction
                                    ?.continuationItems
                                    ?.apply {
                                        Logger.w(TAG, "getContinueTrack -> dataMore: ${this.size}")
                                    }?.mapNotNull {
                                        NextPage.fromMusicResponsiveListItemRenderer(
                                            it.musicResponsiveListItemRenderer ?: return@mapNotNull null,
                                        )
                                    } ?: emptyList()
                            newContinuation =
                                values.getPlaylistContinuation()
                            emit(
                                Pair<ArrayList<Track>?, String?>(
                                    dataMore.toListTrack(),
                                    newContinuation,
                                ),
                            )
                        }.onFailure {
                            Logger.e(TAG, "getContinueTrack -> Error: ${it.message}")
                            emit(Pair(null, null))
                        }
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSongInfo(videoId: String): Flow<SongInfoEntity?> =
        flow {
            runCatching {
                val id =
                    if (videoId.contains(MERGING_DATA_TYPE.VIDEO)) {
                        videoId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    } else {
                        videoId
                    }
                youTube
                    .getSongInfo(id)
                    .onSuccess { songInfo ->
                        val song =
                            SongInfoEntity(
                                videoId = songInfo.videoId,
                                author = songInfo.author,
                                authorId = songInfo.authorId,
                                authorThumbnail = songInfo.authorThumbnail,
                                description = songInfo.description,
                                uploadDate = songInfo.uploadDate,
                                subscribers = songInfo.subscribers,
                                viewCount = songInfo.viewCount,
                                like = songInfo.like,
                                dislike = songInfo.dislike,
                            )
                        emit(song)
                        insertSongInfo(
                            song,
                        )
                    }.onFailure {
                        it.printStackTrace()
                        emit(getSongInfoEntity(videoId).lastOrNull())
                    }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun getLikeStatus(videoId: String): Flow<Boolean> =
        flow {
            runCatching {
                youTube
                    .getLikedInfo(videoId)
                    .onSuccess {
                        if (it == LikeStatus.LIKE) emit(true) else emit(false)
                    }.onFailure {
                        it.printStackTrace()
                        emit(false)
                    }
            }
        }

    override suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int> =
        flow {
            if (mediaId != null) {
                runCatching {
                    youTube
                        .addToLiked(mediaId)
                        .onSuccess {
                            Logger.d(TAG, "Liked -> Success: $it")
                            emit(it)
                        }.onFailure {
                            it.printStackTrace()
                            emit(0)
                        }
                }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int> =
        flow {
            if (mediaId != null) {
                runCatching {
                    youTube
                        .removeFromLiked(mediaId)
                        .onSuccess {
                            Logger.d(TAG, "Liked -> Success: $it")
                            emit(it)
                        }.onFailure {
                            it.printStackTrace()
                            emit(0)
                        }
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun downloadToFile(
        track: Track,
        path: String,
        videoId: String,
        isVideo: Boolean,
    ): Flow<DownloadProgress> =
        youTube
            .download(
                track.toSongItemForDownload(),
                path,
                videoId,
                isVideo,
            ).map {
                DownloadProgress(
                    audioDownloadProgress = it.audioDownloadProgress,
                    videoDownloadProgress = it.videoDownloadProgress,
                    downloadSpeed = it.downloadSpeed,
                    errorMessage = it.errorMessage,
                    isMerging = it.isMerging,
                    isError = it.isError,
                    isDone = it.isDone,
                )
            }

    override fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>> =
        flow {
            runCatching {
                youTube
                    .next(WatchEndpoint(videoId = videoId))
                    .onSuccess { next ->
                        val data: ArrayList<SongItem> = arrayListOf()
                        // Always a radio, though not directly: `next(videoId)` alone answers with
                        // just two rows — the song itself and an `automixPreviewVideoRenderer`
                        // pointing at its `RDAMVM…` radio. `YouTube.next` follows that pointer and
                        // splices the radio in, so everything here past the first row is radio
                        // content, and this is what extends the queue once it runs dry.
                        data.addAll(
                            next.items
                                .filter { it.id != videoId }
                                .toSet()
                                .toList()
                                .dropVideosWhenRadioAudioOnly(isRadio = true),
                        )
                        val nextContinuation = next.continuation
                        emit(Resource.Success<Pair<List<Track>, String?>>(Pair(data.toListTrack().toList(), nextContinuation)))
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        emit(Resource.Error<Pair<List<Track>, String?>>(exception.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>> =
        flow {
            runCatching {
                youTube
                    .next(endpoint.toWatchEndpoint())
                    .onSuccess { next ->
                        val items =
                            next.items.dropVideosWhenRadioAudioOnly(
                                isRadio = endpoint.playlistId?.isRadioQueueId() == true,
                            )
                        emit(Resource.Success(Pair(items.toListTrack(), next.continuation)))
                    }.onFailure {
                        it.printStackTrace()
                        emit(Resource.Error(it.message ?: "Error"))
                    }
            }
        }
}