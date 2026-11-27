package com.maxrave.data.repository

import com.maxrave.domain.data.model.spotify.SpotifyLibraryError
import com.maxrave.domain.data.model.spotify.SpotifyLibraryResult
import com.maxrave.domain.data.model.spotify.SpotifyPlaylistDetail
import com.maxrave.domain.data.model.spotify.SpotifyPlaylistInfo
import com.maxrave.domain.data.model.spotify.SpotifyTrackInfo
import com.maxrave.domain.data.model.spotify.SpotifyTracksPage
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyLibraryRepository
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.Spotify.ApiResult
import com.maxrave.spotify.model.response.spotify.SpotifyTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
 
/**
 * Reads the signed-in user's Spotify library with the same sp_dc session the lyrics and Canvas
 * features already use. Strictly read-only.
 */
class SpotifyLibraryRepositoryImpl(
    private val spotify: Spotify,
    private val dataStoreManager: DataStoreManager,
) : SpotifyLibraryRepository {
    private sealed interface TokenResult {
        data class Ok(
            val value: String,
        ) : TokenResult

        data class Fail(
            val error: SpotifyLibraryError,
        ) : TokenResult
    }

    // One token refresh at a time: a playlist import runs several calls and must not stampede /api/token.
    private val tokenMutex = Mutex()

    override suspend fun isLoggedIn(): Boolean = dataStoreManager.spdc.first().isNotEmpty()

    override suspend fun getPlaylistFromLink(
        input: String,
    ): SpotifyLibraryResult<SpotifyPlaylistDetail> {
        val parsed = parseSpotifyLink(input)
            ?: return SpotifyLibraryResult.Failure(
                SpotifyLibraryError.INVALID_LINK,
                "Invalid Spotify link. Please paste a link like https://open.spotify.com/playlist/..."
            )

        val spdc = dataStoreManager.spdc.first().ifEmpty { null }
        return when (val res = spotify.getEmbedData(parsed.first, parsed.second, spdc)) {
            is ApiResult.Failure -> {
                Logger.e("SpotifyLibraryRepo", "Embed fetch failed: ${res.status} ${res.message}")
                when (res.status) {
                    404 -> SpotifyLibraryResult.Failure(SpotifyLibraryError.NOT_ALLOWED, res.message ?: "Playlist not found or private")
                    429 -> SpotifyLibraryResult.Failure(SpotifyLibraryError.RATE_LIMITED, "Spotify rate limited. Try again later.")
                    else -> SpotifyLibraryResult.Failure(SpotifyLibraryError.NETWORK, res.message ?: "Could not load playlist (${res.status})")
                }
            }
            is ApiResult.Ok -> {
                val entity = res.data
                val tracks = entity.trackList.mapNotNull { item ->
                    val trackUri = item.uri.orEmpty()
                    val trackId = trackUri.substringAfterLast(":").ifEmpty { null } ?: return@mapNotNull null
                    val subtitle = item.subtitle.orEmpty()
                    val artistsList = if (subtitle.isNotBlank()) {
                        subtitle.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }
                    val trackTitle = item.title.orEmpty().ifBlank { "Unknown track" }
                    SpotifyTrackInfo(
                        id = trackId,
                        title = trackTitle,
                        artists = artistsList,
                        durationMs = item.duration ?: 0L,
                        albumName = entity.name.ifBlank { entity.title.orEmpty() }.takeIf { entity.type == "album" },
                        thumbnail = entity.visualIdentity?.image?.firstOrNull()?.url,
                    )
                }
                val playlistTitle = entity.name.ifBlank { entity.title.orEmpty() }.ifBlank { "Spotify Playlist" }
                SpotifyLibraryResult.Success(
                    SpotifyPlaylistDetail(
                        id = entity.id.ifEmpty { parsed.second },
                        type = parsed.first,
                        title = playlistTitle,
                        author = entity.subtitle,
                        thumbnail = entity.visualIdentity?.image?.firstOrNull()?.url,
                        tracks = tracks,
                    )
                )
            }
        }
    }

    private fun parseSpotifyLink(input: String): Pair<String, String>? {
        val trimmed = input.trim()
        val webMatch = Regex("""open\.spotify\.com/(?:[a-zA-Z-]+/)?(playlist|album)/([a-zA-Z0-9]+)""").find(trimmed)
        if (webMatch != null) {
            return Pair(webMatch.groupValues[1], webMatch.groupValues[2])
        }
        val uriMatch = Regex("""spotify:(playlist|album):([a-zA-Z0-9]+)""").find(trimmed)
        if (uriMatch != null) {
            return Pair(uriMatch.groupValues[1], uriMatch.groupValues[2])
        }
        if (Regex("""^[a-zA-Z0-9]{22}$""").matches(trimmed)) {
            return Pair("playlist", trimmed)
        }
        return null
    }

    private suspend fun token(forceRefresh: Boolean = false): TokenResult =
        tokenMutex.withLock {
            val spdc = dataStoreManager.spdc.first()
            if (spdc.isEmpty()) return@withLock TokenResult.Fail(SpotifyLibraryError.NOT_LOGGED_IN)

            if (!forceRefresh) {
                val cached = dataStoreManager.spotifyPersonalToken.first()
                val expires = dataStoreManager.spotifyPersonalTokenExpires.first()
                // 60 s margin so a token does not expire in the middle of a paginated read.
                if (cached.isNotEmpty() && expires > Clock.System.now().toEpochMilliseconds() + 60_000L) {
                    return@withLock TokenResult.Ok(cached)
                }
            }

            spotify.getPersonalTokenWithTotp(spdc).fold(
                onSuccess = { response ->
                    // An expired sp_dc is still answered, with a guest token that has no library.
                    if (response.accessToken.isEmpty() || response.isAnonymous) {
                        TokenResult.Fail(SpotifyLibraryError.SESSION_EXPIRED)
                    } else {
                        dataStoreManager.setSpotifyPersonalToken(response.accessToken)
                        dataStoreManager.setSpotifyPersonalTokenExpires(response.accessTokenExpirationTimestampMs)
                        TokenResult.Ok(response.accessToken)
                    }
                },
                onFailure = {
                    Logger.w("SpotifyLibrary", "token refresh failed: ${it.message}")
                    TokenResult.Fail(SpotifyLibraryError.NETWORK)
                },
            )
        }

    /**
     * Runs [call] with a valid token. A 401 gets exactly one retry with a freshly minted token (the
     * cached one may be an old guest token), then reports SESSION_EXPIRED.
     */
    private suspend fun <T> authorized(call: suspend (String) -> ApiResult<T>): SpotifyLibraryResult<T> {
        var current = token()
        for (attempt in 0..1) {
            val usable = current
            val accessToken =
                when (usable) {
                    is TokenResult.Fail -> return SpotifyLibraryResult.Failure(usable.error)
                    is TokenResult.Ok -> usable.value
                }
            when (val result = call(accessToken)) {
                is ApiResult.Ok -> return SpotifyLibraryResult.Success(result.data)
                is ApiResult.Failure -> {
                    Logger.w("SpotifyLibrary", "request failed: ${result.status} ${result.message}")
                    when (result.status) {
                        401 -> if (attempt == 0) current = token(forceRefresh = true) else return failure(SpotifyLibraryError.SESSION_EXPIRED)
                        403, 404 -> return failure(SpotifyLibraryError.NOT_ALLOWED)
                        429 -> return failure(SpotifyLibraryError.RATE_LIMITED)
                        else -> return failure(SpotifyLibraryError.NETWORK)
                    }
                }
            }
        }
        return failure(SpotifyLibraryError.SESSION_EXPIRED)
    }

    private fun failure(error: SpotifyLibraryError) = SpotifyLibraryResult.Failure(error)

    override suspend fun getPlaylists(
        onProgress: ((count: Int) -> Unit)?,
        onStatusUpdate: ((String) -> Unit)?,
    ): SpotifyLibraryResult<List<SpotifyPlaylistInfo>> =
        when (val result = authorized { spotify.getAllMyPlaylists(it, onProgress, onStatusUpdate) }) {
            is SpotifyLibraryResult.Failure -> result
            is SpotifyLibraryResult.Success ->
                SpotifyLibraryResult.Success(
                    result.data
                        .filter { it.id.isNotEmpty() }
                        .map {
                            SpotifyPlaylistInfo(
                                id = it.id,
                                name = it.name.ifBlank { "Spotify playlist" },
                                thumbnail = it.images?.firstOrNull()?.url,
                                trackCount = it.trackCount,
                                ownerName = it.owner?.displayName ?: it.owner?.id,
                            )
                        },
                )
        }

    override suspend fun getPlaylistTracks(
        playlistId: String,
        onProgress: ((loaded: Int) -> Unit)?,
        onStatusUpdate: ((String) -> Unit)?,
    ): SpotifyLibraryResult<SpotifyTracksPage> =
        when (val result = authorized { spotify.getAllPlaylistEntries(playlistId, it, onProgress, onStatusUpdate) }) {
            is SpotifyLibraryResult.Failure -> result
            is SpotifyLibraryResult.Success -> SpotifyLibraryResult.Success(toPage(result.data.map { it.resolved }))
        }

    override suspend fun getLikedTracks(
        onProgress: ((loaded: Int) -> Unit)?,
        onStatusUpdate: ((String) -> Unit)?,
    ): SpotifyLibraryResult<SpotifyTracksPage> =
        when (val result = authorized { spotify.getAllSavedTracks(it, onProgress, onStatusUpdate) }) {
            is SpotifyLibraryResult.Failure -> result
            is SpotifyLibraryResult.Success -> SpotifyLibraryResult.Success(toPage(result.data.map { it.resolved }))
        }

    /**
     * Drops what cannot be looked up on YT Music (removed tracks, local files, podcast episodes) and
     * repeated entries of the same track, counting the former so the user can be told.
     */
    private fun toPage(entries: List<SpotifyTrack?>): SpotifyTracksPage {
        var skipped = 0
        val seen = HashSet<String>()
        val tracks = ArrayList<SpotifyTrackInfo>(entries.size)
        for (track in entries) {
            val id = track?.id
            if (track == null || track.isLocal || track.type == "episode" || id.isNullOrEmpty() || track.name.isBlank()) {
                skipped++
                continue
            }
            if (!seen.add(id)) continue
            tracks +=
                SpotifyTrackInfo(
                    id = id,
                    title = track.name,
                    artists = track.artists.map { it.name }.filter { it.isNotBlank() },
                    durationMs = track.durationMs,
                    albumName = track.album?.name,
                    thumbnail = track.album?.images?.firstOrNull()?.url,
                )
        }
        return SpotifyTracksPage(tracks = tracks, skippedUnavailable = skipped)
    }
}
