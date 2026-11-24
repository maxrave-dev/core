package com.maxrave.data.repository

import com.maxrave.common.ITAG
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImmediateDirectPolicyTest {
    @Test
    fun isPremiumOnlyAudioItagIdentifies256kbpsFormatsOnly() {
        assertTrue(isPremiumOnlyAudioItag(ITAG.AUDIO_OPUS_HIGH))
        assertTrue(isPremiumOnlyAudioItag(ITAG.AUDIO_AAC_HIGH))
        assertFalse(isPremiumOnlyAudioItag(ITAG.AUDIO_OPUS_MEDIUM))
        assertFalse(isPremiumOnlyAudioItag(ITAG.AUDIO_AAC_MEDIUM))
        assertFalse(isPremiumOnlyAudioItag(ITAG.AUDIO_AAC_LOW))
    }

    @Test
    fun shouldPreferImmediateDirectPreservesPremium256kbpsWhenLoggedIn() {
        assertTrue(
            shouldPreferImmediateDirect(
                isVideo = false,
                muxed = false,
                isLoggedIn = false,
                requestedItag = ITAG.AUDIO_OPUS_MEDIUM,
            ),
        )
        assertTrue(
            shouldPreferImmediateDirect(
                isVideo = false,
                muxed = false,
                isLoggedIn = true,
                requestedItag = ITAG.AUDIO_OPUS_MEDIUM,
            ),
        )
        assertTrue(
            shouldPreferImmediateDirect(
                isVideo = false,
                muxed = false,
                isLoggedIn = false,
                requestedItag = ITAG.AUDIO_OPUS_HIGH,
            ),
        )
        assertFalse(
            shouldPreferImmediateDirect(
                isVideo = false,
                muxed = false,
                isLoggedIn = true,
                requestedItag = ITAG.AUDIO_OPUS_HIGH,
            ),
        )
        assertFalse(
            shouldPreferImmediateDirect(
                isVideo = false,
                muxed = false,
                isLoggedIn = true,
                requestedItag = ITAG.AUDIO_AAC_HIGH,
            ),
        )
        assertFalse(
            shouldPreferImmediateDirect(
                isVideo = true,
                muxed = false,
                isLoggedIn = false,
                requestedItag = ITAG.AUDIO_OPUS_MEDIUM,
            ),
        )
        assertFalse(
            shouldPreferImmediateDirect(
                isVideo = false,
                muxed = true,
                isLoggedIn = false,
                requestedItag = ITAG.AUDIO_OPUS_MEDIUM,
            ),
        )
    }
}
