package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.ServiceList
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
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

    actual fun init() {
        NewPipe.init(newPipeDownloader)
        BraveNewPipe.init(braveNewPipeDownloader)
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
    }

    actual fun logIn(cookie: String?) {
        ServiceList.YouTube.tokens = cookie ?: ""
    }

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        // Three attempts, kept separate on purpose. PipePipe's own local-then-server fallback lives
        // inside a single StreamInfo call and only fires when the local decoder THROWS — but the
        // failure that actually happens is a stale player table, where the signature is well-formed
        // and merely rejected by the CDN. Nothing throws, so the server tier is never asked, and a
        // 403 used to skip straight to BravePipe. Driving the two as separate attempts is what
        // stops that jump.

        // Tier 1 — ciphers solved locally.
        // Re-registered every time: PipePipe drops the local decoder for good the first time it
        // throws, and the getter is package-private so its state cannot be read from here. Setting
        // it again turns "disabled forever" into "skipped for one track".
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
        pipePipeStreams(videoId, LOCAL_TIER)?.let { return it }

        // Tier 2 — same extractor with no local decoder at all, so every challenge is solved at
        // api.pipepipe.dev. This is the tier a 403 from tier 1 must reach.
        //
        // Clearing it is what `setLocalDecoder(null)` does: both call sites read the field and
        // null-check it before use — `decodeBatch` and `getPlayerMetadata` each branch straight to
        // the API path when it is absent (verified in the bytecode of the pinned f8982ca9e7 jar).
        // PipePipe's own `disableLocalDecoder` does exactly this but is private, which is why it
        // cannot simply be called from here.
        YoutubeApiDecoder.setLocalDecoder(null)
        pipePipeStreams(videoId, REMOTE_TIER)?.let { return it }

        // Tier 3 — a different extractor entirely.
        return braveStreams(videoId)
    }

    /**
     * One PipePipe extraction with whatever decoder is currently registered. Returns null when the
     * attempt produced nothing usable, so the caller moves on to the next tier.
     */
    private fun pipePipeStreams(
        videoId: String,
        tier: String,
    ): List<Pair<Int, String>>? {
        try {
            val streamInfo =
                StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val temp =
                streamsList
                    .mapNotNull {
                        (it.itagItem?.id ?: return@mapNotNull null) to it.content
                    }.toMutableList()
            val manifest = streamInfo.dashMpdUrl.takeIf { !it.isNullOrEmpty() } ?: streamInfo.hlsUrl
            if (!manifest.isNullOrEmpty()) temp.add(96 to manifest)
            val pipeResult = temp.toList()
            if (!pipeResult.hasRequiredItags()) {
                Logger.d(TAG, "PipePipe[$tier] missing required itags for $videoId (got=${pipeResult.map { it.first }})")
                return null
            }
            if (!pipeResult.headCheckRandomStream()) {
                Logger.d(TAG, "PipePipe[$tier] stream URL HEAD check failed (non 2xx) for $videoId")
                // A rejected URL is the one symptom of a stale player table: the signature is
                // well-formed and still wrong, so nothing threw on the way here. Only the on-device
                // table can go stale, so only that tier is worth invalidating.
                if (tier == LOCAL_TIER) faradayDecoder.invalidate()
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