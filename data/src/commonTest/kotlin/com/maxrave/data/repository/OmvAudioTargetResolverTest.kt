package com.maxrave.data.repository

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmvAudioTargetResolverTest {
    @Test
    fun knownOmvResolvesAtvBeforeFirstPlayerCallAndCachesResult() {
        val resolver = OmvAudioTargetResolver()
        var lookups = 0

        val first =
            resolver.resolveInitialTarget(
                videoId = "ukbiRcyzrpc",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                "OCY9vN4QzqY"
            }

        assertEquals("OCY9vN4QzqY", first.targetId)
        assertTrue(first.upgradedBeforePlayer)
        assertTrue(first.preResolvedAtvAttempted)
        assertEquals(1, lookups)
        assertEquals("OCY9vN4QzqY", resolver.mappedAtvOf("ukbiRcyzrpc"))

        // Second play hits positive cache without re-querying YouTube.
        val second =
            resolver.resolveInitialTarget(
                videoId = "ukbiRcyzrpc",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                "SHOULD_NOT_RUN"
            }

        assertEquals("OCY9vN4QzqY", second.targetId)
        assertTrue(second.upgradedBeforePlayer)
        assertEquals(1, lookups)
    }

    @Test
    fun omvWithoutAtvCounterpartIsNegativelyCachedAcrossSession() {
        val resolver = OmvAudioTargetResolver()
        var lookups = 0

        val first =
            resolver.resolveInitialTarget(
                videoId = "OMV_ONLY_ID",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                null
            }

        assertEquals("OMV_ONLY_ID", first.targetId)
        assertFalse(first.upgradedBeforePlayer)
        assertTrue(first.preResolvedAtvAttempted)
        assertTrue(resolver.hasNegativeEntry("OMV_ONLY_ID"))
        assertFalse(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "OMV_ONLY_ID",
                resolvedSourceId = "OMV_ONLY_ID",
                preResolvedAtvAttempted = first.preResolvedAtvAttempted,
            ),
        )

        // Repeat play in same session skips lookup completely.
        val second =
            resolver.resolveInitialTarget(
                videoId = "OMV_ONLY_ID",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                "SHOULD_NOT_RUN"
            }

        assertEquals("OMV_ONLY_ID", second.targetId)
        assertEquals(1, lookups)
        assertFalse(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "OMV_ONLY_ID",
                resolvedSourceId = "OMV_ONLY_ID",
                preResolvedAtvAttempted = second.preResolvedAtvAttempted,
            ),
        )
    }

    @Test
    fun watchVideoModeAndNonOmvTracksSkipPrePlayerLookup() {
        val resolver = OmvAudioTargetResolver()
        var lookups = 0

        val videoMode =
            resolver.resolveInitialTarget(
                videoId = "ukbiRcyzrpc",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = false,
            ) {
                lookups++
                "OCY9vN4QzqY"
            }
        assertEquals("ukbiRcyzrpc", videoMode.targetId)
        assertFalse(videoMode.preResolvedAtvAttempted)
        assertEquals(0, lookups)

        val atvTrack =
            resolver.resolveInitialTarget(
                videoId = "HzYrZoALCCA",
                localVideoType = MUSIC_VIDEO_TYPE_ATV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                "SHOULD_NOT_RUN"
            }
        assertEquals("HzYrZoALCCA", atvTrack.targetId)
        assertFalse(atvTrack.preResolvedAtvAttempted)
        assertEquals(0, lookups)
        assertTrue(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "HzYrZoALCCA",
                resolvedSourceId = "HzYrZoALCCA",
                preResolvedAtvAttempted = atvTrack.preResolvedAtvAttempted,
            ),
        )
    }

    @Test
    fun evictMappingClearsCachedAtvAfterFallback() {
        val resolver = OmvAudioTargetResolver()
        resolver.recordPositive("ukbiRcyzrpc", "OCY9vN4QzqY")
        assertEquals("OCY9vN4QzqY", resolver.mappedAtvOf("ukbiRcyzrpc"))

        resolver.evictMapping("ukbiRcyzrpc")
        assertNull(resolver.mappedAtvOf("ukbiRcyzrpc"))
    }
}
