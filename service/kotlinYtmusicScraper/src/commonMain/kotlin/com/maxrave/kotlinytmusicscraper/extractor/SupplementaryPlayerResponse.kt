package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.logger.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * PipePipe scans WEB_REMIX before its configured player and keeps the first similar stream.
 * Limit the supplementary response to the Premium audio it was intended to supply, so its
 * ordinary web streams cannot replace the TV streams with URLs that require a web PoToken.
 */
internal fun filterSupplementaryPlayerResponse(
    requestUrl: String,
    requestBody: ByteArray?,
    responseBody: ByteArray,
): ByteArray {
    if (!requestUrl.startsWith("https://music.youtube.com/youtubei/v1/player") || requestBody == null) {
        return responseBody
    }
    return try {
        val request = Json.parseToJsonElement(requestBody.decodeToString()) as? JsonObject ?: return responseBody
        val context = request["context"] as? JsonObject
        val client = context?.get("client") as? JsonObject
        if ((client?.get("clientName") as? JsonPrimitive)?.contentOrNull != "WEB_REMIX") return responseBody
        val response = Json.parseToJsonElement(responseBody.decodeToString()) as? JsonObject ?: return responseBody
        val streamingData = response["streamingData"] as? JsonObject ?: return responseBody
        val premiumItags = setOf(ITAG.AUDIO_AAC_HIGH, ITAG.AUDIO_OPUS_HIGH)
        var removed = 0
        val filtered = streamingData.toMutableMap()
        for (key in listOf("adaptiveFormats", "formats")) {
            val formats = streamingData[key] as? JsonArray ?: continue
            val premium = formats.filter { format ->
                val itag = ((format as? JsonObject)?.get("itag") as? JsonPrimitive)?.intOrNull
                itag != null && itag in premiumItags
            }
            removed += formats.size - premium.size
            filtered[key] = JsonArray(premium)
        }
        if (removed == 0) return responseBody
        val videoId = (request["videoId"] as? JsonPrimitive)?.contentOrNull
        Logger.i("Extractor", "WEB_REMIX supplement for $videoId: removed $removed ordinary formats; keeping Premium audio")
        JsonObject(response + ("streamingData" to JsonObject(filtered))).toString().encodeToByteArray()
    } catch (e: IllegalArgumentException) {
        Logger.w("Extractor", "Could not filter WEB_REMIX supplement: ${e::class.simpleName}")
        responseBody
    }
}
