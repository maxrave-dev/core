package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress

expect class Extractor() {
    fun init()

    fun logIn(cookie: String?)

    fun mergeAudioVideoDownload(filePath: String): DownloadProgress

    fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress

    fun newPipePlayer(videoId: String): List<Pair<Int, String>>

    /**
     * The HLS playlist of a video that is broadcasting live right now, or null when no extractor
     * produced one.
     *
     * Kept apart from [newPipePlayer] on purpose: that path is built for videos of a fixed length,
     * and every tier there rejects a result without the regular audio and video itags — which a
     * live stream never carries, so a perfectly good live extraction looked like a failed one.
     */
    fun liveHlsUrl(videoId: String): String?
}