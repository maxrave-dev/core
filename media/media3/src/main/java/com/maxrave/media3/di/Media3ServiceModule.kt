package com.maxrave.media3.di

import android.app.Activity
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.maxrave.common.Config.CANVAS_CACHE
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.cast.CastHandoffManager
import com.maxrave.media3.waveform.WaveformReader
import com.maxrave.media3.cache.TrackCachePreloader
import com.maxrave.media3.cast.CastStreamResolver
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter
import com.maxrave.media3.extension.isFullyCached
import com.maxrave.media3.repository.CacheRepositoryImpl
import com.maxrave.media3.service.SimpleMediaService
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.service.download.DownloadUtils
import com.maxrave.media3.service.mediasourcefactory.LiveStreamAwareLoadErrorHandlingPolicy
import com.maxrave.media3.service.mediasourcefactory.LiveStreamDetectedException
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import com.maxrave.media3.service.mediasourcefactory.liveStreamDataSourceFactory
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.simpmusic.cast.initCast
import org.simpmusic.cast.wrapWithCastPlayer
import java.net.Proxy
import kotlin.time.Duration.Companion.seconds

/**
 * Required repository first initialization
 */
@UnstableApi
private val mediaServiceModule =
    module {
        // Service
        // CoroutineScope for service
        single<CoroutineScope>(
            qualifier = named(SERVICE_SCOPE),
        ) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        // Cache
        single<DatabaseProvider> {
            provideDatabaseProvider(androidContext())
        }
        // Player Cache
        single<SimpleCache>(qualifier = named(PLAYER_CACHE)) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "exoplayer",
                cacheSize = runBlocking { get<DataStoreManager>().maxSongCacheSize.first() },
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // Download Cache
        single<SimpleCache>(qualifier = named(DOWNLOAD_CACHE)) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "download",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // Spotify Canvas Cache
        single<SimpleCache>(qualifier = named(CANVAS_CACHE)) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "spotifyCanvas",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // DownloadUtils
        single<DownloadHandler> {
            DownloadUtils(
                context = androidContext(),
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                dataStoreManager = get(),
                databaseProvider = get(),
                streamRepository = get(),
                songRepository = get(),
            )
        }

        // AudioAttributes
        single<AudioAttributes> {
            provideAudioAttributes()
        }

        single { StreamResolveCoordinator() }
        single<MergingMediaSourceFactory> {
            provideMergingMediaSource(
                androidContext(),
                get(named(DOWNLOAD_CACHE)),
                get(named(PLAYER_CACHE)),
                get(),
                get(named(SERVICE_SCOPE)),
                get(),
                get(),
            )
        }

        single<DefaultRenderersFactory> {
            provideRendererFactory(androidContext())
        }

        // Player exposed for MediaSession + UI (video rendering via PlayerView/PlayerSurface).
        // The adapter's ForwardingPlayer (delegating to the active ExoPlayer) is wrapped with
        // Cast support in the full build; org.simpmusic.cast no-ops back to the same instance
        // in the FOSS build, so this stays the stable session-level player either way.
        single<Player>(qualifier = named(MAIN_PLAYER)) {
            val adapter = get<MediaPlayerInterface>() as CrossfadeExoPlayerAdapter
            initCast(androidContext())
            wrapWithCastPlayer(androidContext(), adapter.forwardingPlayer)
        }

        // CoilBitmapLoader
        single<CoilBitmapLoader> {
            provideCoilBitmapLoader(androidContext(), get(named(SERVICE_SCOPE)))
        }

        // Reads a track's audio through the player's own resolving, cache-backed chain, so the
        // waveform costs no second download of what the player streams anyway.
        single<WaveformReader> {
            WaveformReader(
                context = androidContext(),
                dataSourceFactory =
                    provideResolvingDataSourceFactory(
                        androidContext(),
                        provideCacheDataSource(get(named(DOWNLOAD_CACHE)), get(named(PLAYER_CACHE)), androidContext(), provideProxy(get())),
                        get(named(DOWNLOAD_CACHE)),
                        get(named(PLAYER_CACHE)),
                        get(),
                        get(),
                        get(named(SERVICE_SCOPE)),
                        get(),
                    ),
                streamRepository = get(),
            )
        }

        // Prevention for mid-song stalls: pulls the whole track into the player cache through the
        // same resolving chain the player streams through, with a watchdog that keeps retrying
        // until the track is fully local.
        single<TrackCachePreloader>(createdAtStart = true) {
            TrackCachePreloader(
                context = androidContext(),
                dataSourceFactory =
                    provideResolvingDataSourceFactory(
                        androidContext(),
                        provideCacheDataSource(get(named(DOWNLOAD_CACHE)), get(named(PLAYER_CACHE)), androidContext(), provideProxy(get())),
                        get(named(DOWNLOAD_CACHE)),
                        get(named(PLAYER_CACHE)),
                        get(),
                        get(),
                        get(named(SERVICE_SCOPE)),
                        get(),
                    ),
                playerCache = get(named(PLAYER_CACHE)),
                streamRepository = get(),
                dataStoreManager = get(),
                coroutineScope = get(named(SERVICE_SCOPE)),
            )
        }

        single<MediaPlayerInterface> {
            CrossfadeExoPlayerAdapter(
                context = androidContext(),
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                mediaSourceFactory = get(),
                audioAttributes = get(),
                streamRepository = get(),
                waveformReader = get(),
                trackCachePreloader = get(),
                streamResolveCoordinator = get(),
            )
        }

        // Local ↔ Cast receiver handoff. No-op when wrapWithCastPlayer returned the plain
        // ForwardingPlayer (FOSS build or no GMS on the device).
        single<CastHandoffManager>(createdAtStart = true) {
            CastHandoffManager(
                adapter = get<MediaPlayerInterface>() as CrossfadeExoPlayerAdapter,
                sessionPlayer = get(qualifier = named(MAIN_PLAYER)),
                resolver = CastStreamResolver(get(), get()),
                coroutineScope = get(qualifier = named(SERVICE_SCOPE)),
            ).also { it.start() }
        }

        // MediaSession Callback for main player
        single<MediaLibrarySession.Callback> {
            SimpleMediaSessionCallback(
                androidApplication(),
                get<CoroutineScope>(named(SERVICE_SCOPE)),
                get<MediaPlayerHandler>(),
                get<SearchRepository>(),
                get<SongRepository>(),
                get<LocalPlaylistRepository>(),
                get<PlaylistRepository>(),
                get<HomeRepository>(),
                get<StreamRepository>(),
            )
        }

        single<CacheRepository> {
            CacheRepositoryImpl(
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                canvasCache = get(named(CANVAS_CACHE)),
            )
        }
    }

/**
 * How long before its stored expiry a stream URL stops being trusted. googlevideo URLs
 * live ~6h; the margin keeps playback from betting on one in its last minutes.
 */
private const val STORED_URL_EXPIRY_MARGIN_SECONDS = 600L

/** Longest a single stream resolution may take before the load fails into the retry path. */
private const val STREAM_RESOLVE_TIMEOUT_MS = 20_000L

@UnstableApi
private fun provideResolvingDataSourceFactory(
    context: Context,
    cacheDataSourceFactory: CacheDataSource.Factory,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    dataStoreManager: DataStoreManager,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
    streamResolveCoordinator: StreamResolveCoordinator,
): DataSource.Factory {
    val chunkLength = 10 * 512 * 1024L
    return ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val videoId = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        // A live broadcast reached a progressive source that was built before it was known to be
        // live; failing it is what gets the track rebuilt as HLS — see LiveStreamDetectedException.
        if (LiveStreamRegistry.isLive(videoId)) throw LiveStreamDetectedException(videoId)
        Logger.d("Stream", mediaId)
        Logger.d("Stream", mediaId.startsWith(MERGING_DATA_TYPE.VIDEO).toString())
        if (downloadCache.isFullyCached(mediaId, dataSpec.position)) {
            // Only on the first chunk: the subrange below makes the resolver run once per
            // chunk, and updateFormat is a fire-and-forget youTube.player() call with no
            // in-flight dedup, so leaving it ungated would fan out one request per 5 MiB.
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    streamRepository.updateFormat(
                        if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                            mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                        } else {
                            mediaId
                        },
                    )
                }
            }
            Logger.d("Stream", "Downloaded $mediaId")
            return@Factory dataSpec.subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        if (playerCache.isFullyCached(mediaId, dataSpec.position)) {
            // See the note above: once per track, not once per chunk.
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    streamRepository.updateFormat(
                        if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                            mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                        } else {
                            mediaId
                        },
                    )
                }
            }
            Logger.d("Stream", "Cached $mediaId")
            // Every byte is on disk right now, so CacheDataSource can serve this chunk
            // without ever reaching upstream, and the bare media id is safe as the URI.
            //
            // It is only safe for ONE chunk though. A bare id has no scheme, so
            // DefaultDataSource routes it to FileDataSource, not to OkHttp — the failure
            // is FileNotFoundException (ERROR_CODE_IO_FILE_NOT_FOUND), which Media3 lists
            // as non-retriable and which CrossfadeExoPlayerAdapter does not recover from
            // either. Meanwhile CacheDataSource.read() walks span to span inside a single
            // open() without consulting this resolver again, so an unbounded DataSpec
            // would stake the whole remaining track on a snapshot taken here: one LRU
            // eviction (precache and downloads write to playerCache concurrently) or one
            // "clear cache" tap mid-song and playback dies with no way back.
            // Capping to chunkLength forces a re-check at every chunk boundary, so a
            // cache that shrinks under us falls back to resolving a real URL.
            return@Factory dataSpec.subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        var dataSpecReturn: DataSpec = dataSpec
        var resolved = false
        runCancellableStreamResolve(streamResolveCoordinator, videoId, mediaId) {
            withTimeoutOrNull(STREAM_RESOLVE_TIMEOUT_MS) {
                if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                    val id = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    streamRepository.getNewFormat(id).lastOrNull()?.let {
                        val videoUrl = it.videoUrl
                        // Stored URLs are trusted until shortly before they expire instead of
                        // being probed with a HEAD on every chunk: they only die early when
                        // the client IP changes (the media service drops all stored formats
                        // on a network switch for exactly that), and a URL that still turns
                        // out dead fails the load with a 403, which the player adapter
                        // already answers by dropping the stored format and reloading with
                        // a fresh resolve.
                        if (videoUrl != null && it.expiredTime > now().plusSeconds(STORED_URL_EXPIRY_MARGIN_SECONDS)) {
                            Logger.d("Stream", videoUrl)
                            Logger.d("Stream", "Video from format")
                            dataSpecReturn = dataSpec.withUri(videoUrl.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                            resolved = true
                            return@withTimeoutOrNull
                        }
                    }
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            id,
                            isDownloading = false,
                            isVideo = true,
                        ).lastOrNull()
                        ?.let {
                            Logger.d("Stream", it)
                            Logger.d("Stream", "Video")
                            dataSpecReturn = dataSpec.withUri(it.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                            resolved = true
                        }
                } else {
                    streamRepository.getNewFormat(mediaId).lastOrNull()?.let {
                        val audioUrl = it.audioUrl
                        // See the video branch above: no HEAD probe on stored URLs — trust
                        // the stored expiry (with margin), invalidate on network switches,
                        // and let the adapter's 403 handling re-resolve a dead URL.
                        if (audioUrl != null && it.expiredTime > now().plusSeconds(STORED_URL_EXPIRY_MARGIN_SECONDS)) {
                            Logger.d("Stream", audioUrl)
                            Logger.d("Stream", "Audio from format")
                            dataSpecReturn = dataSpec.withUri(audioUrl.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                            resolved = true
                            return@withTimeoutOrNull
                        }
                    }
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            mediaId,
                            isDownloading = false,
                            isVideo = false,
                        ).lastOrNull()
                        ?.let {
                            Logger.d("Stream", it)
                            Logger.d("Stream", "Audio")
                            dataSpecReturn = dataSpec.withUri(it.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                            resolved = true
                        }
                }
            }
        }
        // getStream above is where a live broadcast is first recognised. Its HLS playlist URL must
        // not be read as a file, so this source fails and the track is rebuilt as HLS.
        if (LiveStreamRegistry.isLive(videoId)) throw LiveStreamDetectedException(videoId)
        if (!resolved) {
            val refusal = streamRepository.consumeLastUnrecoverableError(videoId)
            if (refusal != null) {
                Logger.e("Stream", "Unrecoverable stream failure for $mediaId: $refusal")
                throw com.maxrave.media3.service.mediasourcefactory.UnrecoverableStreamException(refusal)
            }
            Logger.e("Stream", "Failed to resolve stream URL for $mediaId")
            throw java.io.IOException("Failed to resolve stream URL for $mediaId")
        }
        return@Factory dataSpecReturn
    }
}

@UnstableApi
private fun provideExtractorFactory(): ExtractorsFactory =
    ExtractorsFactory {
        arrayOf(
            FlacExtractor(
                FlacExtractor.FLAG_DISABLE_ID3_METADATA,
            ),
            MatroskaExtractor(
                DefaultSubtitleParserFactory(),
            ),
            FragmentedMp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
            Mp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
        )
    }

private inline fun <T> runCancellableStreamResolve(
    coordinator: StreamResolveCoordinator,
    key: String,
    label: String,
    crossinline block: suspend CoroutineScope.() -> T,
): T =
    try {
        runBlocking(Dispatchers.IO) {
            val resolveJob = coroutineContext[Job]
            val unregister =
                if (resolveJob != null) {
                    coordinator.register(key, resolveJob)
                } else {
                    {}
                }
            try {
                block()
            } finally {
                unregister()
            }
        }
    } catch (e: CancellationException) {
        Logger.d("Stream", "Cancelled stream resolve for $label")
        throw java.io.IOException("Stream resolve cancelled for $label", e)
    }

@UnstableApi
private fun provideMediaSourceFactory(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    dataStoreManager: DataStoreManager,
    coroutineScope: CoroutineScope,
    streamResolveCoordinator: StreamResolveCoordinator,
): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(
        provideResolvingDataSourceFactory(
            context,
            provideCacheDataSource(
                downloadCache,
                playerCache,
                context,
                provideProxy(dataStoreManager),
            ),
            downloadCache,
            playerCache,
            dataStoreManager,
            streamRepository,
            coroutineScope,
            streamResolveCoordinator,
        ),
        provideExtractorFactory(),
    ).setLoadErrorHandlingPolicy(LiveStreamAwareLoadErrorHandlingPolicy())

/** The proxy the user configured, for every HTTP source the player opens. */
private fun provideProxy(dataStoreManager: DataStoreManager): Proxy? =
    dataStoreManager.getJVMProxy()?.let {
        Proxy(
            when (it.type) {
                DataStoreManager.ProxyType.PROXY_TYPE_HTTP -> Proxy.Type.HTTP
                DataStoreManager.ProxyType.PROXY_TYPE_SOCKS -> Proxy.Type.SOCKS
            },
            java.net.InetSocketAddress(it.host, it.port),
        )
    }

/**
 * HLS for live broadcasts. The playlist placeholder is resolved through the same getStream every
 * other track uses, which answers with the broadcast's HLS playlist once it knows the video is live.
 */
@UnstableApi
private fun provideLiveStreamMediaSourceFactory(
    context: Context,
    streamRepository: StreamRepository,
    dataStoreManager: DataStoreManager,
    streamResolveCoordinator: StreamResolveCoordinator,
): HlsMediaSource.Factory =
    HlsMediaSource.Factory(
        liveStreamDataSourceFactory(provideHttpDataSourceFactory(context, provideProxy(dataStoreManager))) { videoId ->
            runCancellableStreamResolve(streamResolveCoordinator, videoId, videoId) {
                streamRepository
                    .getStream(
                        dataStoreManager,
                        videoId,
                        isDownloading = false,
                        isVideo = false,
                    ).lastOrNull()
            }
        },
    )

@OptIn(UnstableApi::class)
private fun provideMergingMediaSource(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
    dataStoreManager: DataStoreManager,
    streamResolveCoordinator: StreamResolveCoordinator,
): MergingMediaSourceFactory =
    MergingMediaSourceFactory(
        provideMediaSourceFactory(
            context,
            downloadCache,
            playerCache,
            streamRepository,
            dataStoreManager,
            coroutineScope,
            streamResolveCoordinator,
        ),
        provideLiveStreamMediaSourceFactory(
            context,
            streamRepository,
            dataStoreManager,
            streamResolveCoordinator,
        ),
        dataStoreManager,
        context,
        downloadCache,
    )

@UnstableApi
private fun provideRendererFactory(context: Context): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        init {
            // A platform decoder that fails to init or decode gets a second chance on
            // another decoder instead of failing the track outright.
            setEnableDecoderFallback(true)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray(),
                        SilenceSkippingAudioProcessor(
                            2_000_000,
                            (20_000 / 2_000_000).toFloat(),
                            2_000_000,
                            0,
                            256,
                        ),
                        SonicAudioProcessor(),
                    ),
                ).build()
    }

@UnstableApi
private fun provideCacheDataSource(
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    context: Context,
    proxy: Proxy? = null,
): CacheDataSource.Factory =
    CacheDataSource
        .Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(provideHttpDataSourceFactory(context, proxy)),
        ).setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

/** The network underneath every player source: OkHttp behind the user's proxy, with header logging. */
@UnstableApi
private fun provideHttpDataSourceFactory(
    context: Context,
    proxy: Proxy?,
): DataSource.Factory =
    DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory(
            OkHttpClient
                .Builder()
                .connectTimeout(30.seconds)
                .readTimeout(30.seconds)
                .proxy(
                    proxy,
                ).addInterceptor(
                    HttpLoggingInterceptor()
                        .apply {
                            level = HttpLoggingInterceptor.Level.HEADERS
                        },
                ).build(),
        ),
    )

@UnstableApi
private fun provideLoadControl(): LoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(
            DEFAULT_MIN_BUFFER_MS * 4,
            DEFAULT_MAX_BUFFER_MS * 4,
            // bufferForPlaybackMs=
            0,
            // bufferForPlaybackAfterRebufferMs=
            0,
        ).build()

@UnstableApi
private fun provideAudioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

@UnstableApi
private fun provideDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)

@UnstableApi
private fun provideSimpleCache(
    context: Context,
    cacheName: String,
    cacheSize: Int = -1,
    databaseProvider: DatabaseProvider,
) = SimpleCache(
    context.filesDir.resolve(cacheName),
    when (cacheSize) {
        -1 -> NoOpCacheEvictor()
        else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
    },
    databaseProvider,
)

@UnstableApi
private fun provideCoilBitmapLoader(
    context: Context,
    coroutineScope: CoroutineScope,
): CoilBitmapLoader = CoilBitmapLoader(context, coroutineScope)

@OptIn(UnstableApi::class)
fun loadMediaService() {
    loadKoinModules(mediaServiceModule)
}

@OptIn(UnstableApi::class)
fun startService(
    context: Context,
    serviceConnection: ServiceConnection,
) {
    val intent = Intent(context, SimpleMediaService::class.java)
    try {
        context.startService(intent)
    } catch (e: IllegalStateException) {
        // BackgroundServiceStartNotAllowedException (Android 12+)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e2: Exception) {
            // The fallback can be refused too (Android 12+ background-start limits):
            // an exception born inside the catch had nobody left to catch it and
            // crashed the caller.
            Logger.e("Service", "startForegroundService fallback failed", e2)
        }
    }
    context.bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    Logger.d("Service", "Service started")
}

@OptIn(UnstableApi::class)
fun stopService(context: Context) {
    context.stopService(Intent(context, SimpleMediaService::class.java))
}

@OptIn(UnstableApi::class)
fun setServiceActivitySession(
    context: Context,
    cls: Class<out Activity>,
    musicService: IBinder?,
) {
    (musicService as? SimpleMediaService.MusicBinder)?.setActivitySession(context, cls)
}