package com.maxrave.spotify

import com.maxrave.spotify.auth.SpotifyAuth
import com.maxrave.spotify.model.response.spotify.CanvasResponse
import com.maxrave.spotify.model.response.spotify.ClientTokenResponse
import com.maxrave.spotify.model.response.spotify.PersonalTokenResponse
import com.maxrave.spotify.model.response.spotify.SpotifyLyricsResponse
import com.maxrave.spotify.model.response.spotify.search.SpotifySearchResponse
import com.maxrave.spotify.model.response.spotify.SpotifyMe
import com.maxrave.spotify.model.response.spotify.SpotifyPaging
import com.maxrave.spotify.model.response.spotify.SpotifyPlaylistEntry
import com.maxrave.spotify.model.response.spotify.SpotifyPlaylistSummary
import com.maxrave.spotify.model.response.spotify.SpotifySavedTrack
import com.maxrave.spotify.model.response.spotify.SpotifyEmbedEntity
import com.maxrave.spotify.model.response.spotify.SpotifyEmbedNextData
import io.ktor.client.call.body
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class Spotify {
    private val spotifyClient = SpotifyClient()
    private val spotifyAuth = SpotifyAuth(spotifyClient)

    /**
     * Remove proxy for client
     */
    fun removeProxy() {
        spotifyClient.proxy = null
    }

    /**
     * Set the proxy for client
     */
    fun setProxy(
        isHttp: Boolean,
        host: String,
        port: Int,
    ) {
        val verifiedHost =
            if (!host.contains("http")) {
                "http://$host"
            } else {
                host
            }
        runCatching {
            if (isHttp) ProxyBuilder.http("$verifiedHost:$port") else ProxyBuilder.socks(verifiedHost, port)
        }.onSuccess {
            spotifyClient.proxy = it
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * Get personal token using the standard method
     */
    suspend fun getPersonalToken(spdc: String) =
        runCatching {
            spotifyClient.getSpotifyLyricsToken(spdc).body<PersonalTokenResponse>()
        }

    /**
     * Get personal token using the more reliable TOTP-based method
     * This should be used when the standard method fails
     */
    suspend fun getPersonalTokenWithTotp(spdc: String) = spotifyAuth.refreshToken(spdc)

    suspend fun getClientToken() =
        runCatching {
            spotifyClient
                .getSpotifyClientToken()
                .body<ClientTokenResponse>()
        }

    suspend fun searchSpotifyTrack(
        query: String,
        authToken: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .searchSpotifyTrack(query, authToken, clientToken)
            .body<SpotifySearchResponse>()
    }

    suspend fun getSpotifyLyrics(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .getSpotifyLyrics(
                token = token,
                clientToken = clientToken,
                trackId,
            ).body<SpotifyLyricsResponse>()
    }

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient.getSpotifyCanvas(trackId, token, clientToken).body<CanvasResponse>()
    }

    // ---------------------------------------------------------------------------------------------
    // Library (playlists + liked songs). Read-only: nothing here ever writes to the user's account.
    // ---------------------------------------------------------------------------------------------

    /** Outcome of a Web API call, with the HTTP status kept so callers can tell 401 from 403 from 429. */
    sealed interface ApiResult<out T> {
        data class Ok<T>(
            val data: T,
        ) : ApiResult<T>

        data class Failure(
            val status: Int,
            val message: String? = null,
        ) : ApiResult<Nothing>
    }

    private suspend inline fun <reified T> HttpResponse.toApiResult(): ApiResult<T> =
        if (status.isSuccess()) {
            runCatching { ApiResult.Ok(body<T>()) }
                .getOrElse { ApiResult.Failure(status.value, "Unexpected response: ${it.message}") }
        } else {
            ApiResult.Failure(status.value, status.description)
        }

    /**
     * Runs one request and retries on 429 (honouring Retry-After) up to [MAX_RATE_LIMIT_RETRIES]
     * times. Any other status is returned as is.
     */
    private suspend inline fun <reified T> withRateLimit(
        noinline onStatusUpdate: ((String) -> Unit)? = null,
        request: () -> HttpResponse,
    ): ApiResult<T> {
        var attempt = 0
        while (true) {
            val response =
                try {
                    request()
                } catch (e: Exception) {
                    return ApiResult.Failure(-1, e.message ?: "Network error")
                }
            if (response.status.value == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                val retryAfterHeader = response.headers[HttpHeaders.RetryAfter]
                val waitSeconds = retryAfterHeader?.toLongOrNull() ?: 5L
                val wait = waitSeconds.coerceIn(2L, 60L)
                for (remaining in wait downTo 1) {
                    onStatusUpdate?.invoke("Spotify is limiting requests. Retrying in ${remaining}s…")
                    delay(1000L)
                }
                attempt++
                continue
            }
            return response.toApiResult<T>()
        }
    }

    suspend fun getEmbedData(type: String, id: String, spdc: String? = null): ApiResult<SpotifyEmbedEntity> {
        // Try first with spdc (if present), then fallback to no spdc (for public links if cookie expired)
        var response =
            try {
                spotifyClient.getSpotifyEmbedHtml(type, id, spdc)
            } catch (e: Exception) {
                null
            }

        if (response == null || !response.status.isSuccess()) {
            if (!spdc.isNullOrEmpty()) {
                // Retry without cookie
                response = try {
                    spotifyClient.getSpotifyEmbedHtml(type, id, null)
                } catch (e: Exception) {
                    null
                }
            }
        }

        if (response == null) {
            return ApiResult.Failure(-1, "Network connection error")
        }
        if (!response.status.isSuccess()) {
            return ApiResult.Failure(response.status.value, "HTTP ${response.status.value}: ${response.status.description}")
        }
        val html = response.bodyAsText()
        val match = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)
            ?: return ApiResult.Failure(404, "Playlist not found or private (page length: ${html.length})")
        val jsonText = match.groupValues[1]
        val json = Json { 
            ignoreUnknownKeys = true 
            isLenient = true
            coerceInputValues = true
        }
        return try {
            val nextData = json.decodeFromString<SpotifyEmbedNextData>(jsonText)
            val entity = nextData.props?.pageProps?.state?.data?.entity
            if (entity != null) {
                ApiResult.Ok(entity)
            } else {
                ApiResult.Failure(404, "Invalid playlist data in response")
            }
        } catch (e: Exception) {
            ApiResult.Failure(-1, "Failed to parse playlist: ${e.message}")
        }
    }

    suspend fun getMe(token: String): ApiResult<SpotifyMe> = withRateLimit { spotifyClient.getMe(token) }

    /** All playlists of the signed-in user, following `next` up to [MAX_PAGES] pages. */
    suspend fun getAllMyPlaylists(
        token: String,
        onProgress: ((count: Int) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): ApiResult<List<SpotifyPlaylistSummary>> {
        val all = mutableListOf<SpotifyPlaylistSummary>()
        var offset = 0
        repeat(MAX_PAGES) {
            when (val page = withRateLimit<SpotifyPaging<SpotifyPlaylistSummary>>(onStatusUpdate) { spotifyClient.getMyPlaylists(token, offset) }) {
                is ApiResult.Failure -> return page
                is ApiResult.Ok -> {
                    all += page.data.items
                    onProgress?.invoke(all.size)
                    if (page.data.next == null || page.data.items.isEmpty()) return ApiResult.Ok(all)
                    offset += page.data.items.size
                    delay(200L)
                }
            }
        }
        return ApiResult.Ok(all)
    }

    /**
     * Every entry of one playlist. Tries the current `/items` path and falls back to the old `/tracks`
     * path when the former answers 404, because which one a given token accepts has changed over time.
     * Playlists the account may not read (Spotify-made ones, other people's in dev mode) come back as
     * a 403/404 Failure and are left to the caller to skip.
     */
    suspend fun getAllPlaylistEntries(
        playlistId: String,
        token: String,
        onProgress: ((loaded: Int) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): ApiResult<List<SpotifyPlaylistEntry>> {
        var legacy = false
        val all = mutableListOf<SpotifyPlaylistEntry>()
        var offset = 0
        repeat(MAX_PAGES) {
            var page =
                withRateLimit<SpotifyPaging<SpotifyPlaylistEntry>>(onStatusUpdate) {
                    spotifyClient.getPlaylistItems(playlistId, token, offset, useLegacyPath = legacy)
                }
            if (page is ApiResult.Failure && page.status == 404 && !legacy && offset == 0) {
                legacy = true
                page =
                    withRateLimit<SpotifyPaging<SpotifyPlaylistEntry>>(onStatusUpdate) {
                        spotifyClient.getPlaylistItems(playlistId, token, offset, useLegacyPath = true)
                    }
            }
            when (page) {
                is ApiResult.Failure -> return page
                is ApiResult.Ok -> {
                    all += page.data.items
                    onProgress?.invoke(all.size)
                    if (page.data.next == null || page.data.items.isEmpty()) return ApiResult.Ok(all)
                    offset += page.data.items.size
                    delay(200L)
                }
            }
        }
        return ApiResult.Ok(all)
    }

    /** The user's liked songs, newest first (Spotify's own order). */
    suspend fun getAllSavedTracks(
        token: String,
        onProgress: ((loaded: Int) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
    ): ApiResult<List<SpotifySavedTrack>> {
        val all = mutableListOf<SpotifySavedTrack>()
        var offset = 0
        repeat(MAX_PAGES) {
            when (val page = withRateLimit<SpotifyPaging<SpotifySavedTrack>>(onStatusUpdate) { spotifyClient.getSavedTracks(token, offset) }) {
                is ApiResult.Failure -> return page
                is ApiResult.Ok -> {
                    all += page.data.items
                    onProgress?.invoke(all.size)
                    if (page.data.next == null || page.data.items.isEmpty()) return ApiResult.Ok(all)
                    offset += page.data.items.size
                    delay(200L)
                }
            }
        }
        return ApiResult.Ok(all)
    }

    private companion object {
        const val MAX_RATE_LIMIT_RETRIES = 3

        // 200 pages * 50-100 entries: far past any real library, only there to stop a runaway `next` loop.
        const val MAX_PAGES = 200
    }
}