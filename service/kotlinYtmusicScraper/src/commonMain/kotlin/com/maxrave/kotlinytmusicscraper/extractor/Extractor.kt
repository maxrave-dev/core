package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import io.ktor.client.engine.ProxyConfig

expect class Extractor() {
    fun init()

    fun setProxy(proxy: ProxyConfig?)

    fun logIn(cookie: String?)

    fun mergeAudioVideoDownload(filePath: String): DownloadProgress

    fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress

    fun newPipePlayer(videoId: String): List<Pair<Int, String>>

    fun newPipePlayer(
        videoId: String,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>>

    fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>>

    fun cancelExtraction(extractionId: Long)

    /**
     * The HLS playlist of a video that is broadcasting live right now, or null when no extractor
     * produced one.
     *
     * Kept apart from [newPipePlayer] on purpose: that path is built for videos of a fixed length,
     * and every tier there rejects a result without the regular audio and video itags — which a
     * live stream never carries, so a perfectly good live extraction looked like a failed one.
     */
    fun liveHlsUrl(videoId: String): String?

    /**
     * Pay the local decoder's one-time setup (current player id, remote player table,
     * player script, QuickJS solver) ahead of the first extraction that needs it, so the
     * first play does not have to. Platforms without a local decoder do nothing.
     */
    fun warmUp()

    /** Signals that URLs extracted for [videoId] failed playback, revoking HEAD trust if skipped. */
    fun onStreamRejected(videoId: String)

    /** Clears trusted-tier windows (e.g., on network switch or account change). */
    fun clearHeadTrust()
}