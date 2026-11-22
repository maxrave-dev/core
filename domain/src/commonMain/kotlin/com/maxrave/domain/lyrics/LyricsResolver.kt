package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deterministic, cancelable lyrics orchestrator:
 * - Optional cache-first SWR emission for instant display and offline resilience.
 * - Tries preferred provider first with bounded timeout and parallel hedged fallback.
 * - Ranks quality (RICH_SYNCED > LINE_SYNCED > UNSYNCED), upgrading in background without
 *   ever downgrading or overwriting user-authored lyrics.
 * - Distinguishes definitive 404/empty exhaustion from transient network failures.
 */
class LyricsResolver(
    private val readCache: suspend (videoId: String) -> Lyrics?,
    private val fetchSource: suspend (source: LyricsSource, request: LyricsLookupRequest) -> ProviderFetchOutcome,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val hedgeDelayMs: Long = DEFAULT_HEDGE_DELAY_MS,
) {
    fun resolve(request: LyricsLookupRequest): Flow<LyricsResolutionEvent> =
        channelFlow {
            var currentBestLyrics: Lyrics? = null
            var currentBestLevel = LyricsQuality.LEVEL_INVALID
            var currentFromCache = false
            var cachedSignature: Int? = null
            var simpMusicNotFoundObserved = false
            var anyTransientFailure = false

            val titleInstrumental = LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle(request.title)
            if (request.preferCache) {
                val cached = readCache(request.videoId)
                val cachedLevel = LyricsQuality.level(cached)
                if (cached != null && cachedLevel > LyricsQuality.LEVEL_INVALID) {
                    val signature = LyricsQuality.contentSignature(cached)
                    val isUserProtected = signature in request.userProtectedSignatures
                    if (!isUserProtected && titleInstrumental) {
                        send(
                            LyricsResolutionEvent.Exhausted(
                                videoId = request.videoId,
                                definitiveNotFound = true,
                                confirmedInstrumental = true,
                            ),
                        )
                        return@channelFlow
                    }
                    cachedSignature = signature
                    currentBestLyrics = cached
                    currentBestLevel = cachedLevel
                    currentFromCache = true
                    send(
                        LyricsResolutionEvent.Found(
                            LyricsResolution(
                                videoId = request.videoId,
                                lyrics = cached,
                                source = LyricsSource.OFFLINE,
                                fromCache = true,
                                isUserProtected = isUserProtected,
                            ),
                        ),
                    )
                    if (isUserProtected || cachedLevel >= LyricsQuality.LEVEL_RICH_SYNCED || !request.allowUpgradeScan) {
                        return@channelFlow
                    }
                }
            }
            if (titleInstrumental) {
                send(
                    LyricsResolutionEvent.Exhausted(
                        videoId = request.videoId,
                        definitiveNotFound = true,
                        confirmedInstrumental = true,
                    ),
                )
                return@channelFlow
            }

            val sources = orderedSources(request.preferredSource, request.allowSpotify)
            if (sources.isEmpty()) {
                if (currentBestLyrics == null) {
                    send(LyricsResolutionEvent.Exhausted(request.videoId, definitiveNotFound = true))
                }
                return@channelFlow
            }

            val primary = sources.first()
            val others = sources.drop(1)
            val completionChannel = Channel<Pair<LyricsSource, ProviderFetchOutcome>>(sources.size)
            val launchMutex = Mutex()
            val launchedOtherJobs = ArrayList<Job>(others.size)
            var othersLaunched = false
            var pendingCount = 1

            fun launchSource(source: LyricsSource): Job =
                launch(start = CoroutineStart.DEFAULT) {
                    val outcome =
                        try {
                            withTimeoutOrNull(providerTimeoutMs) {
                                fetchSource(source, request)
                            } ?: ProviderFetchOutcome.TransientFailure("Timeout after ${providerTimeoutMs}ms")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            ProviderFetchOutcome.TransientFailure(t.message)
                        }
                    completionChannel.trySend(source to outcome)
                }

            suspend fun ensureOthersLaunched() {
                launchMutex.withLock {
                    if (othersLaunched || others.isEmpty()) return
                    othersLaunched = true
                    pendingCount += others.size
                    others.forEach { fallbackSource ->
                        launchedOtherJobs.add(launchSource(fallbackSource))
                    }
                }
            }

            val primaryJob = launchSource(primary)
            val hedgeJob =
                if (others.isNotEmpty()) {
                    launch {
                        delay(hedgeDelayMs)
                        ensureOthersLaunched()
                    }
                } else {
                    null
                }

            var handled = 0
            while (true) {
                val expected = launchMutex.withLock { pendingCount }
                if (handled >= expected) break
                val (source, outcome) = completionChannel.receive()
                handled++

                when (outcome) {
                    is ProviderFetchOutcome.ConfirmedInstrumental -> {
                        hedgeJob?.cancel()
                        primaryJob.cancel()
                        launchedOtherJobs.forEach { it.cancel() }
                        send(
                            LyricsResolutionEvent.Exhausted(
                                videoId = request.videoId,
                                definitiveNotFound = true,
                                confirmedInstrumental = true,
                            ),
                        )
                        return@channelFlow
                    }
                    is ProviderFetchOutcome.DefinitiveNotFound -> {
                        if (source == LyricsSource.SIMPMUSIC) {
                            simpMusicNotFoundObserved = true
                        }
                    }
                    is ProviderFetchOutcome.TransientFailure -> {
                        anyTransientFailure = true
                    }
                    is ProviderFetchOutcome.Success -> {
                        val candidateLevel = LyricsQuality.level(outcome.lyrics)
                        if (candidateLevel > LyricsQuality.LEVEL_INVALID) {
                            val signature = LyricsQuality.contentSignature(outcome.lyrics)
                            val isSameTierCacheRefresh =
                                currentFromCache &&
                                    source == request.preferredSource &&
                                    candidateLevel == currentBestLevel &&
                                    signature != cachedSignature
                            val isUpgrade = candidateLevel > currentBestLevel
                            if (isUpgrade || isSameTierCacheRefresh) {
                                currentBestLyrics = outcome.lyrics
                                currentBestLevel = candidateLevel
                                currentFromCache = false
                                send(
                                    LyricsResolutionEvent.Found(
                                        LyricsResolution(
                                            videoId = request.videoId,
                                            lyrics = outcome.lyrics,
                                            source = source,
                                            fromCache = false,
                                            youtubeTranslatedLyrics = outcome.translatedLyrics,
                                            simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                        ),
                                    ),
                                )
                            }
                        } else {
                            // Unusable payload treated as missing so fallback continues cleanly.
                            if (source == LyricsSource.SIMPMUSIC) simpMusicNotFoundObserved = true
                        }
                    }
                }

                if (currentBestLevel >= LyricsQuality.LEVEL_RICH_SYNCED) {
                    hedgeJob?.cancel()
                    primaryJob.cancel()
                    launchedOtherJobs.forEach { it.cancel() }
                    break
                }

                // If primary finished (with not-found or < RICH_SYNCED) or we have no lyrics yet,
                // start fallback/upgrade sources immediately rather than waiting the full hedge delay.
                if (source == primary || currentBestLyrics == null) {
                    val allowMoreSources = currentBestLyrics == null || request.allowUpgradeScan
                    if (allowMoreSources) {
                        hedgeJob?.cancel()
                        ensureOthersLaunched()
                    } else if (source == primary) {
                        hedgeJob?.cancel()
                        launchedOtherJobs.forEach { it.cancel() }
                        break
                    }
                }
            }

            if (currentBestLyrics == null) {
                send(
                    LyricsResolutionEvent.Exhausted(
                        videoId = request.videoId,
                        definitiveNotFound = !anyTransientFailure,
                    ),
                )
            }
        }

    companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 8_000L
        const val DEFAULT_HEDGE_DELAY_MS = 2_500L

        fun orderedSources(
            preferred: LyricsSource,
            allowSpotify: Boolean,
        ): List<LyricsSource> {
            val baseOrder =
                listOf(
                    LyricsSource.SIMPMUSIC,
                    LyricsSource.BETTER_LYRICS,
                    LyricsSource.SPOTIFY,
                    LyricsSource.LRCLIB,
                    LyricsSource.YOUTUBE,
                )
            return buildList {
                if (preferred != LyricsSource.OFFLINE && (preferred != LyricsSource.SPOTIFY || allowSpotify)) {
                    add(preferred)
                }
                for (source in baseOrder) {
                    if (source !in this && (source != LyricsSource.SPOTIFY || allowSpotify)) {
                        add(source)
                    }
                }
            }
        }
    }
}
