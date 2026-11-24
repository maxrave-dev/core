package com.maxrave.media3.service.callback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.maxrave.common.Config
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toListTrack
import com.maxrave.domain.utils.toPlaylistEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AndroidAuto"

@UnstableApi
internal class SimpleMediaSessionCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val homeRepository: HomeRepository,
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) : MediaLibrarySession.Callback {
    var toggleLike: () -> Unit = {
        mediaPlayerHandler.toggleLike()
    }
    var toggleRadio: () -> Unit = {
        mediaPlayerHandler.toggleRadio()
    }
    /** The last search's query and results, replaced whole so a reader never sees them half updated. */
    @Volatile
    private var lastSearch: Pair<String, List<Track>> = "" to emptyList()

    /** Counts [onSearch] calls, so a call can tell whether a newer query arrived after it. */
    private val searchCalls = AtomicInteger()

    /** The newest query's text. */
    @Volatile
    private var newestQuery = ""

    /** When [lastSearch] was written, so a repeat of the same text (Search pressed after typing) reuses it. */
    @Volatile
    private var lastSearchAt = 0L

    /** The query whose YouTube search is out right now, so a repeat of the same text waits for its answer. */
    @Volatile
    private var searchingQuery: String? = null
    private val listHomeItem = mutableListOf<HomeItem>()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Logger.w(TAG, "onConnect: ${controller.packageName}")
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                // Add custom commands
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.LIKE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.REPEAT, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.RADIO, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.SHUFFLE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle()))
                .build()
        return MediaSession.ConnectionResult
            .AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(
                Player.Commands
                    .Builder()
                    .addAllCommands()
                    .remove(COMMAND_GET_TIMELINE)
                    .build(),
            ).build()
    }

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        Logger.w(TAG, "Player Command $playerCommand")
        // Next and Previous go through the handler only, the way the app's own buttons do, and Media3
        // is told not to run them as well. Both used to run: two Previous calls mid-song restarted the
        // song and then, reading the position as 0, went to the song before. The handler path, not
        // Media3's, is the one kept because while casting the session player is the receiver, whose
        // queue starts at the current song, so Media3's Previous could never reach the song before.
        val event =
            when (playerCommand) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> PlayerEvent.Next
                COMMAND_SEEK_TO_PREVIOUS -> PlayerEvent.Previous
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> PlayerEvent.SkipToPrevious
                else -> null
            }
        if (event != null) {
            scope.launch { mediaPlayerHandler.onPlayerEvent(event) }
            return SessionResult.RESULT_INFO_SKIPPED
        }
        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    @UnstableApi
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MEDIA_CUSTOM_COMMAND.LIKE -> {
                toggleLike()
            }

            MEDIA_CUSTOM_COMMAND.REPEAT -> {
                session.player.repeatMode =
                    when (session.player.repeatMode) {
                        ExoPlayer.REPEAT_MODE_OFF -> ExoPlayer.REPEAT_MODE_ONE
                        ExoPlayer.REPEAT_MODE_ONE -> ExoPlayer.REPEAT_MODE_ALL
                        ExoPlayer.REPEAT_MODE_ALL -> ExoPlayer.REPEAT_MODE_OFF
                        else -> ExoPlayer.REPEAT_MODE_OFF
                    }
            }

            MEDIA_CUSTOM_COMMAND.RADIO -> {
                toggleRadio()
            }

            MEDIA_CUSTOM_COMMAND.SHUFFLE -> {
                scope.launch {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }

            MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN -> {
                return Futures.immediateFuture(
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putParcelable(MEDIA_CUSTOM_COMMAND.KEY_PLATFORM_TOKEN, session.platformToken)
                        },
                    ),
                )
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * Playback resumption: a `play` command that arrives with nothing loaded in the player.
     *
     * This is how a Samsung Routine (and any headset or Bluetooth `play` button, and the system
     * resumption chip) reaches the app when it is not running: `MediaButtonReceiver` starts
     * [com.maxrave.media3.service.SimpleMediaService] with `startForegroundService()`, Media3
     * sees a play request against an empty player, and calls this.
     *
     * Leaving it unimplemented is what caused a background ANR. The default returns
     * `Futures.immediateFailedFuture(UnsupportedOperationException())`; Media3 logs that and
     * still calls `prepare() + play()`, but on an empty player that lands in ENDED rather than
     * BUFFERING, so `MediaNotificationManager` never considers the session user-engaged and
     * never calls `startForeground()`. Nothing is blocked - the main thread sits in
     * `nativePollOnce`, which is exactly what the ANR trace shows - the service simply misses
     * the foreground-start deadline and the system tears it down with "Context
     * .startForegroundService() did not then call Service.startForeground()".
     *
     * The queue is loaded through [MediaPlayerHandler] instead of being handed back to Media3.
     * The session player is a single-item delegate in front of `CrossfadeExoPlayerAdapter`,
     * which owns the real playlist, so a playlist pushed in through `setMediaItems` here would
     * desync the two. Returning an empty list with [C.INDEX_UNSET] leaves the player alone -
     * the same contract [onSetMediaItems] already relies on for Android Auto.
     *
     * Ordering matters: Media3 runs its own success handling inline the moment this future
     * completes, so the restore is deliberately started on a later main-thread message. Priming
     * the player first would have it cleared right back out.
     *
     * `isForPlayback == false` is a different question - Android Auto asking for the recent item
     * to show after a device boot - and is left answered the way the default answered it, with
     * nothing. A raw song `MediaItem` would show up there as an entry [onSetMediaItems] cannot
     * parse (it wants a `song/<id>` style media id), i.e. one that does nothing when tapped.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Logger.w(TAG, "onPlaybackResumption: ${controller.packageName}, isForPlayback=$isForPlayback")
        if (isForPlayback) resumeSavedQueue()
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
        )
    }

    private fun resumeSavedQueue() {
        scope.launch {
            if (!mediaPlayerHandler.restoreQueueAndPlay()) {
                Logger.w(TAG, "onPlaybackResumption: nothing to resume")
            }
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    /**
     * Android Auto calls this on every keystroke, and every call that reached YouTube went into the
     * signed-in account's search history, one entry per character (#2295). Only the query the user
     * stops at is searched: each call waits [SEARCH_DEBOUNCE_MS] and gives up when a newer one arrived
     * meanwhile, and a search overtaken while it was out does not replace the newer results. Every
     * query is still answered (an overtaken one with nothing): the legacy browser holds each request
     * open until its own query is notified, so unanswered ones piled up for the whole connection.
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        // Counted here, in the order Android Auto sent the queries, not inside the IO coroutine, where
        // two calls close together can start in either order.
        newestQuery = query
        val call = searchCalls.incrementAndGet()
        return scope.future(Dispatchers.IO) {
            // The legacy browser answers every open request with the same text at once, so an
            // overtaken call answers with nothing only when the newer query differs: answering a
            // repeat of the same text would empty the newer request's results too.
            delay(SEARCH_DEBOUNCE_MS)
            if (call != searchCalls.get()) {
                if (query != newestQuery) session.notifySearchResultChanged(browser, query, 0, params)
                return@future LibraryResult.ofVoid()
            }
            // The same text still out (the user paused, then pressed Search while it was searching) is
            // answered by that search's own notify, which reaches every open request with this text.
            // Checked before the reuse below: the search clears this only after notifying.
            if (searchingQuery == query) return@future LibraryResult.ofVoid()
            // The same text searched moments ago is answered from those results, or YouTube's history
            // would get the query twice.
            val (previousQuery, previousTracks) = lastSearch
            if (previousQuery == query && System.currentTimeMillis() - lastSearchAt < SEARCH_REUSE_MS) {
                session.notifySearchResultChanged(browser, query, previousTracks.size, params)
                return@future LibraryResult.ofVoid()
            }
            searchingQuery = query
            try {
                val resource = searchRepository.getSearchDataSong(query).lastOrNull()
                if (call != searchCalls.get() && query != newestQuery) {
                    session.notifySearchResultChanged(browser, query, 0, params)
                    return@future LibraryResult.ofVoid()
                }
                if (resource !is Resource.Success) {
                    // Not kept for reuse: a failed search is tried again when the user asks again.
                    session.notifySearchResultChanged(browser, query, 0, params)
                    return@future LibraryResult.ofVoid()
                }
                val tracks = resource.data?.toListTrack().orEmpty()
                lastSearch = query to tracks
                lastSearchAt = System.currentTimeMillis()
                session.notifySearchResultChanged(browser, query, tracks.size, params)
            } finally {
                if (searchingQuery == query) searchingQuery = null
            }
            LibraryResult.ofVoid()
        }
    }

    @UnstableApi
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            // Only the results of this very query: an overtaken query is answered with nothing.
            val (lastQuery, tracks) = lastSearch
            LibraryResult.ofItemList(
                if (lastQuery == query) tracks.map { it.toMediaItemWithoutPath() } else emptyList(),
                params,
            )
        }

    @UnstableApi
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            val rootExtras =
                Bundle().apply {
                    putBoolean(
                        MEDIA_SEARCH_SUPPORTED,
                        true,
                    )
                }
            val libraryParams =
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(rootExtras)
                    .build()
            return@future LibraryResult.ofItemList(
                when (parentId) {
                    ROOT -> {
                        listOf(
                            browsableMediaItem(
                                HOME,
                                context.getString(R.string.home),
                                context.getString(R.string.available_online),
                                drawableUri(R.drawable.home_android_auto),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            browsableMediaItem(
                                SONG,
                                context.getString(R.string.songs),
                                null,
                                drawableUri(R.drawable.baseline_album_24),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                FAVORITE,
                                context.getString(R.string.favorites),
                                null,
                                drawableUri(R.drawable.baseline_favorite_24),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                DOWNLOADED,
                                context.getString(R.string.downloaded),
                                null,
                                drawableUri(R.drawable.baseline_downloaded),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                PLAYLIST,
                                context.getString(R.string.playlists),
                                null,
                                drawableUri(R.drawable.baseline_playlist_add_24),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            ),
                        )
                    }

                    SONG -> {
                        songRepository
                            .getAllSongs(1000)
                            .last()
                            .sortedBy { it.inLibrary }
                            .map { it.toMediaItem(parentId) }
                    }

                    FAVORITE -> {
                        withPlayAll(
                            parentId,
                            songRepository
                                .getLikedSongs()
                                .first()
                                .map { it.toMediaItem(parentId) },
                        )
                    }

                    DOWNLOADED -> {
                        withPlayAll(
                            parentId,
                            songRepository
                                .getDownloadedSongs()
                                .first()
                                ?.map { it.toMediaItem(parentId) }
                                ?: emptyList(),
                        )
                    }

                    PLAYLIST -> {
                        localPlaylistRepository
                            .getAllLocalPlaylists()
                            .first()
                            .sortedBy { it.inLibrary }
                            .map {
                                browsableMediaItem(
                                    "$PLAYLIST/${it.id}",
                                    it.title,
                                    "${it.tracks?.size ?: 0} ${context.getString(R.string.track)}",
                                    it.thumbnail?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            }
                    }

                    HOME -> {
                        val temp =
                            homeRepository
                                .getHomeData(
                                    viewString = context.getString(R.string.view_count),
                                    songString = context.getString(R.string.song),
                                ).lastOrNull()
                                ?.data
                        listHomeItem.clear()
                        listHomeItem.addAll(temp?.second ?: emptyList())
                        if (!temp?.first.isNullOrEmpty()) {
                            var continueParam = temp.first
                            while (continueParam != null) {
                                homeRepository
                                    .getHomeDataContinue(
                                        continueParam,
                                        viewString = context.getString(R.string.view_count),
                                        songString = context.getString(R.string.song),
                                    ).lastOrNull()
                                    .let {
                                        listHomeItem.addAll(it?.data?.second ?: emptyList())
                                        continueParam = it?.data?.first
                                    }
                            }
                        }
                        listHomeItem.map {
                            browsableMediaItem(
                                "$HOME/${it.title}",
                                it.title,
                                it.subtitle,
                                null,
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            )
                        }
                    }

                    else -> {
                        when {
                            parentId.startsWith("$HOME/") -> {
                                if (parentId.split("/").size == 2) {
                                    val homeItem =
                                        listHomeItem.find {
                                            it.title == parentId.split("/").getOrNull(1)
                                        }
                                    homeItem
                                        ?.contents
                                        ?.filter { it?.playlistId != null || it?.videoId != null }
                                        ?.mapNotNull {
                                            if (it?.playlistId != null) {
                                                browsableMediaItem(
                                                    id = "$HOME/${homeItem.title}/$PLAYLIST/${it.playlistId}",
                                                    title = it.title,
                                                    subtitle = it.description,
                                                    iconUri =
                                                        it.thumbnails
                                                            .lastOrNull()
                                                            ?.url
                                                            ?.toUri(),
                                                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                                )
                                            } else if (it?.videoId != null) {
                                                it
                                                    .toTrack()
                                                    .toSongEntity()
                                                    .toMediaItem("$HOME/${homeItem.title}/$SONG")
                                            } else {
                                                null
                                            }
                                        }
                                        ?: emptyList()
                                } else {
                                    val playlistId = parentId.split("/").getOrNull(3)
                                    if (playlistId != null) {
                                        val playlist =
                                            playlistRepository
                                                .getFullPlaylistData(playlistId, context.getString(R.string.view_count))
                                                .lastOrNull()
                                        if (playlist?.data?.tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            playlist.data?.toPlaylistEntity()?.let { playlistRepository.insertAndReplacePlaylist(it) }
                                            withPlayAll(
                                                parentId,
                                                playlist.data?.tracks?.map { track ->
                                                    track
                                                        .toSongEntity()
                                                        .also {
                                                            songRepository.insertSong(it).first()
                                                        }.toMediaItem(parentId)
                                                } ?: emptyList(),
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    }
                                }
                            }

                            parentId.startsWith("$PLAYLIST/") -> {
                                val playlistId = parentId.split("/").getOrNull(1)
                                Logger.d(TAG, "onGetChildren: local playlist $playlistId")
                                val songs = playlistId?.let { localPlaylistSongs(it) }?.second.orEmpty()
                                withPlayAll(parentId, songs.map { it.toMediaItem(parentId) })
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    }
                },
                libraryParams,
            )
        }

    @UnstableApi
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            songRepository.getSongById(mediaId).first()?.let {
                LibraryResult.ofItem(it.toMediaItem(), null)
            } ?: streamRepository.getFullMetadata(mediaId).lastOrNull()?.data?.let {
                LibraryResult.ofItem(it.toMediaItemWithoutPath(), null)
            } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }

    /**
     * Something tapped in the car. The queue is loaded through [MediaPlayerHandler], and the session
     * gets an empty item list back, as it always has. When nothing could be found to play, the
     * future fails instead: Media3 then leaves the player alone (the legacy Android Auto path drops
     * the request, newer controllers get ERROR_UNKNOWN). Media3 applies even an empty list to the
     * player, and for that case it cleared the playlist under a player still in use: the FOSS build
     * kept playing the previous song, the Full build crashed in CastPlayer with "Empty playlist only
     * allowed in STATE_IDLE or STATE_ENDED" (Sentry ANDROID-EER).
     */
    @UnstableApi
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val mediaId = mediaItems.firstOrNull()?.mediaId
        return Futures.transformAsync(
            scope.future { if (playFromCar(mediaId)) MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs) else null },
            { result ->
                result?.let { Futures.immediateFuture(it) } ?: run {
                    Logger.w(TAG, "onSetMediaItems: nothing to play for $mediaId")
                    Futures.immediateFailedFuture<MediaSession.MediaItemsWithStartPosition>(IllegalStateException("Nothing to play for $mediaId"))
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /** Loads the queue for [mediaId], a path built by [onGetChildren] or [onGetSearchResult]; false when there is nothing to play. */
    private suspend fun playFromCar(mediaId: String?): Boolean {
        val path = mediaId?.split("/") ?: return false
        return when (path.firstOrNull()) {
            SONG -> {
                val songId = path.getOrNull(1) ?: return false
                playRadio(resolveTrack(songId) ?: return false)
            }

            FAVORITE -> {
                playList(songRepository.getLikedSongs().first(), path.getOrNull(1) ?: return false, null, context.getString(R.string.favorites))
            }

            DOWNLOADED -> {
                val songs = songRepository.getDownloadedSongs().first().orEmpty()
                playList(songs, path.getOrNull(1) ?: return false, null, context.getString(R.string.downloaded))
            }

            PLAYLIST -> {
                val playlistId = path.getOrNull(1) ?: return false
                val songId = path.getOrNull(2) ?: return false
                val (title, songs) = localPlaylistSongs(playlistId) ?: return false
                Logger.d(TAG, "onSetMediaItems playlistId: $playlistId, songs: ${songs.size}")
                playList(songs, songId, playlistId, "${context.getString(R.string.playlists)} \"$title\"")
            }

            HOME -> {
                val type = path.getOrNull(2) ?: return false
                val content = listHomeItem.find { it.title == path.getOrNull(1) }?.contents
                when (type) {
                    SONG -> {
                        val songId = path.getOrNull(3) ?: return false
                        // Content.toTrack() leaves isExplicit false, so the Home row's own flag is carried over
                        // for the explicit check below.
                        val songs =
                            content
                                ?.filter { it?.videoId != null }
                                ?.mapNotNull { item -> item?.let { it.toTrack().copy(isExplicit = it.isExplicit == true) } }
                                .orEmpty()
                        val firstQueue = songs.firstOrNull { it.videoId == songId }
                        if (firstQueue == null) {
                            // The Home section is no longer in memory: the app was restarted while the
                            // car kept showing Home. The tapped song still plays, as its radio.
                            return playRadio(resolveTrack(songId) ?: return false)
                        }
                        if (!allowed(firstQueue)) return false
                        songs.forEach {
                            songRepository.insertSong(it.toSongEntity()).first()
                        }
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = songs,
                                firstPlayedTrack = firstQueue,
                                playlistId = "RDAMVM$songId",
                                playlistName = "\"${firstQueue.title}\" Radio",
                                playlistType = PlaylistType.RADIO,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            firstQueue,
                            Config.SONG_CLICK,
                            0,
                        )
                        true
                    }

                    PLAYLIST -> {
                        val songId = path.getOrNull(4) ?: return false
                        val playlistId = path.getOrNull(3) ?: return false
                        Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                        val playlistEntity = playlistRepository.getPlaylist(playlistId).first()
                        val tracks = playlistEntity?.tracks
                        if (tracks.isNullOrEmpty()) return false
                        val songs = songRepository.getSongsByListVideoId(tracks).first()
                        Logger.d(TAG, "onSetMediaItems list songs: ${songs.size}")
                        playList(songs, songId, playlistId, "${context.getString(R.string.playlists)} \"${playlistEntity.title}\"")
                    }

                    else -> {
                        false
                    }
                }
            }

            else -> {
                false
            }
        }
    }

    /**
     * A song tapped in the car, wherever it was listed: the database, then the last search results
     * (never saved to the database), then YouTube itself. A search result or Home row the app had
     * never played was only looked up in the database, so tapping it played nothing (#2445, #2857).
     */
    private suspend fun resolveTrack(songId: String): Track? =
        songRepository.getSongById(songId).first()?.toTrack()
            ?: lastSearch.second.firstOrNull { it.videoId == songId }
            ?: streamRepository.getFullMetadata(songId).lastOrNull()?.data

    /**
     * Whether [track] may play: with explicit content turned off, the handler refuses it in
     * loadMediaItem and returns without loading anything, so the car would be handed an empty list.
     */
    private suspend fun allowed(track: Track): Boolean {
        if (!track.isExplicit || explicitAllowed()) return true
        // The same notice the handler shows when it refuses an explicit song itself.
        mediaPlayerHandler.showToast(ToastType.ExplicitContent)
        return false
    }

    private suspend fun explicitAllowed(): Boolean = dataStoreManager.explicitContentEnabled.first() != DataStoreManager.FALSE

    /** Plays [track] as its radio; false when it may not play. */
    private suspend fun playRadio(track: Track): Boolean {
        if (!allowed(track)) return false
        mediaPlayerHandler.setQueueData(
            QueueData.Data(
                listTracks = arrayListOf(track),
                firstPlayedTrack = track,
                playlistId = "RDAMVM${track.videoId}",
                playlistName = "\"${track.title}\" Radio",
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        mediaPlayerHandler.loadMediaItem(track, Config.SONG_CLICK, 0)
        return true
    }

    /** Queues [songs] from the tapped one, or from the first for [PLAY_ALL]; false when it is not among them. */
    private suspend fun playList(
        songs: List<SongEntity>,
        songId: String,
        playlistId: String?,
        playlistName: String,
    ): Boolean {
        val index =
            if (songId == PLAY_ALL) {
                // From the first song that may play: one explicit song at the top must not refuse the list.
                // None may play: the first song, so allowed() below shows why nothing started.
                val explicitOk = explicitAllowed()
                songs.indexOfFirst { !it.isExplicit || explicitOk }.coerceAtLeast(0)
            } else {
                songs.indexOfFirst { it.videoId == songId }
            }
        val clickedSong = songs.getOrNull(index)?.toTrack() ?: return false
        if (!allowed(clickedSong)) return false
        mediaPlayerHandler.setQueueData(
            QueueData.Data(
                listTracks = songs.toArrayListTrack(),
                firstPlayedTrack = clickedSong,
                playlistId = playlistId,
                playlistName = playlistName,
                playlistType = PlaylistType.LOCAL_PLAYLIST,
                continuation = null,
            ),
        )
        mediaPlayerHandler.loadMediaItem(clickedSong, Config.PLAYLIST_CLICK, index)
        return true
    }

    /**
     * A local playlist's title and its songs in the order the app lists and plays it: the playlist
     * sort the user picked in the app (one setting for every playlist, older first by default).
     * Null when the playlist is gone. Not the playlist's `tracks`, which keep the order songs were
     * added in: moving a song only changes its position row.
     */
    private suspend fun localPlaylistSongs(playlistId: String): Pair<String, List<SongEntity>>? {
        val id = playlistId.toLongOrNull() ?: return null
        val playlist = localPlaylistRepository.getLocalPlaylist(id).lastOrNull()?.data ?: return null
        val sort =
            when (dataStoreManager.localPlaylistFilter.first()) {
                DataStoreManager.LOCAL_PLAYLIST_FILTER_NEWER_FIRST -> FilterState.NewerFirst
                DataStoreManager.LOCAL_PLAYLIST_FILTER_TITLE -> FilterState.Title
                DataStoreManager.LOCAL_PLAYLIST_FILTER_CUSTOM_ORDER -> FilterState.CustomOrder
                else -> FilterState.OlderFirst
            }
        return playlist.title to localPlaylistRepository.getFullPlaylistTracks(id, sort)
    }

    /** "Play all" at the top of a non-empty list of songs: plays the whole list from its first song. */
    private fun withPlayAll(
        parentId: String,
        items: List<MediaItem>,
    ): List<MediaItem> =
        if (items.isEmpty()) {
            items
        } else {
            listOf(
                MediaItem
                    .Builder()
                    .setMediaId("$parentId/$PLAY_ALL")
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(context.getString(R.string.play_all))
                            .setArtworkUri(drawableUri(R.drawable.ic_car_play_all))
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build(),
                    ).build(),
            ) + items
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun SongEntity.toMediaItem(path: String) =
        MediaItem
            .Builder()
            .setMediaId("$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artistName?.joinToString(", "))
                    .setArtist(this.artistName?.joinToString(" "))
                    .setArtworkUri(this.thumbnails?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    private fun Track.toMediaItemWithoutPath(path: String? = SONG) =
        MediaItem
            .Builder()
            .setMediaId(if (path == null) this.videoId else "$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artists?.toListName()?.connectArtists())
                    .setArtist(this.artists?.toListName()?.connectArtists())
                    .setArtworkUri(
                        this.thumbnails
                            ?.lastOrNull()
                            ?.url
                            ?.toUri(),
                    ).setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val HOME = "home"
        const val ONLINE_PLAYLIST = "online_playlist"
        const val PLAYLIST = "playlist"
        const val FAVORITE = "favorite"
        const val DOWNLOADED = "downloaded"

        /** Last segment of a "Play all" item's media id; a YouTube video id is 11 characters, so none is this. */
        const val PLAY_ALL = "play_all"
        const val SEARCH_DEBOUNCE_MS = 800L
        const val SEARCH_REUSE_MS = 10_000L
        const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}