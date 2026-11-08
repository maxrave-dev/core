package com.maxrave.media3.service.mediasourcefactory

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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

/** Whether this error, or anything that led to it, is a [LiveStreamDetectedException]. */
internal fun Throwable.isLiveStreamDetected(): Boolean =
    // take(): a cause chain is not supposed to loop, but nothing stops one from doing so.
    generateSequence(this) { it.cause }.take(8).any { it is LiveStreamDetectedException }

/**
 * The default retry policy, except that a [LiveStreamDetectedException] is never retried. Retrying
 * cannot succeed — the resolver throws it again at once — and the default backs off for several
 * seconds before giving up, which the listener would sit through in silence before the stream starts.
 */
@UnstableApi
internal class LiveStreamAwareLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.isLiveStreamDetected()) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
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
