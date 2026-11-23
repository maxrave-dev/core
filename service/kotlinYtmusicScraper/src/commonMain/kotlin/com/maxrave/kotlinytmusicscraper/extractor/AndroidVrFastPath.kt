package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks HEAD-verification trust and failure/rejection cooldown for the anonymous `ANDROID_VR`
 * direct audio fast path.
 *
 * - After a direct URL passes a real HEAD check, subsequent audio resolutions skip the serial
 *   HEAD probe for [trustWindowMs] (default 5 minutes).
 * - On any rejection (`onStreamRejected` for an `ANDROID_VR`-served video) or failure/HEAD error,
 *   trust is revoked immediately and `ANDROID_VR` enters a [cooldownMs] (default 15 minutes)
 *   cooldown so the player falls straight back to `BravePipe` at `t = 0 ms`.
 */
internal class AndroidVrFastPathState(
    private val trustWindowMs: Long = DEFAULT_TRUST_WINDOW_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxServedTracks: Int = MAX_SERVED_TRACKS,
) {
    private data class Snapshot(
        val trustedUntilMs: Long = 0L,
        val cooldownUntilMs: Long = 0L,
        val servedVideoIds: List<String> = emptyList(),
    )

    private val state = MutableStateFlow(Snapshot())

    fun isAvailable(nowMs: Long): Boolean = nowMs >= state.value.cooldownUntilMs

    fun isTrusted(nowMs: Long): Boolean {
        val current = state.value
        return nowMs >= current.cooldownUntilMs && nowMs < current.trustedUntilMs
    }

    fun markTrusted(nowMs: Long) {
        state.update { current ->
            if (nowMs < current.cooldownUntilMs) {
                current
            } else {
                current.copy(trustedUntilMs = nowMs + trustWindowMs)
            }
        }
    }

    fun recordServed(videoId: String) {
        if (videoId.isBlank()) return
        state.update { current ->
            val updated = current.servedVideoIds.filterNot { it == videoId } + videoId
            val trimmed =
                if (updated.size > maxServedTracks) {
                    updated.drop(updated.size - maxServedTracks)
                } else {
                    updated
                }
            current.copy(servedVideoIds = trimmed)
        }
    }

    fun markFailedOrRejected(nowMs: Long) {
        state.update { current ->
            current.copy(
                trustedUntilMs = 0L,
                cooldownUntilMs = nowMs + cooldownMs,
            )
        }
    }

    /**
     * Marks [videoId] as rejected if it was served by `ANDROID_VR`. Returns true if `ANDROID_VR`
     * owned the stream and was placed into cooldown.
     */
    fun onStreamRejected(
        videoId: String,
        nowMs: Long,
    ): Boolean {
        if (videoId.isBlank()) return false
        var matched = false
        state.update { current ->
            if (videoId !in current.servedVideoIds) {
                current
            } else {
                matched = true
                current.copy(
                    trustedUntilMs = 0L,
                    cooldownUntilMs = nowMs + cooldownMs,
                    servedVideoIds = current.servedVideoIds.filterNot { it == videoId },
                )
            }
        }
        return matched
    }

    fun clear() {
        state.value = Snapshot()
    }

    companion object {
        const val DEFAULT_TRUST_WINDOW_MS = 5 * 60 * 1000L
        const val DEFAULT_COOLDOWN_MS = 15 * 60 * 1000L
        const val MAX_SERVED_TRACKS = 32
    }
}

internal object AndroidVrStreamMerger {
    /**
     * Extracts playable direct audio `(itag, url)` pairs from an `ANDROID_VR` [PlayerResponse],
     * discarding non-playable itags, live broadcasts, and URLs whose `dur=` disagrees with
     * [expectedDurationSec].
     */
    fun extractDirectAudioStreams(
        vrResponse: PlayerResponse,
        expectedDurationSec: Int? = null,
    ): List<Pair<Int, String>> {
        if (vrResponse.playabilityStatus.status != "OK") return emptyList()
        if (vrResponse.videoDetails?.isLive == true) return emptyList()
        val effectiveExpectedDur =
            expectedDurationSec?.takeIf { it > 0 }
                ?: vrResponse.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }
        return vrResponse.streamingData
            ?.adaptiveFormats
            .orEmpty()
            .asSequence()
            .filter { format ->
                format.isAudio &&
                    format.itag in ITAG.AUDIO_PLAYABLE &&
                    !format.url.isNullOrEmpty() &&
                    isUrlDurationCompatible(format.url, effectiveExpectedDur)
            }.map { format -> format.itag to format.url!! }
            .toList()
    }

    /**
     * Picks the highest-priority playable audio URL for a HEAD health probe, respecting
     * [preferredAudioLanguage] track ordering.
     */
    fun pickHeadProbeUrl(
        streams: List<Pair<Int, String>>,
        preferredAudioLanguage: String? = null,
    ): String? {
        if (streams.isEmpty()) return null
        val ordered = streams.orderByAudioTrack(preferredAudioLanguage)
        return ITAG.AUDIO_PLAYABLE.firstNotNullOfOrNull { target ->
            ordered.firstOrNull { it.first == target }?.second
        } ?: ordered.firstOrNull()?.second
    }

    /**
     * Merges direct audio URLs from [vrResponse] onto [baseResponse] (typically `WEB_REMIX`),
     * preserving [baseResponse]'s `playbackTracking`, `playerConfig` (`loudnessDb`), and metadata,
     * while enforcing `dur=` compatibility against [expectedDurationSec] (falling back to
     * [baseResponse]'s declared duration).
     *
     * Returns null if no duration-compatible playable audio stream can be produced.
     */
    fun mergeDirectAudioUrls(
        baseResponse: PlayerResponse,
        vrResponse: PlayerResponse,
        preferredAudioLanguage: String? = null,
        expectedDurationSec: Int? = null,
    ): PlayerResponse? {
        val effectiveExpectedDur =
            expectedDurationSec?.takeIf { it > 0 }
                ?: baseResponse.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }
                ?: vrResponse.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }
        val directStreams =
            extractDirectAudioStreams(vrResponse, effectiveExpectedDur)
                .orderByAudioTrack(preferredAudioLanguage)
        if (directStreams.isEmpty()) return null

        val vrFormatsByItag =
            vrResponse.streamingData
                ?.adaptiveFormats
                .orEmpty()
                .filter { it.isAudio && it.itag in ITAG.AUDIO_PLAYABLE && !it.url.isNullOrEmpty() }
                .associateBy { it.itag }

        if (baseResponse.playabilityStatus.status != "OK" || baseResponse.streamingData == null) {
            val filteredAdaptive =
                vrResponse.streamingData
                    ?.adaptiveFormats
                    .orEmpty()
                    .mapNotNull { format ->
                        val url = directStreams.firstOrNull { it.first == format.itag }?.second ?: return@mapNotNull null
                        format.copy(
                            url = url,
                            contentLength = contentLengthOf(url) ?: format.contentLength,
                        )
                    }
            if (filteredAdaptive.isEmpty()) return null
            return vrResponse.copy(
                playabilityStatus = vrResponse.playabilityStatus.copy(status = "OK", reason = null),
                playerConfig = baseResponse.playerConfig ?: vrResponse.playerConfig,
                playbackTracking = baseResponse.playbackTracking ?: vrResponse.playbackTracking,
                streamingData =
                    vrResponse.streamingData?.copy(
                        adaptiveFormats = filteredAdaptive,
                    ),
            )
        }

        val matchedItags = HashSet<Int>()
        val updatedAdaptive =
            baseResponse.streamingData.adaptiveFormats.map { format ->
                val matchedUrl = directStreams.firstOrNull { it.first == format.itag }?.second
                if (matchedUrl != null && format.isAudio && format.itag in ITAG.AUDIO_PLAYABLE) {
                    matchedItags += format.itag
                    val vrFormat = vrFormatsByItag[format.itag]
                    format.copy(
                        url = matchedUrl,
                        contentLength = contentLengthOf(matchedUrl) ?: vrFormat?.contentLength ?: format.contentLength,
                    )
                } else {
                    format
                }
            }.toMutableList()

        // Append any playable audio format carried by ANDROID_VR that WEB_REMIX omitted.
        for ((itag, url) in directStreams) {
            if (itag !in matchedItags) {
                val vrFormat = vrFormatsByItag[itag] ?: continue
                matchedItags += itag
                updatedAdaptive +=
                    vrFormat.copy(
                        url = url,
                        contentLength = contentLengthOf(url) ?: vrFormat.contentLength,
                    )
            }
        }

        val hasPlayableAudio =
            updatedAdaptive.any { it.isAudio && it.itag in ITAG.AUDIO_PLAYABLE && !it.url.isNullOrEmpty() }
        if (!hasPlayableAudio) return null

        val expiresIn =
            vrResponse.streamingData?.expiresInSeconds?.takeIf { it > 0 }
                ?: baseResponse.streamingData.expiresInSeconds
        return baseResponse.copy(
            streamingData =
                baseResponse.streamingData.copy(
                    adaptiveFormats = updatedAdaptive,
                    expiresInSeconds = expiresIn,
                ),
        )
    }
}
