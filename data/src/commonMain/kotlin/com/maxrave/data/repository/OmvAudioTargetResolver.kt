package com.maxrave.data.repository

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Resolves and caches `OMV -> ATV` audio target IDs before the first `youTube.player(...)`
 * extraction so known music videos do not pay a wasted extraction on the OMV ID first.
 *
 * Also maintains a bounded negative cache ([omvNoAtvIds]) so tracks with no official ATV
 * studio counterpart do not trigger repeated `/next` + `/search` lookups in the same session.
 */
internal class OmvAudioTargetResolver(
    private val maxPositiveEntries: Int = MAX_POSITIVE_ENTRIES,
    private val maxNegativeEntries: Int = MAX_NEGATIVE_ENTRIES,
) {
    data class InitialResolution(
        val targetId: String,
        val preResolvedAtvAttempted: Boolean,
        val upgradedBeforePlayer: Boolean,
    )

    private val omvToAtvCache = MutableStateFlow<Map<String, String>>(emptyMap())
    private val omvNoAtvIds = MutableStateFlow<Set<String>>(emptySet())

    inline fun resolveInitialTarget(
        videoId: String,
        localVideoType: String?,
        allowAudioUpgrade: Boolean,
        lookupAtv: () -> String?,
    ): InitialResolution {
        if (!allowAudioUpgrade) {
            return InitialResolution(
                targetId = videoId,
                preResolvedAtvAttempted = false,
                upgradedBeforePlayer = false,
            )
        }
        mappedAtvOf(videoId)?.let { cachedAtv ->
            return InitialResolution(
                targetId = cachedAtv,
                preResolvedAtvAttempted = true,
                upgradedBeforePlayer = true,
            )
        }
        if (localVideoType == MUSIC_VIDEO_TYPE_OMV && !hasNegativeEntry(videoId)) {
            val atvId = lookupAtv()
            if (!atvId.isNullOrBlank() && atvId != videoId) {
                recordPositive(videoId, atvId)
                return InitialResolution(
                    targetId = atvId,
                    preResolvedAtvAttempted = true,
                    upgradedBeforePlayer = true,
                )
            }
            recordNegative(videoId)
            return InitialResolution(
                targetId = videoId,
                preResolvedAtvAttempted = true,
                upgradedBeforePlayer = false,
            )
        }
        return InitialResolution(
            targetId = videoId,
            preResolvedAtvAttempted = false,
            upgradedBeforePlayer = false,
        )
    }

    fun shouldAttemptPostPlayerLookup(
        videoId: String,
        resolvedSourceId: String,
        preResolvedAtvAttempted: Boolean,
    ): Boolean =
        resolvedSourceId == videoId &&
            !preResolvedAtvAttempted &&
            videoId !in omvNoAtvIds.value

    fun recordPositive(
        videoId: String,
        atvId: String,
    ) {
        if (videoId.isBlank() || atvId.isBlank() || atvId == videoId) return
        omvNoAtvIds.update { if (videoId in it) it - videoId else it }
        omvToAtvCache.update { current ->
            val trimmed =
                if (current.size >= maxPositiveEntries && videoId !in current) {
                    current - current.keys.first()
                } else {
                    current
                }
            trimmed + (videoId to atvId)
        }
    }

    fun recordNegative(videoId: String) {
        if (videoId.isBlank()) return
        omvNoAtvIds.update { current ->
            if (videoId in current) {
                current
            } else {
                val base =
                    if (current.size >= maxNegativeEntries) {
                        current - current.first()
                    } else {
                        current
                    }
                base + videoId
            }
        }
    }

    fun evictMapping(videoId: String) {
        omvToAtvCache.update { current ->
            if (videoId in current) current - videoId else current
        }
    }

    fun mappedAtvOf(videoId: String): String? = omvToAtvCache.value[videoId]

    fun hasNegativeEntry(videoId: String): Boolean = videoId in omvNoAtvIds.value

    companion object {
        const val MAX_POSITIVE_ENTRIES = 128
        const val MAX_NEGATIVE_ENTRIES = 256
    }
}
