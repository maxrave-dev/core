@file:OptIn(ExperimentalTime::class)

package com.maxrave.data.spotify

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A ready-to-use Spotify (personalToken, clientToken) pair.
 */
data class SpotifyTokens(
    val personalToken: String,
    val clientToken: String,
)

/**
 * Returns a valid `(personalToken, clientToken)` pair for calling Spotify's internal
 * pathfinder/GraphQL API, using the already-cached tokens in [DataStoreManager] if they
 * haven't expired yet, or minting fresh ones (via the `sp_dc` cookie + TOTP flow already
 * implemented in [Spotify]) otherwise. Returns `null` if the user isn't logged in to
 * Spotify (no `sp_dc` cookie saved) or refresh fails.
 *
 * This is the same expiry-check-and-refresh logic that used to be duplicated inline in
 * [com.maxrave.data.repository.LyricsCanvasRepositoryImpl] (Canvas + lyrics), factored out
 * so it can also back Spotify playlist/library fetching.
 */
class SpotifyTokenProvider(
    private val spotify: Spotify,
) {
    suspend fun getValidTokens(dataStoreManager: DataStoreManager): SpotifyTokens? {
        val now = Clock.System.now().toEpochMilliseconds()
        val cachedPersonalToken = dataStoreManager.spotifyPersonalToken.first()
        val cachedClientToken = dataStoreManager.spotifyClientToken.first()
        val personalTokenExpires = dataStoreManager.spotifyPersonalTokenExpires.first()
        val clientTokenExpires = dataStoreManager.spotifyClientTokenExpires.first()

        if (cachedPersonalToken.isNotEmpty() &&
            cachedClientToken.isNotEmpty() &&
            personalTokenExpires != 0L &&
            personalTokenExpires > now &&
            clientTokenExpires != 0L &&
            clientTokenExpires > now
        ) {
            return SpotifyTokens(cachedPersonalToken, cachedClientToken)
        }

        val spdc = dataStoreManager.spdc.first()
        if (spdc.isEmpty()) {
            // Not logged in to Spotify.
            return null
        }

        var clientToken = ""
        spotify
            .getClientToken()
            .onSuccess {
                clientToken = it.grantedToken.token
                dataStoreManager.setSpotifyClientToken(clientToken)
                dataStoreManager.setSpotifyClientTokenExpires(
                    (it.grantedToken.expiresAfterSeconds * 1000L) + Clock.System.now().toEpochMilliseconds(),
                )
            }.onFailure {
                Logger.e("SpotifyTokenProvider", "Failed to get client token: ${it.message}")
            }

        var personalToken = ""
        spotify
            .getPersonalTokenWithTotp(spdc)
            .onSuccess {
                personalToken = it.accessToken
                dataStoreManager.setSpotifyPersonalToken(personalToken)
                dataStoreManager.setSpotifyPersonalTokenExpires(it.accessTokenExpirationTimestampMs)
            }.onFailure {
                Logger.e("SpotifyTokenProvider", "Failed to get personal token: ${it.message}")
            }

        return if (personalToken.isNotEmpty() && clientToken.isNotEmpty()) {
            SpotifyTokens(personalToken, clientToken)
        } else {
            null
        }
    }
}
