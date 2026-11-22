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
            var currentBestOverflow = false
            var currentFromCache = false
            var cachedSignature: Int? = null
            var simpMusicNotFoundObserved = false
            var anyTransientFailure = false
            var cacheInvalidated = false

            var verifiedBestLyrics: Lyrics? = null
            var pendingBlindCandidate: LyricsResolution? = null
            var pendingBlindLevel = LyricsQuality.LEVEL_INVALID
            var pendingBlindOverflow = false
            val finishedSources = HashSet<LyricsSource>(6)

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
                    val cachedContradicts =
                        !isUserProtected &&
                            LyricsIdentityGuard.contradictsTrack(cached, request.title, request.artist)
                    if (cachedContradicts) {
                        cacheInvalidated = true
                    } else {
                        val cachedOverflow = LyricsQuality.hasSyncedTimestampOverflow(cached, request.durationSeconds)
                        cachedSignature = signature
                        currentBestLyrics = cached
                        currentBestLevel = cachedLevel
                        currentBestOverflow = cachedOverflow
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
                        if (isUserProtected || (!cachedOverflow && (cachedLevel >= LyricsQuality.LEVEL_RICH_SYNCED || !request.allowUpgradeScan))) {
                            return@channelFlow
                        }
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

            val verificationSources = sources.filter { it.isCatalogVerified() }.toSet()

            fun hasPendingVerificationSource(): Boolean = verificationSources.any { it !in finishedSources }

            fun canAcceptBlindCandidate(candidate: Lyrics): Boolean {
                val verified = verifiedBestLyrics ?: return true
                return LyricsIdentityGuard.checkTextualConsensus(verified, candidate) != TextualConsensus.CONFLICT
            }

            suspend fun tryFlushPendingBlindCandidate() {
                val pending = pendingBlindCandidate ?: return
                if (!canAcceptBlindCandidate(pending.lyrics)) {
                    pendingBlindCandidate = null
                    pendingBlindLevel = LyricsQuality.LEVEL_INVALID
                    pendingBlindOverflow = false
                    return
                }
                if (hasPendingVerificationSource()) return

                val isBetterThanCurrent =
                    pendingBlindLevel > currentBestLevel ||
                        (currentBestLyrics != null && pendingBlindLevel == currentBestLevel && currentBestOverflow && !pendingBlindOverflow) ||
                        currentBestLyrics == null
                if (isBetterThanCurrent) {
                    currentBestLyrics = pending.lyrics
                    currentBestLevel = pendingBlindLevel
                    currentBestOverflow = pendingBlindOverflow
                    currentFromCache = false
                    send(
                        LyricsResolutionEvent.Found(
                            pending.copy(simpMusicNotFoundObserved = simpMusicNotFoundObserved),
                        ),
                    )
                }
                pendingBlindCandidate = null
                pendingBlindLevel = LyricsQuality.LEVEL_INVALID
                pendingBlindOverflow = false
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
                finishedSources.add(source)

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
                        val contradicts =
                            LyricsIdentityGuard.contradictsTrack(
                                lyrics = outcome.lyrics,
                                expectedTitle = request.title,
                                expectedArtist = request.artist,
                            )
                        if (candidateLevel > LyricsQuality.LEVEL_INVALID && !contradicts) {
                            val candidateOverflow =
                                LyricsQuality.hasSyncedTimestampOverflow(outcome.lyrics, request.durationSeconds)
                            if (source.isCatalogVerified() && verifiedBestLyrics == null) {
                                verifiedBestLyrics = outcome.lyrics
                            }

                            if (source.isBlindMetadataSource() &&
                                verifiedBestLyrics == null &&
                                hasPendingVerificationSource()
                            ) {
                                // Defer blind provider emission until catalog-verified sources
                                // confirm the lyrics belong to the same song (or exhaust).
                                val shouldHold =
                                    pendingBlindCandidate == null ||
                                        candidateLevel > pendingBlindLevel ||
                                        (candidateLevel == pendingBlindLevel && pendingBlindOverflow && !candidateOverflow)
                                if (shouldHold) {
                                    pendingBlindCandidate =
                                        LyricsResolution(
                                            videoId = request.videoId,
                                            lyrics = outcome.lyrics,
                                            source = source,
                                            fromCache = false,
                                            youtubeTranslatedLyrics = outcome.translatedLyrics,
                                            simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                        )
                                    pendingBlindLevel = candidateLevel
                                    pendingBlindOverflow = candidateOverflow
                                }
                            } else if (!source.isBlindMetadataSource() || canAcceptBlindCandidate(outcome.lyrics)) {
                                val signature = LyricsQuality.contentSignature(outcome.lyrics)
                                val isSameTierCacheRefresh =
                                    currentFromCache &&
                                        source == request.preferredSource &&
                                        candidateLevel == currentBestLevel &&
                                        signature != cachedSignature &&
                                        (!candidateOverflow || currentBestOverflow)
                                val isTimingFix =
                                    currentBestLyrics != null &&
                                        candidateLevel == currentBestLevel &&
                                        currentBestOverflow &&
                                        !candidateOverflow
                                val isUpgrade = candidateLevel > currentBestLevel
                                if (isUpgrade || isTimingFix || isSameTierCacheRefresh) {
                                    currentBestLyrics = outcome.lyrics
                                    currentBestLevel = candidateLevel
                                    currentBestOverflow = candidateOverflow
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
                            }
                        } else {
                            // Unusable or contradictory payload treated as missing so fallback continues cleanly.
                            if (source == LyricsSource.SIMPMUSIC) simpMusicNotFoundObserved = true
                        }
                    }
                }

                tryFlushPendingBlindCandidate()

                if (currentBestLevel >= LyricsQuality.LEVEL_RICH_SYNCED && !currentBestOverflow) {
                    hedgeJob?.cancel()
                    primaryJob.cancel()
                    launchedOtherJobs.forEach { it.cancel() }
                    break
                }

                // If primary finished (with not-found, < RICH_SYNCED, or overflowing timestamps),
                // or a blind candidate needs verification, or we have no lyrics yet: launch others immediately.
                if (source == primary || currentBestLyrics == null || pendingBlindCandidate != null) {
                    val allowMoreSources =
                        currentBestLyrics == null ||
                            request.allowUpgradeScan ||
                            currentBestOverflow ||
                            (pendingBlindCandidate != null && hasPendingVerificationSource())
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

            tryFlushPendingBlindCandidate()

            // Static lyrics fallback (Letras.mus.br): only queried after all synced providers
            // returned no lyrics and the track was NOT confirmed instrumental.
            if (currentBestLyrics == null) {
                val letrasOutcome =
                    try {
                        withTimeoutOrNull(providerTimeoutMs) {
                            fetchSource(LyricsSource.LETRAS, request)
                        } ?: ProviderFetchOutcome.TransientFailure("Timeout after ${providerTimeoutMs}ms")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ProviderFetchOutcome.TransientFailure(t.message)
                    }
                if (letrasOutcome is ProviderFetchOutcome.Success) {
                    val candidateLevel = LyricsQuality.level(letrasOutcome.lyrics)
                    if (candidateLevel > LyricsQuality.LEVEL_INVALID &&
                        !LyricsIdentityGuard.contradictsTrack(letrasOutcome.lyrics, request.title, request.artist)
                    ) {
                        currentBestLyrics = letrasOutcome.lyrics
                        currentBestLevel = candidateLevel
                        send(
                            LyricsResolutionEvent.Found(
                                LyricsResolution(
                                    videoId = request.videoId,
                                    lyrics = letrasOutcome.lyrics,
                                    source = LyricsSource.LETRAS,
                                    fromCache = false,
                                    simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                ),
                            ),
                        )
                    }
                }
            }

            if (currentBestLyrics == null) {
                send(
                    LyricsResolutionEvent.Exhausted(
                        videoId = request.videoId,
                        definitiveNotFound = !anyTransientFailure,
                        cacheInvalidated = cacheInvalidated,
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
                if (preferred != LyricsSource.OFFLINE &&
                    preferred != LyricsSource.LETRAS &&
                    (preferred != LyricsSource.SPOTIFY || allowSpotify)
                ) {
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
