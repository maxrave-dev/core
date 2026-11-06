package com.maxrave.domain.data.model.discord

/**
 * A Discord login made through OAuth (Full builds). The access token only covers presence; Discord
 * expires it after about a week, and [refreshToken] gets a new one without asking the user again.
 */
data class DiscordSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)
