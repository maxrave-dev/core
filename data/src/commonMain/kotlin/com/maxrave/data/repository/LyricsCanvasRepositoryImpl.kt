@file:OptIn(ExperimentalTime::class)

package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toCanvasResult
import com.maxrave.data.mapping.toLyrics
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistLogo
import com.maxrave.domain.data.model.canvas.CanvasResult
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.metadata.SimpMusicLyrics
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toPlainLrcString
import com.maxrave.domain.utils.toRichSyncLrcString
import com.maxrave.domain.utils.toSyncedLrcString
import com.maxrave.domain.utils.toSyncedLyrics
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.simpmusic.aiservice.AiClient
import org.simpmusic.lyrics.SimpMusicLyricsClient
import org.simpmusic.lyrics.am.AMAlbumResource
import org.simpmusic.lyrics.am.AMEditorialVideo
import org.simpmusic.lyrics.am.AMMotionVideo
import org.simpmusic.lyrics.am.AMSongWithAlbum
import org.simpmusic.lyrics.am.toImageUrl
import org.simpmusic.lyrics.models.request.LyricsBody
import org.simpmusic.lyrics.models.request.TranslatedLyricsBody
import org.simpmusic.lyrics.parser.parseTtmlLyrics
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class LyricsCanvasRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val spotify: Spotify,
    private val simpMusicLyrics: SimpMusicLyricsClient,
    private val aiClient: AiClient,
) : LyricsCanvasRepository {
    /**
     * Video ids whose animated artwork is being searched right now, so the same track is never sent
     * to AM twice at once. Not a cancellation: the first attempt is the one that fills the cache.
     */
    private val amArtworkInFlight = mutableSetOf<String>()
    private val amArtworkLock = Mutex()

    override fun getSavedLyrics(videoId: String): Flow<LyricsEntity?> = flow { emit(localDataSource.getSavedLyrics(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun insertLyrics(lyricsEntity: LyricsEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertLyrics(lyricsEntity)
        }

    override suspend fun insertTranslatedLyrics(translatedLyrics: TranslatedLyricsEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertTranslatedLyrics(translatedLyrics)
        }

    override fun getSavedTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<TranslatedLyricsEntity?> = flow { emit(localDataSource.getTranslatedLyrics(videoId, language)) }.flowOn(Dispatchers.IO)

    override suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    ) = withContext(Dispatchers.IO) {
        localDataSource.removeTranslatedLyrics(videoId, language)
    }

    override fun getYouTubeCaption(
        preferLang: String,
        videoId: String,
    ): Flow<Resource<Pair<Lyrics, Lyrics?>>> =
        flow {
            runCatching {
                youTube
                    .getYouTubeCaption(videoId, preferLang)
                    .onSuccess { lyrics ->
                        emit(
                            Resource.Success<Pair<Lyrics, Lyrics?>>(
                                Pair(lyrics.first.toLyrics(), lyrics.second?.toLyrics()),
                            ),
                        )
                    }.onFailure { e ->
                        Logger.d("Lyrics", "Error: ${e.message}")
                        emit(Resource.Error<Pair<Lyrics, Lyrics?>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getCanvas(
        dataStoreManager: DataStoreManager,
        videoId: String,
        duration: Int,
    ): Flow<Resource<CanvasResult>> =
        flow {
            runCatching {
                localDataSource.getSong(videoId).let { song ->
                    val q =
                        "${song?.title} ${song?.artistName?.firstOrNull() ?: ""}"
                            .replace(
                                Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                                " ",
                            ).replace(
                                Regex("( và | & | и | e | und |, |和| dan)"),
                                " ",
                            ).replace("  ", " ")
                            .replace(Regex("([()])"), "")
                            .replace(".", " ")
                            .replace("  ", " ")
                    var spotifyPersonalToken = ""
                    var spotifyClientToken = ""
                    Logger.w("Lyrics", "getSpotifyLyrics: ${dataStoreManager.spotifyPersonalTokenExpires.first()}")
                    Logger.w("Lyrics", "getSpotifyLyrics ${dataStoreManager.spotifyClientTokenExpires.first()}")
                    Logger.w("Lyrics", "getSpotifyLyrics now: ${now()}")
                    if (dataStoreManager.spotifyPersonalToken
                            .first()
                            .isNotEmpty() &&
                        dataStoreManager.spotifyClientToken.first().isNotEmpty() &&
                        dataStoreManager.spotifyPersonalTokenExpires.first() > Clock.System.now().toEpochMilliseconds() &&
                        dataStoreManager.spotifyPersonalTokenExpires.first() != 0L &&
                        dataStoreManager.spotifyClientTokenExpires.first() > Clock.System.now().toEpochMilliseconds() &&
                        dataStoreManager.spotifyClientTokenExpires.first() != 0L
                    ) {
                        spotifyPersonalToken = dataStoreManager.spotifyPersonalToken.first()
                        spotifyClientToken = dataStoreManager.spotifyClientToken.first()
                        Logger.d("Canvas", "spotifyPersonalToken: $spotifyPersonalToken")
                        Logger.d("Canvas", "spotifyClientToken: $spotifyClientToken")
                    } else if (dataStoreManager.spdc.first().isNotEmpty()) {
                        spotify
                            .getClientToken()
                            .onSuccess {
                                Logger.d("Canvas", "Request clientToken: ${it.grantedToken.token}")
                                dataStoreManager.setSpotifyClientTokenExpires(
                                    (it.grantedToken.expiresAfterSeconds * 1000L) + Clock.System.now().toEpochMilliseconds(),
                                )
                                dataStoreManager.setSpotifyClientToken(it.grantedToken.token)
                                spotifyClientToken = it.grantedToken.token
                            }.onFailure {
                                it.printStackTrace()
                                emit(Resource.Error<CanvasResult>(it.message ?: "Not found"))
                            }
                        spotify
                            .getPersonalTokenWithTotp(dataStoreManager.spdc.first())
                            .onSuccess {
                                spotifyPersonalToken = it.accessToken
                                dataStoreManager.setSpotifyPersonalToken(spotifyPersonalToken)
                                dataStoreManager.setSpotifyPersonalTokenExpires(
                                    it.accessTokenExpirationTimestampMs,
                                )
                                Logger.d("Canvas", "Request spotifyPersonalToken: $spotifyPersonalToken")
                            }.onFailure {
                                it.printStackTrace()
                                emit(Resource.Error<CanvasResult>(it.message ?: "Not found"))
                            }
                    }
                    if (spotifyPersonalToken.isNotEmpty() && spotifyClientToken.isNotEmpty()) {
                        val authToken = spotifyPersonalToken
                        spotify
                            .searchSpotifyTrack(q, authToken, spotifyClientToken)
                            .onSuccess { searchResponse ->
                                Logger.w("Canvas", "searchSpotifyResponse: $searchResponse")
                                val track =
                                    if (duration != 0) {
                                        searchResponse.data?.searchV2?.tracksV2?.items?.find {
                                            abs(
                                                (
                                                    (
                                                        (
                                                            it.item
                                                                ?.data
                                                                ?.duration
                                                                ?.totalMilliseconds ?: (0 / 1000)
                                                        ) - duration
                                                    )
                                                ),
                                            ) < 1
                                        }
                                            ?: searchResponse.data
                                                ?.searchV2
                                                ?.tracksV2
                                                ?.items
                                                ?.firstOrNull()
                                    } else {
                                        searchResponse.data
                                            ?.searchV2
                                            ?.tracksV2
                                            ?.items
                                            ?.firstOrNull()
                                    }
                                if (track != null) {
                                    Logger.w("Canvas", "track: $track")
                                    spotify
                                        .getSpotifyCanvas(
                                            track.item?.data?.id ?: "",
                                            spotifyPersonalToken,
                                            spotifyClientToken,
                                        ).onSuccess {
                                            Logger.w("Canvas", "canvas: $it")
                                            it.toCanvasResult()?.let {
                                                emit(Resource.Success(it))
                                            } ?: run {
                                                emit(Resource.Error<CanvasResult>("Not found"))
                                            }
                                        }.onFailure {
                                            Logger.e("Canvas", "Error: ${it.message}")
                                            it.printStackTrace()
                                            emit(Resource.Error<CanvasResult>(it.message ?: "Not found"))
                                        }
                                } else {
                                    emit(Resource.Error<CanvasResult>("Not found"))
                                }
                            }.onFailure { throwable ->
                                throwable.printStackTrace()
                                emit(Resource.Error<CanvasResult>(throwable.message ?: "Not found"))
                            }
                    } else {
                        emit(Resource.Error<CanvasResult>("Not found"))
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Animated album artwork from the hidden AM catalog. It fills exactly the slot a Spotify canvas
     * fills — same [CanvasResult], same two columns on `song` — so everything downstream is unaware
     * of which source produced it. Unlike the canvas path it needs no account of any kind.
     */
    override fun getAMAnimatedArtwork(videoId: String): Flow<Resource<CanvasResult>> =
        flow {
            val song = localDataSource.getSong(videoId)
            if (song == null) {
                Logger.e(AM_ARTWORK_TAG, "Not found $videoId")
                emit(Resource.Error<CanvasResult>("Song not found"))
                return@flow
            }
            // Reuse what an earlier play already resolved. The column is shared with the Spotify
            // canvas, so only an HLS url is taken back — an `.mp4` left there by that path would
            // pin the track to it and this source would never be asked. Without this the number of
            // requests grows with plays rather than with tracks, against an API that is not ours.
            val cached = song.canvasUrl?.takeIf { it.contains(".m3u8") }
            if (cached != null) {
                emit(
                    Resource.Success(
                        CanvasResult(
                            isVideo = true,
                            canvasUrl = cached,
                            canvasThumbUrl = song.canvasThumbUrl,
                        ),
                    ),
                )
                return@flow
            }

            // The caller launches this outside the collector that triggered it, so a second
            // timeline emission for the same track — the duration changes once buffering finishes —
            // fires an identical second search while the first is still in the air. Claiming the id
            // drops the duplicate without cancelling the attempt that is already running.
            if (!claimAmArtwork(videoId)) {
                emit(Resource.Error<CanvasResult>("Already searching"))
                return@flow
            }

            val artist = song.artistName?.firstOrNull().orEmpty()
            // YouTube Music leaves the album name null on plenty of rows, and rows written by older
            // builds carry the literal placeholder "Album"; neither can be searched for.
            val albumName = song.albumName?.takeIf { it.isNotBlank() && it != PLACEHOLDER_ALBUM_NAME }
            Logger.d(AM_ARTWORK_TAG, "resolving $videoId — album=$albumName title=${song.title} artist=$artist")

            try {
                // Tier 1 — the album name, which is what AM indexes one animated artwork against.
                // Only an exact answer is taken: see pickAlbumMatch for why a near-miss is worse
                // than no answer at all here.
                val byAlbum =
                    albumName?.let { name ->
                        simpMusicLyrics
                            .searchAMAlbum("$name $artist".cleanForSearch(), limit = AM_SEARCH_LIMIT)
                            .onFailure { Logger.e(AM_ARTWORK_TAG, "album search failed: ${it.message}") }
                            .getOrNull()
                            ?.pickAlbumMatch(name, artist)
                    }

                // Tier 2 — the track itself, which carries the album it belongs to. A title cannot
                // be searched as an album (AM answers a title with the singles that share it), so
                // this goes through songs; the album name, when known, still decides between the
                // several albums a track can legitimately appear on.
                val album =
                    byAlbum ?: simpMusicLyrics
                        .searchAMSongsWithAlbums("${song.title} $artist".cleanForSearch(), limit = AM_SEARCH_LIMIT)
                        .onFailure { Logger.e(AM_ARTWORK_TAG, "song search failed: ${it.message}") }
                        .getOrNull()
                        ?.pickSongMatch(
                            title = song.title,
                            artist = artist,
                            durationSeconds = song.durationSeconds,
                            albumHint = albumName,
                        )
                        ?.album

                val master =
                    album
                        ?.attributes
                        ?.editorialVideo
                        ?.preferredRendition()
                        ?.video
                Logger.d(AM_ARTWORK_TAG, "Matched ${album?.attributes?.name} for $videoId --> $master")
                if (master == null) {
                    emit(Resource.Error<CanvasResult>("No animated artwork"))
                    return@flow
                }
                // Resolve the ladder down to one rendition here, so neither player has to choose:
                // mpv would take the top of it by default (10-bit HEVC at 2048x2732, ~52 MB for a
                // 22-second loop) and ExoPlayer would pick by bandwidth estimate, giving the two
                // platforms different results for the same track. Falls back to the master url,
                // which still plays — just not as cheaply.
                val url =
                    simpMusicLyrics
                        .selectAMRendition(master, AM_MIN_RENDITION_WIDTH)
                        .getOrNull()
                        ?: master
                Logger.d(AM_ARTWORK_TAG, "Rendition for $videoId --> $url")
                val previewFrame =
                    album.attributes
                        ?.editorialVideo
                        ?.preferredRendition()
                        ?.previewFrame
                emit(
                    Resource.Success(
                        CanvasResult(
                            isVideo = true,
                            canvasUrl = url,
                            // The url is a {w}x{h} template and the frame carries its own
                            // dimensions — the tall rendition is 2048x2732, not square — so asking
                            // for a square crop of it would squash the image.
                            canvasThumbUrl =
                                previewFrame?.toImageUrl(
                                    width = previewFrame.width ?: AM_PREVIEW_FRAME_SIZE,
                                    height = previewFrame.height ?: AM_PREVIEW_FRAME_SIZE,
                                ),
                        ),
                    ),
                )
            } finally {
                amArtworkLock.withLock { amArtworkInFlight -= videoId }
            }
        }.flowOn(Dispatchers.IO)

    /** Returns true when this call took ownership of [videoId]; false when a search is already out. */
    private suspend fun claimAmArtwork(videoId: String): Boolean =
        amArtworkLock.withLock {
            if (videoId in amArtworkInFlight) false else amArtworkInFlight.add(videoId)
        }

    override suspend fun updateCanvasUrl(
        videoId: String,
        canvasUrl: String,
    ) = withContext(Dispatchers.IO) {
        localDataSource.updateCanvasUrl(videoId, canvasUrl)
    }

    override suspend fun updateCanvasThumbUrl(
        videoId: String,
        canvasThumbUrl: String,
    ) = withContext(Dispatchers.IO) {
        localDataSource.updateCanvasThumbUrl(videoId, canvasThumbUrl)
    }

    override fun getSpotifyLyrics(
        dataStoreManager: DataStoreManager,
        query: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            runCatching {
                val q =
                    query
                        .replace(
                            Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                            " ",
                        ).replace(
                            Regex("( và | & | и | e | und |, |和| dan)"),
                            " ",
                        ).replace("  ", " ")
                        .replace(Regex("([()])"), "")
                        .replace(".", " ")
                        .replace("  ", " ")
                Logger.d("Lyrics", "query: $q")
                var spotifyPersonalToken = ""
                var spotifyClientToken = ""
                Logger.w("Lyrics", "getSpotifyLyrics: ${dataStoreManager.spotifyPersonalTokenExpires.first()}")
                if (dataStoreManager.spotifyPersonalToken
                        .first()
                        .isNotEmpty() &&
                    dataStoreManager.spotifyPersonalTokenExpires.first() > Clock.System.now().toEpochMilliseconds() &&
                    dataStoreManager.spotifyPersonalTokenExpires.first() != 0L &&
                    dataStoreManager.spotifyClientTokenExpires.first() > Clock.System.now().toEpochMilliseconds() &&
                    dataStoreManager.spotifyClientTokenExpires.first() != 0L
                ) {
                    spotifyPersonalToken = dataStoreManager.spotifyPersonalToken.first()
                    spotifyClientToken = dataStoreManager.spotifyClientToken.first()
                    Logger.d("Lyrics", "spotifyPersonalToken: $spotifyPersonalToken")
                    Logger.d("Lyrics", "spotifyClientToken: $spotifyClientToken")
                } else if (dataStoreManager.spdc.first().isNotEmpty()) {
                    runBlocking {
                        spotify
                            .getClientToken()
                            .onSuccess {
                                Logger.d("Canvas", "Request clientToken: ${it.grantedToken.token}")
                                dataStoreManager.setSpotifyClientTokenExpires(
                                    (it.grantedToken.expiresAfterSeconds * 1000L) + Clock.System.now().toEpochMilliseconds(),
                                )
                                dataStoreManager.setSpotifyClientToken(it.grantedToken.token)
                                spotifyClientToken = it.grantedToken.token
                            }.onFailure {
                                it.printStackTrace()
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                    }
                    runBlocking {
                        spotify
                            .getPersonalTokenWithTotp(dataStoreManager.spdc.first())
                            .onSuccess {
                                spotifyPersonalToken = it.accessToken
                                dataStoreManager.setSpotifyPersonalToken(spotifyPersonalToken)
                                dataStoreManager.setSpotifyPersonalTokenExpires(
                                    it.accessTokenExpirationTimestampMs,
                                )
                                Logger.d("Lyrics", "REQUEST spotifyPersonalToken: $spotifyPersonalToken")
                            }.onFailure {
                                it.printStackTrace()
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                    }
                }
                if (spotifyPersonalToken.isNotEmpty() && spotifyClientToken.isNotEmpty()) {
                    val authToken = spotifyPersonalToken
                    Logger.d("Lyrics", "authToken: $authToken")
                    spotify
                        .searchSpotifyTrack(q, authToken, spotifyClientToken)
                        .onSuccess { searchResponse ->
                            val track =
                                if (duration != 0 && duration != null) {
                                    searchResponse.data?.searchV2?.tracksV2?.items?.find {
                                        abs(
                                            (
                                                (
                                                    (
                                                        it.item
                                                            ?.data
                                                            ?.duration
                                                            ?.totalMilliseconds ?: (0 / 1000)
                                                    ) - duration
                                                )
                                            ),
                                        ) < 1
                                    }
                                        ?: searchResponse.data
                                            ?.searchV2
                                            ?.tracksV2
                                            ?.items
                                            ?.firstOrNull()
                                } else {
                                    searchResponse.data
                                        ?.searchV2
                                        ?.tracksV2
                                        ?.items
                                        ?.firstOrNull()
                                }
                            Logger.d("Lyrics", "track: $track")
                            if (track != null) {
                                spotify
                                    .getSpotifyLyrics(track.item?.data?.id ?: "", spotifyPersonalToken, spotifyClientToken)
                                    .onSuccess {
                                        emit(Resource.Success<Lyrics>(it.toLyrics()))
                                    }.onFailure {
                                        it.printStackTrace()
                                        emit(Resource.Error<Lyrics>("Not found"))
                                    }
                            } else {
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                        }.onFailure { throwable ->
                            throwable.printStackTrace()
                            emit(Resource.Error<Lyrics>("Not found"))
                        }
                }
            }
        }

    override fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.w("Lyrics", "getLrclibLyricsData: $sartist $strack $duration")
            val qartist =
                sartist
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                strack
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchLrclibLyrics(qtrack, qartist, duration)
                .onSuccess {
                    it?.let { emit(Resource.Success<Lyrics>(it.toLyrics())) }
                }.onFailure {
                    it.printStackTrace()
                    emit(Resource.Error<Lyrics>("Not found"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.w("Lyrics", "getBetterLyrics: $artist $track")
            val qartist =
                artist
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                track
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchBetterLyrics(qtrack, qartist, duration)
                .onSuccess { ttml ->
                    if (ttml.isNullOrEmpty()) {
                        emit(Resource.Error<Lyrics>("No BetterLyrics found"))
                        return@onSuccess
                    }
                    val lyrics = parseTtmlLyrics(ttml).toLyrics()
                    emit(Resource.Success(lyrics))
                }.onFailure {
                    it.printStackTrace()
                    emit(Resource.Error<Lyrics>("BetterLyrics search failed"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getArtistLogo(artistName: String): Flow<Resource<ArtistLogo>> =
        flow {
            simpMusicLyrics
                .searchAMArtist(artistName, limit = 1)
                .onSuccess { artists ->
                    val id = artists.firstOrNull()?.id
                    if (id == null) {
                        emit(Resource.Error<ArtistLogo>("Artist not found"))
                        return@onSuccess
                    }
                    simpMusicLyrics
                        .getAMArtist(id)
                        .onSuccess { artist ->
                            val logo = artist?.attributes?.editorialArtwork?.musicContentColorLogoTrimmed
                            val srcW = logo?.width ?: 0
                            val srcH = logo?.height ?: 0
                            // Scale down to ~1000px wide, keeping the logo aspect ratio.
                            val targetW = 1000
                            val targetH = if (srcW > 0) (srcH.toLong() * targetW / srcW).toInt() else srcH
                            val url = logo?.toImageUrl(targetW, targetH)
                            if (logo == null || url == null) {
                                emit(Resource.Error<ArtistLogo>("No artist logo"))
                                return@onSuccess
                            }
                            emit(
                                Resource.Success(
                                    ArtistLogo(
                                        logoUrl = url,
                                        bgColorHex = logo.bgColor,
                                        width = srcW,
                                        height = srcH,
                                    ),
                                ),
                            )
                        }.onFailure {
                            emit(Resource.Error<ArtistLogo>(it.message ?: "Failed to fetch artist"))
                        }
                }.onFailure {
                    emit(Resource.Error<ArtistLogo>(it.message ?: "Artist search failed"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getAITranslationLyrics(
        lyrics: Lyrics,
        targetLanguage: String,
    ): Flow<Resource<Lyrics>> =
        flow {
            runCatching {
                Logger.w("AI Translation", "targetLanguage: $targetLanguage")
                aiClient
                    .translateLyrics(lyrics, targetLanguage)
                    .onSuccess { translatedLyrics ->
                        Logger.w("AI Translation", "translatedLyrics: $translatedLyrics")
                        emit(Resource.Success(translatedLyrics))
                    }.onFailure { throwable ->
                        Logger.e("AI Translation", "Error: ${throwable.message}")
                        emit(Resource.Error<Lyrics>("Translation failed"))
                    }
            }
        }.flowOn(Dispatchers.IO)

    // SimpMusic Lyrics
    private val simpMusicLyricsTag = "SimpMusicLyricsRepository"

    override fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>> =
        flow {
            simpMusicLyrics
                .getLyrics(videoId)
                .onSuccess { lyrics ->
                    Logger.d(simpMusicLyricsTag, "Lyrics found: $lyrics")
                    val result = lyrics.firstOrNull()
                    if (result == null) {
                        Logger.w(simpMusicLyricsTag, "No lyrics found for videoId: $videoId")
                        emit(Resource.Error<Lyrics>("No lyrics found"))
                        return@onSuccess
                    }
                    val appLyrics =
                        result.toLyrics()?.copy(
                            simpMusicLyrics =
                                SimpMusicLyrics(
                                    id = result.id,
                                    vote = result.vote,
                                ),
                        )
                    if (appLyrics == null) {
                        Logger.w(simpMusicLyricsTag, "Failed to convert lyrics for videoId: $videoId")
                        emit(Resource.Error<Lyrics>("Failed to convert lyrics"))
                        return@onSuccess
                    }
                    emit(
                        Resource.Success<Lyrics>(
                            appLyrics,
                        ),
                    )
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Get Lyrics Error: ${it.message}")
                    emit(Resource.Error<Lyrics>(it.message ?: "Failed to get lyrics"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getSimpMusicTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<Resource<Lyrics>> =
        flow {
            simpMusicLyrics
                .getTranslatedLyrics(videoId, language)
                .onSuccess { lyrics ->
                    Logger.d(simpMusicLyricsTag, "Translated Lyrics found: ${lyrics.toLyrics()}")
                    emit(
                        Resource.Success<Lyrics>(
                            lyrics
                                .toLyrics()
                                .copy(
                                    simpMusicLyrics =
                                        SimpMusicLyrics(
                                            id = lyrics.id,
                                            vote = lyrics.vote,
                                        ),
                                ),
                        ),
                    )
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Get Translated Lyrics Error: ${it.message}")
                    emit(Resource.Error<Lyrics>(it.message ?: "Failed to get translated lyrics"))
                }
        }.flowOn(Dispatchers.IO)

    override fun voteSimpMusicLyrics(
        lyricsId: String,
        upvote: Boolean,
    ): Flow<Resource<String>> =
        flow {
            simpMusicLyrics
                .voteLyrics(lyricsId, upvote)
                .onSuccess {
                    Logger.d(simpMusicLyricsTag, "Vote Lyrics Success: $it")
                    emit(Resource.Success(it.id))
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Vote Lyrics Error: ${it.message}")
                    emit(Resource.Error<String>(it.message ?: "Failed to vote lyrics"))
                }
        }.flowOn(Dispatchers.IO)

    override fun voteSimpMusicTranslatedLyrics(
        translatedLyricsId: String,
        upvote: Boolean,
    ): Flow<Resource<String>> =
        flow {
            simpMusicLyrics
                .voteTranslatedLyrics(translatedLyricsId, upvote)
                .onSuccess {
                    Logger.d(simpMusicLyricsTag, "Vote Translated Lyrics Success: $it")
                    emit(Resource.Success(it.id))
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Vote Translated Lyrics Error: ${it.message}")
                    emit(Resource.Error<String>(it.message ?: "Failed to vote translated lyrics"))
                }
        }.flowOn(Dispatchers.IO)

    override fun insertSimpMusicLyrics(
        dataStoreManager: DataStoreManager,
        track: Track,
        duration: Int,
        lyrics: Lyrics,
    ): Flow<Resource<String>> =
        flow {
            if (lyrics.lines.isNullOrEmpty()) {
                emit(
                    Resource.Error<String>("Lyrics are empty"),
                )
                return@flow
            }
            val syncedLyric =
                if (lyrics.syncType == "LINE_SYNCED") {
                    lyrics.toSyncedLrcString()
                } else if (lyrics.syncType == "RICH_SYNCED") {
                    lyrics.toSyncedLyrics().toSyncedLrcString()
                } else {
                    null
                }
            val richSyncedLyric =
                if (lyrics.syncType == "RICH_SYNCED") {
                    lyrics.toRichSyncLrcString()
                } else {
                    null
                }
            val (contributorName, contributorEmail) = dataStoreManager.contributorName.first() to dataStoreManager.contributorEmail.first()
            simpMusicLyrics
                .insertLyrics(
                    LyricsBody(
                        videoId = track.videoId,
                        songTitle = track.title,
                        artistName = track.artists?.toListName()?.connectArtists() ?: "",
                        albumName = track.album?.name ?: "",
                        durationSeconds = duration,
                        plainLyric = lyrics.toPlainLrcString() ?: "",
                        syncedLyrics = syncedLyric,
                        richSyncLyrics = richSyncedLyric,
                        contributor = contributorName,
                        contributorEmail = contributorEmail,
                        trackType = if (track.thumbnails?.firstOrNull()?.let { it.width == it.height && it.width > 0 } == true) "SONG" else "VIDEO",
                    ),
                ).onSuccess {
                    Logger.d(simpMusicLyricsTag, "Inserted Lyrics: $it")
                    emit(Resource.Success(it.id))
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Insert Lyrics Error: ${it.message}")
                    emit(Resource.Error<String>(it.message ?: "Failed to insert lyrics"))
                }
        }.flowOn(Dispatchers.IO)

    override fun insertSimpMusicTranslatedLyrics(
        dataStoreManager: DataStoreManager,
        track: Track,
        translatedLyrics: Lyrics,
        language: String,
    ): Flow<Resource<String>> =
        flow {
            val syncedLyrics = translatedLyrics.toSyncedLrcString()
            if (translatedLyrics.lines.isNullOrEmpty() || syncedLyrics == null || language.length != 2) {
                emit(
                    Resource.Error<String>("Lyrics are empty"),
                )
                return@flow
            }
            val (contributorName, contributorEmail) = dataStoreManager.contributorName.first() to dataStoreManager.contributorEmail.first()
            simpMusicLyrics
                .insertTranslatedLyrics(
                    TranslatedLyricsBody(
                        videoId = track.videoId,
                        translatedLyric = syncedLyrics,
                        language = language,
                        contributor = contributorName,
                        contributorEmail = contributorEmail,
                    ),
                ).onSuccess {
                    Logger.d(simpMusicLyricsTag, "Inserted Translated Lyrics: $it")
                    emit(Resource.Success(it.id))
                }.onFailure {
                    Logger.e(simpMusicLyricsTag, "Insert Translated Lyrics Error: ${it.message}")
                    emit(Resource.Error<String>(it.message ?: "Failed to insert translated lyrics"))
                }
        }.flowOn(Dispatchers.IO)
}

private const val AM_ARTWORK_TAG = "AMArtwork"
private const val AM_SEARCH_LIMIT = 5

// Narrowest animated-artwork rendition considered sharp enough. On the measured ladders this lands
// on 830x1106 for the tall cut and 768x768 for the square one, both H.264 at roughly 2-3 Mbps.
private const val AM_MIN_RENDITION_WIDTH = 720

/** [matchScore] tier for an exact name match. */
private const val EXACT_NAME_TIER = 0

/**
 * How far two running times may differ and still be the same recording. Wide enough to absorb the
 * encoding differences between YouTube and Apple, far narrower than the gap between a studio take
 * and a live one.
 */
private const val AM_DURATION_TOLERANCE_SECONDS = 3
// Only used when AM omits the preview frame's own dimensions, which it normally supplies.
private const val AM_PREVIEW_FRAME_SIZE = 1080

// The album name YouTube Music rows fall back to when the real one is unknown. Declared here for
// the same reason the DAO and the local data source each declare their own: it is a value this
// file has to recognise, not one it shares.
private const val PLACEHOLDER_ALBUM_NAME = "Album"

/**
 * The query cleaning the Spotify canvas search applies before it hits the network — featured-artist
 * markers, the conjunctions that join two artists, and the punctuation that survives them. Kept
 * character-for-character identical to that chain so both sources search for the same thing.
 */
private fun String.cleanForSearch(): String =
    this
        .replace(
            Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
            " ",
        ).replace(
            Regex("( và | & | и | e | und |, |和| dan)"),
            " ",
        ).replace("  ", " ")
        .replace(Regex("([()])"), "")
        .replace(".", " ")
        .replace("  ", " ")

/**
 * Lowercase, and reduce anything that is not a letter or a digit to a single space. Deliberately
 * built on [Char.isLetterOrDigit] rather than an `[^a-z0-9]` character class: that class treats
 * every accented letter as punctuation, which would grind "Hoàng Thùy Linh" down to "ho ng th y
 * linh" and make the comparison below meaningless for most of the languages this app serves.
 */
private fun String.normalizeForMatch(): String =
    lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/**
 * Comparison key. Falls back to the raw lowercased text when normalising leaves nothing behind,
 * which is exactly what happens to an album titled with a symbol: Ed Sheeran's "÷" and "=" contain
 * no letters or digits at all, reduce to an empty string, and would otherwise match nothing.
 */
private fun String.matchKey(): String = normalizeForMatch().ifEmpty { trim().lowercase() }

private fun String.matchesLoosely(other: String): Boolean {
    val a = matchKey()
    val b = other.matchKey()
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

/**
 * How closely an album name answers the name that was searched for — lower is better, null means
 * it is not that album at all.
 *
 * AM's search cannot be told to sort, and its own ranking puts reissues above the plain edition:
 * "Hybrid Theory (Deluxe Edition)" ranks above "Hybrid Theory", "1989 (Taylor's Version) [Deluxe]"
 * above "1989". Taking the first loose match therefore returns the artwork of a *different edition*
 * of the right album. Ranking by how much text the candidate adds picks the plain one back out.
 */
private fun matchScore(
    candidate: String,
    subject: String,
): Pair<Int, Int>? {
    val c = candidate.matchKey()
    val s = subject.matchKey()
    if (c.isEmpty() || s.isEmpty()) return null
    return when {
        c == s -> 0 to 0
        // The subject plus a suffix — "<album> (Deluxe Edition)". The shorter the addition, the
        // closer the edition is to the one being played.
        c.startsWith(s) -> 1 to (c.length - s.length)
        c.contains(s) -> 2 to (c.length - s.length)
        s.contains(c) -> 3 to (s.length - c.length)
        else -> null
    }
}

private data class ScoredMatch(
    val tier: Int,
    val extraLength: Int,
    val demoted: Int,
    val rank: Int,
)

/**
 * Rank by how closely a name answers [subject] and return the best, or null when nothing is that
 * thing at all.
 *
 * [demote] is consulted only AFTER name closeness, never before it — lower sorts first. Ordering
 * those the other way round is what made a track pick up the wrong recording: filtering to "has
 * artwork" first threw away the studio version and left the live one, which then won by default.
 */
private fun <T> List<T>.bestNameMatch(
    subject: String,
    demote: (T) -> Int = { 0 },
    nameOf: (T) -> String?,
): T? =
    mapIndexedNotNull { rank, item ->
        matchScore(nameOf(item).orEmpty(), subject)?.let { (tier, extra) ->
            ScoredMatch(tier, extra, demote(item), rank) to item
        }
    }.minWithOrNull(
        compareBy(
            { it.first.tier },
            { it.first.extraLength },
            { it.first.demoted },
            { it.first.rank },
        ),
    )?.second

/**
 * Pick the album by name, and only when the name matches EXACTLY.
 *
 * AM's album index is not complete enough to trust a near-miss. Searching "Mắt Nhắm Mắt Mở
 * HIEUTHUHAI" returns exactly one album — *Mắt Nhắm Mắt Mở (Studio Live Session) - EP* — and not
 * the album itself, so the best available match was a different release of a different recording,
 * accepted purely because nothing else was on offer. The track search below finds the real album
 * for the same track, so anything short of an exact name here defers to it rather than guessing.
 */
private fun List<AMAlbumResource>.pickAlbumMatch(
    subject: String,
    artist: String,
): AMAlbumResource? =
    filter { it.hasAnimatedArtwork() }
        .filter { it.attributes?.artistName.agreesWith(artist) }
        .bestNameMatch(subject) { it.attributes?.name }
        ?.takeIf { matchScore(it.attributes?.name.orEmpty(), subject)?.first == EXACT_NAME_TIER }

/**
 * Pick the album for a track reached by title.
 *
 * The album must be the one the matched TRACK belongs to, which is why the search asks for songs
 * and carries [AMSongWithAlbum.album] along. Reading an album straight out of the response instead
 * — "the first one that has artwork" — attaches an unrelated release to the track: for an artist
 * with exactly one animated release, every song of theirs ends up wearing it. Observed with
 * HIEUTHUHAI, where a single carrying artwork was served for unrelated tracks.
 *
 * The track title is scored too, for the same reason the album name is on the other path: AM's
 * order is a ranking, not an answer.
 */
private fun List<AMSongWithAlbum>.pickSongMatch(
    title: String,
    artist: String,
    durationSeconds: Int,
    albumHint: String?,
): AMSongWithAlbum? =
    filter { it.artistName.agreesWith(artist) }
        .bestNameMatch(
            subject = title,
            // Three tiebreaks, weakest last. The name alone cannot separate a studio take from a
            // live one when both are titled the same, so RUNNING TIME leads here — it is the same
            // signal the Spotify canvas search picks its track with, and it separates them
            // decisively: "Không Thể Say" is 228s, its Live Band cut 188s and its festival cut
            // 327s. The album name comes next, deciding between the several albums one recording
            // legitimately appears on. Whether artwork exists at all only breaks what is left.
            demote = { candidate ->
                val durationMisses = !candidate.matchesDuration(durationSeconds)
                val albumName = candidate.album.attributes?.name.orEmpty()
                val hintMisses = albumHint != null && matchScore(albumName, albumHint)?.first != EXACT_NAME_TIER
                val hasNoArtwork = !candidate.album.hasAnimatedArtwork()
                (if (durationMisses) 4 else 0) + (if (hintMisses) 2 else 0) + (if (hasNoArtwork) 1 else 0)
            },
        ) { it.songName }
        ?.takeIf { it.album.hasAnimatedArtwork() }

/**
 * Whether this is the same recording, by length. Unknown on either side counts as agreement — an
 * absent number is not evidence of a different take.
 */
private fun AMSongWithAlbum.matchesDuration(durationSeconds: Int): Boolean {
    val theirs = durationInMillis ?: return true
    if (durationSeconds <= 0) return true
    return abs(theirs / 1000L - durationSeconds) <= AM_DURATION_TOLERANCE_SECONDS
}

/**
 * Whether a credited name is the artist being played. Blank means the row carried no artist at all,
 * which cannot disagree with anything.
 */
private fun String?.agreesWith(artist: String): Boolean =
    artist.isBlank() || (this != null && this.matchesLoosely(artist))

private fun AMAlbumResource.hasAnimatedArtwork(): Boolean =
    attributes
        ?.editorialVideo
        ?.preferredRendition()
        ?.video != null

/**
 * Every album that has animated artwork ships all four renditions, but they are read defensively
 * anyway. The tall cut is preferred because the canvas slot it lands in is a full-height backdrop,
 * which is what the Spotify canvas it replaces was shaped for.
 */
private fun AMEditorialVideo.preferredRendition(): AMMotionVideo? =
    listOfNotNull(motionDetailTall, motionTallVideo3x4, motionDetailSquare, motionSquareVideo1x1)
        .firstOrNull { it.video != null }