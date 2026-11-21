package com.maxrave.data.mediaservice

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.maxrave.common.ASC
import com.maxrave.common.CUSTOM_ORDER
import com.maxrave.common.Config.ALBUM_CLICK
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.common.Config.RADIO_CLICK
import com.maxrave.common.Config.RECOVER_TRACK_QUEUE
import com.maxrave.common.Config.SHARE
import com.maxrave.common.Config.SONG_CLICK
import com.maxrave.common.Config.VIDEO_CLICK
import com.maxrave.common.DESC
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.common.LOCAL_PLAYLIST_ID_SAVED_QUEUE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.SPONSOR_BLOCK_MIN_SEGMENT_SECONDS
import com.maxrave.common.SPONSOR_BLOCK_SKIP_MARGIN_MS
import com.maxrave.common.SponsorBlockType
import com.maxrave.common.TITLE
import com.maxrave.data.db.Converters
import com.maxrave.data.lastfm.LastfmScrobbler
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.DelayEffect
import com.maxrave.domain.data.player.EightDDirection
import com.maxrave.domain.data.player.EightDEffect
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.data.player.ReverbEffect
import com.maxrave.domain.data.player.ReverbPreset
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.extension.toSongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RadioQueueTrim
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.DiscordRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.isEndlessQueueId
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

private val TAG = "Media3ServiceHandlerImpl"

// Stream error recovery (see onPlayerError): how many times a failed item is re-prepared before
// the error is surfaced to the user. The delay between attempts grows exponentially up to a cap
// (see streamRecoveryDelayMs): a dropped connection can take tens of seconds to come back (the
// user toggling airplane mode, a tunnel, a network switch), and the first version's 3 attempts
// over ~12s ran out before the network returned in on-device testing, leaving the item paused
// for good. Six attempts span about a minute instead.
private const val MAX_STREAM_RECOVERY_ATTEMPTS = 6
private const val STREAM_RECOVERY_DELAY_MS = 2_000L
private const val MAX_STREAM_RECOVERY_DELAY_MS = 15_000L

// Attempt on which a track that is fully in the player cache gets that cached copy dropped:
// it never needed the network to play, so failing this many times in a row points at the
// cached bytes themselves rather than at the connection (see tryScheduleStreamRecovery).
private const val CACHE_SELF_HEAL_ATTEMPT = 3

private fun streamRecoveryDelayMs(attempt: Int): Long =
    minOf(STREAM_RECOVERY_DELAY_MS * (1L shl (attempt - 1)), MAX_STREAM_RECOVERY_DELAY_MS)

internal class MediaServiceHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val discordRepository: DiscordRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler,
    MediaPlayerListener {
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context: Context = getKoin().get()
    override val player: MediaPlayerInterface = getKoin().get()

    // Same getKoin() pattern as player/context above: the quality-change sweep below
    // needs it, and threading it through createMediaServiceHandler would mean changing
    // that expect signature and all three actuals for one dependency.
    private val cacheRepository: CacheRepository = getKoin().get()

    @Volatile
    private var discordRPC: RichPresenceClient? = null

    /**
     * Built here rather than injected: it needs nothing this handler does not already hold, and
     * threading it through [createMediaServiceHandler] would mean changing that expect signature
     * and all three actuals for one dependency.
     */
    private val lastfmScrobbler = LastfmScrobbler(dataStoreManager)

    // Resume-on-reconnect state (see resumePlaybackAfterReconnect, by the stream recovery
    // below). Declared before init because init registers [networkCallback]; its methods only
    // run long after construction, when every property they touch exists. Both flags are
    // written from the connectivity thread and read from the player thread, hence volatile.
    @Volatile
    private var networkWasLost = false

    @Volatile
    private var resumeWhenNetworkReturns = false

    @Volatile
    private var lastConnectedNetwork: Network? = null

    @Volatile
    private var terminalError = false

    @Volatile
    private var lastObservedPlayPositionMs = 0L

    @Volatile
    private var userLoadedExplicitly = false

    @Volatile
    private var recoveryArmedPositionMs = -1L

    @Volatile
    private var recoveryArmedMediaId: String? = null

    private var appliedEnhancerSessionId = PlayerConstants.AUDIO_SESSION_ID_UNSET

    private var restoreQueueJob: Job? = null

    // PlayTiming (read only when debug logging is on): when the current track was asked
    // for, so the player callbacks below can report how long prepare and the wait for the
    // first sound took. Cleared once the playing line is out, so a resume does not
    // re-report the same track.
    @Volatile
    private var playTimingStartMs: Long = 0L

    @Volatile
    private var playTimingVideoId: String? = null

    @Volatile
    private var pendingInsertSongJob: Pair<String, Job>? = null

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkWasLost = true
                // player getters belong on Main — dispatch rather than reading on ConnectivityThread.
                coroutineScope.launch {
                    if (player.playWhenReady &&
                        player.currentMediaItem != null &&
                        player.playbackState != PlayerConstants.STATE_ENDED &&
                        !terminalError
                    ) {
                        resumeWhenNetworkReturns = true
                    }
                }
            }

            override fun onAvailable(network: Network) {
                val switchedNetwork = lastConnectedNetwork != null && lastConnectedNetwork != network
                lastConnectedNetwork = network
                if (!networkWasLost && !switchedNetwork) return
                networkWasLost = false
                coroutineScope.launch {
                    val shouldResume = resumeWhenNetworkReturns
                    resumeWhenNetworkReturns = false
                    val mediaIdAtReconnect = player.currentMediaItem?.mediaId
                    streamRepository.invalidateAllFormats()
                    _networkRestoredEvents.tryEmit(Unit)
                    if (!shouldResume || terminalError) return@launch
                    if (!resumePlaybackAfterReconnect()) {
                        delay(2_000)
                        if (player.currentMediaItem?.mediaId == mediaIdAtReconnect &&
                            player.playWhenReady &&
                            !terminalError
                        ) {
                            resumePlaybackAfterReconnect()
                        }
                    }
                }
            }
        }

    private val _networkRestoredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val networkRestoredEvents: Flow<Unit> = _networkRestoredEvents.asSharedFlow()

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(player.currentMediaItem)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData =
        MutableStateFlow<QueueData>(
            QueueData(
                queueState = QueueData.StateSource.STATE_CREATED,
                data = QueueData.Data(),
            ),
        )
    override val queueData = _queueData.asStateFlow()

    private val _controlState =
        MutableStateFlow<ControlState>(
            ControlState(
                isPlaying = player.isPlaying,
                isShuffle = player.shuffleModeEnabled,
                repeatState =
                    when (player.repeatMode) {
                        PlayerConstants.REPEAT_MODE_ONE -> {
                            RepeatState.One
                        }

                        PlayerConstants.REPEAT_MODE_ALL -> {
                            RepeatState.All
                        }

                        PlayerConstants.REPEAT_MODE_OFF -> {
                            RepeatState.None
                        }

                        else -> {
                            RepeatState.None
                        }
                    },
                isLiked = false,
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
                isCrossfading = false,
                volume = 1f,
            ),
        )

    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<NowPlayingTrackState>(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState(false, 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    // SponsorBlock skip segments
    private val _skipSegments: MutableStateFlow<List<SponsorSkipSegments>?> = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    // SponsorBlock's ticked categories, kept current by a collector in init; null while SponsorBlock is
    // off or not loaded yet. The per-tick skip check reads this instead of asking DataStore on every progress tick (one
    // read for the switch plus one per category). Kept identical to JvmMediaPlayerHandlerImpl.
    private val sponsorBlockCategories = MutableStateFlow<Set<String>?>(null)

    private val _format: MutableStateFlow<NewFormatEntity?> = MutableStateFlow<NewFormatEntity?>(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex: MutableStateFlow<Int> = MutableStateFlow(player.currentMediaItemIndex)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _castState = MutableStateFlow(GenericCastState.NOT_CASTING)
    override val castState: StateFlow<GenericCastState> = _castState.asStateFlow()

    // List of Specific variables

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var secondLoudnessEnhancer: LoudnessEnhancer? = null

    @Volatile
    private var skipSilent = false

    @Volatile
    private var normalizeVolume = false

    @Volatile
    private var endlessQueueEnabled = false

    private val pendingQualityEvictVideoIds = ConcurrentHashMap.newKeySet<String>()

    /** Written by the equalizer-type collector; read on the player's thread by [shouldOpenOrCloseEqualizerIntent]. */
    @Volatile
    private var systemEqualizerSelected = false

    private var watchTimeList: ArrayList<Float> = arrayListOf()

    private var volumeNormalizationJob: Job? = null

    private var sleepTimerJob: Job? = null

    /** How long the sleep timer spends ramping the volume down before it stops playback. */
    private val sleepFadeDurationMs = 5_000L

    /** Steps in that ramp — 50, matching the crossfade ramp, so 100ms per step at 5 seconds. */
    private val sleepFadeSteps = 50

    /**
     * Silence held after the ramp before playback is actually stopped.
     *
     * The gain is applied in the audio pipeline, ahead of the sink, so it reaches the speaker one
     * buffer late — AudioTrack alone holds 250–750 ms. Pausing the moment the ramp hits zero would
     * therefore cut while the audible level is still around -12 dBFS, which is exactly the abrupt
     * stop this feature exists to remove. Sized above the largest buffer media3 will allocate.
     */
    private val sleepFadeTailMs = 800L

    private var getSkipSegmentsJob: Job? = null

    private var getFormatJob: Job? = null

    private var progressJob: Job? = null

    private var bufferedJob: Job? = null

    private var updateNotificationJob: Job? = null

    private var toggleLikeJob: Job? = null

    private var loadJob: Job? = null
    private var recoverQueueSaveJob: Job? = null

    // Track ids of the queue last written by mayBeSaveRecentSong, so repeated saves (every
    // onTrimMemory, every transition) skip rewriting an unchanged queue.
    private var lastPersistedQueueIds: List<String>? = null
    private val restoreQueueMutex = Mutex()

    // Set by restoreQueueAndPlay before it contends for restoreQueueMutex, so a startup
    // restore already holding the lock can honour the play request itself instead of
    // finishing paused. One-way for the life of the process: mayBeRestoreQueue only ever
    // runs once, from init.
    @Volatile
    private var playRequestedDuringRestore = false

    // Set by restoreSavedQueue once the saved track is in the player - i.e. once a play request
    // can be honoured without the rest of the queue. Written after the addMediaItem that reads
    // [playRequestedDuringRestore] and re-read immediately afterwards, so the restore and a
    // resumption request racing it can never both miss the other's write: whichever runs second
    // sees the first, and exactly one of them starts playback.
    @Volatile
    private var restoreHasPrimedPlayer = false

    private var songEntityJob: Job? = null

    private var jobWatchtime: Job? = null

    private var getDataOfNowPlayingTrackStateJob: Job? = null

    // Discord Rich Presence is pushed event-driven (song change, resume, seek, speed) instead of on
    // the 100ms progress tick: the gateway only tolerates a few presence updates per minute, so the
    // old 10Hz spam kept disconnecting the socket and froze presence on the previous song (#2236).
    // Ordering uses a monotonic sequence (rpcEventSeq), NOT wall-clock time, since the wall clock can
    // step backward (NTP/manual) and would otherwise freeze presence. Snapshots are written with a
    // compare-and-keep-newest update (never overwriting a newer `seq` with an older one, regardless of
    // suspend-resume interleaving) and conflated through a single sender (rpcSenderJob), which drops
    // any snapshot older than the last one it handled and drops snapshots while playback isn't active
    // (per controlState.isPlaying) so a stale in-flight send can't resurrect presence after pause/close.
    private val rpcEventSeq = AtomicLong(0L)

    private data class RpcSnapshot(
        val song: SongEntity,
        val progressMs: Long,
        val durationMs: Long,
        val speed: Float,
        val seq: Long,
    )

    private val rpcSnapshotFlow = MutableStateFlow<RpcSnapshot?>(null)

    @Volatile
    private var rpcSenderJob: Job? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private fun fromListIntToString(list: List<Int>?): String? = list?.let { json.encodeToString(list) }

    private fun fromStringToListInt(value: String?): List<Int>? =
        try {
            value?.let { json.decodeFromString<List<Int>>(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    //
    init {
        player.addListener(this)
        // Resume-on-reconnect (see networkCallback): registered for the handler's lifetime
        // and removed again in release(). A failure here only loses the instant resume; the
        // timed loader retries and stream recovery keep working as before.
        try {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Logger.e(TAG, "Could not register network callback: ${e.message}")
        }
        // Pay the stream decipher's one-time setup (current player id, remote table,
        // player script, QuickJS solver) now, in the background, instead of inside the
        // first track's resolution. Silent on purpose: a failed warm-up only means the
        // first play pays the cost itself, exactly as before.
        backgroundScope.launch {
            try {
                streamRepository.warmUpDecoder()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d(TAG, "Decoder warm-up failed: ${e.message}")
            }
        }
        // A new audio-quality choice has to reach songs that already played: their bytes
        // sit in the player cache and their resolved stream — picked under the old
        // quality — sits in the format table, and the resolver serves both before it
        // ever re-resolves. Dropping both on a change makes the next play of anything
        // fetch the newly chosen quality instead. The first emission is the stored
        // value, not a change, so it only seeds the comparison.
        backgroundScope.launch {
            var lastQuality: String? = null
            dataStoreManager.quality.distinctUntilChanged().collect { quality ->
                val previous = lastQuality
                lastQuality = quality
                if (previous != null && previous != quality) {
                    val activeBaseId =
                        withContext(Dispatchers.Main) {
                            player.currentMediaItem?.mediaId
                        }?.removePrefix(MERGING_DATA_TYPE.VIDEO)?.takeIf { it.isNotBlank() }
                    Logger.i(
                        TAG,
                        "Audio quality changed ($previous -> $quality): " +
                            "clearing player cache and stored formats" +
                            (activeBaseId?.let { " (deferring active track $it)" } ?: ""),
                    )
                    if (activeBaseId != null) {
                        pendingQualityEvictVideoIds.add(activeBaseId)
                        val protectedKeys = setOf(activeBaseId, "${MERGING_DATA_TYPE.VIDEO}$activeBaseId")
                        streamRepository.invalidateAllFormatsExcept(protectedKeys)
                        cacheRepository.clearCacheExcept(PLAYER_CACHE, protectedKeys)
                    } else {
                        streamRepository.invalidateAllFormats()
                        cacheRepository.clearCache(PLAYER_CACHE)
                    }
                }
            }
        }
        progressJob = Job()
        bufferedJob = Job()
        sleepTimerJob = Job()
        volumeNormalizationJob = Job()
        updateNotificationJob = Job()
        toggleLikeJob = Job()
        loadJob = Job()
        songEntityJob = Job()
        getSkipSegmentsJob = Job()
        getFormatJob = Job()
        jobWatchtime = Job()
        backgroundScope.launch {
            dataStoreManager.skipSilent.distinctUntilChanged().collect { value ->
                val enabled = value == TRUE
                if (skipSilent != enabled) {
                    skipSilent = enabled
                    withContext(Dispatchers.Main) {
                        player.skipSilenceEnabled = enabled
                    }
                }
            }
        }
        backgroundScope.launch {
            dataStoreManager.normalizeVolume.distinctUntilChanged().collect { value ->
                val enabled = value == TRUE
                if (normalizeVolume != enabled) {
                    normalizeVolume = enabled
                    withContext(Dispatchers.Main) {
                        mayBeNormalizeVolume()
                    }
                }
            }
        }
        backgroundScope.launch {
            dataStoreManager.endlessQueue.distinctUntilChanged().collect { value ->
                endlessQueueEnabled = value == TRUE
            }
        }
        // Collected rather than read once like the settings around it: the equalizer is adjusted
        // while music is playing, and a curve that only takes effect after a restart is useless
        // for judging what you just changed.
        backgroundScope.launch {
            // While the system equalizer is selected the built-in curve goes flat, the same as its own switch being off.
            combine(
                dataStoreManager.equalizerType,
                dataStoreManager.equalizerEnabled,
                dataStoreManager.equalizerBands,
                dataStoreManager.equalizerPreamp,
            ) { type, enabled, bands, preamp -> Triple(enabled == TRUE && type != DataStoreManager.EQUALIZER_TYPE_SYSTEM, bands, preamp) }
                .distinctUntilChanged()
                .collect { (enabled, bands, preamp) ->
                    // Switched off sends a flat curve rather than skipping the call: the filter
                    // has to actually come out of the audio chain, and the stored bands are left
                    // alone so switching back on returns to the user's own shape.
                    player.setEqualizer(
                        bandsDb =
                            if (enabled) bands.split(",").mapNotNull { it.trim().toFloatOrNull() } else emptyList(),
                        preampDb = if (enabled) preamp else 0f,
                    )
                }
        }
        // Switching while music plays must hand over at once, or both equalizers run together: the
        // adapter only asks to open or close when the player's own state changes, not the setting.
        backgroundScope.launch {
            dataStoreManager.equalizerType
                .map { it == DataStoreManager.EQUALIZER_TYPE_SYSTEM }
                .distinctUntilChanged()
                .collect { system ->
                    if (system == systemEqualizerSelected) return@collect
                    systemEqualizerSelected = system
                    // Main, like the adapter's own calls: both read the player's audio session.
                    withContext(Dispatchers.Main) {
                        if (!system) {
                            sendCloseEqualizerIntent()
                        } else if (player.isPlaying) {
                            sendOpenEqualizerIntent()
                        }
                    }
                }
        }
        // A collector of its own rather than more legs on the equalizer's: `combine` takes at most
        // five flows with a lambda, and these seven fold into two halves that each stand alone.
        backgroundScope.launch {
            val delayEffects =
                combine(
                    dataStoreManager.delayEnabled,
                    dataStoreManager.delayTimeMs,
                    dataStoreManager.delayFeedback,
                    dataStoreManager.delayMix,
                ) { enabled, timeMs, feedback, mix ->
                    // Off is null rather than a zero mix: the filter has to actually come out of
                    // the audio chain, and the stored values are left alone so switching back on
                    // returns to the user's own settings.
                    if (enabled == TRUE) DelayEffect(timeMs = timeMs, feedback = feedback, mix = mix) else null
                }
            val reverbEffects =
                combine(
                    dataStoreManager.reverbEnabled,
                    dataStoreManager.reverbPreset,
                    dataStoreManager.reverbMix,
                ) { enabled, presetName, mix ->
                    if (enabled == TRUE) {
                        ReverbEffect(
                            // A room written by a newer build is a name this one has never heard
                            // of; falling back beats letting valueOf take the whole collector down.
                            preset = runCatching { ReverbPreset.valueOf(presetName) }.getOrDefault(ReverbPreset.HALL),
                            mix = mix,
                        )
                    } else {
                        null
                    }
                }
            combine(delayEffects, reverbEffects) { echo, room -> AudioEffects(delay = echo, reverb = room) }
                .distinctUntilChanged()
                .collect { effects -> player.setAudioEffects(effects) }
        }
        backgroundScope.launch {
            combine(
                dataStoreManager.eightDEnabled,
                dataStoreManager.eightDDirection,
                dataStoreManager.eightDPeriodSeconds,
            ) { enabled, direction, seconds ->
                if (enabled == TRUE) {
                    EightDEffect(EightDDirection.fromStored(direction), seconds).normalized()
                } else {
                    null
                }
            }.distinctUntilChanged().collect { player.setEightD(it) }
        }
        _nowPlaying.value = player.currentMediaItem
        // Load initial DataStore playback preferences off the caller thread instead of 5
        // sequential runBlocking reads during DI/service startup.
        backgroundScope.launch {
            val initialSkipSilent = dataStoreManager.skipSilent.first() == TRUE
            val initialNormalize = dataStoreManager.normalizeVolume.first() == TRUE
            val savePlaybackState = dataStoreManager.saveStateOfPlayback.first() == TRUE
            val shuffleKey = if (savePlaybackState) dataStoreManager.shuffleKey.first() else null
            val repeatKey = if (savePlaybackState) dataStoreManager.repeatKey.first() else null
            withContext(Dispatchers.Main) {
                skipSilent = initialSkipSilent
                player.skipSilenceEnabled = initialSkipSilent
                normalizeVolume = initialNormalize
                if (savePlaybackState) {
                    Logger.d(TAG, "SaveStateOfPlayback TRUE")
                    Logger.d(TAG, "Shuffle: $shuffleKey")
                    Logger.d(TAG, "Repeat: $repeatKey")
                    val restoredShuffle = shuffleKey == TRUE
                    val restoredRepeatMode =
                        when (repeatKey) {
                            DataStoreManager.REPEAT_ONE -> PlayerConstants.REPEAT_MODE_ONE
                            DataStoreManager.REPEAT_ALL -> PlayerConstants.REPEAT_MODE_ALL
                            else -> PlayerConstants.REPEAT_MODE_OFF
                        }
                    player.shuffleModeEnabled = restoredShuffle
                    player.repeatMode = restoredRepeatMode
                    // Ensure controlState is in sync after restore, regardless of listener callbacks
                    _controlState.value =
                        _controlState.value.copy(
                            isShuffle = restoredShuffle,
                            repeatState =
                                when (restoredRepeatMode) {
                                    PlayerConstants.REPEAT_MODE_ONE -> RepeatState.One
                                    PlayerConstants.REPEAT_MODE_ALL -> RepeatState.All
                                    else -> RepeatState.None
                                },
                        )
                }
            }
        }
        mayBeRestoreQueue()
        coroutineScope.launch {
            val controlStateJob =
                launch {
                    controlState.collectLatest {
                        updateNotification()
                    }
                }
            val sponsorBlockCategoriesJob =
                launch {
                    combine(
                        SponsorBlockType.toList().map { type ->
                            dataStoreManager.getString(type.value).map { if (it == TRUE) type.value else null }
                        },
                    ) { ticked -> ticked.filterNotNull().toSet() }
                        .combine(dataStoreManager.sponsorBlockEnabled) { ticked, enabled -> ticked.takeIf { enabled == TRUE } }
                        .collect { sponsorBlockCategories.value = it }
                }
            val skipSegmentsJob =
                launch {
                    simpleMediaState
                        .filter { it is SimpleMediaState.Progress }
                        .map {
                            val current = (it as SimpleMediaState.Progress).progress
                            val duration = player.duration
                            if (duration > 0L) {
                                (current.toFloat() / player.duration) * 100
                            } else {
                                -1f
                            }
                        }.filter { it >= 0f }
                        .distinctUntilChanged()
                        .collect { current ->
                            val categories = sponsorBlockCategories.value
                            val skipSegments = skipSegments.value
                            if (categories != null && player.duration > 0L && skipSegments != null) {
                                val trackDurationMs = player.duration
                                for (skip in skipSegments) {
                                    if (categories.contains(skip.category)) {
                                        if (skip.videoDuration <= 0f ||
                                            skip.segment.size < 2 ||
                                            skip.segment[1] - skip.segment[0] < SPONSOR_BLOCK_MIN_SEGMENT_SECONDS
                                        ) {
                                            continue
                                        }
                                        val firstPart = ((skip.segment[0] / skip.videoDuration) * 100).toFloat()
                                        val secondPart =
                                            ((skip.segment[1] / skip.videoDuration) * 100).toFloat()
                                        if (current in firstPart..secondPart) {
                                            Logger.w(TAG, "Seek to $secondPart")
                                            Logger.d(TAG, "Seek to Cr: $current, First: $firstPart, Second: $secondPart")
                                            val rawTargetMs = (secondPart * trackDurationMs).toLong() / 100 + SPONSOR_BLOCK_SKIP_MARGIN_MS
                                            val targetMs = if (secondPart >= 99f) rawTargetMs else rawTargetMs.coerceAtMost(trackDurationMs)
                                            skipSegment(targetMs)
                                            showToast(ToastType.SponsorBlockSkip(skip.category))
                                            break
                                        }
                                    }
                                }
                            }
                        }
                }
            val playbackJob =
                launch {
                    format.collectLatest { formatTemp ->
                        if (dataStoreManager.sendBackToGoogle.first() == TRUE) {
                            if (formatTemp != null) {
                                println("format in viewModel: $formatTemp")
                                Logger.d(TAG, "Collect format ${formatTemp.videoId}")
                                Logger.w(TAG, "Format expire at ${formatTemp.expiredTime}")
                                Logger.i(TAG, "AtrUrl ${formatTemp.playbackTrackingAtrUrl}")
                                initPlayback(
                                    formatTemp.playbackTrackingVideostatsPlaybackUrl,
                                    formatTemp.playbackTrackingAtrUrl,
                                    formatTemp.playbackTrackingVideostatsWatchtimeUrl,
                                    formatTemp.cpn,
                                )
                            }
                        }
                    }
                }
            val playbackSpeedPitchJob =
                launch {
                    combine(dataStoreManager.playbackSpeed, dataStoreManager.pitch) { speed, pitch ->
                        Pair(speed, pitch)
                    }.collectLatest { pair ->
                        Logger.w(TAG, "Playback speed: ${pair.first}, Pitch: ${pair.second}")
                        player.playbackParameters =
                            GenericPlaybackParameters(
                                pair.first,
                                2f.pow(pair.second.toFloat() / 12),
                            )
                        Logger.w(TAG, "Playback current speed: ${player.playbackParameters.speed}, Pitch: ${player.playbackParameters.pitch}")
                        // A speed change shifts the RPC start/end timestamps (Discord renders the bar
                        // from timestamps client-side), so refresh presence while actively playing.
                        if (player.isPlaying) {
                            nowPlayingState.value.songEntity?.let { updateDiscordRpc(it) }
                        }
                    }
                }
            val discordRPCEnabledJob =
                launch {
                    // Run Rich Presence only when it's enabled AND the user is logged in. Building a
                    // client with a blank token makes the gateway loop connect→identify-fail→
                    // reconnect forever and drains the battery (issue #2157). Combining both flows also
                    // tears the RPC down the moment the user logs out.
                    combine(
                        dataStoreManager.richPresenceEnabled,
                        discordRepository.loggedIn,
                    ) { enabled, loggedIn ->
                        enabled == TRUE && loggedIn
                    }.distinctUntilChanged().collectLatest { shouldRun ->
                        if (shouldRun) {
                            // Both branches below are independently idempotent: a toggle on→off→on
                            // race must not skip (re)creating whichever of discordRPC/rpcSenderJob
                            // dropped out (#Fix 6).
                            if (discordRPC == null) {
                                discordRPC = createRichPresenceClient(discordRepository)
                            }
                            if (rpcSenderJob?.isActive != true) {
                                // One sender for the whole RPC lifetime: collectLatest cancels an
                                // in-flight send (socket spin-wait or artwork HTTP) the moment a newer
                                // snapshot arrives, giving both ordering and latest-wins.
                                rpcSenderJob =
                                    coroutineScope.launch(Dispatchers.IO) {
                                        var lastHandledSeq = 0L
                                        rpcSnapshotFlow.filterNotNull().collectLatest { snap ->
                                            if (snap.seq < lastHandledSeq) return@collectLatest
                                            lastHandledSeq = snap.seq
                                            // Drop it if playback stopped meanwhile — e.g. a seek's
                                            // updateDiscordRpc() suspends at playbackSpeed.first() and
                                            // its snapshot lands here after onIsPlayingChanged(false)
                                            // already closed the RPC (Fix 1). controlState.value is a
                                            // safe field read from Dispatchers.IO, unlike player.isPlaying.
                                            if (!controlState.value.isPlaying) return@collectLatest
                                            discordRPC
                                                ?.updateSong(snap.progressMs, snap.durationMs, snap.speed, snap.song)
                                                ?.onFailure { if (it !is CancellationException) Logger.e(TAG, "Discord RPC update failed: ${it.message}") }
                                        }
                                    }
                                nowPlayingState.value.songEntity?.let { song ->
                                    backgroundScope.launch {
                                        updateDiscordRpc(song)
                                    }
                                }
                            }
                        } else {
                            // NonCancellable: this cleanup must run to completion even if a newer
                            // upstream emission cancels this collectLatest action mid-flight, otherwise
                            // the next `shouldRun` pass could see a half-torn-down state (Fix 6).
                            withContext(NonCancellable) {
                                rpcSenderJob?.cancel()
                                rpcSenderJob = null
                                if (discordRPC?.isRpcRunning() == true) {
                                    discordRPC?.closeRPC()
                                }
                                discordRPC = null
                                // Drop any retained snapshot so a relaunched sender (fresh
                                // lastHandledSeq = 0) can't replay a stale update to the freshly created
                                // socket on an off→on toggle (Fix B). rpcEventSeq itself is NOT reset —
                                // it must stay monotonic across toggles.
                                rpcSnapshotFlow.value = null
                            }
                        }
                    }
                }
            controlStateJob.join()
            sponsorBlockCategoriesJob.join()
            skipSegmentsJob.join()
            playbackJob.join()
            playbackSpeedPitchJob.join()
            discordRPCEnabledJob.join()
        }
    }

    private fun getDataOfNowPlayingState(mediaItem: GenericMediaItem) {
        val videoId =
            if (mediaItem.isVideo()) {
                mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
            } else {
                mediaItem.mediaId
            }
        val track =
            queueData.value.data.listTracks
                .find { it.videoId == videoId }
        // The previous track's Room collector must stop BEFORE the state moves on: left running
        // until the async lookup below finishes, it could re-publish the OLD songEntity next to
        // the NEW mediaItem (lyrics/canvas were then fetched for the wrong song).
        songEntityJob?.cancel()
        _nowPlayingState.update {
            it.copy(
                mediaItem = mediaItem,
                track = track,
                songEntity = null,
            )
        }
        _format.value = null
        _skipSegments.value = null
        getDataOfNowPlayingTrackStateJob?.cancel()
        getDataOfNowPlayingTrackStateJob =
            coroutineScope.launch {
                Logger.w(TAG, "getDataOfNowPlayingState: $videoId")
                pendingInsertSongJob?.takeIf { it.first == videoId }?.second?.join()
                songRepository.getSongById(videoId).cancellable().singleOrNull().let { songEntity ->
                    if (songEntity != null) {
                        _controlState.update { it.copy(isLiked = songEntity.liked) }
                        var thumbUrl =
                            track?.thumbnails?.lastOrNull()?.url
                                ?: songEntity.thumbnails
                                ?: "http://i.ytimg.com/vi/${songEntity.videoId}/maxresdefault.jpg"
                        Logger.d(TAG, "getDataOfNowPlayingState before: $thumbUrl")
                        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                        Logger.d(TAG, "getDataOfNowPlayingState: $thumbUrl")
                        if (songEntity.thumbnails != thumbUrl) {
                            songRepository.updateThumbnailsSongEntity(thumbUrl, songEntity.videoId).singleOrNull()?.let {
                                Logger.d(TAG, "getDataOfNowPlayingState: Updated thumbs $it")
                            }
                        }
                        // Rows written before the parsers carried YouTube's real MUSIC_VIDEO_TYPE_*
                        // hold an invented label ("Song", "video", a view count). They are corrected
                        // here as the user plays them rather than by a migration. normalize() drops
                        // anything that is not a real type, so an unknown never overwrites a known one.
                        MusicVideoType.normalize(track?.videoType)?.let { freshVideoType ->
                            if (songEntity.videoType != freshVideoType) {
                                songRepository.updateVideoTypeSongEntity(freshVideoType, songEntity.videoId).singleOrNull()?.let {
                                    Logger.d(TAG, "getDataOfNowPlayingState: Updated videoType $it")
                                }
                            }
                        }
                        songRepository.updateSongInLibrary(now(), songEntity.videoId).singleOrNull().let {
                            Logger.d(TAG, "getDataOfNowPlayingState: $it")
                        }
                        songRepository.updateListenCount(songEntity.videoId)
                        _nowPlayingState.update {
                            it.copy(
                                songEntity =
                                    songEntity.copy(
                                        thumbnails = thumbUrl,
                                    ),
                            )
                        }
                        updateDiscordRpc(songEntity)
                        // Launched separately: "now playing" is a network round trip, and this job
                        // still has the rest of the track state to publish.
                        coroutineScope.launch { lastfmScrobbler.onTrackStarted(songEntity) }
                    } else {
                        _controlState.update { it.copy(isLiked = false) }
                        var thumbUrl =
                            track?.thumbnails?.lastOrNull()?.url
                                ?: "http://i.ytimg.com/vi/${track?.videoId}/maxresdefault.jpg"
                        Logger.d(TAG, "getDataOfNowPlayingState before: $thumbUrl")
                        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                        val songEntity =
                            (track?.toSongEntity() ?: mediaItem.toSongEntity()).copy(
                                thumbnails = thumbUrl,
                            )
                        songRepository
                            .insertSong(
                                songEntity,
                            ).singleOrNull()
                            ?.let {
                                Logger.d(TAG, "getDataOfNowPlayingState: $it")
                            }
                        _nowPlayingState.update {
                            it.copy(
                                songEntity = songEntity,
                            )
                        }
                        updateDiscordRpc(songEntity)
                        // Launched separately: "now playing" is a network round trip, and this job
                        // still has the rest of the track state to publish.
                        coroutineScope.launch { lastfmScrobbler.onTrackStarted(songEntity) }
                    }
                }
                songEntityJob?.cancel()
                songEntityJob =
                    coroutineScope.launch {
                        songRepository.getSongAsFlow(videoId).cancellable().filterNotNull().collectLatest { songEntity ->
                            if (dataStoreManager.explicitContentEnabled.first() == FALSE && songEntity.isExplicit) {
                                showToast(ToastType.ExplicitContent)
                                if (player.hasNextMediaItem()) {
                                    player.seekToNext()
                                } else if (player.hasPreviousMediaItem()) {
                                    player.seekToPrevious()
                                } else {
                                    player.stop()
                                }
                                return@collectLatest
                            }
                            _nowPlayingState.update {
                                it.copy(
                                    songEntity = songEntity,
                                )
                            }
                            _controlState.update {
                                it.copy(
                                    isLiked = songEntity.liked,
                                )
                            }
                        }
                    }
                if (dataStoreManager.sponsorBlockEnabled.first() == TRUE) {
                    getSkipSegments(videoId)
                }
                if (dataStoreManager.sendBackToGoogle.first() == TRUE) {
                    getFormat(videoId)
                }
            }
    }

    private fun getSkipSegments(videoId: String) {
        getSkipSegmentsJob?.cancel()
        _skipSegments.value = null
        getSkipSegmentsJob =
            coroutineScope.launch {
                streamRepository.getSkipSegments(videoId).collect { response ->
                    when (response) {
                        is Resource.Success -> {
                            _skipSegments.value = response.data
                        }

                        is Resource.Error -> {
                            Logger.e(TAG, "getSkipSegments: ${response.message}")
                            _skipSegments.value = null
                        }
                    }
                }
            }
    }

    private fun getFormat(mediaId: String?) {
        getFormatJob?.cancel()
        getFormatJob =
            coroutineScope.launch {
                if (mediaId != null) {
                    streamRepository.getFormatFlow(mediaId).cancellable().collectLatest { f ->
                        Logger.d(TAG, "Get format for $mediaId: itag ${f?.itag}, expires ${f?.expiredTime}")
                        if (f != null) {
                            _format.emit(f)
                        } else {
                            _format.emit(null)
                        }
                    }
                }
            }
    }

    private fun initPlayback(
        playback: String?,
        atr: String?,
        watchTime: String?,
        cpn: String?,
    ) {
        jobWatchtime?.cancel()
        jobWatchtime =
            coroutineScope.launch {
                if (playback != null && atr != null && watchTime != null && cpn != null) {
                    watchTimeList = arrayListOf()
                    streamRepository
                        .initPlayback(playback, atr, watchTime, cpn, queueData.value.data.playlistId)
                        .collect {
                            if (it.first == 204) {
                                Logger.d("Check initPlayback", "Success")
                                watchTimeList.add(0f)
                                watchTimeList.add(5.54f)
                                watchTimeList.add(it.second)
                                updateWatchTime()
                            }
                        }
                }
            }
    }

    private suspend fun updateWatchTime() {
        simpleMediaState.collect { state ->
            if (state is SimpleMediaState.Progress) {
                val value = state.progress
                val durationMs = player.duration
                if (value > 0 && durationMs > 0L && watchTimeList.isNotEmpty()) {
                    val second = (value / 1000).toFloat()
                    if (second in watchTimeList.last()..watchTimeList.last() + 1.2f) {
                        val watchTimeUrl = _format.value?.playbackTrackingVideostatsWatchtimeUrl
                        val cpn = _format.value?.cpn
                        if (second + 20.23f < (durationMs / 1000).toFloat()) {
                            watchTimeList.add(second + 20.23f)
                            if (watchTimeUrl != null && cpn != null) {
                                streamRepository
                                    .updateWatchTime(
                                        watchTimeUrl,
                                        watchTimeList,
                                        cpn,
                                        queueData.value.data.playlistId,
                                    ).collect { response ->
                                        if (response == 204) {
                                            Logger.d("Check updateWatchTime", "Success")
                                        }
                                    }
                            }
                        } else {
                            watchTimeList.clear()
                            if (watchTimeUrl != null && cpn != null) {
                                streamRepository
                                    .updateWatchTimeFull(
                                        watchTimeUrl,
                                        cpn,
                                        queueData.value.data.playlistId,
                                    ).collect { response ->
                                        if (response == 204) {
                                            Logger.d("Check updateWatchTimeFull", "Success")
                                        }
                                    }
                            }
                        }
                        Logger.w("Check updateWatchTime", watchTimeList.toString())
                    }
                }
            }
        }
    }

    private fun updateNextPreviousTrackAvailability() {
        _controlState.value =
            _controlState.value.copy(
                isNextAvailable = player.hasNextMediaItem(),
                // On the first track of the queue Previous restarts it, so the button stays usable there,
                // except under Repeat All in a radio or a mix, where it would wrap round to the end of a
                // queue that keeps loading more.
                isPreviousAvailable =
                    (player.hasPreviousMediaItem() || currentOrderIndex() == 0) &&
                        !(
                            player.repeatMode == PlayerConstants.REPEAT_MODE_ALL &&
                                currentOrderIndex() == 0 &&
                                queueData.value.data.playlistId?.isEndlessQueueId() == true
                        ),
            )
    }

    private fun addMediaItemNotSet(
        mediaItem: GenericMediaItem,
        index: Int? = null,
    ) {
        index?.let {
            player.addMediaItem(it, mediaItem)
        } ?: player.addMediaItem(mediaItem)
        if (player.mediaItemCount == 1) {
            player.prepare()
            player.playWhenReady = true
        }
        updateNextPreviousTrackAvailability()
    }

    private fun moveMediaItem(
        fromIndex: Int,
        newIndex: Int,
    ) {
        player.moveMediaItem(fromIndex, newIndex)
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    private fun skipSegment(position: Long) {
        if (position in 0..player.duration) {
            player.seekTo(position)
        } else if (position > player.duration) {
            player.seekToNext()
        }
    }

    private fun sendOpenEqualizerIntent() {
        // No local audio session to expose to an equalizer while casting (or before one exists).
        if (_castState.value.isRemote || player.audioSessionId == PlayerConstants.AUDIO_SESSION_ID_UNSET) return
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun sendCloseEqualizerIntent() {
        if (_castState.value.isRemote || player.audioSessionId == PlayerConstants.AUDIO_SESSION_ID_UNSET) return
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                // Mandatory per AudioEffect's javadoc, and missing from the pre-2.0.0 code this was
                // restored from. AOSP's MusicFX returns early on a null package and closes sessions
                // by package, so without it every CLOSE was ignored — and switching from the system
                // equalizer back to the built-in one left both running until the track ended.
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            },
        )
    }

    @SuppressLint("PrivateResource")
    private fun updateNotification() {
        updateNotificationJob?.cancel()
        updateNotificationJob =
            coroutineScope.launch {
                var id = (player.currentMediaItem?.mediaId ?: "")
                if (id.contains("Video")) {
                    id = id.removePrefix("Video")
                }
                val liked =
                    songRepository
                        .getSongById(id)
                        .singleOrNull()
                        ?.liked ?: false
                Logger.w("Check liked", liked.toString())
                _controlState.value = _controlState.value.copy(isLiked = liked)
                onUpdateNotification.invoke(
                    listOf(
                        GenericCommandButton.Like(liked),
                        GenericCommandButton.Shuffle(isShuffled = _controlState.value.isShuffle),
                        GenericCommandButton.Repeat(repeatState = _controlState.value.repeatState),
                        GenericCommandButton.Radio,
                    ),
                )
            }
    }

    // Region: Override functions
    override fun startProgressUpdate() {
        // Cancel any previous loop first: onIsPlayingChanged(true) can fire repeatedly
        // (crossfade swap, rebuffer→ready, resume-on-focus-gain) and a leaked loop would
        // otherwise multiply both the UI updates and the periodic position writes (#2152).
        progressJob?.cancel()
        progressJob =
            coroutineScope.launch {
                // Persist the playback position to DataStore on this interval so a sudden
                // service/process kill while playing in the background (aggressive OEMs)
                // still restores the correct position instead of restarting the track from
                // the beginning (#2152). The position is otherwise only saved on pause /
                // track change / release, which misses uninterrupted background playback.
                val positionPersistIntervalMs = 5_000L
                // Named so the persist counter below cannot drift out of step with it, which is
                // what a second hardcoded copy of the number did.
                val tickIntervalMs = 50L
                var sinceLastPositionSaveMs = 0L
                while (true) {
                    delay(tickIntervalMs)
                    // A load in progress is not progress. While the player reports
                    // BUFFERING (a fresh track still loading, e.g. with no network), a
                    // Progress publish is read by the view model as "not loading"
                    // whenever the track's duration is already known from metadata —
                    // which it always is — so the bar froze at 0:00 over a track that
                    // had not loaded a single byte. Publish Buffering instead; the
                    // position persistence below is unaffected.
                    if (player.playbackState == PlayerConstants.STATE_BUFFERING) {
                        _simpleMediaState.value = SimpleMediaState.Buffering(player.currentPosition)
                    } else {
                        val pos = player.currentPosition
                        if (pos > lastObservedPlayPositionMs) {
                            lastObservedPlayPositionMs = pos
                        }
                        _simpleMediaState.value = SimpleMediaState.Progress(pos)
                    }
                    sinceLastPositionSaveMs += tickIntervalMs
                    if (sinceLastPositionSaveMs >= positionPersistIntervalMs) {
                        sinceLastPositionSaveMs = 0
                        mayBeSaveRecentPosition()
                        // Riding the existing 5s tick instead of adding one: the scrobble point is
                        // half the track or four minutes, so five seconds of granularity is plenty
                        // and the 100ms loop stays as cheap as it was.
                        lastfmScrobbler.onProgress(player.currentPosition)
                    }
                }
            }
    }

    override fun startBufferedUpdate() {
        // Same reason as startProgressUpdate above: this is reached once per track load and once
        // per stall, and stopBufferedUpdate only cancels the newest job — so every earlier loop
        // survives and keeps pushing Loading every 500 ms for the rest of the session.
        bufferedJob?.cancel()
        bufferedJob =
            coroutineScope.launch {
                while (true) {
                    delay(500)
                    _simpleMediaState.value =
                        SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
                    val current = nowPlayingState.value.songEntity
                    if (current?.durationSeconds == 0 && player.duration > 0L) {
                        _nowPlayingState.update {
                            it.copy(
                                songEntity =
                                    current.copy(
                                        durationSeconds = (player.duration / 1000).toInt(),
                                    ),
                            )
                        }
                    }
                }
            }
    }

    override fun stopProgressUpdate() {
        progressJob?.cancel()
        Logger.w(TAG, "stopProgressUpdate: ${progressJob?.isActive}")
    }

    override fun stopBufferedUpdate() {
        bufferedJob?.cancel()
        // Deliberately emits nothing: this runs when buffering *ends*, so publishing Loading here
        // said the opposite of what happened.
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            is PlayerEvent.UpdateVolume -> {}

            PlayerEvent.Backward -> {
                player.seekBack()
            }

            PlayerEvent.Forward -> {
                player.seekForward()
            }

            PlayerEvent.PlayPause -> {
                if (player.isPlaying) {
                    streamRecoveryJob?.cancel()
                    streamRecoveryJob = null
                    resumeWhenNetworkReturns = false
                    player.pause()
                    stopProgressUpdate()
                } else {
                    terminalError = false
                    player.play()
                    startProgressUpdate()
                }
            }

            PlayerEvent.Next -> {
                resetCrossfade()
                player.seekToNext()
            }

            PlayerEvent.Previous -> {
                resetCrossfade()
                player.seekToPrevious()
            }

            PlayerEvent.SkipToPrevious -> {
                resetCrossfade()
                player.seekToPreviousMediaItem()
            }

            PlayerEvent.Stop -> {
                streamRecoveryJob?.cancel()
                streamRecoveryJob = null
                resumeWhenNetworkReturns = false
                terminalError = false
                lastObservedPlayPositionMs = 0L
                stopProgressUpdate()
                player.stop()
                _nowPlayingState.value = NowPlayingTrackState.initial()
                flushPendingQualityEvictions(keepBaseId = null)
            }

            is PlayerEvent.UpdateProgress -> {
                val duration = player.duration
                // While a track is still loading its duration is TIME_UNSET, and
                // the percentage math on that sentinel overflows into a garbage
                // position that can land past the end of the track and skip to
                // the next one. Ignore the seek until the duration is real, and
                // clamp the target into the track once it is.
                if (duration > 0) {
                    player.seekTo(
                        (duration * playerEvent.newProgress / 100).toLong().coerceIn(0L, duration),
                    )
                }
            }

            PlayerEvent.Shuffle -> {
                if (player.shuffleModeEnabled) {
                    player.shuffleModeEnabled = false
                    _controlState.value = _controlState.value.copy(isShuffle = false)
                } else {
                    player.shuffleModeEnabled = true
                    _controlState.value = _controlState.value.copy(isShuffle = true)
                }
            }

            PlayerEvent.Repeat -> {
                when (player.repeatMode) {
                    PlayerConstants.REPEAT_MODE_OFF -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                    }

                    PlayerConstants.REPEAT_MODE_ONE -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_OFF
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.None)
                    }

                    PlayerConstants.REPEAT_MODE_ALL -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.One)
                    }

                    else -> {
                        when (controlState.first().repeatState) {
                            RepeatState.None -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                            }

                            RepeatState.One -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                            }

                            RepeatState.All -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.One)
                            }
                        }
                    }
                }
            }

            PlayerEvent.ToggleLike -> {
                toggleLike()
            }
        }
    }

    override fun toggleRadio() {
        userLoadedExplicitly = true
        restoreQueueJob?.cancel()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        terminalError = false
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                val currentSong = nowPlayingState.value.songEntity ?: return@launch
                Logger.d(TAG, "toggleRadio: ${currentSong.title}")
                songRepository
                    .getRadioFromEndpoint(
                        YouTubeWatchEndpoint(
                            videoId = currentSong.videoId,
                            playlistId = "RDAMVM${currentSong.videoId}",
                        ),
                    ).collectLatest { res ->
                        val data = res.data
                        when (res) {
                            is Resource.Success if (data != null && data.first.isNotEmpty()) -> {
                                val currentTrackInRadio =
                                    data.first.firstOrNull { it.videoId == currentSong.videoId }
                                        ?: nowPlayingState.value.track
                                        ?: currentSong.toTrack()
                                val remainingTracks =
                                    data.first.filter { it.videoId != currentSong.videoId }.toCollection(arrayListOf())
                                val alreadyPlayingCurrent =
                                    player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO) == currentSong.videoId &&
                                        player.mediaItemCount > 0
                                if (alreadyPlayingCurrent) {
                                    setQueueData(
                                        QueueData.Data(
                                            listTracks = arrayListOf(currentTrackInRadio),
                                            firstPlayedTrack = currentTrackInRadio,
                                            playlistId = "RDAMVM${currentSong.videoId}",
                                            playlistName = "\"${currentSong.title}\" Radio",
                                            playlistType = PlaylistType.RADIO,
                                            continuation = data.second,
                                        ),
                                    )
                                    val curIdx = player.currentMediaItemIndex
                                    for (i in (player.mediaItemCount - 1) downTo (curIdx + 1)) {
                                        player.removeMediaItem(i)
                                    }
                                    for (i in (curIdx - 1) downTo 0) {
                                        player.removeMediaItem(i)
                                    }
                                    if (remainingTracks.isNotEmpty()) {
                                        loadMoreCatalog(remainingTracks)
                                    } else {
                                        _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED) }
                                    }
                                } else {
                                    setQueueData(
                                        QueueData.Data(
                                            listTracks = data.first,
                                            firstPlayedTrack = data.first.first(),
                                            playlistId = "RDAMVM${currentSong.videoId}",
                                            playlistName = "\"${currentSong.title}\" Radio",
                                            playlistType = PlaylistType.RADIO,
                                            continuation = data.second,
                                        ),
                                    )
                                    clearMediaItems()
                                    currentSong.durationSeconds.let {
                                        songRepository.updateDurationSeconds(it, currentSong.videoId)
                                    }
                                    addMediaItem(currentSong.toGenericMediaItem(), playWhenReady = true)
                                    loadPlaylistOrAlbum(0)
                                }
                            }

                            else -> {
                                Logger.e(TAG, "toggleRadio: ${res.message}")
                            }
                        }
                    }
            }
    }

    override fun toggleLike() {
        Logger.w(TAG, "toggleLike: ${nowPlayingState.value.mediaItem.mediaId}")
        toggleLikeJob?.cancel()
        toggleLikeJob =
            coroutineScope.launch {
                var id = (player.currentMediaItem?.mediaId ?: "")
                if (id.contains("Video")) {
                    id = id.removePrefix("Video")
                }
                songRepository.updateLikeStatus(
                    id,
                    if (!(controlState.first().isLiked)) 1 else 0,
                )
                delay(200)
            }
    }

    override fun like(liked: Boolean) {
        _controlState.value = _controlState.value.copy(isLiked = liked)
    }

    override fun resetSongAndQueue() {
        userLoadedExplicitly = true
        restoreQueueJob?.cancel()
        loadJob?.cancel()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        getSkipSegmentsJob?.cancel()
        getDataOfNowPlayingTrackStateJob?.cancel()
        progressJob?.cancel()
        bufferedJob?.cancel()
        terminalError = false
        resumeWhenNetworkReturns = false
        lastObservedPlayPositionMs = 0L
        player.clearMediaItems()
        _queueData.value = QueueData()
        _nowPlayingState.value = NowPlayingTrackState.initial()
        _nowPlaying.value = null
        _controlState.update { it.copy(isPlaying = false, isCrossfading = false) }
    }

    override fun sleepStart(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob =
            coroutineScope.launch(Dispatchers.Main) {
                var stoppedPlayback = false
                try {
                    if (minutes == Int.MAX_VALUE) {
                        // "End of current song" mode: use sentinel -1 to indicate this special state
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = -1)
                        }
                        val fadeWindowMs = sleepFadeDurationMs + sleepFadeTailMs
                        while (isActive) {
                            val dur = player.duration
                            val pos = player.currentPosition
                            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
                            if (!player.isPlaying || dur <= 0L || pos < 0L) {
                                delay(250)
                                continue
                            }
                            val wallRemainingMs = ((dur - pos) / speed).toLong().coerceAtLeast(0L)
                            if (wallRemainingMs <= fadeWindowMs + 150L) {
                                val fadeMs = sleepFadeDurationMs.coerceAtMost(wallRemainingMs)
                                val tailMs = sleepFadeTailMs.coerceAtMost((wallRemainingMs - fadeMs).coerceAtLeast(0L))
                                val leadDelay = (wallRemainingMs - fadeMs - tailMs).coerceAtLeast(0L)
                                if (leadDelay > 0L) delay(leadDelay)
                                fadeOutForSleep(fadeMs)
                                if (tailMs > 0L) delay(tailMs)
                                break
                            }
                            delay(250)
                        }
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    } else {
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = minutes)
                        }
                        var count = minutes
                        while (count > 0) {
                            // The fade belongs inside the final minute, so shorten that wait by its length.
                            val isFinalMinute = count == 1
                            delay(
                                if (isFinalMinute) 60 * 1000L - sleepFadeDurationMs - sleepFadeTailMs else 60 * 1000L,
                            )
                            if (isFinalMinute) {
                                fadeOutForSleep(sleepFadeDurationMs)
                                delay(sleepFadeTailMs)
                            }
                            count--
                            _sleepTimerState.update {
                                it.copy(isDone = false, timeRemaining = count)
                            }
                        }
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    }
                } finally {
                    // Only the cancelled path clears the attenuation here — sleepStop(), or the
                    // scope going away mid-fade. When the timer runs to completion the adapter
                    // clears it instead, from inside the pause it queued.
                    if (!stoppedPlayback) player.sleepFadeFactor = 1f
                }
            }
    }

    /**
     * Ramps [player]'s sleep-fade attenuation down to silence over [durationMs].
     */
    private suspend fun fadeOutForSleep(durationMs: Long) {
        if (durationMs <= 0L) return
        val steps = sleepFadeSteps.toLong().coerceAtMost(durationMs).toInt()
        val delayPerStep = (durationMs / steps).coerceAtLeast(1L)
        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            player.sleepFadeFactor = cos(progress * PI / 2).toFloat()
            delay(delayPerStep)
        }
    }

    override fun sleepStop() {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(false, 0)
    }

    override fun removeMediaItem(position: Int) {
        // A position handed in from the queue screen can be stale: a radio trims its played history
        // off the front while that screen is open. Check it against the queue as it is now, and
        // apply the removal to the current list rather than to a copy taken before.
        if (position !in queueData.value.data.listTracks.indices) return
        // The queue shows the shuffled order while shuffle is on, the player removes by playlist
        // position; the same translation playMediaItemInMediaSource makes when a row is tapped.
        player.removeMediaItem(if (player.shuffleModeEnabled) player.getUnshuffledIndex(position) else position)
        _queueData.update {
            val list = it.data.listTracks.toMutableList()
            if (position !in list.indices) return@update it
            list.removeAt(position)
            it.copy(
                data =
                    it.data.copy(
                        listTracks = list,
                    ),
            )
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {
        player.playWhenReady = playWhenReady
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        for (mediaItem in mediaItemList) {
            addMediaItemNotSet(mediaItem)
        }
    }

    override fun playMediaItemInMediaSource(index: Int) {
        userLoadedExplicitly = true
        restoreQueueJob?.cancel()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        terminalError = false
        val i = if (player.shuffleModeEnabled) player.getUnshuffledIndex(index) else index
        player.playWhenReady = true
        player.seekTo(i, 0)
    }

    override fun currentSongIndex(): Int = player.currentMediaItemIndex

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
        // A drag in the queue is in the order the queue shows, which with shuffle on is the shuffled
        // one; see moveQueueItem. queueData follows through the timeline event either way.
        if (player.shuffleModeEnabled) player.moveShuffledItem(from, to) else moveMediaItem(from, to)
    }

    override fun resetCrossfade() {
        _controlState.update {
            it.copy(
                isCrossfading = false,
            )
        }
    }

    override fun shufflePlaylist(randomTrackIndex: Int) {
        val playlistId = _queueData.value.data.playlistId ?: return
        val firstPlayedTrack = _queueData.value.data.firstPlayedTrack ?: return
        userLoadedExplicitly = true
        restoreQueueJob?.cancel()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        terminalError = false
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                if (playlistId.startsWith(LOCAL_PLAYLIST_ID)) {
                    songRepository.insertSong(firstPlayedTrack.toSongEntity()).collect {
                        Logger.w(TAG, "Inserted song: ${firstPlayedTrack.title}")
                    }
                    clearMediaItems()
                    firstPlayedTrack.durationSeconds?.let {
                        songRepository.updateDurationSeconds(it, firstPlayedTrack.videoId)
                    }
                    addMediaItem(firstPlayedTrack.toGenericMediaItem(), playWhenReady = true)
                    val longId = playlistId.replace(LOCAL_PLAYLIST_ID, "").toLongOrNull() ?: return@launch
                    val localPlaylist = localPlaylistRepository.getLocalPlaylist(longId).lastOrNull()?.data
                    if (localPlaylist != null) {
                        Logger.w(TAG, "shufflePlaylist: Local playlist track size ${localPlaylist.tracks?.size}")
                        val trackCount = localPlaylist.tracks?.size ?: return@launch
                        val listPosition =
                            (0 until trackCount).toMutableList().apply {
                                if (randomTrackIndex in indices) {
                                    removeAt(randomTrackIndex)
                                }
                            }
                        if (listPosition.isEmpty()) {
                            _queueData.update {
                                it.copy(
                                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                                    data = it.data.copy(continuation = null),
                                )
                            }
                            return@launch
                        }
                        listPosition.shuffle()
                        _queueData.update {
                            it.copy(
                                data =
                                    it.data.copy(
                                        continuation = "SHUFFLE0_${fromListIntToString(listPosition)}",
                                    ),
                            )
                        }
                        loadMore()
                    }
                }
            }
    }

    override fun loadMore() {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        val playlistId = _queueData.value.data.playlistId ?: return
        Logger.w("Check loadMore", playlistId.toString())
        val continuation = _queueData.value.data.continuation
        Logger.w("Check loadMore", continuation.toString())
        if (continuation != null) {
            if (playlistId.startsWith(LOCAL_PLAYLIST_ID)) {
                coroutineScope.launch {
                    _queueData.update {
                        it.copy(
                            queueState = QueueData.StateSource.STATE_INITIALIZING,
                        )
                    }
                    try {
                        val longId = playlistId.replace(LOCAL_PLAYLIST_ID, "").toLongOrNull() ?: return@launch
                        Logger.w("Check loadMore", longId.toString())
                        if (continuation.startsWith("SHUFFLE")) {
                            val regex = Regex("(?<=SHUFFLE)\\d+(?=_)")
                            var offset = regex.find(continuation)?.value?.toIntOrNull() ?: return@launch
                            val posString = continuation.removePrefix("SHUFFLE${offset}_")
                            val listPosition = fromStringToListInt(posString) ?: return@launch
                            val from = 50 * offset
                            if (from >= listPosition.size) {
                                _queueData.update { it.copy(data = it.data.copy(continuation = null)) }
                                return@launch
                            }
                            val theLastLoad = 50 * (offset + 1) >= listPosition.size
                            localPlaylistRepository
                                .getPlaylistPairSongByListPosition(
                                    longId,
                                    listPosition.subList(from, if (theLastLoad) listPosition.size else 50 * (offset + 1)),
                                ).lastOrNull()
                                ?.let { pair ->
                                    Logger.w("Check loadMore response", pair.size.toString())
                                    val pairIds = pair.map { p -> p.songId }
                                    val indexById = pairIds.withIndex().associate { (i, id) -> id to i }
                                    songRepository
                                        .getSongsByListVideoId(pairIds)
                                        .lastOrNull()
                                        ?.sortedBy { indexById[it.videoId] ?: Int.MAX_VALUE }
                                        ?.let { songs ->
                                            if (songs.isNotEmpty()) {
                                                delay(300)
                                                loadMoreCatalog(songs.toArrayListTrack())
                                                offset++
                                                _queueData.update {
                                                    it.copy(
                                                        data =
                                                            it.data.copy(
                                                                continuation =
                                                                    if (!theLastLoad) {
                                                                        "SHUFFLE${offset}_$posString"
                                                                    } else {
                                                                        null
                                                                    },
                                                            ),
                                                    )
                                                }
                                            }
                                        }
                                }
                        } else if (
                            continuation.startsWith(ASC) ||
                            continuation.startsWith(DESC) ||
                            continuation.startsWith(CUSTOM_ORDER) ||
                            continuation.startsWith(TITLE)
                        ) {
                            val filter =
                                if (continuation.startsWith(ASC)) {
                                    FilterState.OlderFirst
                                } else if (continuation.startsWith(DESC)) {
                                    FilterState.NewerFirst
                                } else if (continuation.startsWith(CUSTOM_ORDER)) {
                                    FilterState.CustomOrder
                                } else {
                                    FilterState.Title
                                }
                            val converters = Converters()

                            when (filter) {
                                FilterState.NewerFirst, FilterState.OlderFirst -> {
                                    val localDateTime =
                                        try {
                                            val timestampString =
                                                if (filter == FilterState.OlderFirst) {
                                                    continuation.removePrefix(ASC)
                                                } else {
                                                    continuation.removePrefix(DESC)
                                                }
                                            val timestamp = timestampString.toLongOrNull() ?: return@launch
                                            converters.fromTimestamp(timestamp) ?: return@launch
                                        } catch (e: Exception) {
                                            Logger.e(TAG, "loadMore: Failed to parse timestamp", e)
                                            return@launch
                                        }
                                    localPlaylistRepository
                                        .getPlaylistPairSongByTime(
                                            longId,
                                            filter,
                                            localDateTime,
                                        ).lastOrNull()
                                        ?.let { pair ->
                                            Logger.w("Check loadMore response", pair.size.toString())
                                            val pairIds = pair.map { p -> p.songId }
                                            val indexById = pairIds.withIndex().associate { (i, id) -> id to i }
                                            songRepository
                                                .getSongsByListVideoId(pairIds)
                                                .lastOrNull()
                                                ?.sortedBy { indexById[it.videoId] ?: Int.MAX_VALUE }
                                                ?.let { songs ->
                                                    if (songs.isNotEmpty()) {
                                                        delay(300)
                                                        loadMoreCatalog(songs.toArrayListTrack())
                                                        _queueData.update {
                                                            it.copy(
                                                                data =
                                                                    it.data.copy(
                                                                        continuation =
                                                                            pair.lastOrNull()?.inPlaylist?.let { inPlaylist ->
                                                                                val prefix =
                                                                                    if (filter == FilterState.OlderFirst) ASC else DESC
                                                                                prefix + converters.dateToTimestamp(inPlaylist)
                                                                            },
                                                                    ),
                                                            )
                                                        }
                                                    } else {
                                                        reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                                    }
                                                }
                                        }
                                }

                                FilterState.Title, FilterState.CustomOrder -> {
                                    val offset =
                                        (
                                            if (filter == FilterState.CustomOrder) {
                                                continuation.removePrefix(CUSTOM_ORDER).toIntOrNull()
                                            } else {
                                                continuation.removePrefix(TITLE).toIntOrNull()
                                            }
                                        ) ?: return@launch
                                    localPlaylistRepository
                                        .getPlaylistPairSongByOffset(
                                            longId,
                                            offset,
                                            filter,
                                        ).lastOrNull()
                                        ?.let { pair ->
                                            Logger.w("Check loadMore response", pair.size.toString())
                                            val pairIds = pair.map { p -> p.songId }
                                            val indexById = pairIds.withIndex().associate { (i, id) -> id to i }
                                            songRepository
                                                .getSongsByListVideoId(pairIds)
                                                .lastOrNull()
                                                ?.sortedBy { indexById[it.videoId] ?: Int.MAX_VALUE }
                                                ?.let { songs ->
                                                    if (songs.isNotEmpty()) {
                                                        delay(300)
                                                        loadMoreCatalog(songs.toArrayListTrack())
                                                        _queueData.update {
                                                            it.copy(
                                                                data =
                                                                    it.data.copy(
                                                                        continuation =
                                                                            if (filter == FilterState.CustomOrder) {
                                                                                CUSTOM_ORDER + (offset + 1)
                                                                            } else {
                                                                                TITLE + (offset + 1).toString()
                                                                            },
                                                                    ),
                                                            )
                                                        }
                                                    } else {
                                                        reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                                    }
                                                }
                                        }
                                }
                            }
                        }
                    } finally {
                        if (_queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) {
                            _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED) }
                        }
                    }
                }
            } else {
                coroutineScope.launch {
                    _queueData.update {
                        it.copy(
                            queueState = QueueData.StateSource.STATE_INITIALIZING,
                        )
                    }
                    try {
                        Logger.w(TAG, "Check loadMore continuation $continuation")
                        songRepository
                            .getContinueTrack(playlistId, continuation)
                            .lastOrNull()
                            .let { response ->
                                val list = response?.first
                                if (list != null) {
                                    Logger.d(TAG, "Check loadMore response: ${list.size} tracks")
                                    loadMoreCatalog(list)
                                    _queueData.update {
                                        it.copy(
                                            data =
                                                it.data.copy(
                                                    continuation = response.second,
                                                ),
                                        )
                                    }
                                } else {
                                    _queueData.update {
                                        it.copy(
                                            data =
                                                it.data.copy(
                                                    continuation = null,
                                                ),
                                        )
                                    }
                                    if (endlessQueueEnabled) {
                                        Logger.w(TAG, "loadMore: Endless Queue")
                                        val lastTrack =
                                            queueData.value.data.listTracks
                                                .lastOrNull() ?: return@launch
                                        val radioId = "RDAMVM${lastTrack.videoId}"
                                        if (radioId == queueData.value.data.playlistId) {
                                            Logger.w(TAG, "loadMore: Already in radio mode")
                                            return@launch
                                        }
                                        _queueData.update {
                                            it.copy(
                                                data =
                                                    it.data.copy(
                                                        playlistId = radioId,
                                                        playlistType = PlaylistType.RADIO,
                                                    ),
                                                queueState = QueueData.StateSource.STATE_INITIALIZED,
                                            )
                                        }
                                        reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                        Logger.d("Check loadMore", "queueData: ${queueData.value}")
                                        getRelated(lastTrack.videoId)
                                    }
                                }
                            }
                    } finally {
                        if (_queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) {
                            _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED) }
                        }
                    }
                }
            }
        } else if (endlessQueueEnabled) {
            Logger.w(TAG, "loadMore: Endless Queue")
            val lastTrack =
                queueData.value.data.listTracks
                    .lastOrNull() ?: return
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                    data =
                        it.data.copy(
                            playlistId = "RDAMVM${lastTrack.videoId}",
                            playlistType = PlaylistType.RADIO,
                        ),
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
            Logger.d("Check loadMore", "queueData: ${queueData.value}")
            getRelated(lastTrack.videoId)
        }
    }

    override fun getRelated(videoId: String) {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        coroutineScope.launch {
            try {
                songRepository.getRelatedData(videoId).collect { response ->
                    when (response) {
                        is Resource.Success -> {
                            loadMoreCatalog(response.data?.first?.toCollection(arrayListOf()) ?: arrayListOf())
                            _queueData.update {
                                it.copy(
                                    data =
                                        it.data.copy(
                                            continuation = response.data?.second,
                                        ),
                                )
                            }
                        }

                        is Resource.Error -> {
                            Logger.d("Check Related", "getRelated: ${response.message}")
                            _queueData.update {
                                it.copy(
                                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                                    data =
                                        it.data.copy(
                                            continuation = null,
                                        ),
                                )
                            }
                            reorderShuffledQueue(player.getCurrentMediaTimeLine())
                        }
                    }
                }
            } finally {
                if (_queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) {
                    _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED) }
                }
            }
        }
    }

    override fun setQueueData(queueData: QueueData.Data) {
        _queueData.update {
            it.copy(
                data = queueData,
            )
        }
        // Snapshot which tracks came from the album, for the crossfade rule. Taken at load time
        // because endless queue appends to this same queue afterwards, and those additions are not
        // album tracks — that boundary is exactly where crossfade should resume.
        player.albumTrackIds =
            if (queueData.playlistType == PlaylistType.ALBUM) {
                queueData.listTracks.map { it.videoId }.toSet()
            } else {
                emptySet()
            }
        Logger.d(TAG, "setQueueData: ${queueData.listTracks.size} tracks")
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = player.currentMediaItem

    override suspend fun moveItemUp(position: Int) = moveQueueItem(position, position - 1)

    override suspend fun moveItemDown(position: Int) = moveQueueItem(position, position + 1)

    override suspend fun moveItemToPlayNext(position: Int) {
        val current = currentOrderIndex()
        // Already playing, or already next: nothing to move.
        if (current < 0 || position == current || position == current + 1) return
        // Once the track leaves its slot, everything after that slot shifts up by one: the slot
        // right after the current track is `current` for a track coming from behind it.
        moveQueueItem(position, if (position < current) current else current + 1)
    }

    /**
     * Moves the queue track at [from] to [to], in the player and in queueData alike.
     *
     * The positions are the ones the queue shows, and with shuffle on that is the shuffled order:
     * the move then goes to the player's shuffle order. Moving the playlist instead touched a
     * different track than the one picked, and rebuilt the whole shuffle on top.
     */
    private fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        val tracks = queueData.value.data.listTracks
        // Checked against the queue as it is now, for the reason in removeMediaItem.
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        val movedId = tracks[from].videoId
        if (player.shuffleModeEnabled) player.moveShuffledItem(from, to) else moveMediaItem(from, to)
        _queueData.update {
            val list = it.data.listTracks.toMutableList()
            // The player's own timeline event rebuilds the queue in the moved order too. If it got
            // here first, the track is no longer at `from`, and moving again would move it twice.
            if (list.getOrNull(from)?.videoId != movedId || to !in list.indices) return@update it
            list.add(to, list.removeAt(from))
            it.copy(
                data = it.data.copy(listTracks = list),
            )
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {
        if (mediaItem != null) {
            Logger.d("MusicSource", "addFirstMediaItem: ${mediaItem.mediaId}")
            moveMediaItem(0, index)
        }
    }

    override fun reset() {
        _queueData.value = QueueData()
    }

    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) {
        updateCatalog(downloaded, index).let {
            if (index != 0 && index != null && player.currentMediaItemIndex == 0) {
                moveMediaItem(0, index)
            }
            updateNextPreviousTrackAvailability()
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
        }
    }

    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) {
        Logger.d("Queue", listTrack.map { it.title }.toString())
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val catalogMetadata: ArrayList<Track> = arrayListOf()
        for (i in 0 until listTrack.size) {
            val track = listTrack[i]
            var thumbUrl =
                track.thumbnails?.lastOrNull()?.url
                    ?: "http://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
            thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
            val artistName: String = track.artists.toListName().connectArtists()
            val isSong =
                (
                    track.thumbnails?.lastOrNull()?.height != 0 &&
                        track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                        track.thumbnails?.lastOrNull()?.height != null
                ) &&
                    (
                        !thumbUrl
                            .contains("hq720") &&
                            !thumbUrl
                                .contains("maxresdefault") &&
                            !thumbUrl.contains("sddefault")
                    )
            if (track.artists.isNullOrEmpty()) {
                songRepository
                    .getSongInfo(track.videoId)
                    .lastOrNull()
                    .let { songInfo ->
                        if (songInfo != null) {
                            catalogMetadata.add(
                                track.copy(
                                    artists =
                                        listOf(
                                            Artist(
                                                songInfo.authorId,
                                                songInfo.author ?: "",
                                            ),
                                        ),
                                ),
                            )
                            addMediaItemNotSet(
                                GenericMediaItem(
                                    mediaId = track.videoId,
                                    uri = track.videoId,
                                    metadata =
                                        GenericMediaMetadata(
                                            title = track.title,
                                            artist = songInfo.author ?: "",
                                            albumTitle = track.album?.name,
                                            artworkUri = thumbUrl,
                                            description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                        ),
                                    customCacheKey = track.videoId,
                                ),
                            )
                        } else {
                            val mediaItem =
                                GenericMediaItem(
                                    mediaId = track.videoId,
                                    uri = track.videoId,
                                    metadata =
                                        GenericMediaMetadata(
                                            title = track.title,
                                            artist = "Various Artists",
                                            albumTitle = track.album?.name,
                                            artworkUri = thumbUrl,
                                            description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                        ),
                                    customCacheKey = track.videoId,
                                )
                            addMediaItemNotSet(mediaItem)
                            catalogMetadata.add(
                                track.copy(
                                    artists = listOf(Artist("", "Various Artists")),
                                ),
                            )
                        }
                    }
            } else {
                addMediaItemNotSet(
                    GenericMediaItem(
                        mediaId = track.videoId,
                        uri = track.videoId,
                        metadata =
                            GenericMediaMetadata(
                                title = track.title,
                                artist = artistName,
                                albumTitle = track.album?.name,
                                artworkUri = thumbUrl,
                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                            ),
                        customCacheKey = track.videoId,
                    ),
                )
                catalogMetadata.add(track)
            }
            Logger.d(
                "MusicSource",
                "updateCatalog: ${track.title}, ${catalogMetadata.size}",
            )
            Logger.d("MusicSource", "updateCatalog: ${track.title}")
        }
        // Intent, not observed: isPlaying is also false while paused OR while the next track
        // is still preparing, so reading it here let a background queue append strip a live
        // play-intent mid-load and the incoming track came up silent.
        if (!player.playWhenReady && isAddToQueue) {
            player.playWhenReady = false
        }
        _queueData.update {
            it
                .copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                ).addTrackList(catalogMetadata)
        }
        // Right after a batch is the only moment the queue grows, and the moment both lists are
        // known to be aligned again.
        trimRadioHistoryIfNeeded()
        reorderShuffledQueue(player.getCurrentMediaTimeLine())
    }

    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean {
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val tempQueue: ArrayList<Track> = arrayListOf()
        tempQueue.addAll(queueData.value.data.listTracks)
        val chunkedList = tempQueue.chunked(100)
        // Reset queue
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = arrayListOf(),
                    ),
            )
        }
        val current = if (index != null) tempQueue.getOrNull(index) else null
        chunkedList.forEachIndexed { chunkIdx, list ->
            val catalogMetadata: ArrayList<Track> = arrayListOf()
            Logger.w("SimpleMediaServiceHandler", "Catalog size: ${tempQueue.size}")
            Logger.w("SimpleMediaServiceHandler", "Skip index: $index")
            for (i in list.indices) {
                val track = list[i]
                val globalIndex = chunkIdx * 100 + i
                if (index != null && globalIndex == index) continue
                var thumbUrl =
                    track.thumbnails?.lastOrNull()?.url
                        ?: "http://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
                thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                val isSong =
                    (
                        track.thumbnails?.lastOrNull()?.height != 0 &&
                            track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                            track.thumbnails?.lastOrNull()?.height != null
                    ) &&
                        (
                            !thumbUrl
                                .contains("hq720") &&
                                !thumbUrl
                                    .contains("maxresdefault") &&
                                !thumbUrl.contains("sddefault")
                        )
                if (downloaded == 1) {
                    if (track.artists.isNullOrEmpty()) {
                        songRepository.getSongInfo(track.videoId).lastOrNull().let { songInfo ->
                            if (songInfo != null) {
                                val mediaItem =
                                    GenericMediaItem(
                                        mediaId = track.videoId,
                                        uri = track.videoId,
                                        metadata =
                                            GenericMediaMetadata(
                                                title = track.title,
                                                artist = songInfo.author ?: "",
                                                albumTitle = track.album?.name,
                                                artworkUri = thumbUrl,
                                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                            ),
                                        customCacheKey = track.videoId,
                                    )
                                addMediaItemNotSet(mediaItem)
                                catalogMetadata.add(
                                    track.copy(
                                        artists =
                                            listOf(
                                                Artist(
                                                    songInfo.authorId,
                                                    songInfo.author ?: "",
                                                ),
                                            ),
                                    ),
                                )
                            } else {
                                val mediaItem =
                                    GenericMediaItem(
                                        mediaId = track.videoId,
                                        uri = track.videoId,
                                        metadata =
                                            GenericMediaMetadata(
                                                title = track.title,
                                                artist = "Various Artists",
                                                albumTitle = track.album?.name,
                                                artworkUri = thumbUrl,
                                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                            ),
                                        customCacheKey = track.videoId,
                                    )
                                addMediaItemNotSet(mediaItem)
                                catalogMetadata.add(
                                    track.copy(
                                        artists = listOf(Artist("", "Various Artists")),
                                    ),
                                )
                            }
                        }
                    } else {
                        val mediaItem =
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = track.artists.toListName().connectArtists(),
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                    ),
                                customCacheKey = track.videoId,
                            )
                        addMediaItemNotSet(mediaItem)
                        catalogMetadata.add(track)
                    }
                    Logger.d("MusicSource", "updateCatalog: ${track.title}, ${catalogMetadata.size}")
                } else {
                    val artistName: String = track.artists.toListName().connectArtists()
                    if (track.artists.isNullOrEmpty()) {
                        songRepository
                            .getSongInfo(track.videoId)
                            .cancellable()
                            .lastOrNull()
                            .let { songInfo ->
                                if (songInfo != null) {
                                    catalogMetadata.add(
                                        track.copy(
                                            artists =
                                                listOf(
                                                    Artist(
                                                        songInfo.authorId,
                                                        songInfo.author ?: "",
                                                    ),
                                                ),
                                        ),
                                    )
                                    addMediaItemNotSet(
                                        GenericMediaItem(
                                            mediaId = track.videoId,
                                            uri = track.videoId,
                                            metadata =
                                                GenericMediaMetadata(
                                                    title = track.title,
                                                    artist = songInfo.author ?: "",
                                                    albumTitle = track.album?.name,
                                                    artworkUri = thumbUrl,
                                                    description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                                ),
                                            customCacheKey = track.videoId,
                                        ),
                                    )
                                } else {
                                    val mediaItem =
                                        GenericMediaItem(
                                            mediaId = track.videoId,
                                            uri = track.videoId,
                                            metadata =
                                                GenericMediaMetadata(
                                                    title = track.title,
                                                    artist = "Various Artists",
                                                    albumTitle = track.album?.name,
                                                    artworkUri = thumbUrl,
                                                    description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                                ),
                                            customCacheKey = track.videoId,
                                        )
                                    addMediaItemNotSet(mediaItem)
                                    catalogMetadata.add(
                                        track.copy(
                                            artists = listOf(Artist("", "Various Artists")),
                                        ),
                                    )
                                }
                            }
                    } else {
                        addMediaItemNotSet(
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = artistName,
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                    ),
                                customCacheKey = track.videoId,
                            ),
                        )
                        catalogMetadata.add(track)
                    }
                    Logger.d(
                        "MusicSource",
                        "updateCatalog: ${track.title}, ${catalogMetadata.size}",
                    )
                    Logger.d("MusicSource", "updateCatalog: ${track.title}")
                }
            }
            _queueData.update {
                it.addTrackList(catalogMetadata)
            }
            delay(200)
        }
        if (current != null && index != null) {
            _queueData.update {
                it.addToIndex(current, index)
            }
        }
        Logger.w("SimpleMediaServiceHandler", "current queue: ${player.mediaItemCount}")
        return true
    }

    override fun addQueueToPlayer() {
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                load()
            }
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                load(index = index)
            }
    }

    override fun currentOrderIndex(): Int =
        if (player.shuffleModeEnabled) {
            queueData.value.data.listTracks.indexOfLast {
                it.videoId == player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
            }
        } else {
            currentSongIndex()
        }

    override fun setCurrentSongIndex(index: Int) {
        _currentSongIndex.value = index
    }

    override suspend fun playNext(track: Track) {
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        try {
            var thumbUrl =
                track.thumbnails?.lastOrNull()?.url
                    ?: "http://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
            thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
            val artistName: String = track.artists.toListName().connectArtists()
            val isSong =
                (
                    track.thumbnails?.lastOrNull()?.height != 0 &&
                        track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                        track.thumbnails?.lastOrNull()?.height != null
                ) &&
                    (
                        !thumbUrl
                            .contains("hq720") &&
                            !thumbUrl
                                .contains("maxresdefault") &&
                            !thumbUrl.contains("sddefault")
                    )

            val resolvedTrack: Track
            val displayArtist: String
            if (track.artists.isNullOrEmpty()) {
                val songInfo = songRepository.getSongInfo(track.videoId).cancellable().lastOrNull()
                if (songInfo != null) {
                    displayArtist = songInfo.author ?: ""
                    resolvedTrack =
                        track.copy(
                            artists = listOf(Artist(songInfo.authorId, displayArtist)),
                        )
                } else {
                    displayArtist = "Various Artists"
                    resolvedTrack = track.copy(artists = listOf(Artist("", displayArtist)))
                }
            } else {
                displayArtist = artistName
                resolvedTrack = track
            }

            val currentPlayerIdx = player.currentMediaItemIndex
            val queueSize = queueData.value.data.listTracks.size
            val insertPlayerIdx = (currentPlayerIdx + 1).coerceIn(0, player.mediaItemCount)
            val currentUiIdx = if (player.shuffleModeEnabled) currentOrderIndex() else currentPlayerIdx
            val insertUiIdx = (currentUiIdx + 1).coerceIn(0, queueSize)

            addMediaItemNotSet(
                GenericMediaItem(
                    mediaId = track.videoId,
                    uri = track.videoId,
                    metadata =
                        GenericMediaMetadata(
                            title = track.title,
                            artist = displayArtist,
                            albumTitle = track.album?.name,
                            artworkUri = thumbUrl,
                            description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                        ),
                    customCacheKey = track.videoId,
                ),
                insertPlayerIdx,
            )
            Logger.d("MusicSource", "playNext: ${track.title}, playerAt=$insertPlayerIdx, uiAt=$insertUiIdx")
            _queueData.update {
                val tracks = it.data.listTracks
                it.copy(
                    data =
                        it.data.copy(
                            listTracks =
                                tracks.toMutableList().apply {
                                    add(insertUiIdx.coerceIn(0, size), resolvedTrack)
                                },
                        ),
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
        } finally {
            if (_queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) {
                _queueData.update { it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED) }
            }
        }
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        val track =
            when (anyTrack) {
                is Track -> anyTrack
                is SongEntity -> anyTrack.toTrack()
                else -> return
            }
        if (track.isExplicit && dataStoreManager.explicitContentEnabled.first() == FALSE) {
            showToast(ToastType.ExplicitContent)
            return
        }
        userLoadedExplicitly = true
        restoreQueueJob?.cancel()
        loadJob?.cancel()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        streamRecoveryAttempts = 0
        recoveryArmedMediaId = null
        recoveryArmedPositionMs = -1L
        resumeWhenNetworkReturns = false
        terminalError = false
        lastObservedPlayPositionMs = 0L

        playTimingStartMs = SystemClock.elapsedRealtime()
        playTimingVideoId = track.videoId
        pendingInsertSongJob =
            track.videoId to
            coroutineScope.launch(Dispatchers.IO) {
                songRepository.insertSong(track.toSongEntity()).singleOrNull()?.let {
                    Logger.d(TAG, "Inserted song: ${track.title}")
                }
                track.durationSeconds?.let {
                    songRepository.updateDurationSeconds(it, track.videoId)
                }
            }
        addMediaItem(track.toGenericMediaItem(), playWhenReady = type != RECOVER_TRACK_QUEUE)
        when (type) {
            SONG_CLICK, VIDEO_CLICK, SHARE -> {
                getRelated(track.videoId)
            }

            PLAYLIST_CLICK, ALBUM_CLICK, RADIO_CLICK -> {
                loadPlaylistOrAlbum(index)
            }
        }
    }

    override fun getPlayerDuration(): Long = player.duration

    override fun getProgress(): Long = player.currentPosition

    override fun mayBeSaveRecentSong(runBlocking: Boolean) {
        val unit =
            suspend {
                // Skip while the playing song is unknown or the queue is mid-rebuild:
                // updateCatalog clears listTracks and re-inserts the current track only at
                // the end, so saving in that window persists a queue missing the current
                // track (plus a blank media id), which desyncs the next restore.
                val videoId = nowPlayingState.value.songEntity?.videoId
                if (videoId != null && queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZED) {
                    dataStoreManager.saveRecentSong(
                        videoId,
                        player.contentPosition,
                    )
                    dataStoreManager.setPlaylistFromSaved(queueData.value.data.playlistName ?: "")
                    Logger.d(
                        "Check saved",
                        player.currentMediaItem
                            ?.metadata
                            ?.title
                            .toString(),
                    )
                    val temp: ArrayList<Track> = ArrayList()
                    temp.clear()
                    temp.addAll(_queueData.value.data.listTracks)
                    val queueIds = temp.map { it.videoId }
                    if (queueIds == lastPersistedQueueIds) {
                        Logger.d("Check recover queue", "Queue unchanged (${temp.size} tracks): skipping rewrite")
                    } else {
                        Logger.i("Check recover queue", "Saved ${temp.size} tracks for the next launch")
                        songRepository.recoverQueue(temp)
                        lastPersistedQueueIds = queueIds
                    }
                }
            }
        if (runBlocking) {
            recoverQueueSaveJob?.cancel()
            runBlocking { unit() }
        } else {
            recoverQueueSaveJob?.cancel()
            recoverQueueSaveJob =
                coroutineScope.launch {
                    delay(300)
                    unit()
                }
        }
    }

    /**
     * Lightweight periodic persistence of just the playback position (#2152).
     * Unlike [mayBeSaveRecentSong] this does NOT rewrite the whole saved queue, so it is
     * cheap enough to call every few seconds while a track plays uninterrupted. The saved
     * media id + position are what [mayBeRestoreQueue] reads to resume after a process kill.
     */
    private fun mayBeSaveRecentPosition() {
        coroutineScope.launch {
            val videoId = nowPlayingState.value.songEntity?.videoId ?: return@launch
            dataStoreManager.saveRecentSong(videoId, player.contentPosition)
        }
    }

    override fun mayBeNormalizeVolume() {
        if (!normalizeVolume) {
            appliedEnhancerSessionId = PlayerConstants.AUDIO_SESSION_ID_UNSET
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            if (!_castState.value.isRemote && player.volume != 1f) {
                player.volume = 1f
            }
            return
        }

        // Always recreate LoudnessEnhancer when ExoPlayer audioSessionId changes.
        // Skip entirely while casting: a Cast session has no local audio session.
        val sessionId = player.audioSessionId
        if (!_castState.value.isRemote &&
            sessionId != PlayerConstants.AUDIO_SESSION_ID_UNSET &&
            (loudnessEnhancer == null || appliedEnhancerSessionId != sessionId)
        ) {
            try {
                loudnessEnhancer?.release()
            } catch (_: Exception) {
            }
            try {
                loudnessEnhancer = LoudnessEnhancer(sessionId)
                appliedEnhancerSessionId = sessionId
            } catch (e: Exception) {
                appliedEnhancerSessionId = PlayerConstants.AUDIO_SESSION_ID_UNSET
                Logger.e(TAG, "mayBeNormalizeVolume: ${e.message}")
            }
        }

        player.currentMediaItem?.mediaId?.let { songId ->
            val videoId = songId.removePrefix(MERGING_DATA_TYPE.VIDEO)
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob =
                coroutineScope.launch(Dispatchers.Main) {
                    fun Float?.toMb() = ((this ?: 0f) * 100).toInt()
                    streamRepository
                        .getFormatFlow(videoId)
                        .cancellable()
                        .distinctUntilChanged()
                        .collectLatest { format ->
                            if (format != null) {
                                val loudnessMb =
                                    format.loudnessDb.toMb().let {
                                        if (it !in -2000..2000) 0 else it
                                    }
                                Logger.d(TAG, "Loudness: ${format.loudnessDb} db, $loudnessMb")
                                try {
                                    loudnessEnhancer?.setTargetGain(0f.toMb() - loudnessMb)
                                    loudnessEnhancer?.enabled = true
                                    Logger.w(
                                        TAG,
                                        "mayBeNormalizeVolume: ${loudnessEnhancer?.targetGain}",
                                    )
                                } catch (e: Exception) {
                                    Logger.e(TAG, "mayBeNormalizeVolume: ${e.message}")
                                }
                                try {
                                    secondLoudnessEnhancer?.setTargetGain(0f.toMb() - loudnessMb)
                                    secondLoudnessEnhancer?.enabled = true
                                } catch (e: Exception) {
                                    Logger.e(TAG, "mayBeNormalizeVolume: ${e.message}")
                                }
                            }
                        }
                }
        }
    }

    override fun mayBeSavePlaybackState() {
        if (runBlocking { dataStoreManager.saveStateOfPlayback.first() } == TRUE) {
            runBlocking {
                dataStoreManager.recoverShuffleAndRepeatKey(
                    player.shuffleModeEnabled,
                    player.repeatMode,
                )
            }
        }
    }

    private fun savePlaybackStateAsync() {
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        backgroundScope.launch {
            if (dataStoreManager.saveStateOfPlayback.first() == TRUE) {
                dataStoreManager.recoverShuffleAndRepeatKey(shuffle, repeat)
            }
        }
    }

    override fun mayBeRestoreQueue() {
        restoreQueueJob?.cancel()
        restoreQueueJob = coroutineScope.launch { restoreSavedQueue(playWhenReady = false) }
    }

    override suspend fun restoreQueueAndPlay(): Boolean {
        playRequestedDuringRestore = true
        if (restoreHasPrimedPlayer && player.mediaItemCount > 0) {
            requestPlayWhenReady()
            return true
        }
        return restoreSavedQueue(playWhenReady = true)
    }

    private fun requestPlayWhenReady() {
        player.playWhenReady = true
    }

    private suspend fun restoreSavedQueue(playWhenReady: Boolean): Boolean =
        restoreQueueMutex.withLock {
            if (player.mediaItemCount > 0 || (userLoadedExplicitly && !playWhenReady)) {
                // Already restored, or the user explicitly started another track/queue.
                if (playWhenReady || playRequestedDuringRestore) requestPlayWhenReady()
                return@withLock true
            }
            val currentPlayingTrack =
                songRepository
                    .getSongById(dataStoreManager.recentMediaId.first())
                    .lastOrNull()
                    ?.toTrack() ?: return@withLock false
            val savedPosition = (dataStoreManager.recentPosition.first().toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val savedTracks =
                songRepository
                    .getSavedQueue()
                    .singleOrNull()
                    ?.firstOrNull()
                    ?.listTrack
                    .orEmpty()
            val savedPlaylistName = dataStoreManager.playlistFromSaved.first()
            if (player.mediaItemCount > 0 || (userLoadedExplicitly && !playWhenReady)) {
                return@withLock true
            }
            var index = savedTracks.indexOfFirst { it.videoId == currentPlayingTrack.videoId }
            val listTracks =
                if (index == -1) {
                    index = 0
                    (listOf(currentPlayingTrack) + savedTracks).toCollection(arrayListOf())
                } else {
                    savedTracks.toCollection(arrayListOf())
                }
            setQueueData(
                QueueData.Data(
                    listTracks = listTracks,
                    firstPlayedTrack = currentPlayingTrack,
                    playlistId = LOCAL_PLAYLIST_ID_SAVED_QUEUE,
                    playlistName = savedPlaylistName,
                    playlistType = PlaylistType.PLAYLIST,
                    continuation = null,
                ),
            )
            addMediaItem(
                currentPlayingTrack.toGenericMediaItem(),
                playWhenReady = playWhenReady || playRequestedDuringRestore,
            )
            // Single-arg seek seeks within the already-prepared player without destroying & recreating it.
            if (savedPosition > 0L) {
                player.seekTo(savedPosition)
            }
            restoreHasPrimedPlayer = true
            if (playWhenReady || playRequestedDuringRestore) requestPlayWhenReady()
            loadPlaylistOrAlbum(index = index)
            loadJob?.join()
            resetCrossfade()
            _simpleMediaState.value = SimpleMediaState.Progress(savedPosition)
            if (playWhenReady || playRequestedDuringRestore) requestPlayWhenReady()
            true
        }

    override fun shouldReleaseOnTaskRemoved() =
        runBlocking {
            dataStoreManager.killServiceOnExit.first() == TRUE
        }

    override fun release() {
        Logger.w("ServiceHandler", "Starting release process")
        try {
            if (discordRPC?.isRpcRunning() == true) {
                discordRPC?.closeRPC()
            }
            discordRPC = null
            // Save state first
            mayBeSaveRecentSong(true)
            mayBeSavePlaybackState()

            // Stop and release player
            player.removeListener(this)

            // Stop listening for the connection coming back (see networkCallback)
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Logger.e("ServiceHandler", "Error unregistering network callback ${e.message}")
            }

            // Release audio effects
            try {
                appliedEnhancerSessionId = PlayerConstants.AUDIO_SESSION_ID_UNSET
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null

                secondLoudnessEnhancer?.enabled = false
                secondLoudnessEnhancer?.release()
                secondLoudnessEnhancer = null
            } catch (e: Exception) {
                Logger.e("ServiceHandler", "Error releasing audio effects ${e.message}")
            }

            // Send close equalizer intent
            sendCloseEqualizerIntent()

            // Cancel all jobs
            restoreQueueJob?.cancel()
            restoreQueueJob = null
            streamRecoveryJob?.cancel()
            streamRecoveryJob = null
            progressJob?.cancel()
            progressJob = null
            bufferedJob?.cancel()
            bufferedJob = null
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob = null
            toggleLikeJob?.cancel()
            toggleLikeJob = null
            updateNotificationJob?.cancel()
            updateNotificationJob = null
            loadJob?.cancel()
            loadJob = null
            songEntityJob?.cancel()
            songEntityJob = null
            getSkipSegmentsJob?.cancel()
            getSkipSegmentsJob = null
            getFormatJob?.cancel()
            getFormatJob = null
            jobWatchtime?.cancel()
            jobWatchtime = null
            getDataOfNowPlayingTrackStateJob?.cancel()
            getDataOfNowPlayingTrackStateJob = null
            rpcSenderJob?.cancel()
            rpcSenderJob = null

            // Cancel coroutine scope
            coroutineScope.cancel()
            backgroundScope.cancel()

            Logger.w("ServiceHandler", "Handler released successfully. Scope active: ${coroutineScope.isActive}")
        } catch (e: Exception) {
            Logger.e("ServiceHandler", "Error during release ${e.message}")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val loaded =
            player.bufferedPosition.let {
                if (it > 0) it else 0
            }
        val current =
            player.currentPosition.let {
                if (it > 0) it else 0
            }
        when (playbackState) {
            PlayerConstants.STATE_IDLE -> {
                _simpleMediaState.value = SimpleMediaState.Initial
                Logger.d(TAG, "onPlaybackStateChanged: Idle")
            }

            PlayerConstants.STATE_ENDED -> {
                _simpleMediaState.value = SimpleMediaState.Ended
                Logger.d(TAG, "onPlaybackStateChanged: Ended")
                if (player.repeatMode != PlayerConstants.REPEAT_MODE_ONE) {
                    flushPendingQualityEvictions(keepBaseId = null)
                }
            }

            PlayerConstants.STATE_READY -> {
                Logger.d(TAG, "onPlaybackStateChanged: Ready")
                val timingVideoId = playTimingVideoId
                if (timingVideoId != null) {
                    Logger.i(
                        "PlayTiming",
                        "id=$timingVideoId ready=${SystemClock.elapsedRealtime() - playTimingStartMs}ms",
                    )
                }
                val durMs = player.duration
                if (durMs > 0L) {
                    val durSec = (durMs / 1000L).toInt()
                    val curEntity = nowPlayingState.value.songEntity
                    if (curEntity != null && curEntity.durationSeconds == 0 && durSec > 0) {
                        _nowPlayingState.update { it.copy(songEntity = curEntity.copy(durationSeconds = durSec)) }
                    }
                    if (durSec > 0) {
                        coroutineScope.launch { lastfmScrobbler.updateDurationSeconds(durSec) }
                    }
                }
                if (normalizeVolume &&
                    !_castState.value.isRemote &&
                    player.audioSessionId != PlayerConstants.AUDIO_SESSION_ID_UNSET &&
                    player.audioSessionId != appliedEnhancerSessionId
                ) {
                    mayBeNormalizeVolume()
                }
                if (player.currentPosition == 0L && player.bufferedPosition == 0L && !player.isPlaying) {
                    // Nothing buffered and nothing playing means it is still waiting: say Buffering instead.
                    _simpleMediaState.value = SimpleMediaState.Buffering(player.currentPosition)
                } else {
                    _simpleMediaState.value = SimpleMediaState.Ready(durMs)
                }
            }

            else -> {
                if (current >= loaded) {
                    _simpleMediaState.value = SimpleMediaState.Buffering(player.currentPosition)
                    Logger.d(TAG, "onPlaybackStateChanged: Buffering")
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _controlState.value = _controlState.value.copy(isPlaying = isPlaying)
        if (isPlaying) {
            terminalError = false
            // Reset recovery attempts only once playback is actually READY and either the item
            // changed or the buffer/playhead moved past where recovery started — prevents an
            // immediate mid-track PREPARING->PLAYING flash from resetting retry count infinitely.
            val currentId = player.currentMediaItem?.mediaId
            val madeRealProgress =
                player.playbackState == PlayerConstants.STATE_READY &&
                    (
                        currentId != recoveryArmedMediaId ||
                            player.bufferedPosition > recoveryArmedPositionMs.coerceAtLeast(0L) ||
                            player.currentPosition > recoveryArmedPositionMs + 500L
                    )
            if (madeRealProgress) {
                streamRecoveryAttempts = 0
                recoveryArmedMediaId = null
                recoveryArmedPositionMs = -1L
                streamRecoveryJob?.cancel()
                streamRecoveryJob = null
            }
            resumeWhenNetworkReturns = false
            audioTrackRecoveryMediaId = null
            val timingVideoId = playTimingVideoId
            if (timingVideoId != null) {
                Logger.i(
                    "PlayTiming",
                    "id=$timingVideoId playing=${SystemClock.elapsedRealtime() - playTimingStartMs}ms",
                )
                playTimingVideoId = null
            }
            startProgressUpdate()
            val expectedVideoId = currentId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
            nowPlayingState.value.songEntity?.takeIf { it.videoId == expectedVideoId }?.let {
                updateDiscordRpc(it)
            }
        } else {
            stopProgressUpdate()
            mayBeSaveRecentSong()
            savePlaybackStateAsync()
            rpcEventSeq.incrementAndGet()
            rpcSnapshotFlow.value = null
            if (discordRPC?.isRpcRunning() == true) {
                discordRPC?.closeRPC()
            }
        }
        updateNextPreviousTrackAvailability()
    }

    override fun onSeeked(positionMs: Long) {
        if (positionMs > lastObservedPlayPositionMs) {
            lastObservedPlayPositionMs = positionMs
        }
        // System-panel / notification seeks bypass the handler and land straight on ExoPlayer;
        // refresh the RPC timestamps so Discord's progress bar follows the new position.
        if (player.isPlaying) {
            val expectedVideoId = player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
            nowPlayingState.value.songEntity?.takeIf { it.videoId == expectedVideoId }?.let {
                updateDiscordRpc(it)
            }
        } else {
            // Paused, the progress ticker is stopped, so nothing else tells the UI the position
            // moved: every seek bar, clock and lyric line would wait for play to catch up (#2480).
            _simpleMediaState.value = SimpleMediaState.Progress(positionMs)
        }
    }

    override fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) {
        val nextBaseId = mediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)?.takeIf { it.isNotBlank() }
        flushPendingQualityEvictions(keepBaseId = nextBaseId)
        Logger.w(TAG, "Checking current state before transition ${simpleMediaState.value}")
        terminalError = false
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        val lastPlayed = nowPlayingState.value.songEntity
        val currentState = simpleMediaState.value
        val trackedProgressMs =
            maxOf(
                lastObservedPlayPositionMs,
                (currentState as? SimpleMediaState.Progress)?.progress ?: 0L,
            )
        lastObservedPlayPositionMs = 0L
        if (trackedProgressMs > 0L && lastPlayed != null && lastPlayed.durationSeconds > 0) {
            mayBeTrackingListeningLocal(lastPlayed, trackedProgressMs)
        }
        Logger.w(TAG, "Smooth Switching Transition Current Position: ${player.currentPosition}")
        mayBeNormalizeVolume()
        Logger.w(TAG, "REASON onMediaItemTransition: $reason")
        Logger.d(TAG, "Media Item Transition Media Item: ${mediaItem?.metadata?.title}")
        if (mediaItem?.mediaId != _nowPlaying.value?.mediaId) {
            _nowPlaying.value = mediaItem
        }
        if (mediaItem?.mediaId != nowPlayingState.value.mediaItem.mediaId) {
            Logger.w(TAG, "onMediaItemTransition: ${mediaItem?.mediaId}")
            if (mediaItem != null) {
                getDataOfNowPlayingState(mediaItem)
            } else {
                _nowPlayingState.update {
                    NowPlayingTrackState
                        .initial()
                }
            }
        } else if (mediaItem != null) {
            // Repeat-one replays the same mediaId without reloading now-playing data: reset
            // Last.fm scrobble state so the replay scrobbles again, and refresh Discord RPC.
            nowPlayingState.value.songEntity?.let { song ->
                updateDiscordRpc(song)
                coroutineScope.launch {
                    lastfmScrobbler.reset()
                    lastfmScrobbler.onTrackStarted(song)
                }
            }
        }
        queueData.value.data.listTracks.let { list ->
            if ((list.size > 3 || endlessQueueEnabled || queueData.value.data.continuation != null) &&
                list.size - player.currentMediaItemIndex < 3 &&
                list.size - player.currentMediaItemIndex >= 0 &&
                queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZED
            ) {
                Logger.d("Check loadMore", "loadMore")
                loadMore()
            }
        }
        updateNextPreviousTrackAvailability()
        updateNotification()
        if (player.currentMediaItemIndex == 0) {
            resetCrossfade()
        }
        mayBeSaveRecentSong()
    }

    private fun mayBeTrackingListeningLocal(
        song: SongEntity,
        currentPositionMillis: Long,
    ) {
        coroutineScope.launch {
            val trackingEnabled = dataStoreManager.localTrackingEnabled.first() == TRUE
            if (!trackingEnabled) {
                return@launch
            }
            val percent = (currentPositionMillis / (song.durationSeconds * 1000f))
            Logger.w(TAG, "${song.title} - $currentPositionMillis ms listened, duration: ${song.durationSeconds * 1000} ms, percent: $percent")
            if (percent < 0.2f) {
                Logger.d(TAG, "Not enough listening time for ${song.title}, skipping tracking")
                return@launch
            }
            Logger.d(TAG, "Tracking listening for ${song.title} at position $currentPositionMillis ms")
            analyticsRepository
                .insertPlaybackEvent(
                    videoId = song.videoId,
                    channelIds = song.artistId ?: emptyList(),
                    albumBrowseId = song.albumId,
                    durationSecond = song.durationSeconds.toLong(),
                    listenedSecond =
                        if (percent >= 0.8f) {
                            song.durationSeconds.toLong()
                        } else {
                            (currentPositionMillis / 1000)
                        },
                ).collect {
                    Logger.d(TAG, "Inserted playback event for ${song.title}: $it")
                }
        }
    }

    private fun updateDiscordRpc(song: SongEntity) {
        coroutineScope.launch {
            // Grab the sequence number as the FIRST statement — before any suspension point — so it
            // reflects true event order.
            val seq = rpcEventSeq.incrementAndGet()
            val progressMs = getProgress().coerceAtLeast(0L)
            val durationMs = getPlayerDuration().takeIf { it > 0L } ?: (song.durationSeconds * 1000L).coerceAtLeast(0L)
            val speed = dataStoreManager.playbackSpeed.first()
            if (!controlState.value.isPlaying) return@launch
            val snapshot =
                RpcSnapshot(
                    song = song,
                    progressMs = progressMs,
                    durationMs = durationMs,
                    speed = speed,
                    seq = seq,
                )
            rpcSnapshotFlow.update { cur -> if (cur == null || seq >= cur.seq) snapshot else cur }
        }
    }

    override fun onTracksChanged(tracks: GenericTracks) {
        Logger.d(TAG, "onTracksChanged: ${tracks.groups.size}")
    }

    // Automatic recovery from a failed stream. The player's data source resolves the stream URL
    // lazily and already falls back to a fresh URL when the cached one is expired or answers 403 —
    // but nothing ever gave it the chance: onPlayerError used to log, toast and pause, so a
    // connection that dropped mid-song (or a URL that died between chunks) ended playback for
    // good, and even pressing play could not resume, because the player stays in its error state
    // until it is prepared again. For the network-shaped errors below, the handler now re-prepares
    // the current item at the interrupted position instead, a bounded number of times per item;
    // the error is surfaced (toast + pushPlayerError + pause, the old behaviour) only when the
    // error is not one a fresh prepare can fix, or the attempts have run out.
    private var streamRecoveryAttempts = 0
    private var streamRecoveryMediaId: String? = null
    private var streamRecoveryJob: Job? = null

    /** Item that already spent its single AudioTrack recovery attempt (see onPlayerError). */
    private var audioTrackRecoveryMediaId: String? = null

    private fun flushPendingQualityEvictions(keepBaseId: String?) {
        if (pendingQualityEvictVideoIds.isEmpty()) return
        val toEvict = pendingQualityEvictVideoIds.filter { it != keepBaseId }
        if (toEvict.isEmpty()) return
        pendingQualityEvictVideoIds.removeAll(toEvict.toSet())
        backgroundScope.launch {
            toEvict.forEach { baseId ->
                runCatching {
                    cacheRepository.removeCachedSong(baseId)
                    streamRepository.invalidateFormat(baseId)
                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$baseId")
                }.onFailure { e ->
                    Logger.w(TAG, "Deferred quality cleanup failed for $baseId: ${e.message}")
                }
            }
        }
    }

    override fun onPlayerError(error: PlayerError) {
        Logger.e("Player Error", "onPlayerError (${error.errorCode}): ${error.message}")
        if (isAudioTrackError(error.errorCode)) {
            val mediaId = player.currentMediaItem?.mediaId
            if (mediaId != null && audioTrackRecoveryMediaId != mediaId) {
                audioTrackRecoveryMediaId = mediaId
                if (tryScheduleStreamRecovery()) {
                    return
                }
            }
        }
        if (error.recoverable && isRecoverableStreamError(error.errorCode) && tryScheduleStreamRecovery()) {
            resumeWhenNetworkReturns = true
            return
        }
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        resumeWhenNetworkReturns = false
        terminalError = true
        if (error.errorCode != PlayerConstants.ERROR_CODE_TIMEOUT) {
            pushPlayerError(error)
        }
        if (isAppInForeground()) {
            showToast(ToastType.PlayerError(error.errorCodeName))
        } else {
            Logger.w("Player Error", "App is not in foreground, skipping toast")
        }
        player.pause()
    }

    private fun isRecoverableStreamError(errorCode: Int): Boolean =
        when (errorCode) {
            PlayerConstants.ERROR_CODE_TIMEOUT,
            2000, // ERROR_CODE_IO_UNSPECIFIED
            2001, // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            2002, // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            2004, // ERROR_CODE_IO_BAD_HTTP_STATUS
            2005, // ERROR_CODE_IO_FILE_NOT_FOUND
            3001, // ERROR_CODE_PARSING_CONTAINER_MALFORMED
            -> true

            else -> false
        }

    /** Media3's 5000-range codes are all AudioTrack failures (5001 init, 5002 write). */
    private fun isAudioTrackError(errorCode: Int): Boolean = errorCode in 5000..5999

    private fun tryScheduleStreamRecovery(): Boolean {
        val mediaItem = player.currentMediaItem ?: return false
        if (streamRecoveryMediaId != mediaItem.mediaId) {
            streamRecoveryMediaId = mediaItem.mediaId
            streamRecoveryAttempts = 0
        }
        if (streamRecoveryAttempts >= MAX_STREAM_RECOVERY_ATTEMPTS) return false
        streamRecoveryAttempts++
        val attempt = streamRecoveryAttempts
        val index = player.currentMediaItemIndex
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val wasPlayWhenReady = player.playWhenReady
        val mediaId = mediaItem.mediaId
        recoveryArmedMediaId = mediaId
        recoveryArmedPositionMs = positionMs

        streamRecoveryJob?.cancel()
        streamRecoveryJob =
            coroutineScope.launch {
                delay(streamRecoveryDelayMs(attempt))
                if (player.currentMediaItem?.mediaId != mediaId) return@launch
                if (attempt == CACHE_SELF_HEAL_ATTEMPT) {
                    val videoId = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    withContext(Dispatchers.IO) {
                        if (cacheRepository.getFullyCachedSongIds().contains(videoId)) {
                            Logger.w(
                                "Player Error",
                                "Stream for $mediaId keeps failing while fully cached; dropping the cached copy to re-resolve",
                            )
                            cacheRepository.removeCachedSong(videoId)
                            streamRepository.invalidateFormat(videoId)
                            streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                        }
                    }
                    if (player.currentMediaItem?.mediaId != mediaId) return@launch
                }
                val latestIndex = player.currentMediaItemIndex.takeIf { it >= 0 } ?: index
                Logger.d(
                    "Player Error",
                    "Recovering stream for $mediaId at ${positionMs}ms (attempt $attempt/$MAX_STREAM_RECOVERY_ATTEMPTS)",
                )
                terminalError = false
                player.playWhenReady = wasPlayWhenReady
                player.seekTo(latestIndex, positionMs)
            }
        return true
    }

    private fun resumePlaybackAfterReconnect(): Boolean {
        if (terminalError) return false
        val mediaItem = player.currentMediaItem ?: return false
        if (!player.playWhenReady) return false
        val state = player.playbackState
        val stalled = player.isStalled
        if (!stalled && state != PlayerConstants.STATE_IDLE && state != PlayerConstants.STATE_BUFFERING) return false
        if (player.isPlaying && !stalled) return false
        streamRecoveryAttempts = 0
        recoveryArmedMediaId = null
        recoveryArmedPositionMs = -1L
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        val index = player.currentMediaItemIndex
        val positionMs = player.currentPosition.coerceAtLeast(0)
        Logger.d(
            "Player Error",
            "Connection is back: resuming ${mediaItem.mediaId} at ${positionMs}ms (stalled=$stalled, state=$state)",
        )
        player.seekTo(index, positionMs)
        return true
    }

    override fun shouldOpenOrCloseEqualizerIntent(shouldOpen: Boolean) {
        // Built-in selected: the session is never handed to a system equalizer, so the two cannot run together.
        if (!systemEqualizerSelected) return
        if (shouldOpen) sendOpenEqualizerIntent() else sendCloseEqualizerIntent()
    }

    override fun onShuffleModeEnabledChanged(
        shuffleModeEnabled: Boolean,
        list: List<GenericMediaItem>,
    ) {
        when (shuffleModeEnabled) {
            true -> {
                _controlState.value = _controlState.value.copy(isShuffle = true)
            }

            false -> {
                _controlState.value = _controlState.value.copy(isShuffle = false)
            }
        }
        reorderShuffledQueue(list)
        updateNextPreviousTrackAvailability()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNextPreviousTrackAvailability()
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_OFF -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.None)
            }

            PlayerConstants.REPEAT_MODE_ONE -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.One)
            }

            PlayerConstants.REPEAT_MODE_ALL -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.All)
            }
        }
    }

    /**
     * Publishes exactly one state, and it matches the argument.
     *
     * It used to write Loading unconditionally and then call stopBufferedUpdate, which wrote
     * Loading again. `_simpleMediaState` is a StateFlow collected from another thread, so those
     * writes conflate and the UI settled on Loading even when buffering had just *finished* —
     * leaving a spinner over playback that was already running.
     *
     * One exception: when loading stops while the player is still BUFFERING, nothing is
     * published at all. That stop means the loader cannot proceed (no network, a retry
     * backoff), not that the track is ready — a Ready there would clear the UI's loading
     * flag and leave a static bar over a stall that is still waiting on the network.
     */
    override fun onIsLoadingChanged(isLoading: Boolean) {
        if (isLoading) {
            startBufferedUpdate()
            // Already holding more than the playhead needs: the stall is nominal, so do not put a
            // spinner over playback that is about to continue.
            if (player.bufferedPosition > player.currentPosition) {
                _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
            } else {
                _simpleMediaState.value =
                    SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
            }
        } else {
            if (player.playbackState == PlayerConstants.STATE_BUFFERING ||
                player.playbackState == PlayerConstants.STATE_IDLE ||
                (player.currentPosition == 0L && player.bufferedPosition == 0L && !player.isPlaying)
            ) {
                // The loader stopped because it cannot proceed (no network, a retry
                // backoff), not because the track is ready. Publishing Ready here
                // would clear the loading flag and leave the player UI showing a
                // static bar over a stall that is still waiting on the network.
                // The same holds when the adapter reports STATE_IDLE (its ERROR
                // state maps to IDLE) and when it reports STATE_READY with zero
                // bytes buffered and nothing playing — a load that died before it
                // started. Stay silent instead: the last Loading/Buffering state
                // stands — and the buffered poll keeps running — until the player
                // really reaches STATE_READY with something buffered, or the
                // track changes.
                return
            }
            stopBufferedUpdate()
            _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
        }
    }

    override fun onCrossfadeStateChanged(isCrossfading: Boolean) {
        _controlState.update {
            it.copy(isCrossfading = isCrossfading)
        }
        // A radio batch lands 1–3 s after the transition that asked for it, which with crossfade on
        // is mid-fade, and the adapters refuse to trim while fading (the fade remembers the track it
        // may revert to as a playlist POSITION). Retry the moment the fade ends, or users with
        // crossfade on would never have their radio history trimmed at all.
        //
        // Posted, never run inline: this is called from INSIDE the adapter — e.g. while Next commits
        // the incoming track mid-fade and then posts a seek to an index it has just computed. Asking
        // for the removal right here would queue it AHEAD of that seek, shift the playlist under it,
        // and the seek would land ~47 tracks later.
        if (!isCrossfading) coroutineScope.launch { trimRadioHistoryIfNeeded() }
    }

    override fun onTimelineChanged(
        list: List<GenericMediaItem>,
        reason: String,
    ) {
        super.onTimelineChanged(list, reason)
        Logger.d(TAG, "onTimelineChanged: Reason: $reason, Items: ${list.size}")
        reorderShuffledQueue(list)
    }

    override fun onCastStateChanged(castState: GenericCastState) {
        _castState.value = castState
    }

    private fun reorderShuffledQueue(list: List<GenericMediaItem>) {
        val listTrack = queueData.value.data.listTracks
        if (list.isEmpty()) {
            Logger.d(TAG, "Reordering shuffled queue: empty list")
            return
        }
        val buckets = HashMap<String, ArrayDeque<Track>>(listTrack.size)
        listTrack.forEach { track ->
            buckets.getOrPut(track.videoId) { ArrayDeque() }.addLast(track)
        }
        val sorted =
            list.mapNotNull { item ->
                val baseId = item.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                buckets[baseId]?.removeFirstOrNull()
            }
        if (sorted.size != listTrack.size) return
        Logger.d(TAG, "Reordering shuffled queue: player ${list.size}, queue ${listTrack.size}")
        _queueData.update {
            // Only over the list this was computed from.
            if (it.data.listTracks !== listTrack) {
                it
            } else {
                it.copy(
                    data =
                        it.data.copy(
                            listTracks = sorted,
                        ),
                )
            }
        }
    }

    /**
     * True from the moment a radio trim is asked for until the player answers it. Two requests in
     * flight would both be computed from the same untrimmed queue, and the second would pass every
     * guard in the adapter and cut again. An atomic rather than a plain flag so this mirrors the
     * Desktop handler, where Add to queue calls in from the UI thread while the player thread does
     * too; here both run on the main thread.
     */
    private val radioTrimInFlight = AtomicBoolean(false)

    /**
     * Asks the player to drop the oldest played tracks of a radio queue.
     *
     * A radio grows without end and everything that walks the queue gets more expensive with it
     * (#2504). A playlist or album is only trimmed once endless queue has carried it past the list
     * the user picked, at which point it is re-typed as radio; see [RadioQueueTrim.appliesTo] for
     * why the type alone does not decide it.
     *
     * Nothing is asked unless the player's list and [queueData] are aligned and the ids at the
     * front match. [queueData] is not touched here at all: the adapter applies the removal later on
     * its own thread, may refuse it there, and reports what it actually did to
     * [onRadioHistoryRemoved] from inside the removal.
     */
    private fun trimRadioHistoryIfNeeded() {
        if (radioTrimInFlight.get()) return
        val data = queueData.value.data
        if (!RadioQueueTrim.appliesTo(data.playlistType, data.playlistId)) return
        // Shuffle splits the two index spaces: `currentMediaItemIndex` and the indices
        // `removeMediaItems` takes both count the player's UNSHUFFLED playlist, while the timeline
        // and `listTracks` are in shuffled order. The oldest-played tracks are then not a range in
        // the playlist at all, so trimming by index there would delete upcoming tracks. Radio with
        // shuffle on simply keeps its full history.
        if (player.shuffleModeEnabled) return
        val listTracks = data.listTracks
        val playerItems = player.getCurrentMediaTimeLine()
        if (playerItems.size != listTracks.size) return
        val drop = RadioQueueTrim.countToDropFromFront(player.currentMediaItemIndex, listTracks.size)
        if (drop <= 0) return
        if (playerItems.take(drop).map { it.mediaId } != listTracks.take(drop).map { it.videoId }) return
        if (!radioTrimInFlight.compareAndSet(false, true)) return
        Logger.d(TAG, "Radio trim requested: dropping $drop of ${listTracks.size}")
        player.removeMediaItems(0, drop) { removedIds -> onRadioHistoryRemoved(removedIds) }
    }

    /**
     * The player's answer to [trimRadioHistoryIfNeeded], called on its thread from inside the
     * removal — so [queueData] is cut in the same step as the player's list, and never on a guess
     * made from a later timeline event.
     */
    private fun onRadioHistoryRemoved(removedIds: List<String>) {
        radioTrimInFlight.set(false)
        if (removedIds.isEmpty()) return
        var cut = false
        _queueData.update { state ->
            val remaining = RadioQueueTrim.afterFrontRemoved(state.data.listTracks, removedIds) { it.videoId }
            cut = remaining != null
            if (remaining == null) state else state.copy(data = state.data.copy(listTracks = remaining))
        }
        if (cut) {
            Logger.d(TAG, "Trimmed radio history: dropped ${removedIds.size}")
        } else {
            // ponytail: only a queue edited in the few ms between the request and the removal lands
            // here, and it is left alone — rebuilding it from the player could clobber a queue the
            // user has just started. If this log shows up in practice, reconcile by id once it settles.
            Logger.e(TAG, "Radio trim: the ${removedIds.size} tracks the player dropped no longer lead the queue")
        }
    }
}

private fun isAppInForeground(): Boolean {
    val appProcessInfo = RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(appProcessInfo)
    return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
