package com.maxrave.media3.service.mediasourcefactory

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@UnstableApi
internal class MergingMediaSourceFactory(
    private val defaultMediaSourceFactory: DefaultMediaSourceFactory,
    // HLS over plain HTTP, for live broadcasts only — see LiveStreamPlayback.kt.
    private val liveStreamMediaSourceFactory: MediaSource.Factory,
    private val dataStoreManager: DataStoreManager,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        liveStreamMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        liveStreamMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (LiveStreamRegistry.isLive(mediaItem.mediaId)) {
            // One HLS playlist carrying audio and video together: no merge, and none of the
            // progressive resolver's caching or chunking.
            Logger.w("Merging Media Source", "${mediaItem.mediaId} is live; building an HLS source")
            return liveStreamMediaSourceFactory.createMediaSource(
                mediaItem
                    .buildUpon()
                    .setUri(LiveStreamPlaylistUri.forVideo(mediaItem.mediaId))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
        }
        Logger.w("Merging Media Source", mediaItem.mediaMetadata.description.toString())
        val getVideo = runBlocking(Dispatchers.IO) { dataStoreManager.watchVideoInsteadOfPlayingAudio.first() } == DataStoreManager.Values.TRUE
        Logger.w("Merging Media Source", getVideo.toString())
        if (mediaItem.mediaMetadata.description == MERGING_DATA_TYPE.VIDEO && getVideo) {
            val videoItem =
                mediaItem
                    .buildUpon()
                    .setMediaId("${MERGING_DATA_TYPE.VIDEO}${mediaItem.mediaId}")
                    .setCustomCacheKey("${MERGING_DATA_TYPE.VIDEO}${mediaItem.mediaId}")
                    .build()
            return MergingMediaSource(
                defaultMediaSourceFactory.createMediaSource(videoItem),
                defaultMediaSourceFactory.createMediaSource(mediaItem),
            )
        } else {
            return defaultMediaSourceFactory.createMediaSource(mediaItem)
        }

//        val default = defaultMediaSourceFactory.createMediaSource(mediaItem.buildUpon().setMediaId("AUDIO-${mediaItem.mediaId}").build())
    }
}