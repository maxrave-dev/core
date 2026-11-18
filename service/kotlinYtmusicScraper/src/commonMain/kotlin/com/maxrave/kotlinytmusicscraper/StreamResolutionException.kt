package com.maxrave.kotlinytmusicscraper

/**
 * Why a stream resolution failed, carried out of [YouTube.player] instead of the plain
 * "No URL found" every failure used to collapse into — so the log (and any future UI)
 * can tell a refusal from an outage from a track that has no playable stream.
 */
class StreamResolutionException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        /** The player requests never got an HTTP answer (offline, captive portal, timeouts). */
        NO_NETWORK,

        /** YouTube answered but refused: a playability status other than OK, or an HTTP error status. */
        REFUSED_BY_YOUTUBE,

        /** The track was reached but no playable stream came out of it (gone, not started yet, empty). */
        UNAVAILABLE,
    }
}
