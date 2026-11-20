package com.maxrave.media3.exoplayer

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.EightDEffect
import com.maxrave.domain.data.player.EightDOrbit
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.media3.di.StreamResolveCoordinator
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.mediaservice.automix.TrackStructure
import com.maxrave.domain.mediaservice.automix.TransitionPlan
import com.maxrave.domain.mediaservice.automix.TransitionPlanner
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.audio.BiquadFilter
import com.maxrave.media3.waveform.WaveformReader
import com.maxrave.media3.cache.TrackCachePreloader
import com.maxrave.media3.audio.ConvolutionReverbAudioProcessor
import com.maxrave.media3.audio.CrossfadeFilterAudioProcessor
import com.maxrave.media3.audio.EchoAudioProcessor
import com.maxrave.media3.audio.EightDAudioProcessor
import com.maxrave.media3.audio.EqualizerAudioProcessor
import com.maxrave.media3.audio.EqualizerCurve
import com.maxrave.media3.audio.SleepFadeAudioProcessor
import com.maxrave.media3.service.mediasourcefactory.isLiveStreamDetected
import com.maxrave.media3.service.mediasourcefactory.isLiveStreamEnded
import com.maxrave.media3.service.mediasourcefactory.isUnrecoverableStreamFailure
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter.Companion.SPEED_PITCH_STEP
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "CrossfadeExoPlayerAdapter"

/**
 * ExoPlayer implementation of [MediaPlayerInterface] with crossfade support.
 *
 * Architecture mirrors [com.simpmusic.media_jvm.GstreamerPlayerAdapter]:
 * - Internal playlist management (not ExoPlayer's playlist)
 * - Multi-player instance model: each track gets its own ExoPlayer
 * - Precaching system for smooth transitions
 * - Crossfade with listener swap pattern
 * - [DelegatingForwardingPlayer] for MediaSession integration
 *
 * Key difference from GstreamerPlayerAdapter:
 * - Uses [MergingMediaSourceFactory] + ResolvingDataSource for URL resolution
 *   (instead of manually extracting URLs via StreamRepository)
 * - Each ExoPlayer gets a single [MediaItem] and auto-resolves the stream URL
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
internal class CrossfadeExoPlayerAdapter(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val mediaSourceFactory: MergingMediaSourceFactory,
    private val audioAttributes: AudioAttributes,
    private val streamRepository: StreamRepository,
    private val waveformReader: WaveformReader,
    private val trackCachePreloader: TrackCachePreloader,
    private val streamResolveCoordinator: StreamResolveCoordinator? = null,
) : MediaPlayerInterface {
    // ========== Internal State Enum (same as GstreamerPlayerAdapter) ==========

    private enum class InternalState {
        IDLE, // No media loaded
        PREPARING, // Loading media
        READY, // Ready to play/paused
        PLAYING, // Currently playing
        PAUSED,
        ENDED, // Playback ended
        ERROR, // Error state
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    // This adapter's own job on the shared service scope. Everything the adapter launches runs
    // under it, so release() cancels only the adapter's work. The injected [coroutineScope] is
    // the SERVICE_SCOPE singleton shared with the preloader, cast, handler and repositories:
    // cancelling it (the old release() behaviour) permanently killed all of them for the rest
    // of the process — no exception, no log, playback simply never responded again.
    private val adapterJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val adapterScope: CoroutineScope = CoroutineScope(coroutineScope.coroutineContext + adapterJob)

    // ========== Crossfade Settings (loaded from DataStore) ==========

    init {
        adapterScope.launch {
            dataStoreManager.crossfadeEnabled.collect { enabled ->
                crossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Crossfade enabled: $crossfadeEnabled")
            }
        }
        adapterScope.launch {
            dataStoreManager.crossfadeDuration.collect { duration ->
                crossfadeDurationMs = duration
                Logger.d(TAG, "Crossfade duration: $crossfadeDurationMs ms")
            }
        }
        adapterScope.launch {
            dataStoreManager.crossfadeDjMode.collect { enabled ->
                djCrossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ crossfade mode: $djCrossfadeEnabled")
            }
        }
        adapterScope.launch {
            dataStoreManager.watchVideoInsteadOfPlayingAudio.collect { enabled ->
                watchVideoEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Watch video enabled: $watchVideoEnabled")
            }
        }
        adapterScope.launch {
            dataStoreManager.crossfadeSkipAlbum.collect { enabled ->
                skipCrossfadeInAlbum = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Skip crossfade inside album: $skipCrossfadeInAlbum")
            }
        }
        adapterScope.launch {
            var lastQuality: String? = null
            dataStoreManager.quality.distinctUntilChanged().collect { quality ->
                val previous = lastQuality
                lastQuality = quality
                if (previous != null && previous != quality) {
                    clearPrecacheExceptCurrentInternal()
                }
            }
        }
    }

    // ========== State Management ==========

    private val listeners = CopyOnWriteArrayList<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: ExoPlayer? = null

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    /** When the current track last entered PAUSED; play() reads it for the long-pause reload. */
    @Volatile
    private var pausedAtMs = 0L

    @Volatile
    private var internalVolume = 1.0f

    /**
     * Sleep-timer fade attenuation. Applied on a separate volume line — every player's
     * [SleepFadeAudioProcessor] reads this value straight out of here, so writing it once covers
     * both players of a crossfade and nothing on the `volume` line has to be touched.
     */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    /**
     * The equalizer curve in force. Read the same way as [internalSleepFadeFactor]: every player's
     * [EqualizerAudioProcessor] samples it on every buffer, so one write covers both players of a
     * crossfade and every precached one without any of them having to be found first.
     */
    @Volatile
    private var internalEqualizerCurve: EqualizerCurve = EqualizerCurve.FLAT

    /**
     * The delay and reverb settings in force. Read exactly like [internalEqualizerCurve]: every
     * player's [EchoAudioProcessor] and [ConvolutionReverbAudioProcessor] sample it on every
     * buffer, so one write covers both players of a crossfade and every precached one without any
     * of them having to be found first.
     */
    @Volatile
    private var internalAudioEffects: AudioEffects = AudioEffects.NONE

    private val eightDOrbit = EightDOrbit()
    override val eightDAvailable: StateFlow<Boolean> = MutableStateFlow(true)

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalSkipSilence = false

    // Position tracking - updated periodically, not on every query
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    private var positionUpdateJob: Job? = null

    // Active Player.Listener (equivalent to BusListeners in GstreamerPlayerAdapter)
    // Only ONE listener instance, attached to ONE ExoPlayer at a time.
    // Swapped between players during crossfade.
    private var activePlayerListener: Player.Listener? = null

    // ========== Audio Focus (manual, session-scoped) — #2155 ==========
    // The multi-player swap model means audio focus must NOT be tied to any single
    // ExoPlayer: releasing the outgoing player would abandon focus, and the incoming
    // player (built with handleAudioFocus=false) never re-requests it — the root cause
    // of "music stops between tracks" (see androidx/media#2100). Instead we hold one
    // app-level AudioFocusRequest at the adapter level so focus survives every swap.

    private val duckVolumeFactor = 0.2f

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @Volatile
    private var hasAudioFocus = false

    /** True when focus was lost transiently so playback should auto-resume on regain. */
    @Volatile
    private var resumeOnFocusGain = false

    @Volatile
    private var isDucked = false

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    isDucked = false
                    // Don't fight the crossfade ramp; while crossfading it owns the volume (and reads isDucked).
                    if (!isCrossfading) currentPlayer?.volume = internalVolume
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        play()
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss (another app took over): pause and stop tracking focus.
                    isDucked = false
                    resumeOnFocusGain = false
                    hasAudioFocus = false
                    pauseInternal(fromFocusLoss = true)
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Temporary loss (e.g. an incoming call): pause and remember to resume
                    // both when playing and when preparing with intent to play.
                    resumeOnFocusGain =
                        internalState == InternalState.PLAYING ||
                        (internalState == InternalState.PREPARING && internalPlayWhenReady)
                    pauseInternal(fromFocusLoss = true)
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Lower the volume instead of pausing (e.g. a navigation prompt).
                    // Skip direct assignment during crossfade — the ramp reads isDucked per step.
                    isDucked = true
                    if (!isCrossfading) currentPlayer?.volume = internalVolume * duckVolumeFactor
                }
            }
        }

    private val audioFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes
                    .Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
    }

    /** Request app-level audio focus once; idempotent while focus is held. */
    private fun requestAudioFocusInternal(): Boolean {
        val am = audioManager ?: return true
        if (hasAudioFocus) return true
        val granted = am.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        Logger.d(TAG, "requestAudioFocus -> granted=$granted")
        return granted
    }

    private fun abandonAudioFocusInternal() {
        val am = audioManager ?: return
        isDucked = false
        if (!hasAudioFocus) return
        am.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        resumeOnFocusGain = false
        Logger.d(TAG, "abandonAudioFocus")
    }

    // ========== Precaching System ==========

    private data class PrecachedPlayer(
        val player: ExoPlayer,
        val mediaItem: GenericMediaItem,
        val filter: CrossfadeFilterAudioProcessor? = null,
        /** Retries this player while it waits; taken off when it is promoted (see [precacheRetryListener]). */
        val retryListener: Player.Listener? = null,
    )

    // VideoId -> PrecachedPlayer
    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()

    // ========== Audio Output ==========

    /**
     * Where the sound leaves by — see [MediaPlayerInterface.audioOutputs]. The picked device goes to
     * every player this adapter runs, and [createExoPlayerInstance] gives it to each new one, so the
     * next track, a crossfade partner or a precached handle cannot slip back to the old output.
     * Declared ahead of the first player built below, which reads it.
     */
    private val outputRouter = AudioOutputRouter(context) { device -> applyPreferredAudioDevice(device) }

    override val audioOutputs: StateFlow<List<AudioOutput>>
        get() = outputRouter.outputs

    override fun selectAudioOutput(id: String?) = outputRouter.select(id)

    override suspend fun loadWaveform(
        mediaId: String,
        onProgress: (FloatArray) -> Unit,
    ): FloatArray? = waveformReader.read(mediaId, onProgress)

    override fun refreshAudioOutputs() = outputRouter.refresh()

    private fun applyPreferredAudioDevice(device: AudioDeviceInfo?) {
        adapterScope.launch {
            currentPlayer?.setPreferredAudioDevice(device)
            secondaryPlayer?.setPreferredAudioDevice(device)
            precachedPlayers.values.forEach { it.player.setPreferredAudioDevice(device) }
        }
    }
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // ========== Crossfade System ==========

    @Volatile
    private var crossfadeEnabled = false

    /** See MediaPlayerInterface.crossfadeSuppressed — set while in a Listen Together room. */
    override var crossfadeSuppressed: Boolean = false

    @Volatile
    private var crossfadeDurationMs = 5000

    @Volatile
    private var djCrossfadeEnabled = true

    // Whether video content plays as video (watch-video setting) — the same condition
    // MergingMediaSourceFactory uses to build a merged audio+video source.
    @Volatile
    private var watchVideoEnabled = false

    /** User setting: leave transitions inside an album alone. */
    @Volatile
    private var skipCrossfadeInAlbum = false

    /** Set by the handler when an album is loaded; empty for every other kind of queue. */
    @Volatile
    private var internalAlbumTrackIds: Set<String> = emptySet()

    @Volatile
    private var secondaryPlayer: ExoPlayer? = null

    @Volatile
    private var crossfadeJob: Job? = null

    @Volatile
    private var crossfadeTransitionJob: Job? = null

    @Volatile
    private var isCrossfading = false

    // Per-player filter references for DJ-style crossfade
    @Volatile
    private var currentPlayerFilter: CrossfadeFilterAudioProcessor? = null

    @Volatile
    private var secondaryPlayerFilter: CrossfadeFilterAudioProcessor? = null

    /** Index we're crossfading from; used when cancelling to revert localCurrentMediaItemIndex. */
    @Volatile
    private var crossfadeFromIndex = -1

    // Class-scoped stall watchdog counter so re-resolution reloads do not reset the retry cap
    private var watchdogFireCount = 0
    private var watchdogVideoId: String? = null

    // ========== Retry on Source Error ==========
    // Track retry attempts per media item to avoid infinite retry loops
    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    // ========== AutoMix Metadata Cache ==========
    // videoId -> audio analysis data from Tidal (populated externally when 320kbps stream is fetched)
    private val audioMetaCache = ConcurrentHashMap<String, SongAudioMeta>()

    // AutoMix structure: tempo, bars, drop and key measured from each track's own audio, and the
    // transition placed by them (TransitionPlanner). Both fill in while the track plays.
    private val trackStructures = ConcurrentHashMap<String, TrackStructure>()
    private val structureAttempts = ConcurrentHashMap<String, Int>()
    private val structureRetryAt = ConcurrentHashMap<String, Long>()
    private var structureJob: Job? = null
    private val transitionPlans = ConcurrentHashMap<String, TransitionPlan>()
    private val pairsWithoutPlan: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Update crossfade state and notify listeners when it changes.
     */
    private fun setCrossfading(value: Boolean) {
        if (isCrossfading != value) {
            isCrossfading = value
            listeners.forEach { it.onCrossfadeStateChanged(value) }
        }
    }

    // ========== Playlist Management ==========

    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    /**
     * Tracks that keep their place right after the current one when a track is appended under
     * shuffle: one put there with Play next, and the one a crossfade has picked. Held by identity,
     * since a queue can hold the same song twice.
     */
    private val pinnedNextItems: MutableSet<GenericMediaItem> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<GenericMediaItem, Boolean>()))

    // Loading management
    private var currentLoadJob: Job? = null
    private var loadingMediaId: String? = null

    private inline fun runOnAdapterMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            adapterScope.launch { block() }
        }
    }

    // ========== ForwardingPlayer for MediaSession ==========

    // Create an initial idle ExoPlayer for MediaSession to hold
    private val initialPlayerWithFilter = createExoPlayerInstance()

    /**
     * Stable [Player] reference for MediaSession.
     * Delegates all calls to the currently active [ExoPlayer] instance.
     * Updated via [DelegatingForwardingPlayer.swapDelegate] when the active player changes.
     */
    val forwardingPlayer: DelegatingForwardingPlayer = DelegatingForwardingPlayer(initialPlayerWithFilter.player)

    init {
        currentPlayer = initialPlayerWithFilter.player
        currentPlayerFilter = initialPlayerWithFilter.filter

        // Wire up playlist navigation so ForwardingPlayer (and thus MediaSession)
        // can see the full playlist state instead of the single-item ExoPlayer state.
        // Only navigation commands are overridden — NOT getMediaItemCount/getCurrentMediaItemIndex
        // which must stay consistent with ExoPlayer's internal Timeline to avoid crashes.
        forwardingPlayer.playlistNavigationProvider =
            object : DelegatingForwardingPlayer.PlaylistNavigationProvider {
                override fun hasNextMediaItem(): Boolean = this@CrossfadeExoPlayerAdapter.hasNextMediaItem()

                override fun hasPreviousMediaItem(): Boolean = this@CrossfadeExoPlayerAdapter.hasPreviousMediaItem()

                override fun seekToNext(): Unit = this@CrossfadeExoPlayerAdapter.seekToNext()

                override fun seekToPrevious(): Unit = this@CrossfadeExoPlayerAdapter.seekToPrevious()

                override fun seekToPreviousMediaItem(): Unit = this@CrossfadeExoPlayerAdapter.seekToPreviousMediaItem()

                override fun play(): Unit = this@CrossfadeExoPlayerAdapter.play()

                override fun pause(): Unit = this@CrossfadeExoPlayerAdapter.pause()

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    this@CrossfadeExoPlayerAdapter.playWhenReady = playWhenReady
                }

                override fun stop(): Unit = this@CrossfadeExoPlayerAdapter.stop()

                override fun getRepeatMode(): Int = this@CrossfadeExoPlayerAdapter.repeatMode

                override fun setRepeatMode(repeatMode: Int) {
                    this@CrossfadeExoPlayerAdapter.repeatMode = repeatMode
                }

                override fun getShuffleModeEnabled(): Boolean = this@CrossfadeExoPlayerAdapter.shuffleModeEnabled

                override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
                    this@CrossfadeExoPlayerAdapter.shuffleModeEnabled = shuffleModeEnabled
                }

                override fun getPlaybackParameters(): PlaybackParameters =
                    PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)

                override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
                    this@CrossfadeExoPlayerAdapter.playbackParameters =
                        GenericPlaybackParameters(playbackParameters.speed, playbackParameters.pitch)
                }

                override fun setPlaybackSpeed(speed: Float) {
                    this@CrossfadeExoPlayerAdapter.playbackParameters =
                        GenericPlaybackParameters(speed, internalPlaybackPitch)
                }

                override fun seekTo(positionMs: Long): Unit = this@CrossfadeExoPlayerAdapter.seekTo(positionMs)

                override fun seekBack(): Unit = this@CrossfadeExoPlayerAdapter.seekBack()

                override fun seekForward(): Unit = this@CrossfadeExoPlayerAdapter.seekForward()
            }
    }

    // ========== Cast Remote Routing ==========

    /**
     * While a Cast session is active this holds the session-level [Player] (the unified
     * CastPlayer wrapping [forwardingPlayer]): transport calls and position/state getters
     * are routed to it, and playback-start requests are handed to [castPlaybackRouter]
     * instead of the local ExoPlayer machinery. The playlist itself stays local — the
     * receiver only ever sees a small resolved-URL window of it.
     */
    @Volatile
    private var castRemotePlayer: Player? = null

    internal val isCastActive: Boolean
        get() = castRemotePlayer != null

    /** Set by CastHandoffManager: (playlistIndex, startPositionMs, playWhenReady) -> load on receiver. */
    internal var castPlaybackRouter: ((Int, Long, Boolean) -> Unit)? = null

    internal fun setCastActive(
        remotePlayer: Player?,
        deviceName: String?,
    ) {
        if (remotePlayer != null) {
            if (castRemotePlayer === remotePlayer) return
            castRemotePlayer = remotePlayer
            isDucked = false
            Logger.w(TAG, "Cast session active on ${deviceName ?: "unknown device"} — local playback suspended")
            runOnAdapterMain {
                // Kill anything that makes local noise or wastes battery while remote, committing
                // any active crossfade cleanly so forwardingPlayer never holds a released player.
                if (isCrossfading || crossfadeTransitionJob?.isActive == true) {
                    commitIncomingAsCurrentInternal()
                }
                currentLoadJob?.cancel()
                currentLoadJob = null
                cancelPrecaching()
                clearAllPrecacheInternal()
                currentPlayer?.pause()
                stopPositionUpdates()
                abandonAudioFocusInternal()
                notifyEqualizerIntent(false)
            }
            listeners.forEach { it.onCastStateChanged(GenericCastState(isRemote = true, deviceName = deviceName)) }
        } else {
            if (castRemotePlayer == null) return
            castRemotePlayer = null
            Logger.w(TAG, "Cast session ended — back to local playback")
            listeners.forEach { it.onCastStateChanged(GenericCastState.NOT_CASTING) }
        }
    }

    /** Remote queue advanced — keep the local playlist pointer and UI in sync. */
    internal fun notifyRemoteTransition(playlistIndex: Int) {
        if (!isCastActive || playlistIndex !in playlist.indices) return
        if (playlistIndex == localCurrentMediaItemIndex) return
        localCurrentMediaItemIndex = playlistIndex
        unpinCurrent()
        val item = playlist[playlistIndex]
        listeners.forEach {
            it.onMediaItemTransition(item, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
    }

    internal fun notifyRemoteIsPlaying(
        isPlaying: Boolean,
        playWhenReady: Boolean = isPlaying,
    ) {
        if (!isCastActive) return
        internalPlayWhenReady = playWhenReady
        listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    internal fun notifyRemotePlayWhenReady(playWhenReady: Boolean) {
        if (!isCastActive) return
        internalPlayWhenReady = playWhenReady
    }

    internal fun notifyRemotePlaybackState(playbackState: Int) {
        if (!isCastActive) return
        listeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }

    // ========== ExoPlayer Instance Factory ==========

    /**
     * Result of creating an ExoPlayer instance, bundled with its per-player crossfade filter.
     */
    private data class PlayerWithFilter(
        val player: ExoPlayer,
        val filter: CrossfadeFilterAudioProcessor,
    )

    /**
     * Create a new ExoPlayer instance with a per-player [CrossfadeFilterAudioProcessor].
     *
     * Each player gets its own filter instance so the fade-out player can have
     * an independent low-pass filter while the fade-in player has a high-pass filter.
     *
     * Audio focus is NOT handled per-player: it is managed once at the adapter level
     * (see the Audio Focus section) so it survives every player swap (#2155).
     */
    private fun createExoPlayerInstance(mediaId: String? = null): PlayerWithFilter {
        val crossfadeFilter = CrossfadeFilterAudioProcessor()
        val sleepFade = SleepFadeAudioProcessor { internalSleepFadeFactor }
        val equalizer = EqualizerAudioProcessor { internalEqualizerCurve }
        val echo = EchoAudioProcessor { internalAudioEffects }
        val reverb = ConvolutionReverbAudioProcessor { internalAudioEffects }
        val eightD = EightDAudioProcessor(eightDOrbit)

        val perPlayerRenderers =
            object : DefaultRenderersFactory(context) {
                init {
                    // A platform decoder that fails to init or decode (seen on Exynos
                    // AAC/Opus) gets a second chance on another decoder instead of
                    // failing the track outright.
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
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            object : DefaultAudioSink.DefaultAudioProcessorChain(
                                // Equalizer first, matching the desktop graph, where the ten
                                // `equalizer` entries sit ahead of everything else in mpv's `af`
                                // list — it decides which stage a boost can clip in, and the
                                // preamp that makes room for the boost belongs with it. Then the
                                // two effects, and only then the crossfade filter: an echo tail
                                // and a reverb tail belong to the track that caused them, so the
                                // crossfade has to be able to sweep them out along with it rather
                                // than the other way round. The sleep fade is the master
                                // attenuation; only the 8D pan, a gain matrix it commutes with,
                                // comes after it (see below).
                                arrayOf(equalizer, echo, reverb, crossfadeFilter, sleepFade),
                                SilenceSkippingAudioProcessor(
                                    2_000_000,
                                    20_000f / 2_000_000f,
                                    2_000_000,
                                    0,
                                    256,
                                ),
                                SonicAudioProcessor(),
                            ) {
                                // Keep the existing chain/timing methods, but rotate the final
                                // PCM after Sonic. One turn then lasts the selected wall-clock
                                // duration even when playback speed or DJ tempo changes.
                                // DefaultAudioSink builds its pipeline from getAudioProcessors()
                                // (media3 1.11.1 DefaultAudioSink.java:759).
                                private val processorsWithEightD = super.getAudioProcessors() + eightD

                                override fun getAudioProcessors(): Array<AudioProcessor> = processorsWithEightD
                            },
                        ).build().let { waveformReader.playbackSink(it, mediaId) }
            }

        val player =
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(audioAttributes, false)
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * 4,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 4,
                            0,
                            0,
                        ).build(),
                ).setWakeMode(C.WAKE_MODE_NETWORK)
                .setHandleAudioBecomingNoisy(true)
                .setSeekForwardIncrementMs(5000)
                .setSeekBackIncrementMs(5000)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(perPlayerRenderers)
                .build()

        // The output the user picked, if any: a player built for the next track, a crossfade or a
        // precache starts where the others are playing rather than back on the system's choice.
        outputRouter.preferredDevice?.let { player.setPreferredAudioDevice(it) }

        // Every way a player starts (play(), a precache promoted, a crossfade's incoming player)
        // goes through playWhenReady, so 8D learns it here without being threaded through them all.
        player.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (playWhenReady) eightD.markStarted()
                }
            },
        )

        return PlayerWithFilter(player, crossfadeFilter)
    }

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        castRemotePlayer?.let { remote ->
            internalPlayWhenReady = true
            remote.play()
            return
        }
        runOnAdapterMain {
            when (internalState) {
                InternalState.IDLE -> {
                    val idleIndex = localCurrentMediaItemIndex
                    if (idleIndex in playlist.indices) {
                        Logger.d(TAG, "Play: loading track from IDLE state at index=$idleIndex")
                        internalPlayWhenReady = true
                        pausedAtMs = 0L
                        retryCount = 0
                        retryVideoId = null
                        watchdogFireCount = 0
                        loadAndPlayTrackInternal(idleIndex, cachedPosition.coerceAtLeast(0L), shouldPlay = true)
                    } else {
                        Logger.w(TAG, "Play: Called in IDLE state with empty playlist or invalid index")
                    }
                }

                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    val longPauseVideoId =
                        if (internalState == InternalState.PAUSED &&
                            pausedAtMs > 0L &&
                            System.currentTimeMillis() - pausedAtMs > LONG_PAUSE_RESUME_MS
                        ) {
                            playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                        } else {
                            null
                        }
                    if (longPauseVideoId != null) {
                        Logger.w(TAG, "Play: resuming after a long pause; reloading $longPauseVideoId with a fresh URL")
                        internalPlayWhenReady = true
                        pausedAtMs = 0L
                        retryCount = 0
                        retryVideoId = null
                        watchdogFireCount = 0
                        watchdogVideoId = longPauseVideoId
                        val resumePos = cachedPosition.coerceAtLeast(0L)
                        currentLoadJob?.cancel()
                        currentLoadJob =
                            adapterScope.launch {
                                val targetIdx =
                                    if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId == longPauseVideoId) {
                                        localCurrentMediaItemIndex
                                    } else {
                                        return@launch
                                    }
                                try {
                                    streamRepository.invalidateFormat(longPauseVideoId)
                                    streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$longPauseVideoId")
                                    precachedPlayers.remove(longPauseVideoId)?.let { cleanupPrecachedEntryInternal(it) }
                                    val freshIdx =
                                        if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId == longPauseVideoId) {
                                            localCurrentMediaItemIndex
                                        } else {
                                            -1
                                        }
                                    if (freshIdx >= 0) {
                                        loadAndPlayTrackInternal(freshIdx, resumePos, shouldPlay = internalPlayWhenReady)
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Play: reload after long pause failed: ${e.message}", e)
                                }
                            }
                    } else {
                        currentPlayer?.let { player ->
                            if (!requestAudioFocusInternal()) {
                                Logger.w(TAG, "Play: audio focus denied; not starting playback")
                                resumeOnFocusGain = true
                                return@let
                            }
                            if (internalState == InternalState.ENDED) {
                                Logger.d(TAG, "Play: replaying from the start after end of queue")
                                player.seekTo(0L)
                                watchdogFireCount = 0
                            }
                            pausedAtMs = 0L
                            player.volume = if (isDucked) internalVolume * duckVolumeFactor else internalVolume
                            player.play()
                            transitionToState(InternalState.PLAYING)
                            internalPlayWhenReady = true
                        } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                    }
                }

                InternalState.PREPARING -> {
                    internalPlayWhenReady = true
                    currentPlayer?.takeIf { it.playbackState != Player.STATE_ENDED }?.let { player ->
                        if (!requestAudioFocusInternal()) {
                            Logger.w(TAG, "Play: audio focus denied during PREPARING; will resume on focus gain")
                            resumeOnFocusGain = true
                            internalPlayWhenReady = false
                            player.pause()
                        } else {
                            player.volume = if (isDucked) internalVolume * duckVolumeFactor else internalVolume
                            player.play()
                            transitionToState(InternalState.PLAYING)
                        }
                    }
                    Logger.d(TAG, "Play: During PREPARING - playWhenReady=$internalPlayWhenReady")
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                    if (currentPlayer?.playbackState != Player.STATE_BUFFERING && cachedIsLoading) {
                        cachedIsLoading = false
                        listeners.forEach { it.onIsLoadingChanged(false) }
                    }
                }

                InternalState.ERROR -> {
                    val errorVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                    if (errorVideoId != null) {
                        Logger.w(TAG, "Play: reloading current track from ERROR state")
                        internalPlayWhenReady = true
                        retryCount = 0
                        retryVideoId = null
                        watchdogFireCount = 0
                        watchdogVideoId = errorVideoId
                        val resumePos = cachedPosition.coerceAtLeast(0L)
                        currentLoadJob?.cancel()
                        currentLoadJob =
                            adapterScope.launch {
                                if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId != errorVideoId) {
                                    return@launch
                                }
                                try {
                                    streamRepository.onStreamRejected(errorVideoId)
                                    streamRepository.invalidateFormat(errorVideoId)
                                    streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$errorVideoId")
                                    precachedPlayers.remove(errorVideoId)?.let { cleanupPrecachedEntryInternal(it) }
                                    val freshIdx =
                                        if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId == errorVideoId) {
                                            localCurrentMediaItemIndex
                                        } else {
                                            -1
                                        }
                                    if (freshIdx >= 0) {
                                        loadAndPlayTrackInternal(freshIdx, resumePos, shouldPlay = internalPlayWhenReady)
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Play: reload from ERROR failed: ${e.message}", e)
                                }
                            }
                    } else {
                        Logger.w(TAG, "Play: Called in ERROR state with no current track")
                    }
                }

                else -> {
                    Logger.w(TAG, "Play: Called in invalid state: $internalState")
                }
            }
        }
    }

    override fun pause() {
        pauseInternal(fromFocusLoss = false)
    }

    private fun pauseInternal(fromFocusLoss: Boolean) {
        Logger.d(TAG, "pause() called (state: $internalState, playWhenReady: $internalPlayWhenReady, focusLoss: $fromFocusLoss)")
        if (!fromFocusLoss) {
            resumeOnFocusGain = false
        }
        castRemotePlayer?.let { remote ->
            internalPlayWhenReady = false
            remote.pause()
            internalSleepFadeFactor = 1f
            return
        }
        runOnAdapterMain {
            try {
                forwardingPlayer.suppressPlaybackEnded = false
                if (isCrossfading || crossfadeTransitionJob?.isActive == true) {
                    Logger.d(TAG, "Pause: committing incoming (A+1) and pausing in place")
                    commitIncomingAsCurrentInternal()
                }

                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            player.pause()
                            transitionToState(InternalState.PAUSED)
                            internalPlayWhenReady = false
                        }
                    }

                    InternalState.PREPARING -> {
                        internalPlayWhenReady = false
                        currentPlayer?.pause()
                    }

                    else -> {
                        Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                    }
                }
            } finally {
                internalSleepFadeFactor = 1f
            }
        }
    }

    override fun stop() {
        resumeOnFocusGain = false
        castRemotePlayer?.let { remote ->
            remote.stop()
            return
        }
        runOnAdapterMain {
            forwardingPlayer.suppressPlaybackEnded = false
            currentLoadJob?.cancel()
            currentLoadJob = null
            loadingMediaId?.let { streamResolveCoordinator?.cancel(it) }
            loadingMediaId = null
            if (isCrossfading || crossfadeTransitionJob?.isActive == true) {
                commitIncomingAsCurrentInternal()
            }
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.currentMediaItem?.mediaId?.let { streamResolveCoordinator?.cancel(it) }
                player.stop()
                cachedPosition = 0L
                cachedBufferedPosition = 0L
                cachedIsLoading = false
                pausedAtMs = 0L
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
                abandonAudioFocusInternal()
                notifyEqualizerIntent(false)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        // A live broadcast is followed at its live edge: its seek bar shows LIVE, not a length.
        if (isCurrentTrackLive()) return
        castRemotePlayer?.let { remote ->
            remote.seekTo(positionMs)
            cachedPosition = positionMs
            return
        }
        // Reflected immediately so the progress bar does not snap back while the seek is queued.
        cachedPosition = positionMs
        runOnAdapterMain {
            val wasCrossfading = isCrossfading || crossfadeTransitionJob?.isActive == true
            if (wasCrossfading) {
                Logger.d(TAG, "seekTo: committing incoming (A+1) before seeking")
                commitIncomingAsCurrentInternal()
            }
            currentPlayer?.let { player ->
                try {
                    player.seekTo(positionMs)
                } catch (e: Exception) {
                    Logger.e(TAG, "Seek exception: ${e.message}", e)
                }
                if (wasCrossfading) {
                    startPositionUpdates()
                }
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        runOnAdapterMain {
            seekToInternal(mediaItemIndex, positionMs)
        }
    }

    private fun seekToInternal(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return
        val shouldPlay = internalPlayWhenReady
        val targetMediaId = playlist[mediaItemIndex].mediaId

        // Cancel any ongoing crossfade
        if (isCrossfading || crossfadeTransitionJob?.isActive == true) {
            Logger.d(TAG, "seekTo: Cancelling crossfade")
            cancelCrossfadeAsAbortedInternal(cancelJob = true, restoreDelegate = false)
        }

        // Cancel any ongoing load and reset retry/watchdog budgets for the user jump
        currentLoadJob?.cancel()
        currentLoadJob = null
        retryCount = 0
        retryVideoId = null
        watchdogFireCount = 0
        watchdogVideoId = targetMediaId

        // Load the new track
        localCurrentMediaItemIndex = mediaItemIndex
        unpinCurrent()
        loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
    }

    override fun seekBack() {
        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val end = duration.takeIf { it > 0 } ?: cachedDuration
        val newPosition = (currentPosition + 5000).coerceAtMost(end)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        runOnAdapterMain {
            // During crossfade A→A+1, "next" commits A+1 as current (Direction 1: the UI already
            // shows A+1) and then advances to A+2. Outside crossfade it advances normally.
            val wasCrossfading = isCrossfading
            if (wasCrossfading) {
                Logger.d(TAG, "seekToNext: committing incoming (A+1), then advancing to A+2")
                commitIncomingAsCurrentInternal()
            }
            if (hasNextMediaItem()) {
                seekToInternal(getNextMediaItemIndex(), 0)
            } else if (wasCrossfading) {
                // A+1 was the last track — stay on it (already promoted), just refresh metadata.
                forwardingPlayer.notifyMediaItemChanged()
            }
        }
    }

    override fun seekToPrevious() {
        runOnAdapterMain {
            // During a crossfade, commit the incoming track (A+1) as current FIRST, so the
            // 3-second rule below evaluates against A+1's position/index (Direction 1).
            if (isCrossfading) {
                Logger.d(TAG, "seekToPrevious: committing incoming (A+1) first")
                commitIncomingAsCurrentInternal()
            }

            // Standard music player behavior:
            // - Position > 3s  → seek to start of current track
            // - Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            val position = currentPosition
            // A live broadcast has no start to go back to, so Previous always means the previous track.
            if (position > positionThresholdMs && !isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms > ${positionThresholdMs}ms — seeking to start")
                seekTo(0)
            } else if (hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms <= ${positionThresholdMs}ms — going to previous track")
                val prevIndex = getPreviousMediaItemIndex()
                seekToInternal(prevIndex, 0)
            } else if (!isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: No previous item, seeking to start")
                seekTo(0)
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        runOnAdapterMain {
            // Mirror seekToPrevious(): commit the incoming track (A+1) as current first.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPreviousMediaItem: committing incoming (A+1) first")
                commitIncomingAsCurrentInternal()
            }

            // Always advance to the previous track regardless of `cachedPosition` —
            // skips the 3-second "seek to start" rule used by seekToPrevious().
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekToInternal(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    /**
     * An empty playlist that gains an item points at it, as ExoPlayer's own does. clearMediaItems()
     * leaves the index at -1 and nothing else moves it, so prepare() below loaded nothing and Add to
     * queue with nothing loaded (fresh start, or after closing the mini player) never played.
     */
    private fun pointAtFirstItemIfUnset() {
        if (localCurrentMediaItemIndex < 0 && playlist.isNotEmpty()) localCurrentMediaItemIndex = 0
    }

    override fun prepare() {
        runOnAdapterMain {
            val targetItem = playlist.getOrNull(localCurrentMediaItemIndex) ?: return@runOnAdapterMain
            val targetMediaId = targetItem.mediaId
            if (currentPlayer != null &&
                currentPlayer?.currentMediaItem?.mediaId == targetMediaId &&
                internalState != InternalState.IDLE &&
                internalState != InternalState.ERROR
            ) {
                Logger.d(TAG, "prepare: $targetMediaId already prepared/preparing ($internalState), skipping reload")
                return@runOnAdapterMain
            }
            loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        runOnAdapterMain {
            // Cancel ongoing operations
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        runOnAdapterMain {
            playlist.add(mediaItem)
            pointAtFirstItemIfUnset()

            if (internalShuffleModeEnabled) {
                insertAppendedIntoShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // Re-precache only when the appended track is one of the next ones Next plays. Under shuffle that
            // is its distance in the shuffled order, not in the playlist; re-triggering on every append of a
            // batch would cancel an in-flight extraction each time. The trigger keeps the precaches Next still
            // reaches and releases the rest, so it needs no clear first (that threw away the next player).
            val landsInPrecache =
                if (internalShuffleModeEnabled) {
                    val gap =
                        (shuffleIndices.getOrNull(playlist.lastIndex) ?: -1) -
                            (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1)
                    gap in 1..maxPrecacheCount
                } else {
                    localCurrentMediaItemIndex >= 0 &&
                        playlist.size - 1 - localCurrentMediaItemIndex <= maxPrecacheCount
                }
            if (landsInPrecache) {
                triggerPrecachingInternal()
            }
        }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        runOnAdapterMain {
            if (index !in 0..playlist.size) return@runOnAdapterMain
            val currentIndexBeforeInsert = localCurrentMediaItemIndex

            playlist.add(index, mediaItem)

            // Adjust current index if needed
            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
            pointAtFirstItemIfUnset()

            // Update shuffle order if enabled
            if (internalShuffleModeEnabled) {
                if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                    insertIntoShuffleOrder(index, currentShufflePos)
                    pinnedNextItems.add(mediaItem)
                } else {
                    createShuffleOrder()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // Same as the append above: under shuffle the insert may reorder what Next plays.
            if (internalShuffleModeEnabled ||
                (localCurrentMediaItemIndex >= 0 && index - 1 - localCurrentMediaItemIndex <= maxPrecacheCount)
            ) {
                triggerPrecachingInternal()
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        runOnAdapterMain {
            // Bounds checked inside adapter serialization: another mutation
            // can shrink the playlist before this runs — issue #2156's crash shape.
            if (index !in playlist.indices) return@runOnAdapterMain
            // Where the track sat in the shuffled order, read before the order loses it.
            val shuffledPos = if (internalShuffleModeEnabled) shuffleIndices.getOrNull(index) ?: -1 else -1
            val track = playlist.removeAt(index)
            // Only this track leaves the shuffled order, before anything below reads the order.
            // Rebuilding it here (createShuffleOrder) reshuffled the whole queue every time one track
            // was removed with shuffle on.
            if (internalShuffleModeEnabled) removeFromShuffleOrder(index)
            pinnedNextItems.remove(track)

            // Remove from precache
            precachedPlayers.remove(track.mediaId)?.let { cached ->
                cleanupPrecachedEntryInternal(cached)
            }

            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }

                index == localCurrentMediaItemIndex -> {
                    if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                        // The track after it in the shuffled order takes over, not the next one in
                        // the playlist: the removal shifted that track into the removed one's slot.
                        localCurrentMediaItemIndex = shuffleOrder[shuffledPos.coerceIn(0, shuffleOrder.lastIndex)]
                    } else if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    unpinCurrent()
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        cleanupCurrentPlayerInternal()
                    }
                }

                else -> {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    /**
     * Drops `[fromIndex, toIndex)` of already-played tracks in one pass: one precache pass, one
     * timeline notification, instead of repeating both per removed track (the cost shape of #2504).
     *
     * Indices count the UNSHUFFLED playlist, like [removeMediaItem] and [currentMediaItemIndex].
     * Only a range strictly BELOW the current track is accepted; anything else is refused rather
     * than guessed at, since getting it wrong stops playback. [onRemoved] hears back exactly once
     * either way, from inside this block — see [MediaPlayerInterface.removeMediaItems] for why.
     */
    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
        onRemoved: (removedIds: List<String>) -> Unit,
    ) {
        runOnAdapterMain {
            // Everything is re-checked against the current queue on Main: another
            // op (clearMediaItems / setMediaItem) can change the playlist before
            // this body runs, which is issue #2156's crash shape.
            val refused =
                fromIndex < 0 ||
                    toIndex <= fromIndex ||
                    toIndex > playlist.size ||
                    toIndex > localCurrentMediaItemIndex ||
                    // crossfadeFromIndex is a position in this same playlist and is NOT shifted here;
                    // a cancelled fade would revert to a track ~toIndex positions away.
                    isCrossfading ||
                    // With shuffle on, the tracks before the current one in this unshuffled list are
                    // not the played ones — they are part of what is still to come.
                    internalShuffleModeEnabled ||
                    // The receiver's queue window is mapped by absolute playlist positions
                    // (CastHandoffManager.remoteToPlaylist); shifting them sends it the wrong tracks.
                    isCastActive
            if (refused) {
                onRemoved(emptyList())
                return@runOnAdapterMain
            }

            val removed = playlist.subList(fromIndex, toIndex).toList()
            playlist.subList(fromIndex, toIndex).clear()
            localCurrentMediaItemIndex -= removed.size
            // Before anything else can run, so the caller cuts its copy of the queue in this same step.
            onRemoved(removed.map { it.mediaId })

            removed.forEach { track ->
                precachedPlayers.remove(track.mediaId)?.let { cached ->
                    cleanupPrecachedEntryInternal(cached)
                }
            }
            // Everything removed sits behind the current track and precache is keyed by mediaId,
            // so the window ahead needs no rebuild — clearing it here would throw away the handle
            // the next crossfade is about to use. This only tops it back up, which also restores
            // any handle an id repeated in the dropped history took with it.
            triggerPrecachingInternal()

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        runOnAdapterMain {
            // Same reason as removeMediaItem (issue #2156).
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return@runOnAdapterMain
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            // Update current index
            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> {
                        toIndex
                    }
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex - 1
                    }
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex + 1
                    }
                    else -> {
                        localCurrentMediaItemIndex
                    }
                }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun moveShuffledItem(
        fromShuffledIndex: Int,
        toShuffledIndex: Int,
    ) {
        runOnAdapterMain {
            // Same reason as removeMediaItem (issue #2156): checked against the order as it is now.
            if (!internalShuffleModeEnabled) return@runOnAdapterMain
            if (fromShuffledIndex !in shuffleOrder.indices || toShuffledIndex !in shuffleOrder.indices) return@runOnAdapterMain
            // Only the play order moves. Playlist indices stay where they were, and with them the
            // current index and the position a running crossfade would revert to.
            shuffleOrder.add(toShuffledIndex, shuffleOrder.removeAt(fromShuffledIndex))
            rebuildShuffleIndices()
            // Put right after the current track (Move to play next, or a drag), it keeps that slot
            // the way a Play next track does; moved anywhere else, it no longer holds one.
            playlist.getOrNull(shuffleOrder[toShuffledIndex])?.let { moved ->
                if (toShuffledIndex == (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) + 1) {
                    pinnedNextItems.add(moved)
                } else {
                    pinnedNextItems.remove(moved)
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // What plays next may have changed.
            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun clearMediaItems() {
        runOnAdapterMain {
            currentLoadJob?.cancel()
            loadingMediaId?.let { streamResolveCoordinator?.cancel(it) }
            loadingMediaId = null
            cancelPrecaching()
            playlist.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        runOnAdapterMain {
            // Same reason as removeMediaItem (issue #2156).
            if (index !in playlist.indices) return@runOnAdapterMain
            val oldItem = playlist[index]
            playlist[index] = mediaItem

            precachedPlayers.remove(oldItem.mediaId)?.let { cached ->
                cleanupPrecachedEntryInternal(cached)
            }
            if (mediaItem.mediaId != oldItem.mediaId) {
                precachedPlayers.remove(mediaItem.mediaId)?.let { cached ->
                    cleanupPrecachedEntryInternal(cached)
                }
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> =
        if (internalShuffleModeEnabled) {
            shuffleOrder.mapNotNull { shuffledIndex -> playlist.getOrNull(shuffledIndex) }
        } else {
            playlist.toList()
        }

    override fun getUnshuffledIndex(shuffledIndex: Int): Int =
        if (internalShuffleModeEnabled) {
            shuffleOrder.getOrNull(shuffledIndex) ?: -1
        } else {
            shuffledIndex
        }

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = castRemotePlayer?.isPlaying ?: (internalState == InternalState.PLAYING)

    // Read live, not from cachedIsLoading alone: that flag is only recalculated when the
    // raw player's isLoading toggles, so it can still read false at the exact moment a
    // stall is checked (entering a stall changes playbackState, not isLoading). The raw
    // playbackState stays STATE_BUFFERING for the whole stall, including the loader's
    // backoff gaps, so it is OR-ed in. While casting, the local player says nothing about
    // the receiver, so the stall signal is false there.
    override val isStalled: Boolean
        get() = castRemotePlayer?.let { false }
            ?: (cachedIsLoading ||
                (currentPlayer?.playbackState == Player.STATE_BUFFERING && internalPlayWhenReady))

    override val currentPosition: Long
        get() = castRemotePlayer?.currentPosition ?: cachedPosition

    override val duration: Long
        get() {
            castRemotePlayer?.let { remote ->
                return remote.duration.takeIf { it > 0 } ?: 0L
            }
            return (if (isCrossfading) secondaryPlayer ?: currentPlayer else currentPlayer)
                ?.duration
                ?.takeIf { it > 0L }
                ?: cachedDuration
        }

    override val bufferedPosition: Long
        get() = castRemotePlayer?.bufferedPosition ?: cachedBufferedPosition

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            if (dur <= 0) return 0
            return ((bufferedPosition * 100) / dur).toInt().coerceIn(0, 100)
        }

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int
        get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = castRemotePlayer?.contentPosition ?: cachedPosition

    override val playbackState: Int
        get() {
            // Media3 Player.STATE_* values match PlayerConstants 1:1.
            castRemotePlayer?.let { return it.playbackState }
            return when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }
        }

    // ========== Navigation ==========

    // Repeat One only loops a track that ends on its own (handleTrackEndInternal);
    // Next and Previous move through the queue as they do with repeat off.
    override fun hasNextMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> playlist.isNotEmpty()
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos =
                        shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    currentShufflePos >= 0 && currentShufflePos < shuffleOrder.lastIndex
                } else {
                    localCurrentMediaItemIndex < playlist.size - 1
                }
        }

    override fun hasPreviousMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> playlist.isNotEmpty()
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos =
                        shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    currentShufflePos > 0
                } else {
                    localCurrentMediaItemIndex > 0
                }
        }

    /** The index Next plays after [from]; [from] itself when nothing follows it. [from] defaults to the
     *  current track, and precaching walks it forward to reach the track after next. */
    private fun getNextMediaItemIndex(from: Int = localCurrentMediaItemIndex): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(from) ?: 0
                    val nextShufflePos = (currentShufflePos + 1) % shuffleOrder.size
                    shuffleOrder.getOrNull(nextShufflePos) ?: from
                } else {
                    if (from < playlist.size - 1) {
                        from + 1
                    } else {
                        0
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(from) ?: 0
                    val nextShufflePos = currentShufflePos + 1
                    if (nextShufflePos < shuffleOrder.size) {
                        shuffleOrder.getOrNull(nextShufflePos) ?: from
                    } else {
                        from
                    }
                } else {
                    (from + 1).coerceAtMost(playlist.size - 1)
                }
            }
        }

    private fun getPreviousMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos =
                        if (currentShufflePos > 0) {
                            currentShufflePos - 1
                        } else {
                            shuffleOrder.size - 1
                        }
                    shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex > 0) {
                        localCurrentMediaItemIndex - 1
                    } else {
                        playlist.size - 1
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos = currentShufflePos - 1
                    if (prevShufflePos >= 0) {
                        shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
                }
            }
        }

    // ========== Playback Modes ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            runOnAdapterMain {
                if (internalShuffleModeEnabled == value) return@runOnAdapterMain

                internalShuffleModeEnabled = value

                if (value) {
                    createShuffleOrder()
                } else {
                    clearShuffleOrder()
                }

                val mediaItemList = getShuffledMediaItemList()
                listeners.forEach { it.onShuffleModeEnabledChanged(value, mediaItemList) }
                forwardingPlayer.notifyShuffleModeEnabledChanged(value)
                notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

                clearPrecacheExceptCurrentInternal()
                triggerPrecachingInternal()

                Logger.d(TAG, "Shuffle mode ${if (value) "enabled" else "disabled"}")
            }
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            runOnAdapterMain {
                if (internalRepeatMode == value) return@runOnAdapterMain
                internalRepeatMode = value
                listeners.forEach { it.onRepeatModeChanged(value) }
                forwardingPlayer.notifyRepeatModeChanged(value)
                clearPrecacheExceptCurrentInternal()
                triggerPrecachingInternal()
            }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            // With nothing queued (addMediaItem clears the playlist, then sets the flag, then
            // sets the item) there is nothing to play or pause: just remember the intent, which
            // setMediaItem reads when it starts loading. Calling play() here only logged
            // "Called in IDLE state" on every track change.
            if (playlist.isEmpty() && castRemotePlayer == null) return
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        set(value) {
            internalPlaybackSpeed = value.speed
            internalPlaybackPitch = value.pitch
            val params = PlaybackParameters(value.speed, value.pitch)
            runOnAdapterMain {
                currentPlayer?.playbackParameters = params
                // Also apply to secondary player during crossfade
                secondaryPlayer?.playbackParameters = params
                // Receiver support varies (speed only, no pitch) — best-effort while casting.
                castRemotePlayer?.let { remote ->
                    runCatching { remote.playbackParameters = params }
                }
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = (if (isCrossfading) secondaryPlayer ?: currentPlayer else currentPlayer)?.audioSessionId ?: 0

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            val applied = if (isDucked) internalVolume * duckVolumeFactor else internalVolume
            runOnAdapterMain {
                castRemotePlayer?.volume = internalVolume
                if (!isCrossfading) {
                    currentPlayer?.volume = applied
                }
                listeners.forEach { it.onVolumeChanged(internalVolume) }
            }
        }

    override var albumTrackIds: Set<String>
        get() = internalAlbumTrackIds
        set(value) {
            internalAlbumTrackIds = value
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            // Nothing to push: each player's SleepFadeAudioProcessor samples this on every buffer.
            // That is the whole point of keeping it off the `volume` line, which the crossfade ramp
            // owns and rewrites fifty times per transition.
            //
            // Deliberately not forwarded to castRemotePlayer, unlike `volume` above. While casting
            // the local pipeline produces no audio, so the processor never sees data and the fade
            // would have to be rebuilt as a volume ramp on the receiver. Decided against: the sleep
            // timer simply stops the cast device without fading.
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
        }

    /**
     * Hand the equalizer a new curve.
     *
     * Nothing is pushed anywhere, for the same reason the sleep fade pushes nothing: the value is
     * sampled per buffer. A fresh [EqualizerCurve] is allocated on every call precisely so each
     * processor can decide whether to rebuild its coefficients with one reference comparison,
     * rather than diffing eleven floats on every buffer for the whole of playback.
     *
     * Not forwarded to `castRemotePlayer`: while casting, the audio is decoded on the receiver and
     * never passes through this pipeline at all, which is why the settings screen greys the
     * equalizer out for the duration.
     */
    override fun setEqualizer(
        bandsDb: List<Float>,
        preampDb: Float,
    ) {
        internalEqualizerCurve = EqualizerCurve(bandsDb, preampDb)
    }

    /**
     * Hand the delay and the reverb a new setting.
     *
     * Nothing is pushed anywhere, for the reason [setEqualizer] pushes nothing: the value is
     * sampled per buffer. A fresh [AudioEffects] is allocated on every call precisely so each
     * processor can decide whether to re-derive its taps — or rebuild its partitioned impulse
     * response — with one reference comparison, rather than diffing the whole setting on every
     * buffer for the whole of playback.
     *
     * Not forwarded to `castRemotePlayer`, again like the equalizer: while casting, the audio is
     * decoded on the receiver and never passes through this pipeline at all.
     */
    override fun setAudioEffects(effects: AudioEffects) {
        internalAudioEffects = effects
    }

    override fun setEightD(effect: EightDEffect?) {
        eightDOrbit.setEffect(effect)
    }

    override var skipSilenceEnabled: Boolean
        get() = internalSkipSilence
        set(value) {
            internalSkipSilence = value
            runOnAdapterMain {
                currentPlayer?.skipSilenceEnabled = value
                // Also apply to secondary player during crossfade
                secondaryPlayer?.skipSilenceEnabled = value
            }
        }

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        eightDOrbit.setPlaying(false)
        // Cancel all ongoing jobs first
        currentLoadJob?.cancel()
        currentLoadJob = null
        precacheJob?.cancel()
        precacheJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null

        // Tear down crossfade, active player, and precaches before closing adapterJob
        cleanupCurrentPlayerInternal(keepPlaceholder = false)
        clearAllPrecacheInternal()

        abandonAudioFocusInternal()
        // Only this adapter's jobs — never the shared SERVICE_SCOPE (see adapterJob).
        adapterJob.cancel()
        outputRouter.release()
        listeners.clear()
    }

    // ========== Internal: State Transition ==========

    /**
     * State transition helper - mirrors GstreamerPlayerAdapter.transitionToState()
     */
    private fun propagatePlayerError(error: PlaybackException) {
        val unrecoverable = error.isUnrecoverableStreamFailure()
        val genericError =
            PlayerError(
                errorCode =
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_TIMEOUT -> PlayerConstants.ERROR_CODE_TIMEOUT
                        else -> error.errorCode
                    },
                errorCodeName = error.errorCodeName,
                message = error.message,
                recoverable = !unrecoverable,
            )
        Logger.e(TAG, "Playback error: ${error.message}")
        // Transition BEFORE notifying listeners so an onPlayerError handler (e.g. pause()) sees ERROR
        // and cannot overwrite internalPlayWhenReady = false for subsequent track skips.
        // notifyError = false avoids emitting a second synthetic 403 alongside the real error.
        transitionToState(InternalState.ERROR, notifyError = false)
        listeners.forEach { it.onPlayerError(genericError) }
    }

    private fun transitionToState(newState: InternalState, notifyError: Boolean = true) {
        if (internalState == newState) {
            Logger.d(TAG, "State transition ignored: already in $newState")
            return
        }

        val oldState = internalState
        internalState = newState
        eightDOrbit.setPlaying(newState == InternalState.PLAYING)

        Logger.d(TAG, "State: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Update cached duration from active player
        (if (isCrossfading) secondaryPlayer ?: currentPlayer else currentPlayer)?.let {
            val dur = it.duration
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners
        when (newState) {
            InternalState.PAUSED -> {
                pausedAtMs = System.currentTimeMillis()
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.IDLE -> {
                pausedAtMs = 0L
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.PREPARING -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
            }

            InternalState.READY -> {
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                pausedAtMs = 0L
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            InternalState.ENDED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                // Only for entries into ERROR that did not already deliver an error:
                // propagatePlayerError passes notifyError = false because it notified the
                // real error itself — without that, every recoverable failure also surfaced
                // as this synthetic 403 (toast + error report) while recovery was pending.
                if (notifyError) {
                    listeners.forEach {
                        it.onPlayerError(
                            PlayerError(
                                errorCode = 403,
                                errorCodeName = "ERROR_UNKNOWN",
                                message = "Can not extract playable URL or playback error",
                            ),
                        )
                    }
                }
            }
        }
    }

    // ========== Internal: Load and Play Track ==========

    /**
     * Load and play track - mirrors GstreamerPlayerAdapter.loadAndPlayTrackInternal()
     *
     * Key difference: instead of extracting URL + creating PlayBin,
     * we create an ExoPlayer, set a MediaItem, and let MediaSourceFactory resolve the URL.
     */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId

        // While casting, playback starts on the receiver — never on a local ExoPlayer.
        castPlaybackRouter?.takeIf { isCastActive }?.let { router ->
            currentLoadJob?.cancel()
            listeners.forEach {
                it.onMediaItemTransition(mediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
            router(index, startPositionMs, shouldPlay)
            return
        }

        // Cancel any lingering load job and active crossfade before loading this track.
        currentLoadJob?.cancel()
        if (isCrossfading || crossfadeTransitionJob?.isActive == true) {
            cancelCrossfadeAsAbortedInternal(cancelJob = true, restoreDelegate = false)
        }

        if (watchdogVideoId != videoId) {
            watchdogVideoId = videoId
            watchdogFireCount = 0
        }

        // Cancel abandoned in-flight extraction if switching away from a loading track.
        val previousLoadingId = loadingMediaId
        if (previousLoadingId != null && previousLoadingId != videoId && !precachedPlayers.containsKey(previousLoadingId)) {
            streamResolveCoordinator?.cancel(previousLoadingId)
        }
        val oldPlayer = currentPlayer
        val oldMediaId = oldPlayer?.currentMediaItem?.mediaId
        loadingMediaId = videoId

        try {
            // Evict any precached player that is neither this track nor an upcoming target
            // immediately on skip instead of waiting for triggerPrecachingInternal's delay.
            clearStalePrecachesOnSkipInternal(index, videoId)

            // Use precached player if available — unless it already failed, was not prepared
            // (STATE_IDLE), or already ended (STATE_ENDED). A precache that ran into a live
            // broadcast holds nothing playable (its progressive source threw
            // LiveStreamDetectedException while nobody was listening), so it is dropped and
            // the track is built afresh, which now gives it an HLS source.
            val cachedPlayerEntry =
                precachedPlayers.remove(videoId)?.let { cached ->
                    cached.retryListener?.let { cached.player.removeListener(it) }
                    val playbackState = cached.player.playbackState
                    if (cached.player.playerError == null &&
                        playbackState != Player.STATE_IDLE &&
                        playbackState != Player.STATE_ENDED
                    ) {
                        cached
                    } else {
                        cleanupPlayerInternal(cached.player)
                        null
                    }
                }

            val startPos = startPositionMs.coerceAtLeast(0L)
            val precacheAlreadyReady =
                cachedPlayerEntry != null &&
                    cachedPlayerEntry.player.playbackState == Player.STATE_READY &&
                    cachedPlayerEntry.player.bufferedPosition > startPos
            if (precacheAlreadyReady) {
                // Precache already holds buffered audio: enter PREPARING quietly without publishing
                // STATE_BUFFERING so the UI does not flash a loading spinner before PLAYING lands.
                internalState = InternalState.PREPARING
                eightDOrbit.setPlaying(false)
            } else {
                transitionToState(InternalState.PREPARING)
            }

            // Cancel oldPlayer's abandoned network resolve BEFORE creating/preparing the new
            // player so only pre-existing resolves are cancelled, never the new resolve.
            if (oldMediaId != null &&
                !precachedPlayers.containsKey(oldMediaId) &&
                (oldMediaId != videoId || cachedPlayerEntry == null)
            ) {
                streamResolveCoordinator?.cancel(oldMediaId)
            }
            if (cachedPlayerEntry == null && oldMediaId == null && previousLoadingId == videoId) {
                streamResolveCoordinator?.cancel(videoId)
            }

            val player: ExoPlayer
            val playerFilter: CrossfadeFilterAudioProcessor?
            if (cachedPlayerEntry?.player != null) {
                Logger.d(TAG, "Using precached player for $videoId (state=${cachedPlayerEntry.player.playbackState}, buf=${cachedPlayerEntry.player.bufferedPosition}ms)")
                player = cachedPlayerEntry.player
                playerFilter = cachedPlayerEntry.filter
            } else {
                Logger.d(TAG, "Creating new player for $videoId")
                val pwf = createExoPlayerInstance(mediaItem.mediaId)
                player = pwf.player
                playerFilter = pwf.filter
                player.setMediaItem(mediaItem.toMedia3MediaItem())
                player.prepare()
            }

            // === CAREFUL ORDER for ForwardingPlayer integration ===

            // 1. Remove active listener from old player and swap currentPlayer first so
            //    oldPlayer stops before we start the new player or notify onMediaItemTransition
            //    (allowing downstream handlers like volume normalization to bind to the new audioSessionId).
            cleanupPlayerListenerInternal()
            stopPositionUpdates()
            currentPlayer = player
            currentPlayerFilter = playerFilter
            setupPlayerListenerInternal(player)
            forwardingPlayer.swapDelegate(player)

            // 2. Release old player BEFORE emitting onMediaItemTransition
            if (oldPlayer != null && oldPlayer !== player) {
                cleanupPlayerInternal(oldPlayer)
            }

            // Keep an adopted precached player's actual buffer/duration instead of wiping them
            // to 0L (which would force SharedViewModel to treat an already-buffered track as loading).
            val adoptedBufferedPos =
                if (cachedPlayerEntry != null) player.bufferedPosition.coerceAtLeast(0L) else 0L
            cachedPosition = startPos
            cachedBufferedPosition = adoptedBufferedPos
            cachedDuration = player.duration.takeIf { it > 0L } ?: 0L
            cachedIsLoading = !precacheAlreadyReady && adoptedBufferedPos <= startPos

            // 3. Apply settings and seek if needed
            player.volume = if (isDucked) internalVolume * duckVolumeFactor else internalVolume
            player.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
            player.skipSilenceEnabled = internalSkipSilence
            if (startPos > 0L) {
                player.seekTo(startPos)
            }

            // 4. Notify listeners while still in PREPARING, then notify MediaSession about
            //    the new media item (it was set before swapDelegate, so MediaSession's listener
            //    missed the transition).
            listeners.forEach {
                it.onMediaItemTransition(
                    mediaItem,
                    PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                )
            }
            forwardingPlayer.notifyMediaItemChanged()

            // Audio focus is held at the adapter level (see Audio Focus section),
            // not per-player, so it survives this swap (#2155).

            // 5. Auto-play if requested (respecting audio focus grant)
            if (shouldPlay) {
                if (!requestAudioFocusInternal()) {
                    Logger.w(TAG, "Load: audio focus denied; pausing until focus is granted")
                    resumeOnFocusGain = true
                    internalPlayWhenReady = false
                    player.pause()
                    transitionToState(InternalState.READY)
                } else {
                    player.play()
                    transitionToState(InternalState.PLAYING)
                }
            } else {
                player.pause()
                transitionToState(InternalState.READY)
            }

            forwardingPlayer.suppressPlaybackEnded = false

            // Start position updates
            startPositionUpdates()

            // Eagerly load audio metadata for auto crossfade calculations
            // so it's available when position updates check the trigger threshold
            if (crossfadeEnabled && crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO) {
                adapterScope.launch {
                    loadAudioMetaIfNeeded(videoId)
                }
            }

            // Trigger precaching
            triggerPrecachingInternal()

            // Prevention: pull the whole track into the player cache so a mid-song
            // network drop has nothing left to fetch. The preloader's watchdog keeps
            // retrying until the track is fully local, then caches the next one.
            val nextIndex = getNextMediaItemIndex()
            trackCachePreloader.onTrackStarted(
                mediaItem.mediaId,
                playlist.getOrNull(nextIndex)?.mediaId?.takeIf { nextIndex != localCurrentMediaItemIndex },
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Load track error: ${e.message}", e)
            forwardingPlayer.suppressPlaybackEnded = false
            transitionToState(InternalState.ERROR)
        }
    }

    // ========== Internal: Player Listener Management ==========
    // Equivalent to setupPlayerListenersInternal / cleanupBusListenersInternal

    /**
     * Setup Player.Listener on the given player.
     * First removes any existing listener from the old player (like cleanupBusListenersInternal),
     * then creates and attaches a new listener to the given player.
     *
     * This is the KEY crossfade mechanism:
     * When crossfade starts, the listener is moved from old player to new player.
     * The old player's STATE_ENDED event is then ignored (no listener to handle it).
     */
    private fun setupPlayerListenerInternal(player: ExoPlayer) {
        // Clean up old listener first
        cleanupPlayerListenerInternal()

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== currentPlayer && !(isCrossfading && player === secondaryPlayer)) {
                        return
                    }
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Logger.d(TAG, "End of stream reached")
                            // Repeat One replays this track, so its end is not the end of playback.
                            if (hasNextMediaItem() || internalRepeatMode == PlayerConstants.REPEAT_MODE_ONE) {
                                forwardingPlayer.suppressPlaybackEnded = true
                                transitionToState(InternalState.PREPARING)
                            } else {
                                transitionToState(InternalState.ENDED)
                            }
                            if (isCrossfading && player === secondaryPlayer) {
                                commitIncomingAsCurrentInternal()
                            }
                            handleTrackEndInternal()
                        }

                        Player.STATE_READY -> {
                            // Always clear loading state when ExoPlayer is ready
                            if (loadingMediaId == player.currentMediaItem?.mediaId) {
                                loadingMediaId = null
                            }
                            if (cachedIsLoading) {
                                cachedIsLoading = false
                                listeners.forEach { it.onIsLoadingChanged(false) }
                            }
                            // Duration should be available now
                            val dur = player.duration
                            if (dur > 0) cachedDuration = dur
                            // Reset retry counter on successful playback
                            retryCount = 0
                            retryVideoId = null
                        }

                        Player.STATE_BUFFERING -> {
                            if (player === currentPlayer && player.playWhenReady && !cachedIsLoading) {
                                cachedIsLoading = true
                                eightDOrbit.setPlaying(false)
                                listeners.forEach { it.onIsLoadingChanged(true) }
                            }
                        }
                    }
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (player !== currentPlayer && !(isCrossfading && player === secondaryPlayer)) {
                        return
                    }
                    if (!playWhenReady &&
                        (
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                        )
                    ) {
                        internalPlayWhenReady = false
                        resumeOnFocusGain = false
                        pause()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== currentPlayer && !(isCrossfading && player === secondaryPlayer)) {
                        Logger.d(TAG, "Ignoring onIsPlayingChanged from non-current player")
                        return
                    }
                    if (isPlaying) {
                        if (cachedIsLoading) {
                            cachedIsLoading = false
                            listeners.forEach { it.onIsLoadingChanged(false) }
                        }
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            notifyEqualizerIntent(true)
                        }
                    } else {
                        if (internalState == InternalState.PLAYING) {
                            if (!player.playWhenReady) {
                                internalPlayWhenReady = false
                                transitionToState(InternalState.PAUSED)
                                notifyEqualizerIntent(false)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // During a crossfade the active listener sits on the incoming player while
                    // currentPlayer is still the outgoing one — and the current index already
                    // points at the incoming track. Its errors are the current track's errors.
                    val isCrossfadeIncoming = isCrossfading && player === secondaryPlayer
                    if (player !== currentPlayer && !isCrossfadeIncoming) {
                        Logger.d(TAG, "Ignoring onPlayerError from non-current player")
                        return
                    }

                    val isRetryableSourceError = !error.isUnrecoverableStreamFailure() && error.isRetryableSourceError()

                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                    if (loadingMediaId == currentVideoId) {
                        loadingMediaId = null
                    }
                    val erroredPlayer = player
                    // The progressive resolver found this track to be a live broadcast (or an HLS
                    // source found the live broadcast ended). Loading it again picks the right source.
                    if ((error.isLiveStreamDetected() || error.isLiveStreamEnded()) && currentVideoId != null) {
                        Logger.w(TAG, "$currentVideoId live/VOD mode switched; rebuilding source")
                        currentLoadJob?.cancel()
                        currentLoadJob =
                            adapterScope.launch {
                                val stillOwned =
                                    currentPlayer === erroredPlayer ||
                                        (isCrossfading && secondaryPlayer === erroredPlayer)
                                val targetIdx =
                                    if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId == currentVideoId) {
                                        localCurrentMediaItemIndex
                                    } else {
                                        -1
                                    }
                                if (!stillOwned || targetIdx < 0) {
                                    return@launch
                                }
                                if (isCrossfading && secondaryPlayer === erroredPlayer) {
                                    cancelCrossfadeAsAbortedInternal()
                                }
                                precachedPlayers.remove(currentVideoId)?.let { cleanupPrecachedEntryInternal(it) }
                                loadAndPlayTrackInternal(targetIdx, 0L, shouldPlay = internalPlayWhenReady)
                            }
                        return
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                        Logger.w(
                            TAG,
                            "Cache disappeared mid-read for $currentVideoId — the resolver had served it " +
                                "as a fully cached bare media id. Retrying to resolve a real URL.",
                        )
                    }
                    if (isRetryableSourceError && currentVideoId != null) {
                        // Reset retry count if this is a different track
                        if (retryVideoId != currentVideoId) {
                            retryVideoId = currentVideoId
                            retryCount = 0
                        }
                        if (retryCount < maxRetryCount) {
                            retryCount++
                            Logger.w(TAG, "Retryable source error (attempt $retryCount/$maxRetryCount) for $currentVideoId: ${error.errorCodeName}")
                            val resumePositionMs = if (isCrossfadeIncoming) 0L else cachedPosition.coerceAtLeast(0L)
                            currentLoadJob?.cancel()
                            currentLoadJob =
                                adapterScope.launch {
                                    val stillOwned =
                                        currentPlayer === erroredPlayer ||
                                            (isCrossfading && secondaryPlayer === erroredPlayer)
                                    val targetIdx =
                                        if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId == currentVideoId) {
                                            localCurrentMediaItemIndex
                                        } else {
                                            -1
                                        }
                                    if (!stillOwned || targetIdx < 0) {
                                        Logger.d(TAG, "Ignoring stale retry launch for superseded track ($currentVideoId)")
                                        return@launch
                                    }
                                    try {
                                        streamRepository.onStreamRejected(currentVideoId)
                                        streamRepository.invalidateFormat(currentVideoId)
                                        streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                        precachedPlayers.remove(currentVideoId)?.let { cleanupPrecachedEntryInternal(it) }
                                        val latestIdx =
                                            if (playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId == currentVideoId) {
                                                localCurrentMediaItemIndex
                                            } else {
                                                -1
                                            }
                                        if (latestIdx < 0) return@launch
                                        if (isCrossfading && secondaryPlayer === erroredPlayer) {
                                            cancelCrossfadeAsAbortedInternal()
                                        }
                                        loadAndPlayTrackInternal(latestIdx, resumePositionMs, shouldPlay = true)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        Logger.e(TAG, "Retry failed: ${e.message}", e)
                                        val stillOwnerAfterFailure =
                                            currentPlayer === erroredPlayer ||
                                                (isCrossfading && secondaryPlayer === erroredPlayer)
                                        if (stillOwnerAfterFailure) {
                                            if (isCrossfading && secondaryPlayer === erroredPlayer) {
                                                commitIncomingAsCurrentInternal()
                                            }
                                            propagatePlayerError(error)
                                        }
                                    }
                                }
                            return
                        }
                        Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                    }

                    if (isCrossfadeIncoming && secondaryPlayer === erroredPlayer) {
                        commitIncomingAsCurrentInternal()
                    }
                    propagatePlayerError(error)
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    val isPlaybackStalled = isLoading && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
                    val isCurrentPlayer = player == currentPlayer
                    Logger.d(TAG, "onIsLoadingChanged: isLoading=$isLoading, isPlaybackStalled=$isPlaybackStalled, isCurrentPlayer=$isCurrentPlayer")
                    if (cachedIsLoading != isPlaybackStalled && isCurrentPlayer) {
                        cachedIsLoading = isPlaybackStalled
                        eightDOrbit.setPlaying(internalState == InternalState.PLAYING && !isPlaybackStalled)
                        listeners.forEach { it.onIsLoadingChanged(isPlaybackStalled) }
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val genericTracks = tracks.toGenericTracks()
                    listeners.forEach { it.onTracksChanged(genericTracks) }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (player != currentPlayer) return
                    // Fires for both in-app seeks and MediaSession/notification seeks — both land on the
                    // raw ExoPlayer. seekTo() does no manual notification, so this is the single source.
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        listeners.forEach { it.onSeeked(newPosition.positionMs) }
                    }
                }

                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val shouldBePlaying =
                        !(player.playbackState == Player.STATE_ENDED || !player.playWhenReady)
                    if (events.containsAny(
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                        )
                    ) {
                        if (shouldBePlaying) {
                            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(true) }
                        } else {
                            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(false) }
                        }
                    }
                }
            }

        player.addListener(listener)
        activePlayerListener = listener
    }

    /**
     * Clean up active player listener from whichever player has it.
     * During crossfade the listener is on secondaryPlayer, not currentPlayer.
     */
    private fun cleanupPlayerListenerInternal() {
        activePlayerListener?.let { listener ->
            currentPlayer?.removeListener(listener)
            secondaryPlayer?.removeListener(listener)
        }
        activePlayerListener = null
    }

    // ========== Internal: Player Cleanup ==========

    private fun cleanupPlayerInternal(player: ExoPlayer) {
        try {
            player.stop()
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    private fun cleanupCurrentPlayerInternal(keepPlaceholder: Boolean = true) {
        stopPositionUpdates()
        cleanupPlayerListenerInternal()

        // Cancel any ongoing crossfade and tear down secondary player safely
        cancelCrossfadeAsAbortedInternal(cancelJob = true, restoreDelegate = false)

        loadingMediaId?.let { streamResolveCoordinator?.cancel(it) }
        loadingMediaId = null
        val oldPlayer = currentPlayer
        oldPlayer?.currentMediaItem?.mediaId?.let { mediaId ->
            if (!precachedPlayers.containsKey(mediaId)) {
                streamResolveCoordinator?.cancel(mediaId)
            }
        }

        if (keepPlaceholder) {
            val placeholder = createExoPlayerInstance()
            currentPlayer = placeholder.player
            currentPlayerFilter = placeholder.filter
            forwardingPlayer.swapDelegate(placeholder.player)
            cachedPosition = 0L
            cachedDuration = 0L
            cachedBufferedPosition = 0L
            cachedIsLoading = false
            pausedAtMs = 0L
            transitionToState(InternalState.IDLE)
        } else {
            currentPlayer = null
            currentPlayerFilter = null
        }

        if (oldPlayer != null) {
            cleanupPlayerInternal(oldPlayer)
        }
    }

    /**
     * Cancel an ongoing crossfade WITHOUT promoting the incoming track (e.g., when jumping to
     * another queue item via seekTo(index), clearing queue, or rolling back an error).
     */
    private fun cancelCrossfadeAsAbortedInternal(
        cancelJob: Boolean = true,
        restoreDelegate: Boolean = true,
    ) {
        val incoming = secondaryPlayer
        val incomingFilter = secondaryPlayerFilter
        secondaryPlayer = null
        secondaryPlayerFilter = null

        if (cancelJob) {
            crossfadeTransitionJob?.cancel()
            crossfadeJob?.cancel()
        }
        crossfadeTransitionJob = null
        crossfadeJob = null

        currentPlayerFilter?.enabled = false
        incomingFilter?.enabled = false
        currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)

        if (incoming != null && incoming !== currentPlayer) {
            if (restoreDelegate && forwardingPlayer.wrappedPlayer === incoming) {
                currentPlayer?.let { cur ->
                    forwardingPlayer.swapDelegate(cur)
                    setupPlayerListenerInternal(cur)
                }
            }
            incoming.currentMediaItem?.mediaId?.let { secId ->
                if (!precachedPlayers.containsKey(secId)) {
                    streamResolveCoordinator?.cancel(secId)
                }
            }
            cleanupPlayerInternal(incoming)
        }

        setCrossfading(false)
        crossfadeFromIndex = -1
    }

    /**
     * Abort an in-progress crossfade by committing the INCOMING track (A+1) as the new
     * current player — the mid-fade counterpart of [finalizeCrossfade]. Invoked when the
     * user interacts during a crossfade (next/prev/pause/seek/stop).
     */
    private fun commitIncomingAsCurrentInternal() {
        val incoming = secondaryPlayer
        val incomingFilter = secondaryPlayerFilter
        // Detach secondary BEFORE cancelling crossfadeJob so performCrossfade's CancellationException
        // handler sees nextPlayer !== secondaryPlayer and (once promoted) nextPlayer === currentPlayer,
        // preventing it from releasing our newly promoted currentPlayer!
        secondaryPlayer = null
        secondaryPlayerFilter = null

        if (incoming == null) {
            crossfadeTransitionJob?.cancel()
            crossfadeJob?.cancel()
            crossfadeTransitionJob = null
            crossfadeJob = null
            currentPlayerFilter?.enabled = false
            currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
            setCrossfading(false)
            crossfadeFromIndex = -1
            return
        }

        val outgoing = currentPlayer
        currentPlayer = incoming
        currentPlayerFilter = incomingFilter

        crossfadeTransitionJob?.cancel()
        crossfadeJob?.cancel()
        crossfadeTransitionJob = null
        crossfadeJob = null
        stopPositionUpdates()

        if (forwardingPlayer.wrappedPlayer !== incoming) {
            forwardingPlayer.swapDelegate(incoming)
        }
        setupPlayerListenerInternal(incoming)

        if (outgoing != null && outgoing !== incoming) {
            cleanupPlayerInternal(outgoing)
        }

        // The incoming player was fading in: reduced volume + DJ filter on. Restore normal playback on A+1.
        currentPlayerFilter?.enabled = false
        incoming.volume = if (isDucked) internalVolume * duckVolumeFactor else internalVolume
        incoming.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        incoming.skipSilenceEnabled = internalSkipSilence

        val activeMediaId = incoming.currentMediaItem?.mediaId ?: playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
        if (watchdogVideoId != activeMediaId) {
            watchdogVideoId = activeMediaId
            watchdogFireCount = 0
        }

        setCrossfading(false)
        crossfadeFromIndex = -1
    }

    // ========== Internal: Track End ==========

    /**
     * Crossfade is skipped when the NEXT track will play as a video (video content with
     * the watch-video setting on — the same condition MergingMediaSourceFactory uses to
     * build a merged audio+video source). The merged source resolves two stream URLs and
     * is error-prone to prepare mid-fade, and a video should start from its first frame
     * instead of fading in under the outgoing song — so the transition takes the normal
     * (non-crossfade) path.
     */
    private fun isNextTrackVideo(): Boolean = watchVideoEnabled && playlist.getOrNull(getNextMediaItemIndex())?.isVideo() == true

    /** Same skip rule for the CURRENT track: a video should play out to its last frame instead of fading out under the incoming song. */
    private fun isCurrentTrackVideo(): Boolean = watchVideoEnabled && currentMediaItem?.isVideo() == true

    /** A live broadcast never reaches an end to fade out of, and its "remaining time" is only how far behind the live edge it is. */
    private fun isCurrentTrackLive(): Boolean = currentMediaItem?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /** Nor can a live broadcast fade in: its HLS source is not one a crossfade can prepare ahead of time. */
    private fun isNextTrackLive(): Boolean = playlist.getOrNull(getNextMediaItemIndex())?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /**
     * Crossfade needs a track long enough that both sides of the blend are still worth hearing. At
     * the default 5s fade a 20s track would spend half its length fading in or out, and a longer
     * fade setting swallows it whole — so the bar scales with the fade rather than being fixed.
     *
     * Only the current track is measured: the next one has not been prepared yet, so its duration
     * is unknown until it becomes current.
     */
    private fun isCurrentTrackTooShortForCrossfade(): Boolean {
        val duration = currentPlayer?.duration ?: return false
        if (duration <= 0L) return false
        val fadeMs =
            if (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO) {
                // Auto resolves to 20–45s, nowhere near the 5s default — measuring against the
                // default would let a 30s track through and then swallow it whole.
                resolveAutoCrossfadeDurationMs(
                    currentMediaItem?.mediaId ?: "",
                    playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: "",
                )
            } else {
                crossfadeDurationMs
            }
        return duration < maxOf(MIN_CROSSFADE_TRACK_MS, fadeMs * 3L)
    }

    /**
     * True when both this track and the next came from the album loaded in the queue, and the user
     * asked for albums to play through uninterrupted.
     *
     * Requiring *both* sides is what keeps the edges intact: the last album track into the first
     * track endless queue appended still crossfades, because that one is not in the set.
     */
    private fun isWithinAlbum(): Boolean {
        if (!skipCrossfadeInAlbum) return false
        val ids = internalAlbumTrackIds
        if (ids.isEmpty()) return false
        val current = currentMediaItem?.mediaId ?: return false
        val next = playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: return false
        return current in ids && next in ids
    }

    /**
     * Handle track end - mirrors GstreamerPlayerAdapter.handleTrackEndInternal()
     */
    private fun handleTrackEndInternal() {
        // While casting, track transitions are driven by the receiver queue.
        if (isCastActive) return
        // Check if crossfade should be used
        val shouldCrossfade =
            crossfadeEnabled &&
                !crossfadeSuppressed &&
                // Repeat One loops this track through the branch below instead of fading into the next.
                internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                hasNextMediaItem() &&
                !isCrossfading &&
                !isCurrentTrackVideo() &&
                !isNextTrackVideo() &&
                !isCurrentTrackLive() &&
                !isNextTrackLive() &&
                !isCurrentTrackTooShortForCrossfade() &&
                !isWithinAlbum()

        if (shouldCrossfade) {
            val nextIndex = getNextMediaItemIndex()
            triggerCrossfadeTransition(nextIndex)
        } else {
            val endedPlayer = currentPlayer
            adapterScope.launch {
                if (currentPlayer !== endedPlayer) return@launch
                when (internalRepeatMode) {
                    PlayerConstants.REPEAT_MODE_ONE -> {
                        seekToInternal(localCurrentMediaItemIndex, 0)
                    }

                    PlayerConstants.REPEAT_MODE_ALL -> {
                        if (hasNextMediaItem()) {
                            seekToNext()
                        } else {
                            forwardingPlayer.suppressPlaybackEnded = false
                            transitionToState(InternalState.ENDED)
                            notifyEqualizerIntent(false)
                        }
                    }

                    else -> {
                        if (hasNextMediaItem()) {
                            seekToNext()
                        } else {
                            forwardingPlayer.suppressPlaybackEnded = false
                            transitionToState(InternalState.ENDED)
                            notifyEqualizerIntent(false)
                        }
                    }
                }
            }
        }
    }

    // ========== Internal: Crossfade ==========

    /**
     * Trigger crossfade to next track.
     * Mirrors GstreamerPlayerAdapter.triggerCrossfadeTransition()
     *
     * Key mechanism: [setupPlayerListenerInternal] moves the active listener
     * from the current player to the next player. The old player's STATE_ENDED
     * event is then ignored (no listener to fire).
     */
    private fun triggerCrossfadeTransition(nextIndex: Int) {
        if (nextIndex !in playlist.indices ||
            isCrossfading ||
            crossfadeTransitionJob?.isActive == true ||
            isCastActive
        ) {
            return
        }

        // Pinned now, not inside the launch: the target is already chosen, and an append that runs
        // before the fade makes it current must not take its slot.
        val initialMediaItem = playlist[nextIndex]
        val nextVideoId = initialMediaItem.mediaId
        if (internalShuffleModeEnabled) pinnedNextItems.add(initialMediaItem)

        crossfadeJob?.cancel()
        val transitionJob =
            adapterScope.launch {
                try {
                    setCrossfading(true)
                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""
                    val isAutoMode = crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO

                    // Lazily load AutoMix metadata BEFORE swapping players/indices so a rapid skip
                    // or queue mutation during this suspend cannot leave stale delegate/index state.
                    if (isAutoMode) {
                        loadAudioMetaIfNeeded(currentVideoId)
                        loadAudioMetaIfNeeded(nextVideoId)
                    }
                    if (!currentCoroutineContext().isActive || isCastActive) {
                        setCrossfading(false)
                        return@launch
                    }
                    val resolvedNextIndex =
                        if (playlist.getOrNull(nextIndex)?.mediaId == nextVideoId) {
                            nextIndex
                        } else {
                            playlist.indexOfFirst { it.mediaId == nextVideoId }.takeIf { it >= 0 }
                        }
                    if (resolvedNextIndex == null) {
                        Logger.w(TAG, "Crossfade aborted: next track $nextVideoId removed during setup")
                        setCrossfading(false)
                        return@launch
                    }
                    val nextMediaItem = playlist[resolvedNextIndex]

                    Logger.d(TAG, "Starting crossfade to track $resolvedNextIndex")

                    // The placed transition, if the outgoing track is at its window. The end-of-file
                    // path and a fade begun after a seek past the window get the plain fade from 0.
                    val plan =
                        if (isAutoMode) {
                            transitionPlan(currentVideoId, nextVideoId)?.startingAt(currentPlayer?.currentPosition ?: 0L)
                        } else {
                            null
                        }
                    val incomingStartMs = plan?.incomingStartMs ?: 0L

                    // Get or create secondary player
                    val cachedPlayerEntry =
                        precachedPlayers.remove(nextVideoId)?.let { cached ->
                            cached.retryListener?.let { cached.player.removeListener(it) }
                            val playbackState = cached.player.playbackState
                            if (cached.player.playerError == null &&
                                playbackState != Player.STATE_IDLE &&
                                playbackState != Player.STATE_ENDED
                            ) {
                                cached
                            } else {
                                cleanupPlayerInternal(cached.player)
                                null
                            }
                        }
                    val nextPlayer: ExoPlayer
                    val nextFilter: CrossfadeFilterAudioProcessor?
                    if (cachedPlayerEntry?.player != null) {
                        nextPlayer = cachedPlayerEntry.player
                        nextFilter = cachedPlayerEntry.filter
                        // A precached player sits at 0 with the intro buffered, so this seek stays inside it.
                        if (incomingStartMs > 0) nextPlayer.seekTo(incomingStartMs)
                    } else {
                        val pwf = createExoPlayerInstance(nextMediaItem.mediaId)
                        nextPlayer = pwf.player
                        nextFilter = pwf.filter
                        nextPlayer.setMediaItem(nextMediaItem.toMedia3MediaItem(), incomingStartMs)
                        nextPlayer.prepare()
                    }

                    // Setup secondary player
                    secondaryPlayer = nextPlayer
                    secondaryPlayerFilter = nextFilter

                    val resolvedConfigDurationMs =
                        if (plan != null) {
                            plan.durationMs.toInt()
                        } else if (isAutoMode) {
                            resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId)
                        } else {
                            crossfadeDurationMs
                        }
                    val bpmSpeedRatio = if (isAutoMode) calculateBpmSpeedRatio(currentVideoId, nextVideoId) else 1.0f
                    val keyPitchRatio = if (isAutoMode) calculateKeyPitchRatio(currentVideoId, nextVideoId) else 1.0f

                    // Incoming player plays at natural speed/pitch — only the outgoing
                    // player is adjusted to match the incoming track during crossfade.
                    nextPlayer.playbackParameters =
                        PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                    nextPlayer.skipSilenceEnabled = internalSkipSilence
                    nextPlayer.volume = 0f

                    // Update index BEFORE swapping/notifying MediaSession so getAvailableCommands()
                    // immediately sees the incoming track's queue navigation state.
                    crossfadeFromIndex = localCurrentMediaItemIndex
                    localCurrentMediaItemIndex = resolvedNextIndex
                    unpinCurrent()

                    // *** KEY: Move our custom listener from current to next player ***
                    setupPlayerListenerInternal(nextPlayer)

                    // === CRITICAL ORDER for MediaSession notification ===
                    // 1. Swap ForwardingPlayer BEFORE play() so MediaSession's listener receives state events
                    forwardingPlayer.swapDelegate(nextPlayer)

                    // 2. Now play - MediaSession's listener is attached and receives state change events
                    requestAudioFocusInternal()
                    nextPlayer.play()

                    forwardingPlayer.suppressPlaybackEnded = false

                    // 3. Notify MediaSession & custom listeners about the track change
                    forwardingPlayer.notifyMediaItemChanged()
                    listeners.forEach {
                        it.onMediaItemTransition(
                            nextMediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    Logger.d(TAG, "Now playing updated to track $resolvedNextIndex during crossfade")

                    // Calculate effective crossfade duration based on ACTUAL remaining time.
                    val actualTimeRemaining =
                        currentPlayer?.let { player ->
                            val dur = player.duration
                            val pos = player.currentPosition
                            // Divide by playback speed: at 2x speed, remaining wall-clock time is halved
                            val speed = internalPlaybackSpeed.coerceAtLeast(0.1f)
                            if (dur > 0 && pos >= 0) ((dur - pos) / speed).toLong() else resolvedConfigDurationMs.toLong()
                        } ?: resolvedConfigDurationMs.toLong()

                    val effectiveCrossfadeDurationMs =
                        minOf(resolvedConfigDurationMs.toLong(), actualTimeRemaining)
                            .coerceAtLeast(1000L)
                            .toInt()

                    Logger.d(
                        TAG,
                        "Crossfade duration: configured=${resolvedConfigDurationMs}ms (auto=$isAutoMode), " +
                            "placed=${plan ?: "no, from the start of the next track"}, " +
                            "bpmRatio=$bpmSpeedRatio, pitchRatio=$keyPitchRatio, " +
                            "actualRemaining=${actualTimeRemaining}ms, effective=${effectiveCrossfadeDurationMs}ms",
                    )

                    // Perform crossfade animation inline inside this job and finalize cleanly
                    performCrossfade(resolvedNextIndex, nextPlayer, effectiveCrossfadeDurationMs, bpmSpeedRatio, keyPitchRatio)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        if (secondaryPlayer != null && secondaryPlayer !== currentPlayer) {
                            cancelCrossfadeAsAbortedInternal(cancelJob = false)
                        } else {
                            setCrossfading(false)
                        }
                        throw e
                    }
                    Logger.e(TAG, "Crossfade error: ${e.message}", e)
                    cancelCrossfadeAsAbortedInternal(cancelJob = false)
                    // Fallback to normal transition if track is still in playlist
                    val fallbackIndex = playlist.indexOfFirst { it.mediaId == nextVideoId }
                    if (fallbackIndex >= 0) {
                        seekTo(fallbackIndex, 0)
                    }
                } finally {
                    if (crossfadeTransitionJob === coroutineContext[Job]) {
                        crossfadeTransitionJob = null
                    }
                    if (crossfadeJob === coroutineContext[Job]) {
                        crossfadeJob = null
                    }
                }
            }
        crossfadeJob = transitionJob
        crossfadeTransitionJob = transitionJob
    }

    /**
     * S-curve (sigmoid) function for DJ filter crossfade timing.
     * Keeps both tracks near full spectrum at the start and end,
     * with a steep transition in the middle — like a real DJ mixer crossfader.
     *
     * k controls steepness: higher = sharper transition.
     * k=6 gives a gentle S-curve: ~8-92% of duration covers the sweep,
     * keeping a subtle onset/ending without harsh "slam" in the middle.
     */
    private fun sigmoid(
        t: Float,
        k: Float = DJ_FILTER_SIGMOID_K,
    ): Float = 1.0f / (1.0f + exp(-k * (t - 0.5f)))

    /**
     * Exponential interpolation between two values.
     * Frequency perception is logarithmic, so this produces a natural-sounding sweep.
     */
    private fun exponentialInterpolate(
        start: Float,
        end: Float,
        t: Float,
    ): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    /**
     * Perform the actual crossfade animation.
     * Mirrors GstreamerPlayerAdapter.performCrossfade()
     *
     * @param effectiveDurationMs The actual crossfade duration to use. May be shorter than
     *   the configured [crossfadeDurationMs] if URL resolution / buffering consumed
     *   part of the crossfade window.
     * @param targetSpeedRatio BPM-based speed ratio for incoming track (1.0 = no adjustment).
     *   Ramps from this value to 1.0 during crossfade so the track plays at natural speed after.
     * @param targetPitchRatio Key-based pitch ratio for incoming track (1.0 = no adjustment).
     *   Ramps from this value to 1.0 during crossfade.
     */
    private suspend fun performCrossfade(
        nextIndex: Int,
        nextPlayer: ExoPlayer,
        effectiveDurationMs: Int,
        targetSpeedRatio: Float = 1.0f,
        targetPitchRatio: Float = 1.0f,
    ) {
        val steps = 50 // 50 steps for smooth transition
        val delayPerStep = (effectiveDurationMs / steps).coerceAtLeast(20) // min 20ms per step
        val targetVolume = internalVolume
        val useDjFilter = djCrossfadeEnabled
        val useAutoMixRamp = targetSpeedRatio != 1.0f || targetPitchRatio != 1.0f
        Logger.d(
            TAG,
            "Crossfade animation: ${effectiveDurationMs}ms, $steps steps, ${delayPerStep}ms/step, " +
                "dj=$useDjFilter, autoMix=$useAutoMixRamp (speed=$targetSpeedRatio, pitch=$targetPitchRatio)",
        )

        // Setup DJ filters before starting animation
        if (useDjFilter) {
            currentPlayerFilter?.let { filter ->
                filter.filterType = BiquadFilter.FilterType.LOW_PASS
                filter.cutoffFrequencyHz = LPF_START_HZ
                filter.enabled = true
            }
            secondaryPlayerFilter?.let { filter ->
                filter.filterType = BiquadFilter.FilterType.HIGH_PASS
                filter.cutoffFrequencyHz = HPF_START_HZ
                filter.enabled = true
            }
        }

        // Track last quantized speed/pitch to avoid redundant PlaybackParameters updates
        var lastOutgoingSpeed = -1f
        var lastOutgoingPitch = -1f

        // Front-loaded BPM/pitch ramp portion (fraction of crossfade duration).
        // Speed/pitch reach target within the first [BPM_RAMP_PORTION] of crossfade,
        // then HOLD for the remainder so both tracks share the same effective BPM
        // throughout the audible overlap.
        val bpmRampPortion = BPM_RAMP_PORTION

        try {
            for (step in 0..steps) {
                if (!currentCoroutineContext().isActive) break

                // The incoming player's error is routed by onPlayerError (retry or
                // propagate) — stop the ramp instead of spending the rest of the fade
                // fading in a dead player. finalizeCrossfade refuses to promote it.
                if (nextPlayer.playerError != null) {
                    Logger.w(TAG, "Crossfade ramp aborted: incoming player errored (${nextPlayer.playerError?.errorCodeName})")
                    break
                }

                val progress = step.toFloat() / steps
                val activeTargetVolume = if (isDucked) internalVolume * duckVolumeFactor else internalVolume

                // Equal-power crossfade (cos/sin curves) instead of linear.
                val fadeAngle = (progress * PI / 2).toFloat()

                // Fade out current player (old track): cos curve, slow at start, fast at end
                val fadeOutVolume = activeTargetVolume * cos(fadeAngle)
                currentPlayer?.volume = fadeOutVolume

                // Fade in next player (new track): sin curve, fast at start, slow at end
                val fadeInVolume = activeTargetVolume * sin(fadeAngle)
                nextPlayer.volume = fadeInVolume

                // DJ-style filter sweep (alongside volume)
                if (useDjFilter) {
                    val filterProgress = sigmoid(progress)

                    // Outgoing: LPF sweeps 20kHz → 200Hz
                    currentPlayerFilter?.cutoffFrequencyHz =
                        exponentialInterpolate(LPF_START_HZ, LPF_END_HZ, filterProgress)

                    // Incoming: HPF sweeps 8kHz → 20Hz
                    secondaryPlayerFilter?.cutoffFrequencyHz =
                        exponentialInterpolate(HPF_START_HZ, HPF_END_HZ, filterProgress)
                }

                if (useAutoMixRamp) {
                    val linearRamp =
                        if (bpmRampPortion <= 0f) {
                            1f
                        } else {
                            (progress / bpmRampPortion).coerceAtMost(1f)
                        }
                    // Smoothstep S-curve: slow→fast→slow (3t²−2t³)
                    val rampProgress = linearRamp * linearRamp * (3f - 2f * linearRamp)
                    val rawOutSpeed = lerp(1.0f, targetSpeedRatio, rampProgress)
                    val rawOutPitch = lerp(1.0f, targetPitchRatio, rampProgress)
                    val qOutSpeed = quantize(rawOutSpeed * internalPlaybackSpeed)
                    val qOutPitch = quantize(rawOutPitch * internalPlaybackPitch)

                    if (qOutSpeed != lastOutgoingSpeed || qOutPitch != lastOutgoingPitch) {
                        currentPlayer?.playbackParameters = PlaybackParameters(qOutSpeed, qOutPitch)
                        lastOutgoingSpeed = qOutSpeed
                        lastOutgoingPitch = qOutPitch
                    }
                }

                delay(delayPerStep.toLong())
            }

            // Transition complete
            finalizeCrossfade(nextIndex, nextPlayer)
        } catch (e: CancellationException) {
            Logger.d(TAG, "Crossfade cancelled")
            currentPlayerFilter?.enabled = false
            secondaryPlayerFilter?.enabled = false
            // Only release nextPlayer if commitIncomingAsCurrentInternal() did NOT promote it to currentPlayer!
            if (nextPlayer !== currentPlayer) {
                currentPlayer?.playbackParameters =
                    PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                if (secondaryPlayer === nextPlayer) {
                    secondaryPlayer = null
                    secondaryPlayerFilter = null
                }
                if (forwardingPlayer.wrappedPlayer === nextPlayer) {
                    currentPlayer?.let {
                        forwardingPlayer.swapDelegate(it)
                        setupPlayerListenerInternal(it)
                    }
                }
                cleanupPlayerInternal(nextPlayer)
            }
            setCrossfading(false)
            crossfadeFromIndex = -1
            throw e
        }
    }

    // ========== AutoMix Public API ==========

    /**
     * Audio analysis metadata for a song, populated from Tidal search response.
     */
    data class SongAudioMeta(
        val bpm: Int?,
        val key: String?,
        val keyScale: String?, // "MAJOR" or "MINOR"
    )

    /**
     * Measures the given tracks in the background, one at a time, through the waveform reader's single
     * decode. Tidal's metadata is loaded first, so a plan is built from the tempo and key the fade will
     * use. A failed measurement is tried again after [STRUCTURE_RETRY_MS], at most
     * [STRUCTURE_MAX_ATTEMPTS] times a session; a job already running is left to finish.
     */
    private fun measureTrackStructures(vararg videoIds: String) {
        if (structureJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val wanted =
            videoIds.filter { id ->
                id.isNotBlank() &&
                    !trackStructures.containsKey(id) &&
                    !LiveStreamRegistry.isLive(id) &&
                    (structureRetryAt[id] ?: 0L) <= now &&
                    (structureAttempts[id] ?: 0) < STRUCTURE_MAX_ATTEMPTS
            }
        if (wanted.isEmpty()) return
        structureJob =
            adapterScope.launch {
                for (id in wanted) {
                    loadAudioMetaIfNeeded(id)
                    structureAttempts[id] = (structureAttempts[id] ?: 0) + 1
                    structureRetryAt[id] = System.currentTimeMillis() + STRUCTURE_RETRY_MS
                    val structure =
                        try {
                            waveformReader.structure(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG, "AutoMix: could not measure $id: ${e.message}")
                            null
                        } ?: continue
                    trackStructures[id] = structure
                }
            }
    }

    /** Where the fade from [currentVideoId] into [nextVideoId] sits, once both are measured. */
    private fun transitionPlan(
        currentVideoId: String,
        nextVideoId: String,
    ): TransitionPlan? {
        val outgoing = trackStructures[currentVideoId] ?: return null
        val incoming = trackStructures[nextVideoId] ?: return null
        val key = "$currentVideoId>$nextVideoId"
        transitionPlans[key]?.let { return it }
        if (key in pairsWithoutPlan) return null
        val plan =
            TransitionPlanner.plan(
                outgoing,
                incoming,
                resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId).toLong(),
                calculateBpmSpeedRatio(currentVideoId, nextVideoId),
            )
        if (plan != null) transitionPlans[key] = plan else pairsWithoutPlan.add(key)
        Logger.d(TAG, "AutoMix plan $key: ${plan ?: "no clear drop, plain fade at the end"}")
        return plan
    }

    /**
     * Update the audio analysis metadata cache for a song.
     * Called externally when Tidal 320kbps stream is fetched and BPM/key data is available.
     * Data is used by Auto crossfade mode for beat-quantized duration, BPM matching, and key matching.
     */
    fun updateSongAudioMeta(
        videoId: String,
        bpm: Int?,
        key: String?,
        keyScale: String?,
    ) {
        if (bpm != null || key != null) {
            audioMetaCache[videoId] = SongAudioMeta(bpm, key, keyScale)
            Logger.d(TAG, "AutoMix meta updated: videoId=$videoId, bpm=$bpm, key=$key $keyScale")
        }
    }

    // ========== AutoMix Internal Logic ==========

    /**
     * Lazily load audio analysis metadata from NewFormatEntity if not already in cache.
     * Called before AutoMix calculations to ensure metadata is available.
     */
    private suspend fun loadAudioMetaIfNeeded(videoId: String) {
        if (videoId.isBlank() || audioMetaCache.containsKey(videoId)) return
        try {
            val format = streamRepository.getNewFormat(videoId).firstOrNull()
            if (format == null) {
                Logger.d(TAG, "AutoMix meta: no NewFormatEntity found for videoId=$videoId")
                return
            }
            if (format.bpm != null || format.musicKey != null) {
                audioMetaCache[videoId] = SongAudioMeta(format.bpm, format.musicKey, format.keyScale)
                Logger.d(TAG, "AutoMix meta loaded: videoId=$videoId, bpm=${format.bpm}, key=${format.musicKey} ${format.keyScale}")
            } else {
                Logger.d(TAG, "AutoMix meta: format exists but no bpm/key data for videoId=$videoId")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load AutoMix meta for $videoId: ${e.message}")
        }
    }

    /**
     * Linear interpolation between two values.
     */
    private fun lerp(
        start: Float,
        end: Float,
        t: Float,
    ): Float = start + (end - start) * t

    /**
     * Quantize a speed/pitch value to the nearest [SPEED_PITCH_STEP] (2%).
     * Prevents SonicAudioProcessor from resetting on micro-adjustments that
     * cause audible popping/clicking artifacts.
     */
    private fun quantize(value: Float): Float = (Math.round(value / SPEED_PITCH_STEP) * SPEED_PITCH_STEP)

    /**
     * Get BPM-adaptive target duration for auto crossfade.
     * Linear interpolation: BPM 70 → 35s, BPM 170 → 12s.
     * Slower songs get longer crossfade (like Apple Music AutoMix ~30s average).
     */
    private fun getAutoTargetDurationMs(bpm: Int): Double {
        val clampedBpm = bpm.coerceIn(70, 170)
        // Linear interpolation: BPM 70 → 30s, BPM 170 → 7s
        return 30000.0 - (clampedBpm - 70) * 230.0
    }

    /**
     * Resolve the crossfade duration for Auto mode based on BPM, BPM gap, and key gap.
     *
     * Algorithm:
     * 1. Base duration from current BPM (slow → longer, fast → shorter)
     * 2. BPM gap factor: larger tempo difference → longer crossfade to smooth transition
     * 3. Key gap factor: larger Camelot distance → longer crossfade to mask harmonic clash
     * 4. Quantize to beat boundaries, clamp to safe range
     */
    private fun resolveAutoCrossfadeDurationMs(
        currentVideoId: String,
        nextVideoId: String,
    ): Int {
        val currentBpm = audioMetaCache[currentVideoId]?.bpm
        val nextBpm = audioMetaCache[nextVideoId]?.bpm
        if (currentBpm == null || nextBpm == null) return AUTO_FALLBACK_DURATION_MS
        if (currentBpm <= 0 || nextBpm <= 0) return AUTO_FALLBACK_DURATION_MS

        val beatMs = 60_000.0 / currentBpm
        val baseTargetMs = getAutoTargetDurationMs(currentBpm)

        val bpmGapFactor = calculateBpmGapDurationFactor(currentBpm, nextBpm)
        val keyGapFactor = calculateKeyGapDurationFactor(currentVideoId, nextVideoId)
        val adjustedTargetMs = baseTargetMs * bpmGapFactor * keyGapFactor

        val bestBeatCount =
            BEAT_COUNT_OPTIONS.minByOrNull { abs(it * beatMs - adjustedTargetMs) }
                ?: DEFAULT_BEAT_COUNT
        val duration = (bestBeatCount * beatMs).toInt()

        Logger.d(
            TAG,
            "AutoMix duration: bpm=$currentBpm→$nextBpm, base=${baseTargetMs.toInt()}ms, " +
                "bpmGap=${"%.2f".format(bpmGapFactor)}, keyGap=${"%.2f".format(keyGapFactor)}, " +
                "adjusted=${adjustedTargetMs.toInt()}ms, beats=$bestBeatCount, final=${duration}ms",
        )

        return duration.coerceIn(AUTO_MIN_DURATION_MS, AUTO_MAX_DURATION_MS)
    }

    /**
     * BPM gap → duration multiplier.
     * Normalizes halftime/doubletime (80 vs 160 → effectively same tempo).
     * Linear: 0% gap → 1.0x, 25% gap → 1.5x.
     */
    private fun calculateBpmGapDurationFactor(
        currentBpm: Int,
        nextBpm: Int,
    ): Double {
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0
        var ratio = nextBpm.toDouble() / currentBpm.toDouble()
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.67) ratio *= 2.0
        val gapPercent = abs(1.0 - ratio)
        return 1.0 + gapPercent * BPM_GAP_DURATION_SCALE
    }

    /**
     * Key gap (Camelot distance) → duration multiplier.
     * Compatible keys (dist ≤ 1) need no extension; far keys need longer blend.
     */
    private fun calculateKeyGapDurationFactor(
        currentVideoId: String,
        nextVideoId: String,
    ): Double {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentKey = currentMeta?.key ?: return UNKNOWN_GAP_DEFAULT_FACTOR
        val nextKey = nextMeta?.key ?: return UNKNOWN_GAP_DEFAULT_FACTOR

        // An unparseable key tells us nothing about compatibility — treat it like a missing
        // key instead of like a perfect match, otherwise it silently shortens the blend.
        val currentCamelot = keyToCamelot(currentKey, currentMeta.keyScale) ?: return UNKNOWN_GAP_DEFAULT_FACTOR
        val nextCamelot = keyToCamelot(nextKey, nextMeta.keyScale) ?: return UNKNOWN_GAP_DEFAULT_FACTOR

        val dist = camelotDistance(currentCamelot, nextCamelot)
        return when {
            dist <= 1 -> 1.0
            dist == 2 -> 1.1
            dist <= 4 -> 1.25
            else -> 1.4
        }
    }

    /**
     * Calculate the playback speed ratio to match the incoming track's BPM to the current track's BPM.
     * Returns a ratio to apply to the incoming track's speed (e.g., 1.05 = speed up 5%).
     * Handles halftime/doubletime harmonic BPM relationships (e.g., 70 BPM ≈ 140 BPM / 2).
     * Returns 1.0 if no BPM data or the ratio exceeds the safe adjustment range.
     */
    private fun calculateBpmSpeedRatio(
        currentVideoId: String,
        nextVideoId: String,
    ): Float {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentBpm = currentMeta?.bpm
        val nextBpm = nextMeta?.bpm

        if (currentBpm == null || nextBpm == null) {
            Logger.d(
                TAG,
                "AutoMix BPM: missing data - current=$currentBpm (cached=${currentMeta != null}), " +
                    "next=$nextBpm (cached=${nextMeta != null})",
            )
            return 1.0f
        }
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0f

        // Ratio is APPLIED to outgoing player so its effective BPM matches next track.
        //   outgoing_effective_BPM = currentBpm × ratio
        //   want outgoing_effective_BPM = nextBpm
        //   → ratio = nextBpm / currentBpm
        var ratio = nextBpm.toFloat() / currentBpm.toFloat()

        // Normalize halftime/doubletime relationships (e.g., 140/70 → 1.0, 70/140 → 1.0)
        while (ratio > 1.5f) ratio /= 2f
        while (ratio < 0.67f) ratio *= 2f

        Logger.d(TAG, "AutoMix BPM: current=$currentBpm, next=$nextBpm, ratio=${"%.4f".format(ratio)}")

        // Only apply if adjustment is within safe range (avoids unnatural artifacts)
        // Quantize to SPEED_PITCH_STEP to avoid SonicAudioProcessor artifacts
        return if (ratio in BPM_RATIO_MIN..BPM_RATIO_MAX) {
            quantize(ratio)
        } else {
            Logger.d(TAG, "AutoMix BPM: ratio ${"%.4f".format(ratio)} outside safe range [$BPM_RATIO_MIN..$BPM_RATIO_MAX], skipping")
            1.0f
        }
    }

    // ========== Camelot Wheel Key Matching ==========

    /**
     * Camelot Wheel position: number (1-12) + type (minor=A, major=B).
     * Standard DJ key compatibility system — adjacent codes are harmonically compatible.
     */
    private data class CamelotCode(
        val number: Int,
        val isMinor: Boolean,
    ) {
        override fun toString(): String = "$number${if (isMinor) "A" else "B"}"
    }

    /**
     * Map a musical key + scale to its Camelot Wheel code.
     *
     * Camelot Wheel (minor = A, major = B):
     *  1A=Abm  1B=B     |  7A=Dm   7B=F
     *  2A=Ebm  2B=F#    |  8A=Am   8B=C
     *  3A=Bbm  3B=Db    |  9A=Em   9B=G
     *  4A=Fm   4B=Ab    | 10A=Bm  10B=D
     *  5A=Cm   5B=Eb    | 11A=F#m 11B=A
     *  6A=Gm   6B=Bb    | 12A=C#m 12B=E
     */
    private fun keyToCamelot(
        key: String,
        keyScale: String?,
    ): CamelotCode? {
        val semitone = keyToSemitone(key)
        if (semitone < 0) return null

        val isMinor = keyScale?.uppercase()?.contains("MIN") == true

        // Camelot number lookup by chromatic semitone (C=0, C#=1, D=2, ... B=11)
        //                                   C  C# D  D# E  F  F# G  G# A  A# B
        val minorCamelotByPitch = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val majorCamelotByPitch = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

        val number = if (isMinor) minorCamelotByPitch[semitone] else majorCamelotByPitch[semitone]
        return CamelotCode(number, isMinor)
    }

    /**
     * Calculate the Camelot distance between two keys.
     * Considers both the circular number distance (0-6) and type difference (A/B).
     *
     * Compatible transitions (distance <= 1):
     * - Same code (8A->8A): distance 0
     * - Adjacent number, same type (8A->7A, 8A->9A): distance 1
     * - Same number, different type / relative key (8A->8B): distance 1
     *
     * Near-compatible (distance 2, same type):
     * - +-2 same type (8A->10A): 2 semitones apart (whole tone) — safe to shift
     */
    private fun camelotDistance(
        a: CamelotCode,
        b: CamelotCode,
    ): Int {
        val numberDiff = abs(a.number - b.number)
        val circularDist = minOf(numberDiff, 12 - numberDiff)
        val typeDiff = if (a.isMinor != b.isMinor) 1 else 0
        return circularDist + typeDiff
    }

    /**
     * Calculate pitch ratio using Camelot Wheel for musically correct key matching.
     *
     * Tries ±1 then ±2 semitone shifts on the outgoing track and picks the
     * smallest shift that brings Camelot distance ≤ 1 (harmonically compatible).
     * ±1 semitone is nearly imperceptible during crossfade; ±2 is a whole tone.
     * If no small shift achieves compatibility, returns 1.0 (no shift).
     */
    private fun calculateKeyPitchRatio(
        currentVideoId: String,
        nextVideoId: String,
    ): Float {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentKey = currentMeta?.key
        val nextKey = nextMeta?.key

        if (currentKey == null || nextKey == null) {
            Logger.d(
                TAG,
                "AutoMix Key: missing data - currentKey=$currentKey (cached=${currentMeta != null}), " +
                    "nextKey=$nextKey (cached=${nextMeta != null})",
            )
            return 1.0f
        }

        val currentCamelot = keyToCamelot(currentKey, currentMeta.keyScale)
        val nextCamelot = keyToCamelot(nextKey, nextMeta.keyScale)

        if (currentCamelot == null || nextCamelot == null) {
            Logger.d(
                TAG,
                "AutoMix Key: unknown key format - currentKey='$currentKey' ${currentMeta.keyScale}, " +
                    "nextKey='$nextKey' ${nextMeta.keyScale}",
            )
            return 1.0f
        }

        val dist = camelotDistance(currentCamelot, nextCamelot)

        Logger.d(
            TAG,
            "AutoMix Key: current=$currentKey ${currentMeta.keyScale} ($currentCamelot), " +
                "next=$nextKey ${nextMeta.keyScale} ($nextCamelot), camelotDist=$dist",
        )

        if (dist <= 1) {
            Logger.d(TAG, "AutoMix Key: compatible (dist=$dist), no shift")
            return 1.0f
        }

        val currentSemitone = keyToSemitone(currentKey)
        if (currentSemitone < 0) return 1.0f

        val isMinor = currentCamelot.isMinor
        //                                   C  C# D  D# E  F  F# G  G# A  A# B
        val minorCamelotByPitch = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val majorCamelotByPitch = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

        for (shift in intArrayOf(-1, 1, -2, 2)) {
            val shiftedSemitone = (currentSemitone + shift + 12) % 12
            val shiftedNumber =
                if (isMinor) minorCamelotByPitch[shiftedSemitone] else majorCamelotByPitch[shiftedSemitone]
            val shiftedCamelot = CamelotCode(shiftedNumber, isMinor)
            if (camelotDistance(shiftedCamelot, nextCamelot) <= 1) {
                val pitchRatio = exp(ln(2.0) * shift.toDouble() / 12.0).toFloat()
                Logger.d(
                    TAG,
                    "AutoMix Key: shift $shift semitones ($currentCamelot→$shiftedCamelot), " +
                        "ratio=${"%.4f".format(pitchRatio)}",
                )
                return pitchRatio
            }
        }

        Logger.d(TAG, "AutoMix Key: dist=$dist, no safe shift within ±2 semitones")
        return 1.0f
    }

    /**
     * Map a musical key name to its chromatic semitone number (0-11).
     * C=0, C#/Db=1, D=2, ..., B=11. Returns -1 for unknown keys.
     *
     * Tidal spells accidentals out ("FSharp", "CSharp") instead of using symbols,
     * so the name is normalised before matching.
     */
    private fun keyToSemitone(key: String): Int {
        val normalized =
            key
                .trim()
                .replace("Sharp", "#", ignoreCase = true)
                .replace("Flat", "b", ignoreCase = true)
                .replaceFirstChar { it.uppercaseChar() }
        return when (normalized) {
            "C" -> 0
            "C#", "Db" -> 1
            "D" -> 2
            "D#", "Eb" -> 3
            "E" -> 4
            "F" -> 5
            "F#", "Gb" -> 6
            "G" -> 7
            "G#", "Ab" -> 8
            "A" -> 9
            "A#", "Bb" -> 10
            "B" -> 11
            else -> -1
        }
    }

    companion object {
        /** Floor for the shortest track worth crossfading, in ms. */
        private const val MIN_CROSSFADE_TRACK_MS = 20_000L

        // Stall watchdog (see startPositionUpdates): how long the position may stay frozen
        // while the player means to be playing before the track is re-resolved in place, and
        // how many times per track load that cure may fire before it gives up.
        private const val STALL_WATCHDOG_THRESHOLD_MS = 15_000L
        private const val STALL_WATCHDOG_MAX_FIRES = 2

        // A pause longer than this makes play() reload the track with a fresh resolve
        // instead of resuming the prepared player (see play()). The value mirrors the
        // 600s margin past which the data source stops trusting a stored stream URL
        // (STORED_URL_EXPIRY_MARGIN_SECONDS in Media3ServiceModule).
        private const val LONG_PAUSE_RESUME_MS = 600_000L

        // DJ crossfade sigmoid steepness (higher = sharper S-curve transition)
        private const val DJ_FILTER_SIGMOID_K = 6f

        // DJ crossfade filter frequency bounds
        private const val LPF_START_HZ = 20000f // Low-pass starts wide open
        private const val LPF_END_HZ = 200f // Low-pass ends muffled (keeps bass thump like Pioneer DJM)
        private const val HPF_START_HZ = 2000f // High-pass starts lower — incoming track fills in faster
        private const val HPF_END_HZ = 20f // High-pass ends wide open

        // AutoMix constants
        private const val AUTO_FALLBACK_DURATION_MS = 30000 // Default when no BPM data
        private const val STRUCTURE_RETRY_MS = 30_000L
        private const val STRUCTURE_MAX_ATTEMPTS = 3
        private const val AUTO_MIN_DURATION_MS = 20000
        private const val AUTO_MAX_DURATION_MS = 45000
        private val BEAT_COUNT_OPTIONS = intArrayOf(8, 16, 24, 32, 40, 48, 64, 80, 96)
        private const val DEFAULT_BEAT_COUNT = 32
        private const val BPM_RATIO_MIN = 0.75f // Max 25% slower
        private const val BPM_RATIO_MAX = 1.25f // Max 25% faster

        // BPM gap → duration scale: 0% gap → 1.0x, 25% gap → 1.5x
        private const val BPM_GAP_DURATION_SCALE = 2.0

        // Default gap factor when BPM or key data is missing — assume moderate incompatibility
        private const val UNKNOWN_GAP_DEFAULT_FACTOR = 1.25

        // Quantization step for speed/pitch ramp (0.5% = 0.005)
        // Prevents SonicAudioProcessor from popping on micro-adjustments
        private const val SPEED_PITCH_STEP = 0.02f

        // Front-loaded BPM/pitch ramp portion (fraction of crossfade duration).
        // Outgoing tempo reaches target within the first [BPM_RAMP_PORTION] of the
        // crossfade (smoothstep S-curve) and then holds for the remainder.
        private const val BPM_RAMP_PORTION = 0.6f
    }

    /**
     * Finalize crossfade: swap players and cleanup.
     * Mirrors GstreamerPlayerAdapter.finalizeCrossfade()
     */
    private fun finalizeCrossfade(
        nextIndex: Int,
        nextPlayer: ExoPlayer,
    ) {
        // The incoming track failed during the fade. onPlayerError already routed the error
        // (retry or propagate) — promoting this player and forcing PLAYING would paper over
        // it, leaving the UI claiming playback in silence. Tear the fade down instead and
        // ensure ForwardingPlayer never stays bound to a released ExoPlayer.
        if (nextPlayer.playerError != null) {
            Logger.w(TAG, "Crossfade finalize: incoming player errored (${nextPlayer.playerError?.errorCodeName}); not promoting it")
            if (secondaryPlayer === nextPlayer) {
                secondaryPlayer = null
                secondaryPlayerFilter = null
            }
            if (forwardingPlayer.wrappedPlayer === nextPlayer) {
                val fallback = currentPlayer ?: createExoPlayerInstance().also {
                    currentPlayer = it.player
                    currentPlayerFilter = it.filter
                }.player
                forwardingPlayer.swapDelegate(fallback)
                setupPlayerListenerInternal(fallback)
            }
            cleanupPlayerInternal(nextPlayer)
            currentPlayerFilter?.enabled = false
            currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
            setCrossfading(false)
            crossfadeFromIndex = -1
            return
        }

        Logger.d(TAG, "Crossfade complete, swapping players")

        // Cleanup old current player WITHOUT touching listeners
        // (listeners are already setup for nextPlayer via setupPlayerListenerInternal)
        stopPositionUpdates()

        // Cleanup the old current player manually
        currentPlayer?.let { oldPlayer ->
            cleanupPlayerInternal(oldPlayer)
        }

        // Disable and reset DJ filters on the new current player (no overhead during normal playback)
        secondaryPlayerFilter?.let { filter ->
            filter.enabled = false
        }
        // Old player's filter is released with the old player (GC'd)

        // Promote secondary to current
        currentPlayer = nextPlayer
        currentPlayerFilter = secondaryPlayerFilter
        secondaryPlayer = null
        secondaryPlayerFilter = null
        // localCurrentMediaItemIndex already updated in triggerCrossfadeTransition()

        // Audio focus is held at the adapter level (see Audio Focus section),
        // not per-player, so it survives this crossfade swap (#2155).

        // Ensure correct volume and playback parameters
        currentPlayer?.volume = if (isDucked) internalVolume * duckVolumeFactor else internalVolume
        currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        currentPlayer?.skipSilenceEnabled = internalSkipSilence

        // Reset watchdog tracking for the newly promoted track
        val activeMediaId = nextPlayer.currentMediaItem?.mediaId ?: playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
        if (watchdogVideoId != activeMediaId) {
            watchdogVideoId = activeMediaId
            watchdogFireCount = 0
        }

        // Reset state
        setCrossfading(false)
        crossfadeFromIndex = -1
        transitionToState(InternalState.PLAYING)

        // Ensure MediaSession notification has correct metadata after crossfade completes.
        forwardingPlayer.notifyMediaItemChanged()

        // Start position tracking
        startPositionUpdates()

        // Trigger next precache
        triggerPrecachingInternal()

        // Keep full-track cache preloader advancing across crossfaded transitions as well.
        if (!activeMediaId.isNullOrBlank()) {
            val upcomingIndex = getNextMediaItemIndex()
            trackCachePreloader.onTrackStarted(
                activeMediaId,
                playlist.getOrNull(upcomingIndex)?.mediaId?.takeIf { upcomingIndex != localCurrentMediaItemIndex },
            )
        }
    }

    // ========== Internal: Position Updates ==========

    /**
     * Start position updates (periodic background task).
     * Also handles crossfade detection when position approaches end.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        // Stall watchdog state — local cursor/stall timer per loop, while watchdogFireCount stays
        // class-scoped per track so a watchdog reload cannot reset its own retry budget (#stall-loop).
        var watchdogLastPositionMs = -1L
        var watchdogStallStartMs = 0L
        var watchdogTrackHasPlayed = false

        positionUpdateJob =
            adapterScope.launch {
                while (isActive && currentPlayer != null) {
                    var watchdogFired = false
                    try {
                        currentPlayer?.let { player ->
                            if (internalState == InternalState.PLAYING ||
                                internalState == InternalState.READY ||
                                internalState == InternalState.PAUSED
                            ) {
                                // During crossfade, show the incoming track's timeline
                                val timelinePlayer = if (isCrossfading) secondaryPlayer ?: player else player
                                val pos = timelinePlayer.currentPosition
                                val dur = timelinePlayer.duration
                                val buf = timelinePlayer.bufferedPosition

                                if (pos >= 0) cachedPosition = pos
                                if (dur > 0) cachedDuration = dur
                                if (buf >= 0) cachedBufferedPosition = buf

                                // Check if should trigger crossfade.
                                // Add a preparation buffer (3s) so that if the next track
                                // is NOT precached, URL resolution + buffering time doesn't
                                // eat into the audible crossfade window.
                                if (crossfadeEnabled &&
                                    !crossfadeSuppressed &&
                                    internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                                    !isCrossfading &&
                                    crossfadeTransitionJob?.isActive != true &&
                                    player.isPlaying &&
                                    dur > 0 &&
                                    pos > 0 &&
                                    !isCurrentTrackVideo() &&
                                    !isNextTrackVideo() &&
                                    !isCurrentTrackLive() &&
                                    !isNextTrackLive() &&
                                    !isCurrentTrackTooShortForCrossfade() &&
                                    !isWithinAlbum()
                                ) {
                                    // Account for playback speed: at higher speed, media time
                                    // is consumed faster, so wall-clock remaining is shorter
                                    val speed = internalPlaybackSpeed.coerceAtLeast(0.1f)
                                    val timeRemaining = ((dur - pos) / speed).toLong()
                                    val nextVideoId = playlist.getOrNull(getNextMediaItemIndex())?.mediaId
                                    val isPrecached = nextVideoId != null && precachedPlayers.containsKey(nextVideoId)
                                    // If next track is precached, trigger at exactly crossfadeDurationMs.
                                    // If NOT precached, trigger 3s earlier to allow preparation time.
                                    val preparationBufferMs = if (isPrecached) 0L else 3000L
                                    val isAutoMode = crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO
                                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""
                                    if (isAutoMode) measureTrackStructures(currentVideoId, nextVideoId ?: "")
                                    val plan = if (isAutoMode) transitionPlan(currentVideoId, nextVideoId ?: "") else null
                                    // In Auto mode, calculate beat-quantized duration for trigger threshold.
                                    val resolvedDurationMs =
                                        if (isAutoMode) {
                                            resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId ?: "")
                                        } else {
                                            crossfadeDurationMs
                                        }
                                    val triggerThreshold = resolvedDurationMs.toLong() + preparationBufferMs
                                    // A placed fade starts when the outgoing track reaches its window,
                                    // with no preparation lead: the incoming track must not start early,
                                    // or its drop misses the phrase end. Past the window (a seek) it falls
                                    // back to the end of the track.
                                    val reached =
                                        if (plan != null && pos < plan.outgoingEndMs) {
                                            pos >= plan.outgoingStartMs
                                        } else {
                                            timeRemaining in 1..triggerThreshold
                                        }
                                    if (reached) {
                                        if (hasNextMediaItem()) {
                                            val nextIndex = getNextMediaItemIndex()
                                            triggerCrossfadeTransition(nextIndex)
                                        }
                                    }
                                }

                                // === Stall watchdog ===
                                // This adapter has no mid-track buffering state: a connection
                                // that stays alive but stops delivering (or a loader backing
                                // off forever) never produces an error, so nothing recovers
                                // and the UI spins forever. If this track has already played,
                                // is meant to be playing, and its position has not advanced
                                // for STALL_WATCHDOG_THRESHOLD_MS while the player sits in
                                // STATE_BUFFERING, run the same re-resolution cure the
                                // onPlayerError retry uses, at the same position. Armed only
                                // after real progress, so the initial load (covered by the
                                // resolver timeout), pauses and live streams never trigger
                                // it; capped per track load so a hopeless track still ends
                                // in the normal error surfacing instead of a reload loop.
                                if (player.isPlaying) watchdogTrackHasPlayed = true
                                val stallCandidate =
                                    watchdogTrackHasPlayed &&
                                        internalState == InternalState.PLAYING &&
                                        internalPlayWhenReady &&
                                        player.playWhenReady &&
                                        !player.isPlaying &&
                                        player.playbackState == Player.STATE_BUFFERING &&
                                        !isCrossfading &&
                                        !isCurrentTrackLive()
                                if (stallCandidate) {
                                    if (pos > watchdogLastPositionMs) {
                                        watchdogLastPositionMs = pos
                                        watchdogStallStartMs = 0L
                                    } else {
                                        val nowElapsedMs = SystemClock.elapsedRealtime()
                                        if (watchdogStallStartMs == 0L) {
                                            watchdogStallStartMs = nowElapsedMs
                                        }
                                        val watchdogStallMs = nowElapsedMs - watchdogStallStartMs
                                        if (watchdogStallMs >= STALL_WATCHDOG_THRESHOLD_MS &&
                                            watchdogFireCount < STALL_WATCHDOG_MAX_FIRES
                                        ) {
                                            val stallVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                                            if (stallVideoId != null) {
                                                watchdogVideoId = stallVideoId
                                                watchdogFireCount++
                                                Logger.w(
                                                    TAG,
                                                    "Stall watchdog: no progress for ${watchdogStallMs}ms on $stallVideoId; " +
                                                        "re-resolving at ${cachedPosition.coerceAtLeast(0L)}ms " +
                                                        "(fire $watchdogFireCount/$STALL_WATCHDOG_MAX_FIRES)",
                                                )
                                                try {
                                                    streamRepository.onStreamRejected(stallVideoId)
                                                    streamRepository.invalidateFormat(stallVideoId)
                                                    streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$stallVideoId")
                                                } catch (e: Exception) {
                                                    if (e is CancellationException) throw e
                                                    Logger.e(TAG, "Stall watchdog: invalidate failed: ${e.message}", e)
                                                }
                                                precachedPlayers.remove(stallVideoId)?.let { cleanupPrecachedEntryInternal(it) }
                                                loadAndPlayTrackInternal(
                                                    localCurrentMediaItemIndex,
                                                    cachedPosition.coerceAtLeast(0L),
                                                    shouldPlay = true,
                                                )
                                                // The reload restarts position updates itself.
                                                watchdogFired = true
                                            }
                                        }
                                    }
                                } else {
                                    watchdogStallStartMs = 0L
                                    if (!isCrossfading && pos > watchdogLastPositionMs) {
                                        watchdogLastPositionMs = pos
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Ignore query errors - don't log to avoid spam
                    }

                    if (watchdogFired) break

                    delay(50) // Update every 50ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // ========== Internal: Precaching ==========

    /**
     * Trigger precaching - mirrors GstreamerPlayerAdapter.triggerPrecachingInternal()
     *
     * Creates ExoPlayer instances for upcoming tracks. Each player gets a MediaItem
     * and calls prepare(), which triggers URL resolution and buffering via MediaSourceFactory.
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty() || isCastActive) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            adapterScope.launch {
                try {
                    // Prioritize initial startup of the current track while it is still
                    // preparing/buffering (up to 1.2s), then debounce rapid skip bursts briefly
                    // (300ms) instead of stalling precache for 3 full seconds on every track.
                    var waitedMs = 0L
                    while (isActive &&
                        waitedMs < 1_200L &&
                        currentPlayer?.playbackState == Player.STATE_BUFFERING
                    ) {
                        delay(100)
                        waitedMs += 100L
                    }
                    delay(300)
                    // The tracks Next will actually play, in Next's own order: shuffle-aware, wrapping
                    // under Repeat All. Picking `index + i` in playlist order precached tracks a shuffled
                    // queue never reached, and nothing released them until the queue was edited (#2619).
                    // Read before the job first suspends, so an edit landing meanwhile cannot shift them.
                    val targets = mutableListOf<GenericMediaItem>()
                    var from = localCurrentMediaItemIndex
                    for (i in 1..maxPrecacheCount) {
                        val next = getNextMediaItemIndex(from)
                        if (next == from || next == localCurrentMediaItemIndex) break
                        playlist.getOrNull(next)?.let { targets += it }
                        from = next
                    }

                    // Release every precache Next no longer reaches: the shuffle picks it skipped, the
                    // tracks ahead of a Previous or a jump. The current track's entry stays: a trigger
                    // can run between the index moving and that track's load taking its entry.
                    val targetIds = targets.mapTo(HashSet()) { it.mediaId }
                    val currentId = currentMediaItem?.mediaId
                    precachedPlayers.entries.removeIf { (id, cached) ->
                        val stale = id !in targetIds && id != currentId
                        if (stale) {
                            streamResolveCoordinator?.cancel(id)
                            cleanupPrecachedEntryInternal(cached)
                        }
                        stale
                    }

                    for (mediaItem in targets) {
                        if (!isActive) break

                        // Checked here, not when the targets were picked: one track can be both targets
                        // (a radio repeats songs), and a second insert would overwrite the first player
                        // without releasing it. A live broadcast is not precached: buffered ahead, it
                        // would start behind the live edge by however long it waited in the queue.
                        if (precachedPlayers.containsKey(mediaItem.mediaId) || LiveStreamRegistry.isLive(mediaItem.mediaId)) {
                            continue
                        }

                        try {
                            val pwf = createExoPlayerInstance(mediaItem.mediaId)
                            pwf.player.setMediaItem(mediaItem.toMedia3MediaItem())
                            val retryListener = precacheRetryListener(mediaItem.mediaId, pwf.player)
                            pwf.player.addListener(retryListener)
                            pwf.player.prepare()
                            precachedPlayers[mediaItem.mediaId] =
                                PrecachedPlayer(pwf.player, mediaItem, pwf.filter, retryListener)
                            Logger.d(TAG, "Precached player for ${mediaItem.mediaId}")
                        } catch (e: Exception) {
                            Logger.e(TAG, "Precaching error for ${mediaItem.mediaId}: ${e.message}")
                        }

                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Precaching error: ${e.message}")
                    }
                }
            }
    }

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    /**
     * Retries a precached player the way the playing one is retried in [setupPlayerListenerInternal]:
     * drop the stored format so the resolver fetches a fresh URL, then prepare the same player again.
     * That listener only serves the playing player, so a precache that failed past Media3's own load
     * retries used to stay failed until its turn (#2239). After [maxRetryCount] tries it is left as it is.
     */
    private fun precacheRetryListener(
        videoId: String,
        player: ExoPlayer,
    ): Player.Listener =
        object : Player.Listener {
            private var attempts = 0

            override fun onPlayerError(error: PlaybackException) {
                if (error.isUnrecoverableStreamFailure() || !error.isRetryableSourceError() || attempts >= maxRetryCount) return
                if (precachedPlayers[videoId]?.player !== player) return
                attempts++
                Logger.w(TAG, "Precache source error (attempt $attempts/$maxRetryCount) for $videoId: ${error.errorCodeName}")
                adapterScope.launch {
                    try {
                        streamRepository.onStreamRejected(videoId)
                        streamRepository.invalidateFormat(videoId)
                        streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                        // Promoted or released while the format was being dropped: nothing left to retry.
                        if (precachedPlayers[videoId]?.player === player) player.prepare()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.e(TAG, "Precache retry failed for $videoId: ${e.message}")
                    }
                }
            }
        }

    /** Source errors a fresh stream URL can fix; the codes are explained in [setupPlayerListenerInternal]. */
    private fun PlaybackException.isRetryableSourceError(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            // Network failures get the same cure as a 403: stored stream URLs are
            // bound to the client IP, so a connection that dies mid-load (timeout,
            // reset, no route) often means the URL died early rather than the track
            // being broken. Dropping the stored format and reloading re-resolves a
            // fresh URL from the same position; retries stay bounded by
            // maxRetryCount, and a device that is simply offline exhausts them and
            // surfaces the error exactly as before.
            errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    private fun cleanupPrecachedEntryInternal(cached: PrecachedPlayer) {
        cached.retryListener?.let { cached.player.removeListener(it) }
        cleanupPlayerInternal(cached.player)
    }

    private fun clearStalePrecachesOnSkipInternal(
        currentIndex: Int,
        currentVideoId: String,
    ) {
        if (precachedPlayers.isEmpty()) return
        val keepIds = HashSet<String>()
        keepIds += currentVideoId
        var walk = currentIndex
        for (i in 1..maxPrecacheCount) {
            val next = getNextMediaItemIndex(walk)
            if (next == walk || next == currentIndex) break
            playlist.getOrNull(next)?.mediaId?.let { keepIds += it }
            walk = next
        }
        precachedPlayers.entries.removeIf { (id, cached) ->
            val stale = id !in keepIds
            if (stale) {
                streamResolveCoordinator?.cancel(id)
                cleanupPrecachedEntryInternal(cached)
            }
            stale
        }
    }

    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        val activeId = currentMediaItem?.mediaId
        precachedPlayers.entries.removeIf { (videoId, cached) ->
            if (videoId != activeId) {
                streamResolveCoordinator?.cancel(videoId)
                cleanupPrecachedEntryInternal(cached)
                true
            } else {
                false
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        runOnAdapterMain {
            if (precachedPlayers.isEmpty()) return@runOnAdapterMain
            if ((level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) &&
                !internalPlayWhenReady
            ) {
                Logger.d(TAG, "TrimMemory level=$level (paused): clearing all ${precachedPlayers.size} precached players")
                clearAllPrecacheInternal()
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                // Keep at most the immediate next track under low memory, freeing extra ExoPlayer instances.
                val nextIdx = getNextMediaItemIndex(localCurrentMediaItemIndex)
                val keepNextId = playlist.getOrNull(nextIdx)?.mediaId
                val currentId = currentMediaItem?.mediaId
                val iterator = precachedPlayers.entries.iterator()
                while (iterator.hasNext()) {
                    val (id, cached) = iterator.next()
                    if (id != keepNextId && id != currentId) {
                        streamResolveCoordinator?.cancel(id)
                        cleanupPrecachedEntryInternal(cached)
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        val activeId = currentMediaItem?.mediaId
        precachedPlayers.forEach { (videoId, cached) ->
            if (videoId != activeId) {
                streamResolveCoordinator?.cancel(videoId)
            }
            cleanupPrecachedEntryInternal(cached)
        }
        precachedPlayers.clear()
    }

    // ========== Internal: Notifications ==========

    private fun notifyEqualizerIntent(shouldOpen: Boolean) {
        listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(shouldOpen) }
    }

    // ========== Internal: Shuffle Management ==========
    // Mirrors GstreamerPlayerAdapter shuffle management exactly

    private fun createShuffleOrder() {
        // A rebuilt order puts no track in any particular place, the pinned ones included.
        pinnedNextItems.clear()
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }

        val indices = playlist.indices.toMutableList()

        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) {
            indices.removeAt(currentIndex)
        }

        indices.shuffle()

        if (currentIndex in playlist.indices) {
            indices.add(0, currentIndex)
        }

        shuffleOrder.clear()
        shuffleOrder.addAll(indices)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            shuffleIndices[originalIndex] = shuffledPos
        }

        Logger.d(TAG, "Created shuffle order: $shuffleOrder")
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
        pinnedNextItems.clear()
        Logger.d(TAG, "Cleared shuffle order")
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) {
            return
        }

        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) {
                shuffleOrder[i]++
            }
        }

        val insertPos = (afterShufflePos + 1).coerceIn(0, shuffleOrder.size)
        shuffleOrder.add(insertPos, insertedOriginalIndex)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, origIndex ->
            if (origIndex < shuffleIndices.size) {
                shuffleIndices[origIndex] = shuffledPos
            }
        }

        Logger.d(
            TAG,
            "Inserted index $insertedOriginalIndex into shuffle at position $insertPos (after shuffle pos $afterShufflePos)",
        )
    }

    /**
     * Gives the track just appended to [playlist] a random place after the current one, leaving
     * every other track where it was. Rebuilding the order here (createShuffleOrder) reshuffled the
     * whole queue on every Add to queue and on every track an endless queue appended, and put tracks
     * already played back in front of the listener.
     *
     * The slots right after the current track that hold [pinnedNextItems] are skipped: a Play next
     * track, and the track a crossfade has picked. The fade chooses its next index first and only
     * makes it current after loading it, so a track inserted ahead of it meanwhile would end up
     * behind Now Playing. Every other slot stays open, so a queue built by appends is still a fair
     * shuffle, its second track included.
     *
     * The full rebuild stays as the fallback for an order that no longer covers the playlist. Appends
     * can run on another thread than the one editing the order, and rebuilding is what used to repair
     * such an order on the next append.
     */
    private fun insertAppendedIntoShuffleOrder() {
        val appendedIndex = playlist.lastIndex
        if (!shuffleOrderCovers(appendedIndex)) {
            createShuffleOrder()
            return
        }
        val orderSize = shuffleOrder.size
        val currentShufflePos =
            (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1).coerceIn(-1, orderSize - 1)
        var firstOpenPos = currentShufflePos + 1
        while (firstOpenPos < orderSize) {
            val item = shuffleOrder.getOrNull(firstOpenPos)?.let { playlist.getOrNull(it) } ?: break
            if (item !in pinnedNextItems) break
            firstOpenPos++
        }
        insertIntoShuffleOrder(appendedIndex, Random.nextInt(firstOpenPos, orderSize + 1) - 1)
    }

    /** Whether [shuffleOrder] is a permutation of the playlist indices `0 until size`. */
    private fun shuffleOrderCovers(size: Int): Boolean {
        if (shuffleOrder.size != size || shuffleIndices.size != size) return false
        val seen = BooleanArray(size)
        for (i in 0 until size) {
            val index = shuffleOrder.getOrNull(i) ?: return false
            if (index !in 0 until size || seen[index]) return false
            seen[index] = true
        }
        return true
    }

    /**
     * Releases the pin of the track that just became current. It has had its turn: still pinned, it
     * would hold every later append back once the queue wraps around to it again.
     */
    private fun unpinCurrent() {
        playlist.getOrNull(localCurrentMediaItemIndex)?.let { pinnedNextItems.remove(it) }
    }

    /**
     * Takes [removedOriginalIndex] — already removed from the playlist — out of the shuffled order,
     * renumbering the playlist indices after it, so every other track keeps its place in the order.
     */
    private fun removeFromShuffleOrder(removedOriginalIndex: Int) {
        shuffleOrder.remove(removedOriginalIndex)
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] > removedOriginalIndex) {
                shuffleOrder[i]--
            }
        }
        rebuildShuffleIndices()
    }

    /** Re-derives the playlist index → shuffled position map from [shuffleOrder]. */
    private fun rebuildShuffleIndices() {
        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            if (originalIndex in shuffleIndices.indices) {
                shuffleIndices[originalIndex] = shuffledPos
            }
        }
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        listeners.forEach { it.onTimelineChanged(list, reason) }
    }
}
