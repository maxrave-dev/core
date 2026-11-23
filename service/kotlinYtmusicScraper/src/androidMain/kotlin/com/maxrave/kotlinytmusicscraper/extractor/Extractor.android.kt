package com.maxrave.kotlinytmusicscraper.extractor

import android.os.SystemClock
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.ServiceList
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
import io.ktor.client.engine.ProxyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import org.schabi.newpipe.extractor.NewPipe as BraveNewPipe
import org.schabi.newpipe.extractor.ServiceList as BraveServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo as BraveStreamInfo

private const val TAG = "Extractor"
private const val LOCAL_TIER = "local"
private const val REMOTE_TIER = "pipepipe.dev"
private const val BRAVE_TIER = "brave"

// How long a PipePipe tier stays skipped after its stream URLs fail HEAD verification.
private const val LOCAL_TIER_SKIP_MS = 10 * 60 * 1000L
private const val REMOTE_TIER_SKIP_MS = 10 * 60 * 1000L
private const val MAX_TIER_SKIP_MS = 30 * 60 * 1000L

// Once a tier passes a real HEAD check, skip the synchronous HEAD probe for subsequent fresh
// tracks on that same tier for up to 5 minutes. Revoked immediately on any player rejection.
private const val HEAD_TRUST_WINDOW_MS = 5 * 60 * 1000L
private const val MAX_UNVERIFIED_TRACKS = 32

// Delay before hedging PipePipe when BravePipe is slow to answer.
private const val HEDGE_PIPE_FALLBACK_MS = 3_500L
private const val PIPE_LOCK_POLL_MS = 50L

actual class Extractor {
    private class TierExtract(
        val streams: List<Pair<Int, String>>,
        val unverifiedTier: String?,
        val sourceLabel: String,
    )

    private var newPipeDownloader = NewPipeDownloaderImpl(proxy = null)
    private var braveNewPipeDownloader = BraveNewPipeDownloaderImpl(proxy = null)
    private val faradayDecoder = FaradayJsDecoder()
    private val pipePipeLock = ReentrantLock()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustLock = Any()
    private var localTierTrustedUntilMs: Long = 0L
    private var remoteTierTrustedUntilMs: Long = 0L
    private var braveTierTrustedUntilMs: Long = 0L
    private val unverifiedExtracts = LinkedHashMap<String, String>()

    // Set when the local tier's stream URLs are rejected (see pipePipeStreams): until this
    // deadline the local tier is skipped, because retrying it on every track would pay a full
    // local extraction plus a from-scratch solver rebuild and fail the same way again.
    @Volatile
    private var localTierBrokenUntilMs: Long = 0L

    // Set when api.pipepipe.dev's stream URLs also fail HEAD verification, so subsequent fresh
    // tracks go straight to BravePipe instead of wasting ~1.8s per song failing PipePipe first.
    @Volatile
    private var remoteTierBrokenUntilMs: Long = 0L

    @Volatile
    private var localConsecutiveFailures: Int = 0

    @Volatile
    private var remoteConsecutiveFailures: Int = 0

    private fun markLocalTierBroken() {
        val streak = (++localConsecutiveFailures).coerceAtMost(3)
        val cooldownMs = (LOCAL_TIER_SKIP_MS * (1L shl (streak - 1))).coerceAtMost(MAX_TIER_SKIP_MS)
        localTierBrokenUntilMs = SystemClock.elapsedRealtime() + cooldownMs
    }

    private fun markRemoteTierBroken() {
        val streak = (++remoteConsecutiveFailures).coerceAtMost(3)
        val cooldownMs = (REMOTE_TIER_SKIP_MS * (1L shl (streak - 1))).coerceAtMost(MAX_TIER_SKIP_MS)
        remoteTierBrokenUntilMs = SystemClock.elapsedRealtime() + cooldownMs
    }

    private fun localTierSkipped(): Boolean = SystemClock.elapsedRealtime() < localTierBrokenUntilMs

    private fun remoteTierSkipped(): Boolean = SystemClock.elapsedRealtime() < remoteTierBrokenUntilMs

    private fun isTierTrusted(tier: String): Boolean =
        synchronized(trustLock) {
            val now = SystemClock.elapsedRealtime()
            when (tier) {
                LOCAL_TIER -> now < localTierTrustedUntilMs
                REMOTE_TIER -> now < remoteTierTrustedUntilMs
                BRAVE_TIER -> now < braveTierTrustedUntilMs
                else -> false
            }
        }

    private fun markTierTrusted(tier: String) {
        synchronized(trustLock) {
            val until = SystemClock.elapsedRealtime() + HEAD_TRUST_WINDOW_MS
            when (tier) {
                LOCAL_TIER -> {
                    localTierTrustedUntilMs = until
                    localConsecutiveFailures = 0
                }
                REMOTE_TIER -> {
                    remoteTierTrustedUntilMs = until
                    remoteConsecutiveFailures = 0
                }
                BRAVE_TIER -> braveTierTrustedUntilMs = until
            }
        }
    }

    private fun clearTierTrust(tier: String) {
        synchronized(trustLock) {
            when (tier) {
                LOCAL_TIER -> localTierTrustedUntilMs = 0L
                REMOTE_TIER -> remoteTierTrustedUntilMs = 0L
                BRAVE_TIER -> braveTierTrustedUntilMs = 0L
            }
        }
    }

    private fun recordUnverified(
        videoId: String,
        tier: String,
    ) {
        if (videoId.isEmpty()) return
        synchronized(trustLock) {
            unverifiedExtracts.remove(videoId)
            unverifiedExtracts[videoId] = tier
            while (unverifiedExtracts.size > MAX_UNVERIFIED_TRACKS) {
                unverifiedExtracts.remove(unverifiedExtracts.keys.firstOrNull() ?: break)
            }
        }
    }

    private fun clearUnverified(videoId: String) {
        synchronized(trustLock) {
            unverifiedExtracts.remove(videoId)
        }
    }

    actual fun init() {
        NewPipe.init(newPipeDownloader)
        BraveNewPipe.init(braveNewPipeDownloader)
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
    }

    actual fun setProxy(proxy: ProxyConfig?) {
        newPipeDownloader.updateProxy(proxy)
        braveNewPipeDownloader.updateProxy(proxy)
        updateStreamHealthCheckProxy(proxy)
        faradayDecoder.setProxy(proxy)
    }

    actual fun warmUp() {
        if (!localTierSkipped()) faradayDecoder.warmUp()
    }

    actual fun logIn(cookie: String?) {
        ServiceList.YouTube.tokens = cookie ?: ""
        clearHeadTrust()
    }

    actual fun isStreamUrlReachable(url: String): Boolean = com.maxrave.kotlinytmusicscraper.extractor.isStreamUrlReachable(url)

    actual fun clearHeadTrust() {
        synchronized(trustLock) {
            localTierTrustedUntilMs = 0L
            remoteTierTrustedUntilMs = 0L
            braveTierTrustedUntilMs = 0L
            unverifiedExtracts.clear()
        }
        localTierBrokenUntilMs = 0L
        remoteTierBrokenUntilMs = 0L
        localConsecutiveFailures = 0
        remoteConsecutiveFailures = 0
    }

    actual fun onStreamRejected(videoId: String) {
        val rejectedTier =
            synchronized(trustLock) {
                val tier = unverifiedExtracts.remove(videoId) ?: return
                when (tier) {
                    LOCAL_TIER -> localTierTrustedUntilMs = 0L
                    REMOTE_TIER -> remoteTierTrustedUntilMs = 0L
                    BRAVE_TIER -> braveTierTrustedUntilMs = 0L
                }
                tier
            }
        Logger.w(TAG, "HEAD-skipped stream rejected for $videoId on tier=$rejectedTier; revoking trust")
        if (rejectedTier == LOCAL_TIER) {
            markLocalTierBroken()
            faradayDecoder.invalidateAsync()
        } else if (rejectedTier == REMOTE_TIER) {
            markRemoteTierBroken()
        }
    }

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> = newPipePlayer(videoId, 0L) { false }

    actual fun newPipePlayer(
        videoId: String,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = newPipePlayer(videoId, 0L, isCancelled)

    actual fun cancelExtraction(extractionId: Long) {
        ExtractorCallRegistry.cancel(extractionId)
    }

    actual fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = newPipePlayer(videoId, extractionId, requireVideo = true, isCancelled)

    actual fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        requireVideo: Boolean,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> =
        ExtractorCallRegistry.runWithToken(extractionId, isCancelled) {
            val resultLock = Any()
            var audioOnlyResult: TierExtract? = null
            val collectAudioOnly: (TierExtract) -> Unit = { extract ->
                synchronized(resultLock) {
                    if (audioOnlyResult == null) audioOnlyResult = extract
                }
            }
            val attempts = Collections.synchronizedList(mutableListOf<String>())
            var winner: TierExtract? = null

            try {
                if (isCancelled()) return@runWithToken emptyList()

                val localSkipped = localTierSkipped()
                val remoteSkipped = remoteTierSkipped()
                if (localSkipped) {
                    attempts.add("$LOCAL_TIER:skipped")
                    Logger.d(TAG, "PipePipe[$LOCAL_TIER] skipped for $videoId (stream URLs rejected recently)")
                }
                if (remoteSkipped) {
                    attempts.add("$REMOTE_TIER:skipped")
                    Logger.d(TAG, "PipePipe[$REMOTE_TIER] skipped for $videoId (stream URLs rejected recently)")
                }

                if (localSkipped && remoteSkipped) {
                    braveStreams(videoId, attempts, isCancelled)?.let {
                        winner = it
                        ExtractSource.record(videoId, it.sourceLabel)
                        return@runWithToken it.streams
                    }
                } else {
                    val pipeToken = ExtractorCallRegistry.newChildToken()
                    val braveToken = ExtractorCallRegistry.newChildToken()
                    val raceWon = AtomicBoolean(false)
                    val isPipeCancelled = { raceWon.get() || isCancelled() }
                    val isBraveCancelled = { raceWon.get() || isCancelled() }

                    ExtractorCallRegistry.registerChild(extractionId, pipeToken)
                    ExtractorCallRegistry.registerChild(extractionId, braveToken)
                    try {
                        val racedWinner =
                            runBlocking(Dispatchers.IO) {
                                racePrimaryWithHedgedFallback(
                                    scope = backgroundScope,
                                    hedgeDelayMs = HEDGE_PIPE_FALLBACK_MS,
                                    isCancelled = isCancelled,
                                    runPrimary = {
                                        ExtractorCallRegistry.runWithToken(braveToken, isBraveCancelled) {
                                            braveStreams(videoId, attempts, isBraveCancelled)
                                        }
                                    },
                                    runFallback = {
                                        ExtractorCallRegistry.runWithToken(pipeToken, isPipeCancelled) {
                                            var fallbackExtract: TierExtract? = null
                                            if (!remoteSkipped && !isPipeCancelled()) {
                                                fallbackExtract =
                                                    pipePipeStreams(
                                                        videoId = videoId,
                                                        tier = REMOTE_TIER,
                                                        useLocalDecoder = false,
                                                        requireVideo = requireVideo,
                                                        attempts = attempts,
                                                        isCancelled = isPipeCancelled,
                                                        onAudioOnlyResult = collectAudioOnly,
                                                    )
                                            }
                                            if (fallbackExtract == null && !localSkipped && !isPipeCancelled()) {
                                                fallbackExtract =
                                                    pipePipeStreams(
                                                        videoId = videoId,
                                                        tier = LOCAL_TIER,
                                                        useLocalDecoder = true,
                                                        requireVideo = requireVideo,
                                                        attempts = attempts,
                                                        isCancelled = isPipeCancelled,
                                                        onAudioOnlyResult = collectAudioOnly,
                                                    )
                                            }
                                            fallbackExtract
                                        }
                                    },
                                    onCancelPrimary = {
                                        raceWon.set(true)
                                        ExtractorCallRegistry.cancel(braveToken)
                                    },
                                    onCancelFallback = {
                                        raceWon.set(true)
                                        ExtractorCallRegistry.cancel(pipeToken)
                                    },
                                )
                            }
                        if (racedWinner != null) {
                            winner = racedWinner
                            ExtractSource.record(videoId, racedWinner.sourceLabel)
                            return@runWithToken racedWinner.streams
                        }
                    } finally {
                        raceWon.set(true)
                        ExtractorCallRegistry.unregisterChild(extractionId, pipeToken)
                        ExtractorCallRegistry.unregisterChild(extractionId, braveToken)
                    }
                }

                if (remoteSkipped && !isCancelled()) {
                    pipePipeStreams(videoId, REMOTE_TIER, useLocalDecoder = false, requireVideo, attempts, isCancelled, collectAudioOnly)?.let {
                        remoteTierBrokenUntilMs = 0L
                        winner = it
                        ExtractSource.record(videoId, it.sourceLabel)
                        return@runWithToken it.streams
                    }
                }
                if (localSkipped && !isCancelled()) {
                    pipePipeStreams(videoId, LOCAL_TIER, useLocalDecoder = true, requireVideo, attempts, isCancelled, collectAudioOnly)?.let {
                        localTierBrokenUntilMs = 0L
                        winner = it
                        ExtractSource.record(videoId, it.sourceLabel)
                        return@runWithToken it.streams
                    }
                }
                if (isCancelled()) return@runWithToken emptyList()
                // No tier produced a complete result; an audio-only one still plays.
                val fallbackAudioOnly = synchronized(resultLock) { audioOnlyResult }
                return@runWithToken fallbackAudioOnly?.also {
                    winner = it
                    ExtractSource.record(videoId, "PipePipe · audio-only")
                    Logger.d(TAG, "extract source=PipePipe audio-only fallback itags=${it.streams.map { pair -> pair.first }} for $videoId")
                }?.streams ?: emptyList()
            } finally {
                if (!isCancelled()) {
                    val resolved = winner
                    if (resolved?.unverifiedTier != null) {
                        recordUnverified(videoId, resolved.unverifiedTier)
                    } else {
                        clearUnverified(videoId)
                    }
                    val snapshot = synchronized(attempts) { attempts.joinToString(" -> ") }
                    ExtractTiming.record(videoId, snapshot)
                }
            }
        }

    actual fun liveHlsUrl(videoId: String): String? {
        // The same three tiers as newPipePlayer, in the same order and for the same reasons.
        if (!localTierSkipped()) {
            pipePipeLiveHlsUrl(videoId, LOCAL_TIER, useLocalDecoder = true)?.let { return it }
        }
        pipePipeLiveHlsUrl(videoId, REMOTE_TIER, useLocalDecoder = false)?.let { return it }
        return braveLiveHlsUrl(videoId)
    }

    private inline fun <T> withPipePipeLock(
        isCancelled: () -> Boolean,
        block: () -> T,
    ): T? {
        while (!isCancelled()) {
            if (pipePipeLock.tryLock(PIPE_LOCK_POLL_MS, TimeUnit.MILLISECONDS)) {
                return try {
                    block()
                } finally {
                    pipePipeLock.unlock()
                }
            }
        }
        return null
    }

    private fun pipePipeLiveHlsUrl(
        videoId: String,
        tier: String,
        useLocalDecoder: Boolean,
    ): String? =
        runCatching {
            withPipePipeLock({ false }) {
                YoutubeApiDecoder.setLocalDecoder(if (useLocalDecoder) faradayDecoder else null)
                StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId").hlsUrl
            }
        }.onFailure {
            Logger.w(TAG, "PipePipe[$tier] live extraction failed for $videoId: ${it.message}")
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                ExtractSource.record(videoId, "PipePipe · $tier · live")
                Logger.d(TAG, "live HLS from PipePipe[$tier] for $videoId")
            }

    private fun braveLiveHlsUrl(videoId: String): String? =
        runCatching {
            BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId").hlsUrl
        }.onFailure {
            Logger.w(TAG, "BravePipe live extraction failed for $videoId: ${it.message}")
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                ExtractSource.record(videoId, "BravePipe · live")
                Logger.d(TAG, "live HLS from BravePipe for $videoId")
            }

    /**
     * One PipePipe extraction with whatever decoder is requested. Returns null when the
     * attempt produced nothing usable, so the caller moves on to the next tier.
     */
    private fun pipePipeStreams(
        videoId: String,
        tier: String,
        useLocalDecoder: Boolean,
        requireVideo: Boolean = true,
        attempts: MutableList<String>,
        isCancelled: () -> Boolean = { false },
        onAudioOnlyResult: (TierExtract) -> Unit = {},
    ): TierExtract? {
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            if (isCancelled()) return null
            val streamInfo =
                withPipePipeLock(isCancelled) {
                    if (isCancelled()) return null
                    YoutubeApiDecoder.setLocalDecoder(if (useLocalDecoder) faradayDecoder else null)
                    StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
                } ?: return null
            val extractMs = SystemClock.elapsedRealtime() - startedAtMs
            if (isCancelled()) {
                attempts.add("$tier:cancelled/${extractMs}ms")
                return null
            }
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val pipeResult =
                streamsList.mapNotNull {
                    (it.itagItem?.id ?: return@mapNotNull null) to it.content
                }
            val complete = if (requireVideo) pipeResult.hasRequiredItags() else pipeResult.hasAudioItag()
            if (!complete && !pipeResult.hasAudioItag()) {
                val got = pipeResult.map { it.first }.distinct().joinToString(",")
                attempts.add("$tier:noItags($got)/${extractMs}ms")
                Logger.d(TAG, "PipePipe[$tier] missing required itags for $videoId (got=${pipeResult.map { it.first }})")
                if (tier == LOCAL_TIER) {
                    markLocalTierBroken()
                } else if (tier == REMOTE_TIER) {
                    markRemoteTierBroken()
                }
                return null
            }
            val trusted = isTierTrusted(tier)
            val headDetail: String
            val unverifiedTier: String?
            if (trusted) {
                headDetail = "head=skip"
                unverifiedTier = tier
            } else {
                val headStartedMs = SystemClock.elapsedRealtime()
                val headOk = pipeResult.headCheckRandomStream()
                val headMs = SystemClock.elapsedRealtime() - headStartedMs
                if (isCancelled()) {
                    attempts.add("$tier:cancelled/${extractMs}ms")
                    return null
                }
                if (!headOk) {
                    clearTierTrust(tier)
                    attempts.add("$tier:headFail=${headMs}ms/${extractMs}ms")
                    Logger.d(
                        TAG,
                        "PipePipe[$tier] ${if (complete) "stream" else "audio-only"} URL HEAD check failed (non 2xx) for $videoId",
                    )
                    // A rejected URL is the one symptom of a stale player table / dead client:
                    // invalidate local decoder or put that tier into temporary skip cooldown.
                    if (tier == LOCAL_TIER) {
                        markLocalTierBroken()
                        faradayDecoder.invalidateAsync()
                    } else if (tier == REMOTE_TIER) {
                        markRemoteTierBroken()
                    }
                    return null
                }
                markTierTrusted(tier)
                headDetail = "head=${headMs}ms"
                unverifiedTier = null
            }
            val label = if (tier == LOCAL_TIER) faradayDecoder.lastOutcomeLabel else REMOTE_TIER
            if (!complete) {
                attempts.add("$tier:audioOnly/${extractMs}ms/$headDetail")
                // Audio with no video at all: playable (the player response's audio formats
                // pair with these URLs), but a complete result from a later tier is better,
                // so this goes to the caller as a fallback and the tiers keep going.
                onAudioOnlyResult(TierExtract(pipeResult, unverifiedTier, "PipePipe · audio-only"))
                return null
            }
            attempts.add("$tier($label):ok/${extractMs}ms/$headDetail")
            Logger.d(TAG, "extract source=PipePipe[$tier] itags=${pipeResult.map { it.first }} for $videoId")
            return TierExtract(pipeResult, unverifiedTier, "PipePipe · $label")
        } catch (e: Throwable) {
            val extractMs = SystemClock.elapsedRealtime() - startedAtMs
            if (isCancelled()) {
                attempts.add("$tier:cancelled/${extractMs}ms")
                return null
            }
            attempts.add("$tier:err(${e::class.simpleName})/${extractMs}ms")
            Logger.w(TAG, "PipePipe[$tier] extractor failed for $videoId: ${e.message}")
            if (tier == LOCAL_TIER) {
                markLocalTierBroken()
            } else if (tier == REMOTE_TIER) {
                markRemoteTierBroken()
            }
            return null
        }
    }

    private fun braveStreams(
        videoId: String,
        attempts: MutableList<String>,
        isCancelled: () -> Boolean = { false },
    ): TierExtract? {
        if (isCancelled()) return null
        val startedAtMs = SystemClock.elapsedRealtime()
        return runCatching {
            val streamInfo =
                BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val extractMs = SystemClock.elapsedRealtime() - startedAtMs
            if (isCancelled()) {
                attempts.add("$BRAVE_TIER:cancelled/${extractMs}ms")
                return@runCatching null
            }
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val braveResult =
                streamsList.mapNotNull {
                    (it.itagItem?.id ?: return@mapNotNull null) to it.content
                }
            if (braveResult.isEmpty() || !braveResult.hasAudioItag()) {
                attempts.add("$BRAVE_TIER:empty/${extractMs}ms")
                return@runCatching null
            }
            val headDetail: String
            val unverifiedTier: String?
            if (isTierTrusted(BRAVE_TIER)) {
                headDetail = "head=skip"
                unverifiedTier = BRAVE_TIER
            } else {
                val headStartedMs = SystemClock.elapsedRealtime()
                val headOk = braveResult.headCheckRandomStream()
                val headMs = SystemClock.elapsedRealtime() - headStartedMs
                if (isCancelled()) {
                    attempts.add("$BRAVE_TIER:cancelled/${extractMs}ms")
                    return@runCatching null
                }
                if (!headOk) {
                    clearTierTrust(BRAVE_TIER)
                    attempts.add("$BRAVE_TIER:headFail=${headMs}ms/${extractMs}ms")
                    Logger.d(TAG, "BravePipe stream URL HEAD check failed (non 2xx) for $videoId")
                    return@runCatching null
                }
                markTierTrusted(BRAVE_TIER)
                headDetail = "head=${headMs}ms"
                unverifiedTier = null
            }
            attempts.add("$BRAVE_TIER:ok/${extractMs}ms/$headDetail")
            Logger.d(TAG, "extract source=BravePipe itags=${braveResult.map { pair -> pair.first }} for $videoId")
            TierExtract(braveResult, unverifiedTier, "BravePipe")
        }.onFailure {
            val extractMs = SystemClock.elapsedRealtime() - startedAtMs
            if (isCancelled()) {
                attempts.add("$BRAVE_TIER:cancelled/${extractMs}ms")
            } else {
                attempts.add("$BRAVE_TIER:err(${it::class.simpleName})/${extractMs}ms")
                Logger.w(TAG, "BravePipe extractor failed for $videoId: ${it.message}")
            }
        }.getOrNull()
    }

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress {
        val command =
            listOf(
                "-i",
                ("$filePath.mp4"),
                "-i",
                ("$filePath.webm"),
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-shortest",
                "$filePath-SimpMusic.mp4",
            ).joinToString(" ")

        if (FileSystem.SYSTEM.exists("$filePath-SimpMusic.mp4".toPath())) {
            FileSystem.SYSTEM.delete("$filePath-SimpMusic.mp4".toPath())
        }

        val session =
            FFmpegKit.execute(
                command,
            )
        if (ReturnCode.isSuccess(session.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.VIDEO_DONE)
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed(session.failStackTrace ?: "Command cancelled"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${session.state}, ${session.returnCode}, ${session.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed(session.failStackTrace ?: "FFmpeg command failed"))
        }
    }

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress {
        val command =
            listOf(
                "-i",
                ("$filePath.webm"),
                "-q:a 0",
                "$filePath.mp3",
            ).joinToString(" ")

        try {
            if (FileSystem.SYSTEM.exists("$filePath.mp3".toPath())) {
                FileSystem.SYSTEM.delete("$filePath.mp3".toPath())
            }
            if (FileSystem.SYSTEM.exists("$filePath-simpmusic.mp3".toPath())) {
                FileSystem.SYSTEM.delete("$filePath-simpmusic.mp3".toPath())
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val session =
            FFmpegKit.execute(
                command,
            )
        if (ReturnCode.isSuccess(session.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${session.state}, ${session.returnCode}, ${session.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        }

        val commandInject =
            listOf(
                "-i",
                "$filePath.mp3",
                "-i $filePath.jpg",
                "-map 0:a",
                "-map 1:v",
                "-c copy",
                "-id3v2_version 3",
                "-metadata",
                "title=\"${track.title}\"",
                "-metadata",
                "artist=\"${track.artists.joinToString(", ") { it.name }}\"",
                "-metadata",
                "album=\"${track.album?.name ?: track.title}\"",
                "-disposition:v:0 attached_pic",
                "$filePath-simpmusic.mp3",
            ).joinToString(" ")
        val sessionInject =
            FFmpegKit.execute(
                commandInject,
            )
        if (ReturnCode.isSuccess(sessionInject.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${sessionInject.state}, ${sessionInject.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.mp3".toPath())
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.AUDIO_DONE)
        } else if (ReturnCode.isCancel(sessionInject.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${sessionInject.state}, ${sessionInject.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath-simpmusic.mp3".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${sessionInject.state}, ${sessionInject.returnCode}, ${sessionInject.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath-simpmusic.mp3".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        }
    }
}