package com.maxrave.media3.service.mediasourcefactory

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/*
 * Everything Android needs to play a live broadcast. A live stream is an HLS playlist, not a file,
 * so it cannot go through the progressive source every other track uses — and which source to
 * build is decided before any URL is known. So a live track is found out the first time it is
 * resolved (LiveStreamRegistry), its progressive source fails with LiveStreamDetectedException, and
 * the track is built again, this time as an HLS source.
 */

/**
 * Thrown by the progressive stream resolver when the video it was asked for turns out to be a live
 * broadcast. By the time this is thrown [com.maxrave.domain.data.player.LiveStreamRegistry] knows
 * the video is live, so building the track again gives [MergingMediaSourceFactory] what it needs to
 * pick an HLS source.
 */
class LiveStreamDetectedException(
    val videoId: String,
) : IOException("$videoId is a live stream; it has to be rebuilt as an HLS source")

/** Thrown when a formerly live track's broadcast has ended and needs a progressive source rebuild. */
class LiveStreamEndedException(
    val videoId: String,
) : IOException("$videoId live broadcast ended; it has to be rebuilt as a progressive source")

/** Thrown when an in-flight stream resolve coroutine was cancelled (e.g. fast skip or release). */
internal class StreamResolveCancelledException(
    label: String,
    cause: Throwable? = null,
) : IOException("Stream resolve cancelled for $label", cause)

/** Whether this error, or anything that led to it, is a [LiveStreamDetectedException]. */
internal fun Throwable.isLiveStreamDetected(): Boolean =
    // take(): a cause chain is not supposed to loop, but nothing stops one from doing so.
    generateSequence(this) { it.cause }.take(8).any { it is LiveStreamDetectedException }

/** Whether this error, or anything that led to it, is a [LiveStreamEndedException]. */
internal fun Throwable.isLiveStreamEnded(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is LiveStreamEndedException }

internal fun Throwable.isStreamResolveCancelled(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is StreamResolveCancelledException }

/**
 * The load error policy of the progressive source every non-live track plays through.
 *
 * Two deliberate deviations from [DefaultLoadErrorHandlingPolicy]:
 *
 * - A [LiveStreamDetectedException] is never retried. Retrying cannot succeed — the resolver
 *   throws it again at once — and the default backs off for several seconds before giving up,
 *   which the listener would sit through in silence before the stream starts.
 * - A retriable I/O failure is retried for minutes, not seconds. The default allows only
 *   [DefaultLoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT] attempts with delays of
 *   at most 5s, so a connection that drops for half a minute exhausts the loader, the error
 *   escapes to the player and the track is over. Players that ride outages out keep the load
 *   alive in the background instead: the player merely buffers, and playback continues from
 *   the same position when the connection returns — no error state, no re-prepare, no seek.
 *   So for media loads this policy allows [OUTAGE_MIN_RETRY_COUNT] attempts, with an
 *   exponential backoff from [OUTAGE_BASE_DELAY_MS] capped at [OUTAGE_MAX_DELAY_MS] — roughly
 *   three minutes of outage — before the error may reach the player, where the service
 *   handler's own recovery (re-resolving the stream and preparing again) takes over.
 *
 * HTTP 403, 404 and 410 fail fast on purpose: the URL itself is dead (expired or gone), so
 * retrying it can never succeed. Surfacing the error at once lets that player-level recovery
 * re-resolve a fresh URL instead of burning the outage window on a dead address. Errors the
 * default policy already treats as fatal (parser errors, missing files, ...) stay fatal.
 */
internal class UnrecoverableStreamException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun Throwable.isUnrecoverableStreamFailure(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is UnrecoverableStreamException }

@UnstableApi
internal class LiveStreamAwareLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.exception.isLiveStreamDetected()) return C.TIME_UNSET
        if (loadErrorInfo.exception.isLiveStreamEnded()) return C.TIME_UNSET
        if (loadErrorInfo.exception.isStreamResolveCancelled()) return C.TIME_UNSET
        if (loadErrorInfo.exception.isUnrecoverableStreamFailure()) return C.TIME_UNSET
        if (loadErrorInfo.exception.hasDeadHttpStatus()) return C.TIME_UNSET
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        // errorCount includes the error being answered, so the first retry waits the base delay.
        val backoffMultiplier = 1L shl (loadErrorInfo.errorCount - 1).coerceAtMost(4)
        return minOf(OUTAGE_BASE_DELAY_MS * backoffMultiplier, OUTAGE_MAX_DELAY_MS)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        when (dataType) {
            C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> OUTAGE_MIN_RETRY_COUNT
            else -> super.getMinimumLoadableRetryCount(dataType)
        }

    /** Whether this error, or anything that led to it, is an HTTP status a fresh URL must replace. */
    private fun Throwable.hasDeadHttpStatus(): Boolean =
        generateSequence(this) { it.cause }.take(8).any {
            it is InvalidResponseCodeException && it.responseCode in DEAD_HTTP_STATUS_CODES
        }

    private companion object {
        // 14 attempts at 1s, 2s, 4s, 8s, then 15s flat: about 2 minutes 45 seconds of outage.
        const val OUTAGE_MIN_RETRY_COUNT = 14
        const val OUTAGE_BASE_DELAY_MS = 1_000L
        const val OUTAGE_MAX_DELAY_MS = 15_000L
        val DEAD_HTTP_STATUS_CODES = setOf(403, 404, 410)
    }
}

/**
 * The URI a live track's HLS source is built with. The real playlist URL is not known when the
 * source is built, and it expires within hours, so the source asks for this placeholder and
 * [liveStreamDataSourceFactory] swaps in a freshly resolved URL every time the playlist is loaded.
 */
internal object LiveStreamPlaylistUri {
    private const val SCHEME = "simpmusic-live"

    // The id rides in the path, not the host: hosts may be case-folded, and video ids are case-sensitive.
    fun forVideo(videoId: String): Uri = "$SCHEME://playlist/$videoId".toUri()

    fun videoIdOf(uri: Uri): String? = uri.takeIf { it.scheme == SCHEME }?.lastPathSegment
}

/**
 * Plain HTTP for a live track, with the playlist placeholder resolved on the way. Nothing else is
 * touched: the media playlists and segments an HLS playlist points to are already real URLs. There is
 * no cache layer either — a live stream is never played twice.
 */
@UnstableApi
internal fun liveStreamDataSourceFactory(
    httpDataSourceFactory: DataSource.Factory,
    resolveLiveHlsUrl: (videoId: String) -> String?,
): DataSource.Factory =
    ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
        val videoId = LiveStreamPlaylistUri.videoIdOf(dataSpec.uri) ?: return@Factory dataSpec
        val playlistUrl = resolveLiveHlsUrl(videoId) ?: throw IOException("No live HLS playlist found for $videoId")
        dataSpec.withUri(playlistUrl.toUri())
    }
