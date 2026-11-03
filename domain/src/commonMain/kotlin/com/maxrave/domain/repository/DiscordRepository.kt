package com.maxrave.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The Discord login behind Rich Presence. A Full build logs in through Discord's OAuth page; a FOSS
 * build carries no Discord application and keeps the token its WebView login stored, used by kizzy.
 */
interface DiscordRepository {
    /** True when this build logs in through OAuth, false when it uses kizzy's token. */
    val isOAuth: Boolean

    val loggedIn: Flow<Boolean>

    /** The Discord page to open for an OAuth login, or null when this build has no OAuth. */
    fun startLogin(): String?

    /**
     * Finishes the OAuth login from the link Discord redirected to, whether it arrived through the
     * `simpmusic://discord-auth` callback or was pasted by hand. False when it cannot be completed.
     */
    suspend fun completeLogin(callbackUrl: String): Boolean

    /**
     * The token the gateway identifies with: the OAuth access token, refreshed first when
     * [forceRefresh] is set or it is about to expire, or kizzy's token. Null when logged out.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String?

    suspend fun logout()
}
