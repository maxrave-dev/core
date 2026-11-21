package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import io.ktor.client.engine.ProxyConfig

actual class Extractor {
    actual fun init() {
    }

    actual fun setProxy(proxy: ProxyConfig?) {}

    actual fun logIn(cookie: String?) {}

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> = emptyList()

    actual fun newPipePlayer(
        videoId: String,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = emptyList()

    actual fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = emptyList()

    actual fun newPipePlayer(
        videoId: String,
        extractionId: Long,
        requireVideo: Boolean,
        isCancelled: () -> Boolean,
    ): List<Pair<Int, String>> = emptyList()

    actual fun cancelExtraction(extractionId: Long) {}

    actual fun liveHlsUrl(videoId: String): String? = null

    actual fun warmUp() {}

    actual fun onStreamRejected(videoId: String) {}

    actual fun clearHeadTrust() {}

    actual fun isStreamUrlReachable(url: String): Boolean = true

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress = DownloadProgress.failed("Not supported on iOS")

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress = DownloadProgress.failed("Not supported on iOS")
}