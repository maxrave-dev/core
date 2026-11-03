package org.simpmusic.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Discord gateway v10 frames, as described in Discord's Gateway documentation. Only the fields
// Rich Presence needs are modelled; everything else is ignored on decode.

@Serializable
internal data class GatewayPayload(
    @SerialName("op") val op: Int,
    @SerialName("d") val d: JsonElement? = null,
    @SerialName("s") val s: Int? = null,
    @SerialName("t") val t: String? = null,
)

@Serializable
internal data class Hello(
    @SerialName("heartbeat_interval") val heartbeatInterval: Long,
)

@Serializable
internal data class Ready(
    @SerialName("session_id") val sessionId: String,
    @SerialName("resume_gateway_url") val resumeGatewayUrl: String,
)

@Serializable
internal data class Identify(
    @SerialName("token") val token: String,
    @SerialName("intents") val intents: Int = 0,
    @SerialName("properties") val properties: IdentifyProperties,
    @SerialName("compress") val compress: Boolean = false,
)

@Serializable
internal data class IdentifyProperties(
    @SerialName("os") val os: String,
    @SerialName("browser") val browser: String,
    @SerialName("device") val device: String,
)

@Serializable
internal data class Resume(
    @SerialName("token") val token: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("seq") val seq: Int,
)

@Serializable
internal data class Presence(
    @SerialName("activities") val activities: List<Activity>,
    @SerialName("afk") val afk: Boolean = true,
    @SerialName("since") val since: Long,
    @SerialName("status") val status: String = "online",
)

@Serializable
internal data class Activity(
    @SerialName("name") val name: String,
    @SerialName("details") val details: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("type") val type: Int,
    @SerialName("status_display_type") val statusDisplayType: Int = 0,
    @SerialName("timestamps") val timestamps: Timestamps? = null,
    @SerialName("assets") val assets: Assets? = null,
    @SerialName("buttons") val buttons: List<String>? = null,
    @SerialName("metadata") val metadata: Metadata? = null,
    @SerialName("application_id") val applicationId: String? = null,
)

@Serializable
internal data class Timestamps(
    @SerialName("start") val start: Long? = null,
    @SerialName("end") val end: Long? = null,
)

@Serializable
internal data class Assets(
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("small_image") val smallImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
    @SerialName("small_text") val smallText: String? = null,
)

@Serializable
internal data class Metadata(
    @SerialName("button_urls") val buttonUrls: List<String>,
)

@Serializable
internal data class ExternalAssetRequest(
    @SerialName("urls") val urls: List<String>,
)

@Serializable
internal data class ExternalAsset(
    @SerialName("external_asset_path") val externalAssetPath: String? = null,
)
