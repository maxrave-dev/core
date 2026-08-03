package com.maxrave.media3.service.callback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
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
import com.maxrave.common.Config
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
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
import com.maxrave.media3.extension.toMediaButtonPreferences
import com.maxrave.media3.extension.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "AndroidAuto"

@UnstableApi
internal class SimpleMediaSessionCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val dataStoreManager: DataStoreManager,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val homeRepository: HomeRepository,
    private val streamRepository: StreamRepository,
) : MediaLibrarySession.Callback {
    var toggleLike: () -> Unit = {
        mediaPlayerHandler.toggleLike()
    }
    var toggleRadio: () -> Unit = {
        mediaPlayerHandler.toggleRadio()
    }
    private val searchTempList = mutableListOf<Track>()
    private val listHomeItem = mutableListOf<HomeItem>()

    private fun isAaLikeInsteadOfPreviousEnabled(): Boolean =
        runBlocking {
            dataStoreManager.androidAutoLikeInsteadOfPrevious.first() == DataStoreManager.TRUE
        }

    private fun isCarOrPlatformMediaController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Boolean =
        session.isMediaNotificationController(controller) ||
            session.isAutoCompanionController(controller) ||
            session.isAutomotiveController(controller) ||
            isAndroidAutoHost(controller.packageName)

    private fun buildMediaButtonPreferences(aaLikeInsteadOfPrevious: Boolean): List<CommandButton> {
        val control = mediaPlayerHandler.controlState.value
        return listOf(
            GenericCommandButton.Like(control.isLiked),
            GenericCommandButton.Shuffle(isShuffled = control.isShuffle),
            GenericCommandButton.Repeat(repeatState = control.repeatState),
            GenericCommandButton.Radio,
        ).toMediaButtonPreferences(context, aaLikeInsteadOfPrevious)
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val aaLikeInsteadOfPrevious = isAaLikeInsteadOfPreviousEnabled()
        val customizeForCar = isCarOrPlatformMediaController(session, controller)
        Logger.w(
            TAG,
            "onConnect pkg=${controller.packageName} aaLike=$aaLikeInsteadOfPrevious " +
                "carOrPlatform=$customizeForCar " +
                "notif=${session.isMediaNotificationController(controller)} " +
                "auto=${session.isAutoCompanionController(controller)}",
        )
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.LIKE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.REPEAT, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.RADIO, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.SHUFFLE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.PREVIOUS, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle()))
                .build()
        // Backup for MODE: when Gearhead rebinds after AA returns, resume only if we were
        // interrupted while playing (adapter also watches CarConnection).
        if (customizeForCar && !session.isMediaNotificationController(controller)) {
            scope.launch {
                delay(500)
                runCatching {
                    (mediaPlayerHandler.player as? com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter)
                        ?.resumeIfInterrupted()
                }.onFailure {
                    Logger.e(TAG, "AA resume failed: ${it.message}")
                }
            }
        }

        val playerCommandsBuilder =
            Player.Commands
                .Builder()
                .addAllCommands()
                .remove(COMMAND_GET_TIMELINE)
        // Withhold system previous so Like in SLOT_BACK can occupy the compact back slot.
        // Steering-wheel previous still arrives via onMediaButtonEvent / remapped seek.
        if (aaLikeInsteadOfPrevious && customizeForCar) {
            playerCommandsBuilder
                .remove(COMMAND_SEEK_TO_PREVIOUS)
                .remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }

        val resultBuilder =
            MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommandsBuilder.build())

        // Platform media notification + AA hosts share this session for transport UI.
        if (aaLikeInsteadOfPrevious && customizeForCar) {
            // Like in SLOT_BACK; custom Previous first in overflow (old Like spot).
            resultBuilder.setMediaButtonPreferences(buildMediaButtonPreferences(true))
        }
        return resultBuilder.build()
    }

    /**
     * Steering-wheel "previous" arrives as [KeyEvent.KEYCODE_MEDIA_PREVIOUS].
     * Map it to like when the AA setting is on.
     */
    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        if (!isAaLikeInsteadOfPreviousEnabled() ||
            !isCarOrPlatformMediaController(session, controllerInfo)
        ) {
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
        val keyEvent =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            }
        if (keyEvent != null &&
            keyEvent.action == KeyEvent.ACTION_DOWN &&
            keyEvent.repeatCount == 0 &&
            (
                keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                    keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            )
        ) {
            Logger.w(
                TAG,
                "onMediaButtonEvent: mapping keyCode=${keyEvent.keyCode} from " +
                    "${controllerInfo.packageName} → like",
            )
            toggleLike()
            return true
        }
        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        Logger.w(TAG, "Player Command $playerCommand from ${controller.packageName}")
        val remapPreviousToLike =
            isAaLikeInsteadOfPreviousEnabled() &&
                isCarOrPlatformMediaController(session, controller)
        when (playerCommand) {
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                if (remapPreviousToLike) {
                    Logger.w(TAG, "Player previous → like (blocking seek)")
                    toggleLike()
                    return SessionResult.RESULT_INFO_SKIPPED
                }
            }
            COMMAND_SEEK_TO_NEXT -> {
                scope.launch {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next)
                }
            }
            COMMAND_GET_TIMELINE -> {
            }
        }
        if (playerCommand == COMMAND_SEEK_TO_PREVIOUS && !remapPreviousToLike) {
            scope.launch {
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous)
            }
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

            MEDIA_CUSTOM_COMMAND.PREVIOUS -> {
                scope.launch {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous)
                }
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

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        scope.future(Dispatchers.IO) {
            val searchResult =
                searchRepository.getSearchDataSong(query).lastOrNull()?.let { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            resource.data?.let {
                                searchTempList.clear()
                                searchTempList.addAll(it.toListTrack())
                            }
                            resource.data
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }
            if (searchResult != null) {
                session.notifySearchResultChanged(browser, query, searchResult.size, params)
            }
            LibraryResult.ofVoid()
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
            LibraryResult.ofItemList(
                searchTempList.map {
                    it.toMediaItemWithoutPath()
                },
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
                        songRepository.syncYouTubeLikedToLocal(force = false)
                        songRepository
                            .getLikedSongs()
                            .first()
                            .map { it.toMediaItem(parentId) }
                    }

                    DOWNLOADED -> {
                        songRepository
                            .getDownloadedSongs()
                            .first()
                            ?.map { it.toMediaItem(parentId) }
                            ?: emptyList()
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
                                            playlist.data?.tracks?.map { track ->
                                                track
                                                    .toSongEntity()
                                                    .also {
                                                        songRepository.insertSong(it).first()
                                                    }.toMediaItem(parentId)
                                            } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                }
                            }

                            parentId.startsWith("$PLAYLIST/") -> {
                                val playlistId = parentId.split("/").getOrNull(1)
                                if (playlistId != null) {
                                    val playlist =
                                        localPlaylistRepository.getLocalPlaylist(playlistId.toLong()).lastOrNull()?.data
                                    if (playlist != null) {
                                        Logger.w(TAG, "onGetChildren: $playlist")
                                        val tracks = playlist.tracks
                                        if (tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            songRepository
                                                .getSongsByListVideoId(tracks)
                                                .lastOrNull()
                                                ?.map { it.toMediaItem(parentId) } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                } else {
                                    emptyList()
                                }
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

    @UnstableApi
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
            // Play from Android Auto
            val defaultResult =
                MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val path =
                mediaItems.firstOrNull()?.mediaId?.split("/")
                    ?: return@future defaultResult
            when (path.firstOrNull()) {
                SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    // Search results are shown from searchTempList but were never written to Room.
                    // Looking up only via getSongById made AA search taps no-op for new songs,
                    // so the previously playing track kept going (looked like the "wrong" song).
                    val firstQueue =
                        searchTempList.firstOrNull { it.videoId == songId }
                            ?: songRepository.getSongById(songId).first()?.toTrack()
                            ?: streamRepository.getFullMetadata(songId).lastOrNull()?.data
                    if (firstQueue == null) {
                        Logger.w(TAG, "onSetMediaItems SONG: no track for $songId")
                        return@future defaultResult
                    }
                    Logger.w(TAG, "onSetMediaItems SONG: playing ${firstQueue.title} ($songId)")
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = arrayListOf(firstQueue),
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
                    defaultResult
                }

                FAVORITE -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    songRepository.syncYouTubeLikedToLocal(force = false)
                    val likedSongs = songRepository.getLikedSongs().first()
                    if (likedSongs.isEmpty()) {
                        defaultResult
                    } else {
                        var index = 0
                        val clickedSong =
                            likedSongs
                                .firstOrNull { it.videoId == songId }
                                ?.also {
                                    index = likedSongs.indexOf(it)
                                }?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = likedSongs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = null,
                                playlistName = context.getString(R.string.favorites),
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            clickedSong,
                            Config.PLAYLIST_CLICK,
                            index,
                        )
                        defaultResult
                    }
                }

                DOWNLOADED -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val downloadedSongs = songRepository.getDownloadedSongs().first().orEmpty()
                    if (downloadedSongs.isEmpty()) {
                        defaultResult
                    } else {
                        var index = 0
                        val clickedSong =
                            downloadedSongs
                                .firstOrNull { it.videoId == songId }
                                ?.also {
                                    index = downloadedSongs.indexOf(it)
                                }?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = downloadedSongs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = null,
                                playlistName = context.getString(R.string.downloaded),
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            clickedSong,
                            Config.PLAYLIST_CLICK,
                            index,
                        )
                        defaultResult
                    }
                }

                PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                    var title = ""
                    val songs =
                        localPlaylistRepository
                            .getLocalPlaylist(playlistId.toLong())
                            .lastOrNull()
                            ?.data
                            ?.also {
                                title = it.title
                            }?.tracks
                            ?.let {
                                songRepository.getSongsByListVideoId(it)
                            }?.lastOrNull()
                    Logger.w(TAG, "onSetMediaItems songs: $songs")
                    if (songs.isNullOrEmpty()) {
                        defaultResult
                    } else {
                        var index = 0
                        val clickedSong =
                            songs
                                .firstOrNull { it.videoId == songId }
                                ?.also {
                                    index = songs.indexOf(it)
                                }?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = songs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = playlistId,
                                playlistName = "${
                                    context.getString(
                                        R.string.playlists,
                                    )
                                } \"${title}\"",
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        mediaPlayerHandler.loadMediaItem(
                            clickedSong,
                            Config.PLAYLIST_CLICK,
                            index,
                        )
                        defaultResult
                    }
                }

                HOME -> {
                    val type = path.getOrNull(2) ?: return@future defaultResult
                    val content = listHomeItem.find { it.title == path.getOrNull(1) }?.contents
                    if (type == SONG) {
                        val songId = path.getOrNull(3) ?: return@future defaultResult
                        val songs =
                            content?.filter { it?.videoId != null }?.mapNotNull {
                                it?.toTrack()
                            }
                        if (songs.isNullOrEmpty()) {
                            defaultResult
                        } else {
                            songs.forEach {
                                songRepository.insertSong(it.toSongEntity()).first()
                            }
                            val firstQueue = songs.firstOrNull { it.videoId == songId } ?: return@future defaultResult
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
                            defaultResult
                        }
                    } else if (type == PLAYLIST) {
                        val songId = path.getOrNull(4) ?: return@future defaultResult
                        val playlistId = path.getOrNull(3) ?: return@future defaultResult
                        Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                        val playlistEntity = playlistRepository.getPlaylist(playlistId).first()
                        Logger.w(TAG, "onSetMediaItems playlistEntity: $playlistEntity")
                        val tracks = playlistEntity?.tracks
                        if (tracks.isNullOrEmpty()) {
                            defaultResult
                        } else if (tracks.isNotEmpty()) {
                            val songs =
                                tracks
                                    .let {
                                        songRepository
                                            .getSongsByListVideoId(tracks)
                                            .first()
                                            .sortedBy {
                                                tracks.indexOf(it.videoId)
                                            }.also {
                                                Logger.w(TAG, "onSetMediaItems list songs: $it")
                                            }
                                    }
                            var index = 0
                            val clickedSong =
                                songs
                                    .firstOrNull { it.videoId == songId }
                                    ?.also {
                                        index = songs.indexOf(it)
                                    }?.toTrack() ?: return@future defaultResult
                            mediaPlayerHandler.setQueueData(
                                QueueData.Data(
                                    listTracks = songs.toArrayListTrack(),
                                    firstPlayedTrack = clickedSong,
                                    playlistId = playlistId,
                                    playlistName = "${
                                        context.getString(
                                            R.string.playlists,
                                        )
                                    } \"${playlistEntity.title}\"",
                                    playlistType = PlaylistType.LOCAL_PLAYLIST,
                                    continuation = null,
                                ),
                            )
                            mediaPlayerHandler.loadMediaItem(
                                clickedSong,
                                Config.PLAYLIST_CLICK,
                                index,
                            )
                            defaultResult
                        } else {
                            defaultResult
                        }
                    } else {
                        return@future defaultResult
                    }
                }

                else -> {
                    defaultResult
                }
            }
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
        const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        private fun isAndroidAutoHost(packageName: String): Boolean =
            packageName == "com.google.android.projection.gearhead" ||
                packageName.contains("projection", ignoreCase = true) ||
                packageName.contains("gearhead", ignoreCase = true)
    }
}