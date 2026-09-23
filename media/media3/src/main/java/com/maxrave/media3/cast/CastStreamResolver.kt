package com.maxrave.media3.cast

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull

/**
 * Resolves a receiver-reachable stream URL for a Cast device.
 *
 * A [com.maxrave.domain.data.player.GenericMediaItem.uri] only carries the raw videoId —
 * the real HTTP URL is normally resolved lazily inside the local ExoPlayer pipeline
 * (ResolvingDataSource), which a remote receiver can never reach. This resolver runs the
 * same [StreamRepository] chain up-front so the receiver gets a self-contained URL.
 * Cast playback is audio-only: video-prefixed media ids are stripped and only audio
 * formats are used.
 */
internal class CastStreamResolver(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) {
    internal data class ResolvedStream(
        val url: String,
        val mimeType: String,
    )

    suspend fun resolve(mediaId: String): ResolvedStream? {
        val videoId = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)

        // 1. Try cached format
        val cachedFormat = streamRepository.getNewFormat(videoId).lastOrNull()
        val cachedUrl = cachedFormat?.audioUrl
        if (cachedUrl != null && cachedFormat.expiredTime > now()) {
            val is403 = streamRepository.is403Url(cachedUrl).firstOrNull() == true
            if (!is403) {
                Logger.d(TAG, "Resolved $videoId from cached format")
                return ResolvedStream(cachedUrl, normalizeMimeType(cachedFormat.mimeType))
            }
        }

        // 2. Fresh extraction
        val freshUrl = streamRepository
            .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = false)
            .lastOrNull() ?: return null

        // 3. Re-read the format that getStream just wrote to get the mimeType.
        val freshFormat = streamRepository.getNewFormat(videoId).lastOrNull()
        Logger.d(TAG, "Resolved $videoId from fresh extraction")
        return ResolvedStream(freshUrl, normalizeMimeType(freshFormat?.mimeType))
    }

    suspend fun invalidate(mediaId: String) {
        streamRepository.invalidateFormat(mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO))
    }

    private fun normalizeMimeType(mimeType: String?): String =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.startsWith("audio/") }
            ?: DEFAULT_AUDIO_MIME_TYPE

    companion object {
        private const val TAG = "CastStreamResolver"

        /** Default Media Receiver renders song metadata correctly only with an audio MIME type. */
        private const val DEFAULT_AUDIO_MIME_TYPE = "audio/mp4"
    }
}
