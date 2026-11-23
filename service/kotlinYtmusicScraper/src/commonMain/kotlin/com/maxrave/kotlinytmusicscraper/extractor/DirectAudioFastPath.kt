package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Tracks HEAD-verification trust and failure/rejection cooldown for the direct anonymous
 * `VISIONOS 1.04` audio fast path.
 *
 * - After a direct URL passes a real HEAD check, subsequent audio resolutions skip the serial
 *   HEAD probe for [trustWindowMs] (default 5 minutes).
 * - On any rejection (`onStreamRejected` for a `VISIONOS`-served video) or failure/HEAD error,
 *   trust is revoked immediately and `VISIONOS` enters a [cooldownMs] (default 15 minutes)
 *   cooldown so the player falls straight back to `BravePipe` at `t = 0 ms`.
 */
internal class DirectAudioFastPathState(
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
     * Marks [videoId] as rejected if it was served by the direct fast path. Returns true if it
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

internal data class ParsedDirectPlayerResponse(
    val status: String,
    val reason: String?,
    val isLive: Boolean,
    val lengthSeconds: Int?,
    val expiresInSeconds: Int,
    val adaptiveFormats: List<PlayerResponse.StreamingData.Format>,
    val loudnessDb: Double?,
)

internal object DirectAudioFastPathParser {
    private val tolerantJson =
        Json {
            ignoreUnknownKeys = true
        }

    /**
     * Parses a raw InnerTube `player` response JSON defensively via [JsonObject], ignoring
     * optional/missing fields (e.g. `playerConfig`, `audioConfig`, `playbackTracking`, `captions`)
     * that would fail strict `PlayerResponse` deserialization.
     */
    fun parsePlayerJson(rawJson: String): ParsedDirectPlayerResponse? =
        runCatching {
            val root = tolerantJson.parseToJsonElement(rawJson).jsonObject
            parsePlayerJson(root)
        }.getOrNull()

    fun parsePlayerJson(root: JsonObject): ParsedDirectPlayerResponse {
        val playability = root["playabilityStatus"] as? JsonObject
        val status = playability?.stringOrNull("status") ?: "ERROR"
        val reason = playability?.stringOrNull("reason")

        val videoDetails = root["videoDetails"] as? JsonObject
        val isLive = videoDetails?.booleanOrNull("isLive") == true
        val lengthSeconds = videoDetails?.intOrNull("lengthSeconds")

        val audioConfig =
            (root["playerConfig"] as? JsonObject)
                ?.get("audioConfig") as? JsonObject
        val loudnessDb = audioConfig?.doubleOrNull("loudnessDb")

        val streamingData = root["streamingData"] as? JsonObject
        val expiresInSeconds = streamingData?.intOrNull("expiresInSeconds")?.takeIf { it > 0 } ?: 21_540
        val adaptiveFormats =
            streamingData
                ?.get("adaptiveFormats")
                ?.let { el -> (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull(::parseFormat) }
                .orEmpty()

        return ParsedDirectPlayerResponse(
            status = status,
            reason = reason,
            isLive = isLive,
            lengthSeconds = lengthSeconds,
            expiresInSeconds = expiresInSeconds,
            adaptiveFormats = adaptiveFormats,
            loudnessDb = loudnessDb,
        )
    }

    private fun parseFormat(element: JsonElement): PlayerResponse.StreamingData.Format? {
        val obj = element as? JsonObject ?: return null
        val itag = obj.intOrNull("itag") ?: return null
        val url = obj.stringOrNull("url")
        val contentLength =
            obj.longOrNull("contentLength")
                ?: url?.let(::contentLengthOf)
        return PlayerResponse.StreamingData.Format(
            itag = itag,
            url = url,
            mimeType = obj.stringOrNull("mimeType") ?: "",
            bitrate = obj.intOrNull("bitrate") ?: 0,
            width = obj.intOrNull("width"),
            height = obj.intOrNull("height"),
            contentLength = contentLength,
            quality = obj.stringOrNull("quality") ?: "",
            fps = obj.intOrNull("fps"),
            qualityLabel = obj.stringOrNull("qualityLabel"),
            averageBitrate = obj.intOrNull("averageBitrate"),
            audioQuality = obj.stringOrNull("audioQuality"),
            approxDurationMs = obj.stringOrNull("approxDurationMs"),
            audioSampleRate = obj.intOrNull("audioSampleRate"),
            audioChannels = obj.intOrNull("audioChannels"),
            loudnessDb = obj.doubleOrNull("loudnessDb"),
            lastModified = obj.longOrNull("lastModified"),
            signatureCipher = obj.stringOrNull("signatureCipher") ?: obj.stringOrNull("cipher"),
        )
    }

    /**
     * Extracts playable direct audio `(itag, url)` pairs from [parsed], discarding non-playable
     * itags, live broadcasts, and URLs whose `dur=` disagrees with [expectedDurationSec].
     */
    fun extractDirectAudioStreams(
        parsed: ParsedDirectPlayerResponse,
        expectedDurationSec: Int? = null,
    ): List<Pair<Int, String>> {
        if (parsed.status != "OK" || parsed.isLive) return emptyList()
        val effectiveExpectedDur =
            expectedDurationSec?.takeIf { it > 0 }
                ?: parsed.lengthSeconds?.takeIf { it > 0 }
        return parsed.adaptiveFormats
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
     * Merges direct audio URLs from [directResponse] (`VISIONOS`) onto [baseResponse] (typically
     * `WEB_REMIX`), preserving [baseResponse]'s `playbackTracking`, `playerConfig` (`loudnessDb`),
     * and metadata, while enforcing `dur=` compatibility against [expectedDurationSec] (falling
     * back to [baseResponse]'s or [directResponse]'s declared duration).
     *
     * Returns null if no duration-compatible playable audio stream can be produced.
     */
    fun mergeDirectAudioUrls(
        baseResponse: PlayerResponse,
        directResponse: ParsedDirectPlayerResponse,
        preferredAudioLanguage: String? = null,
        expectedDurationSec: Int? = null,
    ): PlayerResponse? {
        val effectiveExpectedDur =
            expectedDurationSec?.takeIf { it > 0 }
                ?: baseResponse.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }
                ?: directResponse.lengthSeconds?.takeIf { it > 0 }
        val directStreams =
            extractDirectAudioStreams(directResponse, effectiveExpectedDur)
                .orderByAudioTrack(preferredAudioLanguage)
        if (directStreams.isEmpty()) return null

        val directFormatsByItag =
            directResponse.adaptiveFormats
                .filter { it.isAudio && it.itag in ITAG.AUDIO_PLAYABLE && !it.url.isNullOrEmpty() }
                .associateBy { it.itag }

        if (baseResponse.playabilityStatus.status != "OK" || baseResponse.streamingData == null) {
            val filteredAdaptive =
                directResponse.adaptiveFormats
                    .mapNotNull { format ->
                        val url = directStreams.firstOrNull { it.first == format.itag }?.second ?: return@mapNotNull null
                        format.copy(
                            url = url,
                            contentLength = contentLengthOf(url) ?: format.contentLength,
                        )
                    }
            if (filteredAdaptive.isEmpty()) return null
            val mergedConfig =
                baseResponse.playerConfig
                    ?: directResponse.loudnessDb?.let { loudness ->
                        PlayerResponse.PlayerConfig(
                            audioConfig =
                                PlayerResponse.PlayerConfig.AudioConfig(
                                    loudnessDb = loudness,
                                    perceptualLoudnessDb = loudness,
                                ),
                        )
                    }
            return baseResponse.copy(
                playabilityStatus = baseResponse.playabilityStatus.copy(status = "OK", reason = null),
                playerConfig = mergedConfig,
                streamingData =
                    (
                        baseResponse.streamingData ?: PlayerResponse.StreamingData(
                            hlsManifestUrl = null,
                            formats = emptyList(),
                            adaptiveFormats = emptyList(),
                            expiresInSeconds = directResponse.expiresInSeconds,
                        )
                    ).copy(
                        adaptiveFormats = filteredAdaptive,
                        expiresInSeconds = directResponse.expiresInSeconds,
                    ),
            )
        }

        val matchedItags = HashSet<Int>()
        val updatedAdaptive =
            baseResponse.streamingData.adaptiveFormats.map { format ->
                val matchedUrl = directStreams.firstOrNull { it.first == format.itag }?.second
                if (matchedUrl != null && format.isAudio && format.itag in ITAG.AUDIO_PLAYABLE) {
                    matchedItags += format.itag
                    val directFormat = directFormatsByItag[format.itag]
                    format.copy(
                        url = matchedUrl,
                        contentLength = contentLengthOf(matchedUrl) ?: directFormat?.contentLength ?: format.contentLength,
                    )
                } else {
                    format
                }
            }.toMutableList()

        // Append any playable audio format carried by VISIONOS that WEB_REMIX omitted.
        for ((itag, url) in directStreams) {
            if (itag !in matchedItags) {
                val directFormat = directFormatsByItag[itag] ?: continue
                matchedItags += itag
                updatedAdaptive +=
                    directFormat.copy(
                        url = url,
                        contentLength = contentLengthOf(url) ?: directFormat.contentLength,
                    )
            }
        }

        val hasPlayableAudio =
            updatedAdaptive.any { it.isAudio && it.itag in ITAG.AUDIO_PLAYABLE && !it.url.isNullOrEmpty() }
        if (!hasPlayableAudio) return null

        val expiresIn =
            directResponse.expiresInSeconds.takeIf { it > 0 }
                ?: baseResponse.streamingData.expiresInSeconds
        return baseResponse.copy(
            streamingData =
                baseResponse.streamingData.copy(
                    adaptiveFormats = updatedAdaptive,
                    expiresInSeconds = expiresIn,
                ),
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { prim ->
            prim.intOrNull ?: prim.contentOrNull?.toIntOrNull()
        }

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { prim ->
            prim.longOrNull ?: prim.contentOrNull?.toLongOrNull()
        }

    private fun JsonObject.doubleOrNull(key: String): Double? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { prim ->
            prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull()
        }

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
}
