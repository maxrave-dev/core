package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.MediaType
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectAudioFastPathTest {
    private fun webRemixFormat(
        itag: Int,
        url: String? = null,
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

    private fun webRemixResponse(
        videoId: String = "HzYrZoALCCA",
        status: String = "OK",
        lengthSeconds: String = "199",
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
                isLive = false,
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
    fun visionOsClientProfileMatchesBravePipeExtractor() {
        val client = YouTubeClient.VISIONOS
        assertEquals("VISIONOS", client.clientName)
        assertEquals("1.04", client.clientVersion)
        assertEquals(101, client.xClientName)
        assertEquals("Apple", client.deviceMake)
        assertEquals("RealityDevice17,1", client.deviceModel)
        assertEquals("visionOS", client.osName)
        assertEquals("26.6.0.23O770", client.osVersion)
        assertEquals(
            "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; BR)",
            YouTubeClient.visionOsUserAgent("BR"),
        )
    }

    @Test
    fun tolerantJsonParserHandlesMissingOptionalPlayerFields() {
        val rawJson =
            """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"videoId": "eqQRPRcoIYo", "lengthSeconds": "192"},
              "streamingData": {
                "expiresInSeconds": "21540",
                "adaptiveFormats": [
                  {
                    "itag": 251,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=251&clen=3100123&dur=191.521",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 132000
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=140&clen=3050000&dur=191.540",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 129000
                  }
                ]
              }
            }
            """.trimIndent()

        val parsed = DirectAudioFastPathParser.parsePlayerJson(rawJson)
        assertNotNull(parsed)
        assertEquals("OK", parsed.status)
        assertFalse(parsed.isLive)
        assertEquals(192, parsed.lengthSeconds)
        assertEquals(21_540, parsed.expiresInSeconds)
        assertNull(parsed.loudnessDb)
        assertEquals(2, parsed.adaptiveFormats.size)
        assertEquals(3_100_123L, parsed.adaptiveFormats[0].contentLength)
    }

    @Test
    fun mergeDirectAudioUrlsPreservesWebRemixTrackingLoudnessAndOrdersAudioTrack() {
        val webRemix =
            webRemixResponse(
                lengthSeconds = "199",
                loudnessDb = -7.25,
                playbackUrl = "https://music.youtube.com/api/stats/playback?id=web",
                adaptiveFormats =
                    listOf(
                        webRemixFormat(itag = ITAG.AUDIO_OPUS_MEDIUM, url = null, contentLength = 1_000L),
                    ),
            )
        val rawVisionOs =
            """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"videoId": "HzYrZoALCCA", "lengthSeconds": "199"},
              "streamingData": {
                "expiresInSeconds": 21540,
                "adaptiveFormats": [
                  {
                    "itag": 251,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=251&clen=2000000&dur=198.7&xtags=acont%3Ddubbed-auto%3Alang%3Dvi",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 128000
                  },
                  {
                    "itag": 251,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=251&clen=3210456&dur=198.721&xtags=acont%3Doriginal%3Alang%3Den-US",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 128000
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=140&clen=3100123&dur=198.740",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000
                  }
                ]
              }
            }
            """.trimIndent()
        val parsed = DirectAudioFastPathParser.parsePlayerJson(rawVisionOs)!!

        val merged = DirectAudioFastPathParser.mergeDirectAudioUrls(webRemix, parsed, preferredAudioLanguage = "pt")
        assertNotNull(merged)
        assertEquals(-7.25, merged.playerConfig?.audioConfig?.loudnessDb)
        assertEquals(
            "https://music.youtube.com/api/stats/playback?id=web",
            merged.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
        )
        val opus = merged.streamingData?.adaptiveFormats?.firstOrNull { it.itag == ITAG.AUDIO_OPUS_MEDIUM }
        assertTrue(opus?.url?.contains("acont%3Doriginal") == true)
        assertEquals(3_210_456L, opus?.contentLength)
        val aac = merged.streamingData?.adaptiveFormats?.firstOrNull { it.itag == ITAG.AUDIO_AAC_MEDIUM }
        assertNotNull(aac?.url)
    }

    @Test
    fun mergeRejectsDurationMismatchLiveAndRefusedResponses() {
        val webRemix =
            webRemixResponse(
                lengthSeconds = "199",
                adaptiveFormats = listOf(webRemixFormat(itag = ITAG.AUDIO_OPUS_MEDIUM, url = null)),
            )
        val badDur =
            DirectAudioFastPathParser.parsePlayerJson(
                """
                {
                  "playabilityStatus": {"status": "OK"},
                  "videoDetails": {"lengthSeconds": "199"},
                  "streamingData": {
                    "adaptiveFormats": [
                      {"itag": 251, "url": "https://rr1.googlevideo.com/videoplayback?itag=251&dur=245.5", "mimeType": "audio/webm"}
                    ]
                  }
                }
                """.trimIndent(),
            )!!
        val refused =
            DirectAudioFastPathParser.parsePlayerJson(
                """
                {
                  "playabilityStatus": {"status": "LOGIN_REQUIRED", "reason": "Sign in"},
                  "streamingData": {
                    "adaptiveFormats": [
                      {"itag": 251, "url": "https://rr1.googlevideo.com/videoplayback?itag=251&dur=199.0", "mimeType": "audio/webm"}
                    ]
                  }
                }
                """.trimIndent(),
            )!!
        val live =
            DirectAudioFastPathParser.parsePlayerJson(
                """
                {
                  "playabilityStatus": {"status": "OK"},
                  "videoDetails": {"isLive": true},
                  "streamingData": {
                    "adaptiveFormats": [
                      {"itag": 251, "url": "https://rr1.googlevideo.com/videoplayback?itag=251&dur=199.0", "mimeType": "audio/webm"}
                    ]
                  }
                }
                """.trimIndent(),
            )!!

        assertNull(DirectAudioFastPathParser.mergeDirectAudioUrls(webRemix, badDur))
        assertNull(DirectAudioFastPathParser.mergeDirectAudioUrls(webRemix, refused))
        assertNull(DirectAudioFastPathParser.mergeDirectAudioUrls(webRemix, live))
    }

    @Test
    fun fastPathStateEnforcesTrustWindowAnd15MinRejectionCooldown() {
        val state = DirectAudioFastPathState()
        val t0 = 10_000L

        assertTrue(state.isAvailable(t0))
        assertFalse(state.isTrusted(t0))

        state.markTrusted(t0)
        assertTrue(state.isTrusted(t0 + 60_000L))
        assertFalse(state.isTrusted(t0 + DirectAudioFastPathState.DEFAULT_TRUST_WINDOW_MS))

        state.recordServed("HzYrZoALCCA")
        assertFalse(state.onStreamRejected("OTHER_ID", t0 + 70_000L))
        assertTrue(state.isTrusted(t0 + 70_000L))

        assertTrue(state.onStreamRejected("HzYrZoALCCA", t0 + 80_000L))
        assertFalse(state.isAvailable(t0 + 80_000L))
        assertFalse(state.isTrusted(t0 + 80_000L))
        assertFalse(state.isAvailable(t0 + 80_000L + DirectAudioFastPathState.DEFAULT_COOLDOWN_MS - 1L))
        assertTrue(state.isAvailable(t0 + 80_000L + DirectAudioFastPathState.DEFAULT_COOLDOWN_MS))

        state.markFailedOrRejected(t0 + 2_000_000L)
        assertFalse(state.isAvailable(t0 + 2_000_001L))
        state.clear()
        assertTrue(state.isAvailable(t0 + 2_000_001L))
    }

    @Test
    fun buildStandalonePlayerResponseExtractsVideoDetailsLoudnessAndEnforcesDuration() {
        val raw =
            """
            {
              "playabilityStatus": { "status": "OK" },
              "playerConfig": {
                "audioConfig": { "loudnessDb": -7.42 }
              },
              "videoDetails": {
                "videoId": "HzYrZoALCCA",
                "title": "Test Track",
                "author": "Artist - Topic",
                "channelId": "UC123456",
                "lengthSeconds": "214",
                "isLiveContent": false,
                "musicVideoType": "MUSIC_VIDEO_TYPE_ATV",
                "thumbnail": {
                  "thumbnails": [
                    { "url": "https://i.ytimg.com/vi/HzYrZoALCCA/hqdefault.jpg", "width": 480, "height": 360 }
                  ]
                }
              },
              "streamingData": {
                "expiresInSeconds": "21540",
                "adaptiveFormats": [
                  {
                    "itag": 251,
                    "url": "https://rr3---sn-example.googlevideo.com/videoplayback?itag=251&dur=214.000&clen=3456789",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 132000,
                    "contentLength": "3456789"
                  }
                ]
              }
            }
            """.trimIndent()

        val parsed = DirectAudioFastPathParser.parsePlayerJson(raw)
        assertNotNull(parsed)

        val standalone =
            DirectAudioFastPathParser.buildStandalonePlayerResponse(
                videoId = "HzYrZoALCCA",
                directResponse = parsed,
                expectedDurationSec = 214,
            )
        assertNotNull(standalone)
        assertEquals("OK", standalone.playabilityStatus.status)
        assertEquals("Test Track", standalone.videoDetails?.title)
        assertEquals("Artist - Topic", standalone.videoDetails?.author)
        assertEquals("214", standalone.videoDetails?.lengthSeconds)
        assertEquals(-7.42, standalone.playerConfig?.audioConfig?.loudnessDb)
        assertNull(standalone.playbackTracking)
        assertEquals(1, standalone.streamingData?.adaptiveFormats?.size)
        assertEquals(ITAG.AUDIO_OPUS_MEDIUM, standalone.streamingData?.adaptiveFormats?.first()?.itag)

        // Mismatched expectedDurationSec (e.g., 30s preview when DB expects 214s) must be rejected.
        val rawPreview = raw.replace("dur=214.000", "dur=30.000")
        val parsedPreview = DirectAudioFastPathParser.parsePlayerJson(rawPreview)!!
        val rejected =
            DirectAudioFastPathParser.buildStandalonePlayerResponse(
                videoId = "HzYrZoALCCA",
                directResponse = parsedPreview,
                expectedDurationSec = 214,
            )
        assertNull(rejected)
    }

    @Test
    fun classifyMediaTypeProtectsFastPath4By3ThumbnailsWhileHonoringExplicitVideos() {
        val song4By3NoType =
            PlayerResponse.VideoDetails(
                videoId = "id1",
                title = "Song",
                author = "Some Artist",
                channelId = "UC1",
                authorAvatar = null,
                authorSubCount = null,
                lengthSeconds = "180",
                isLive = false,
                musicVideoType = null,
                viewCount = null,
                thumbnail = Thumbnails(listOf(Thumbnail("https://i.ytimg.com/vi/id1/hqdefault.jpg", 480, 360))),
                description = null,
            )
        // Standard path classifies non-square as Video unless ATV, whereas fast path avoids false Video downgrade.
        assertEquals(MediaType.Video, classifyMediaType(song4By3NoType, isDirectFastPath = false))
        assertEquals(MediaType.Song, classifyMediaType(song4By3NoType, isDirectFastPath = true))

        // Topic channel is always treated as Song on fast path.
        val topic4By3 = song4By3NoType.copy(author = "Artist - Topic")
        assertEquals(MediaType.Song, classifyMediaType(topic4By3, isDirectFastPath = true))

        // Explicit OMV or UGC stays Video even on fast path (unless Topic or square thumbnail).
        assertEquals(
            MediaType.Video,
            classifyMediaType(
                song4By3NoType,
                isDirectFastPath = true,
                knownMusicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            ),
        )
        assertEquals(
            MediaType.Video,
            classifyMediaType(
                song4By3NoType.copy(musicVideoType = "MUSIC_VIDEO_TYPE_UGC"),
                isDirectFastPath = true,
            ),
        )
        // Explicit ATV stays Song even when fast path is off.
        assertEquals(
            MediaType.Song,
            classifyMediaType(
                song4By3NoType,
                isDirectFastPath = false,
                knownMusicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            ),
        )
    }
}

