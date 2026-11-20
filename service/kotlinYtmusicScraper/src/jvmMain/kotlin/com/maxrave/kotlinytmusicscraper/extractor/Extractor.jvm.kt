package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.ServiceList
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
import io.ktor.client.engine.ProxyConfig
import org.schabi.newpipe.extractor.NewPipe as BraveNewPipe
import org.schabi.newpipe.extractor.ServiceList as BraveServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo as BraveStreamInfo

private const val TAG = "Extractor"
private const val LOCAL_TIER = "local"
private const val REMOTE_TIER = "pipepipe.dev"

actual class Extractor {
    private var newPipeDownloader = NewPipeDownloaderImpl(proxy = null)
    private var braveNewPipeDownloader = BraveNewPipeDownloaderImpl(proxy = null)
    private val faradayDecoder = FaradayJsDecoder()
    private val pipePipeLock = Any()

    actual fun init() {
        NewPipe.init(newPipeDownloader)
        BraveNewPipe.init(braveNewPipeDownloader)
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
    }

    actual fun setProxy(proxy: ProxyConfig?) {
        newPipeDownloader.updateProxy(proxy)
        braveNewPipeDownloader.updateProxy(proxy)
        faradayDecoder.setProxy(proxy)
    }

    actual fun warmUp() = faradayDecoder.warmUp()

    actual fun onStreamRejected(videoId: String) {}

    actual fun clearHeadTrust() {}

    actual fun isStreamUrlReachable(url: String): Boolean = com.maxrave.kotlinytmusicscraper.extractor.isStreamUrlReachable(url)

    actual fun logIn(cookie: String?) {
        ServiceList.YouTube.tokens = cookie ?: ""
    }

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> = newPipePlayer(videoId) { false }

    actual fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = newPipePlayer(videoId, extractionId, requireVideo = true, isCancelled)

    actual fun cancelExtraction(extractionId: Long) {}

    actual fun newPipePlayer(
        videoId: String,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = newPipePlayer(videoId, 0L, requireVideo = true, isCancelled)

    actual fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        requireVideo: Boolean,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> {
        var audioOnlyResult: List<Pair<Int, String>>? = null
        val collectAudioOnly: (List<Pair<Int, String>>) -> Unit = { streams ->
            if (audioOnlyResult == null) audioOnlyResult = streams
        }

        if (isCancelled()) return emptyList()

        // Tier 1 — ciphers solved locally.
        pipePipeStreams(videoId, LOCAL_TIER, useLocalDecoder = true, requireVideo, collectAudioOnly)?.let { return it }

        if (isCancelled()) return emptyList()

        // Tier 2 — same extractor with no local decoder at all, so every challenge is solved at
        // api.pipepipe.dev. This is the tier a 403 from tier 1 must reach.
        pipePipeStreams(videoId, REMOTE_TIER, useLocalDecoder = false, requireVideo, collectAudioOnly)?.let { return it }

        if (isCancelled()) return emptyList()

        // Tier 3 — a different extractor entirely.
        val braveResult = braveStreams(videoId)
        if (braveResult.isNotEmpty()) return braveResult
        // No tier produced a complete result; an audio-only one still plays.
        return audioOnlyResult?.also {
            ExtractSource.record(videoId, "PipePipe · audio-only")
            Logger.d(TAG, "extract source=PipePipe audio-only fallback itags=${it.map { pair -> pair.first }} for $videoId")
        } ?: emptyList()
    }

    actual fun liveHlsUrl(videoId: String): String? {
        // The same three tiers as newPipePlayer, in the same order and for the same reasons.
        pipePipeLiveHlsUrl(videoId, LOCAL_TIER, useLocalDecoder = true)?.let { return it }
        pipePipeLiveHlsUrl(videoId, REMOTE_TIER, useLocalDecoder = false)?.let { return it }
        return braveLiveHlsUrl(videoId)
    }

    private fun pipePipeLiveHlsUrl(
        videoId: String,
        tier: String,
        useLocalDecoder: Boolean,
    ): String? =
        runCatching {
            synchronized(pipePipeLock) {
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
        onAudioOnlyResult: (List<Pair<Int, String>>) -> Unit = {},
    ): List<Pair<Int, String>>? {
        try {
            val streamInfo =
                synchronized(pipePipeLock) {
                    YoutubeApiDecoder.setLocalDecoder(if (useLocalDecoder) faradayDecoder else null)
                    StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
                }
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val temp =
                streamsList
                    .mapNotNull {
                        (it.itagItem?.id ?: return@mapNotNull null) to it.content
                    }.toMutableList()
            val manifest = streamInfo.dashMpdUrl.takeIf { !it.isNullOrEmpty() } ?: streamInfo.hlsUrl
            if (!manifest.isNullOrEmpty()) temp.add(96 to manifest)
            val pipeResult = temp.toList()
            val complete = if (requireVideo) pipeResult.hasRequiredItags() else pipeResult.hasAudioItag()
            if (!complete && !pipeResult.hasAudioItag()) {
                Logger.d(TAG, "PipePipe[$tier] missing required itags for $videoId (got=${pipeResult.map { it.first }})")
                return null
            }
            // No HEAD probe on a random URL before accepting the tier: it taxed every
            // extraction with a serial round-trip, and one dead URL (or a failed HEAD)
            // discarded a result whose chosen URL might be perfectly fine. A deciphered
            // URL that turns out dead is found by the player's own load now, which answers
            // a 403 by dropping the stored format and re-resolving — the stale local player
            // table surfaces that way too, instead of being sniffed out here.
            if (!complete) {
                // Audio with no video at all: playable (the player response's audio formats
                // pair with these URLs), but a complete result from a later tier is better,
                // so this goes to the caller as a fallback and the tiers keep going.
                onAudioOnlyResult(pipeResult)
                return null
            }
            val label = if (tier == LOCAL_TIER) faradayDecoder.lastOutcomeLabel else REMOTE_TIER
            ExtractSource.record(videoId, "PipePipe · $label")
            Logger.d(TAG, "extract source=PipePipe[$tier] itags=${pipeResult.map { it.first }} for $videoId")
            return pipeResult
        } catch (e: Throwable) {
            Logger.w(TAG, "PipePipe[$tier] extractor failed for $videoId: ${e.message}")
            return null
        }
    }

    private fun braveStreams(videoId: String): List<Pair<Int, String>> =
        runCatching {
            val streamInfo =
                BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val temp =
                streamsList
                    .mapNotNull {
                        (it.itagItem?.id ?: return@mapNotNull null) to it.content
                    }.toMutableList()
            val manifest = streamInfo.dashMpdUrl.takeIf { !it.isNullOrEmpty() } ?: streamInfo.hlsUrl
            if (!manifest.isNullOrEmpty()) temp.add(96 to manifest)
            ExtractSource.record(videoId, "BravePipe")
            Logger.d(TAG, "extract source=BravePipe itags=${temp.map { it.first }} for $videoId")
            temp.toList()
        }.onFailure {
            Logger.w(TAG, "BravePipe extractor failed for $videoId: ${it.message}")
        }.getOrElse { emptyList() }

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress = DownloadProgress.failed("Not supported on JVM")

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress = DownloadProgress.AUDIO_DONE
}