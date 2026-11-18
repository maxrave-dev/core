package com.maxrave.data.repository

import com.maxrave.common.ITAG
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.QUALITY
import com.maxrave.common.VIDEO_QUALITY
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toSponsorSkipSegments
import com.maxrave.data.mapping.toTrack
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.isBefore
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MediaType
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class StreamRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : StreamRepository {
    // For work a resolve starts but playback must not wait for (Tidal metadata).
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override suspend fun insertNewFormat(newFormat: NewFormatEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertNewFormat(newFormat)
        }

    override suspend fun warmUpDecoder() =
        withContext(Dispatchers.IO) {
            youTube.warmUpDecoder()
        }

    override fun getNewFormat(videoId: String): Flow<NewFormatEntity?> = flow { emit(localDataSource.getNewFormat(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun getFormatFlow(videoId: String) = localDataSource.getNewFormatAsFlow(videoId)

    override fun getExtractSource(videoId: String): String? = youTube.getExtractSource(videoId)

    override suspend fun updateFormat(videoId: String) {
        localDataSource.getNewFormat(videoId)?.let { oldFormat ->
            Logger.d("Stream", "oldFormatExpired: ${oldFormat.expiredTime}")
            Logger.d("Stream", "now: ${now()}")
            Logger.d("Stream", "isExpired: ${oldFormat.expiredTime.isBefore(now())}")
            if (oldFormat.expiredTime.isBefore(now())) {
                youTube
                    .player(videoId)
                    .onSuccess { triple ->
                        val response = triple.second
                        localDataSource.updateNewFormat(
                            oldFormat.copy(
                                expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                                playbackTrackingVideostatsPlaybackUrl =
                                    response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingAtrUrl =
                                    response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingVideostatsWatchtimeUrl =
                                    response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                cpn = triple.first,
                            ),
                        )
                        Logger.w("UpdateFormat", "Updated format for $videoId")
                    }.onFailure { throwable ->
                        Logger.e("UpdateFormat", "Error: ${throwable.message}")
                    }
            }
        }
    }

    override fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean,
    ): Flow<String?> =
        flow {
            val itag =
                if (isDownloading) {
                    QUALITY.itagOf(dataStoreManager.downloadQuality.first())
                } else {
                    QUALITY.itagOf(dataStoreManager.quality.first())
                }
            val videoItag =
                if (!muxed) {
                    VIDEO_QUALITY.itags.getOrNull(
                        VIDEO_QUALITY.items.indexOf(
                            if (isDownloading) {
                                dataStoreManager.videoDownloadQuality.first()
                            } else {
                                dataStoreManager.videoQuality.first()
                            },
                        ),
                    )
                        ?: ITAG.VIDEO_360P
                } else {
                    ITAG.MUXED_360P
                }
            youTube
                .player(
                    videoId,
                    noLogIn = muxed,
                    preferredAudioLanguage = dataStoreManager.preferredAudioLanguage.first(),
                    // The fast path deciphers only the formats the chains below can pick, so
                    // it is asked for with the very itags those chains will use — and never
                    // for a muxed resolve, whose manifest formats only an extraction carries.
                    // Fast path DISABLED (2026-11-18): on its first device build every fresh
                    // resolve ended in a dead player, so resolves run the full extraction
                    // until the fast attempt is diagnosed. The plumbing stays; re-enable by
                    // restoring the two lines below to `if (muxed) null else itag/videoItag`.
                    fastAudioItag = null,
                    fastVideoItag = null,
                    fastIsVideo = isVideo,
                )
                .onSuccess { data ->
                    val response = data.second
                    val isLive = response.videoDetails?.isLive == true
                    LiveStreamRegistry.recordLiveStatus(videoId, isLive)
                    // The song row follows, so the LIVE badge and the download gate know too —
                    // including when a broadcast has ended and its recording downloads normally.
                    // Where a URL is emitted below, that write waits until after the emit:
                    // nothing the player starts from waits on it, and this flow's collectors
                    // read the row only once the flow has completed.
                    if (isLive && isDownloading) {
                        localDataSource.updateSongLiveStatus(videoId, isLive)
                        // Nothing to save: handing an endless HLS playlist to a downloader stores
                        // the playlist text as if it were the song. Emitting nothing fails the
                        // download where the caller resolves its URL.
                        Logger.i("Stream", "$videoId: live stream, refusing download")
                        return@onSuccess
                    }
                    if (isLive) {
                        // A live broadcast plays from its HLS playlist alone, so none of the itag
                        // choice below applies. Nothing about it goes into the format table either:
                        // the playlist URL is good for a few hours and the broadcast may be over by
                        // the next run, so every play resolves it afresh — and the players learn
                        // that it is live from LiveStreamRegistry, recorded just above.
                        val liveHlsUrl = response.streamingData?.hlsManifestUrl
                        Logger.i("Stream", "$videoId: live stream, HLS URL ${if (liveHlsUrl == null) "missing" else "found"}")
                        emit(liveHlsUrl)
                        localDataSource.updateSongLiveStatus(videoId, isLive)
                        return@onSuccess
                    }
                    Logger.d(
                        "Stream",
                        response.streamingData
                            ?.formats
                            ?.map { it.itag }
                            .toString() + " " +
                            response.streamingData
                                ?.adaptiveFormats
                                ?.map { it.itag }
                                .toString(),
                    )
                    val formatList = mutableListOf<PlayerResponse.StreamingData.Format>()
                    formatList.addAll(
                        response.streamingData?.formats?.filter { it.url.isNullOrEmpty().not() } ?: emptyList(),
                    )
                    formatList.addAll(
                        response.streamingData?.adaptiveFormats?.filter { it.url.isNullOrEmpty().not() }
                            ?: emptyList(),
                    )
                    val videoFormat =
                        formatList.find { it.itag == videoItag }
                            ?: formatList.find { it.itag == ITAG.VIDEO_720P }
                            ?: formatList.find { it.itag == ITAG.VIDEO_360P }
                            ?: formatList.find { !it.isAudio && it.url.isNullOrEmpty().not() }
                    val audioTwinItag = ITAG.highQualityTwinOf(itag)
                    // The last resort is the BEST audio the response actually carries,
                    // never an arbitrary entry. When the chosen itag and its high-quality
                    // twin are both absent — a free account asking for a Premium quality
                    // is served neither 774 nor 141 — the selection used to fall through
                    // to the last format in the list, in practice one of the lowest
                    // bitrate streams (~48-70 kbps), below even the Low setting.
                    val audioFormat =
                        formatList.find { it.itag == itag }
                            ?: audioTwinItag?.let { twin -> formatList.find { it.itag == twin } }
                            ?: formatList
                                .filter { it.isAudio && it.url.isNullOrEmpty().not() }
                                .maxByOrNull { it.bitrate }
                    var format =
                        if (isVideo) {
                            videoFormat
                        } else {
                            audioFormat
                        }
                    if (format == null) {
                        format = formatList.lastOrNull { it.url.isNullOrEmpty().not() }
                    }
                    if (muxed) {
                        format = formatList
                            .filter {
                                val url = it.url
                                url != null && youTube.isManifestUrl(url)
                            }.maxByOrNull { it.width ?: 0 } ?: formatList.find { it.itag == videoItag }
                    }
                    // One line per resolved stream and never the URL: a googlevideo URL carries the
                    // listener's IP address.
                    Logger.i(
                        "Stream",
                        "$videoId: ${data.third}, itag ${format?.itag} ${format?.mimeType?.substringBefore(';')}" +
                            (if (muxed) ", HLS" else "") +
                            ", expires in ${response.streamingData?.expiresInSeconds}s",
                    )
                    val durationSecond = response.videoDetails?.lengthSeconds?.toIntOrNull()
                    val newFormat =
                        NewFormatEntity(
                            videoId = if (VIDEO_QUALITY.itags.contains(format?.itag)) "${MERGING_DATA_TYPE.VIDEO}$videoId" else videoId,
                            itag = format?.itag ?: itag,
                            mimeType =
                                Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                    .find(
                                        format?.mimeType ?: "",
                                    )?.groupValues
                                    ?.getOrNull(1) ?: format?.mimeType ?: "",
                            codecs =
                                Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                    .find(
                                        format?.mimeType ?: "",
                                    )?.groupValues
                                    ?.getOrNull(2) ?: format?.mimeType ?: "",
                            bitrate = format?.bitrate,
                            sampleRate = format?.audioSampleRate,
                            contentLength = format?.contentLength,
                            loudnessDb =
                                response.playerConfig
                                    ?.audioConfig
                                    ?.loudnessDb
                                    ?.toFloat(),
                            lengthSeconds = response.videoDetails?.lengthSeconds?.toInt(),
                            playbackTrackingVideostatsPlaybackUrl =
                                response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            playbackTrackingAtrUrl =
                                response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            playbackTrackingVideostatsWatchtimeUrl =
                                response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            cpn = data.first,
                            expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                            audioUrl = if (muxed) response.streamingData?.hlsManifestUrl else format?.url,
                            videoUrl = if (muxed) response.streamingData?.hlsManifestUrl else videoFormat?.url,
                            bpm = null,
                            musicKey = null,
                            keyScale = null,
                        )
                    val streamUrl =
                        if (muxed) {
                            response.streamingData?.hlsManifestUrl
                        } else {
                            val chosenUrl = format?.url
                            val chosenLength = format?.contentLength ?: 10000000
                            when {
                                chosenUrl == null -> null
                                youTube.isManifestUrl(chosenUrl) ->
                                    if (data.first != null) chosenUrl.plus("&cpn=${data.first}") else chosenUrl
                                data.first != null ->
                                    chosenUrl.plus("&cpn=${data.first}&range=0-$chosenLength")
                                else -> chosenUrl.plus("&range=0-$chosenLength")
                            }
                        }
                    // The URL leaves before the database writes: the player starts from it
                    // alone, while the live-status write and the format row only matter to
                    // later readers — and every collector of this flow takes its last value
                    // and reads the stored row only after the flow completes, so both writes
                    // still land before any of them looks. The emitted value never carried
                    // anything from the database: the URL, the cpn and the length all come
                    // from the response above.
                    emit(streamUrl)
                    localDataSource.updateSongLiveStatus(videoId, isLive)
                    insertNewFormat(newFormat)
                    // AutoMix metadata from the Tidal official API (BPM/key) is fetched
                    // only after the format is stored, in the background: it used to be
                    // awaited inline here, so every fresh resolve paid a Tidal search —
                    // plus an OAuth fetch on the first song of a run — before the stream
                    // URL could be emitted and the player could start, for data nothing
                    // in playback itself needs.
                    if (!isVideo && durationSecond != null && data.third == MediaType.Song) {
                        fetchTidalMetadataAsync(
                            videoId = videoId,
                            title = response.videoDetails?.title ?: "",
                            author = response.videoDetails?.author ?: "",
                            durationSecond = durationSecond,
                        )
                    }
                }.onFailure {
                    it.printStackTrace()
                    Logger.e("Stream", "Error: ${it.message}")
                    emit(null)
                }
        }.flowOn(Dispatchers.IO)

    override fun initPlayback(
        playback: String,
        atr: String,
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Pair<Int, Float>> =
        flow {
            youTube
                .initPlayback(playback, atr, watchTime, cpn, playlistId)
                .onSuccess { response ->
                    emit(response)
                }.onFailure {
                    Logger.e("InitPlayback", "Error: ${it.message}")
                    emit(Pair(0, 0f))
                }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTimeFull(
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            runCatching {
                youTube
                    .updateWatchTimeFull(watchTime, cpn, playlistId)
                    .onSuccess { response ->
                        emit(response)
                    }.onFailure {
                        it.printStackTrace()
                        emit(0)
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTime(
        playbackTrackingVideostatsWatchtimeUrl: String,
        watchTimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            runCatching {
                youTube
                    .updateWatchTime(
                        playbackTrackingVideostatsWatchtimeUrl,
                        watchTimeList,
                        cpn,
                        playlistId,
                    ).onSuccess { response ->
                        emit(response)
                    }.onFailure {
                        it.printStackTrace()
                        emit(0)
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>> =
        flow {
            youTube
                .getSkipSegments(videoId)
                .onSuccess { data ->
                    emit(Resource.Success(data.map { it.toSponsorSkipSegments() }))
                }.onFailure {
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getFullMetadata(videoId: String): Flow<Resource<Track>> =
        flow {
            Logger.w("getFullMetadata", "videoId: $videoId")
            youTube
                .getFullMetadata(videoId)
                .onSuccess {
                    emit(Resource.Success(it.toTrack()))
                }.onFailure {
                    Logger.e("getFullMetadata", "Error: ${it.message}")
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun is403Url(url: String) = flow { emit(youTube.is403Url(url)) }.flowOn(Dispatchers.IO)

    override suspend fun invalidateFormat(videoId: String) {
        withContext(Dispatchers.IO) {
            localDataSource.getNewFormat(videoId)?.let { format ->
                Logger.d("Stream", "Invalidating cached format for $videoId")
                localDataSource.updateNewFormat(
                    format.copy(expiredTime = now().plusSeconds(-1)),
                )
            }
        }
    }

    override suspend fun invalidateAllFormats() {
        withContext(Dispatchers.IO) {
            val deleted = localDataSource.deleteAllNewFormats()
            Logger.d("Stream", "Invalidated all stored formats ($deleted rows)")
        }
    }

    /**
     * The Tidal BPM/key lookup for AutoMix, run in the background after a resolve:
     * searches Tidal and patches the stored format row once the answer arrives. A miss
     * or a failure just leaves the fields empty, exactly like a failed inline search did.
     */
    private fun fetchTidalMetadataAsync(
        videoId: String,
        title: String,
        author: String,
        durationSecond: Int,
    ) {
        backgroundScope.launch {
            val q =
                "$title $author"
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
            Logger.d("Stream", "Search Tidal metadata for: $q")
            youTube
                .searchTidalMetadata(q, durationSecond)
                .onSuccess { metadata ->
                    Logger.d("Stream", "Tidal metadata: $metadata")
                    localDataSource.getNewFormat(videoId)?.let { stored ->
                        localDataSource.updateNewFormat(
                            stored.copy(
                                bpm = metadata.bpm,
                                musicKey = metadata.musicKey,
                                keyScale = metadata.keyScale,
                            ),
                        )
                    }
                }.onFailure {
                    Logger.e("Stream", "Tidal metadata error: ${it.message}", it)
                }
        }
    }
}