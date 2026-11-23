package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidVrFastPathTest {
    private fun format(
        itag: Int,
        url: String?,
        width: Int? = null,
        contentLength: Long? = null,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = url,
        mimeType = if (width == null) "audio/webm; codecs=\"opus\"" else "video/mp4",
        bitrate = 128_000,
        width = width,
        height = width,
        contentLength = contentLength,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = 128_000,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = "199000",
        audioSampleRate = 48_000,
        audioChannels = 2,
        loudnessDb = -5.0,
        lastModified = null,
        signatureCipher = null,
    )

    private fun playerResponse(
        videoId: String = "HzYrZoALCCA",
        status: String = "OK",
        lengthSeconds: String = "199",
        isLive: Boolean? = null,
        loudnessDb: Double? = -6.5,
        playbackUrl: String? = "https://music.youtube.com/api/stats/playback",
        adaptiveFormats: List<PlayerResponse.StreamingData.Format>,
        expiresInSeconds: Int = 21_540,
    ) = PlayerResponse(
        responseContext = ResponseContext(serviceTrackingParams = emptyList()),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status = status, reason = null),
        playerConfig =
            loudnessDb?.let {
                PlayerResponse.PlayerConfig(
                    audioConfig =
                        PlayerResponse.PlayerConfig.AudioConfig(
                            loudnessDb = it,
                            perceptualLoudnessDb = it,
                        ),
                )
            },
        streamingData =
            PlayerResponse.StreamingData(
                hlsManifestUrl = null,
                formats = emptyList(),
                adaptiveFormats = adaptiveFormats,
                expiresInSeconds = expiresInSeconds,
            ),
        videoDetails =
            PlayerResponse.VideoDetails(
                videoId = videoId,
                title = "I Can't Handle Change",
                author = "Roar",
                channelId = "UC123",
                authorAvatar = null,
                authorSubCount = null,
                lengthSeconds = lengthSeconds,
                isLive = isLive,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                viewCount = "1000",
                thumbnail = Thumbnails(emptyList()),
                description = null,
            ),
        playbackTracking =
            playbackUrl?.let {
                PlayerResponse.PlaybackTracking(
                    videostatsPlaybackUrl = PlayerResponse.PlaybackTracking.VideostatsPlaybackUrl(it),
                    videostatsWatchtimeUrl = PlayerResponse.PlaybackTracking.VideostatsWatchtimeUrl("$it/watch"),
                    atrUrl = PlayerResponse.PlaybackTracking.AtrUrl("$it/atr"),
                )
            },
        captions = null,
    )

    @Test
    fun androidVrClientIsAnonymousWithExpectedHeaders() {
        val client = YouTubeClient.ANDROID_VR
        assertEquals("ANDROID_VR", client.clientName)
        assertEquals(28, client.xClientName)
        assertNull(client.referer)
    }

    @Test
    fun mergeDirectAudioUrlsPreservesWebRemixTrackingLoudnessAndAdoptsClen() {
        val webRemix =
            playerResponse(
                lengthSeconds = "199",
                loudnessDb = -7.25,
                playbackUrl = "https://music.youtube.com/api/stats/playback?id=web",
                adaptiveFormats =
                    listOf(
                        format(itag = ITAG.AUDIO_OPUS_HIGH, url = null, contentLength = 1_000L),
                        format(itag = ITAG.AUDIO_AAC_MEDIUM, url = null, contentLength = 900L),
                    ),
            )
        val vrOpusUrl = "https://rr1.googlevideo.com/videoplayback?itag=251&clen=3210456&dur=198.721"
        val vrAacUrl = "https://rr1.googlevideo.com/videoplayback?itag=140&clen=3100123&dur=198.740"
        val vr =
            playerResponse(
                lengthSeconds = "199",
                loudnessDb = -2.0,
                playbackUrl = "https://www.youtube.com/api/stats/playback?id=vr",
                adaptiveFormats =
                    listOf(
                        format(itag = ITAG.AUDIO_OPUS_HIGH, url = vrOpusUrl, contentLength = 3_210_456L),
                        format(itag = ITAG.AUDIO_AAC_MEDIUM, url = vrAacUrl, contentLength = 3_100_123L),
                    ),
            )

        val merged = AndroidVrStreamMerger.mergeDirectAudioUrls(webRemix, vr)
        assertNotNull(merged)
        assertEquals(-7.25, merged.playerConfig?.audioConfig?.loudnessDb)
        assertEquals(
            "https://music.youtube.com/api/stats/playback?id=web",
            merged.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
        )
        val opus = merged.streamingData?.adaptiveFormats?.firstOrNull { it.itag == ITAG.AUDIO_OPUS_HIGH }
        assertEquals(vrOpusUrl, opus?.url)
        assertEquals(3_210_456L, opus?.contentLength)
    }

    @Test
    fun mergeRejectsDurationMismatchedVrUrls() {
        val webRemix =
            playerResponse(
                lengthSeconds = "199",
                adaptiveFormats = listOf(format(itag = ITAG.AUDIO_OPUS_HIGH, url = null)),
            )
        val vrBadDur =
            playerResponse(
                lengthSeconds = "199",
                adaptiveFormats =
                    listOf(
                        format(
                            itag = ITAG.AUDIO_OPUS_HIGH,
                            url = "https://rr1.googlevideo.com/videoplayback?itag=251&clen=4000000&dur=245.500",
                        ),
                    ),
            )

        val merged = AndroidVrStreamMerger.mergeDirectAudioUrls(webRemix, vrBadDur)
        assertNull(merged)
    }

    @Test
    fun mergeRejectsRefusedOrLiveVrResponses() {
        val webRemix =
            playerResponse(
                lengthSeconds = "199",
                adaptiveFormats = listOf(format(itag = ITAG.AUDIO_OPUS_HIGH, url = null)),
            )
        val vrRefused =
            playerResponse(
                status = "LOGIN_REQUIRED",
                adaptiveFormats =
                    listOf(
                        format(
                            itag = ITAG.AUDIO_OPUS_HIGH,
                            url = "https://rr1.googlevideo.com/videoplayback?itag=251&dur=199.0",
                        ),
                    ),
            )
        val vrLive =
            playerResponse(
                isLive = true,
                adaptiveFormats =
                    listOf(
                        format(
                            itag = ITAG.AUDIO_OPUS_HIGH,
                            url = "https://rr1.googlevideo.com/videoplayback?itag=251&dur=199.0",
                        ),
                    ),
            )

        assertNull(AndroidVrStreamMerger.mergeDirectAudioUrls(webRemix, vrRefused))
        assertNull(AndroidVrStreamMerger.mergeDirectAudioUrls(webRemix, vrLive))
    }

    @Test
    fun fastPathStateEnforcesTrustWindowAnd15MinRejectionCooldown() {
        val state = AndroidVrFastPathState()
        val t0 = 10_000L

        assertTrue(state.isAvailable(t0))
        assertFalse(state.isTrusted(t0))

        state.markTrusted(t0)
        assertTrue(state.isTrusted(t0 + 60_000L))
        assertFalse(state.isTrusted(t0 + AndroidVrFastPathState.DEFAULT_TRUST_WINDOW_MS))

        state.recordServed("HzYrZoALCCA")
        assertFalse(state.onStreamRejected("OTHER_ID", t0 + 70_000L))
        assertTrue(state.isTrusted(t0 + 70_000L))

        assertTrue(state.onStreamRejected("HzYrZoALCCA", t0 + 80_000L))
        assertFalse(state.isAvailable(t0 + 80_000L))
        assertFalse(state.isTrusted(t0 + 80_000L))
        assertFalse(state.isAvailable(t0 + 80_000L + AndroidVrFastPathState.DEFAULT_COOLDOWN_MS - 1L))
        assertTrue(state.isAvailable(t0 + 80_000L + AndroidVrFastPathState.DEFAULT_COOLDOWN_MS))

        state.markFailedOrRejected(t0 + 2_000_000L)
        assertFalse(state.isAvailable(t0 + 2_000_001L))
        state.clear()
        assertTrue(state.isAvailable(t0 + 2_000_001L))
    }
}
