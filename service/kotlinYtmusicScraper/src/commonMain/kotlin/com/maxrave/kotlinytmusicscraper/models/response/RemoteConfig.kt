package com.maxrave.kotlinytmusicscraper.models.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Remote app config fetched from GitHub raw on each app launch.
 *
 * Flat schema. All fields are nullable so a partial, malformed, or future version of the
 * file never crashes parsing — a missing field just leaves the corresponding credential
 * empty (TIDAL metadata stays disabled until a valid value is fetched).
 */
@Serializable
data class RemoteConfig(
    @SerialName("tidalClientId")
    val tidalClientId: String? = null,
    @SerialName("tidalClientSecret")
    val tidalClientSecret: String? = null,
    // Launch banners. Kept as raw JSON, so a block of the wrong shape cannot fail the TIDAL fields
    // above; the app decodes it entry by entry and drops what it cannot read. A JSON syntax error
    // anywhere in the file still fails the whole file, TIDAL included.
    @SerialName("promos")
    val promos: JsonElement? = null,
)
