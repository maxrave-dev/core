package org.simpmusic.discord

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Rich Presence for the Full build, through the Discord application set with [configDiscord]. It
 * shows the same activity kizzy shows on the FOSS build; only the credential differs, a Bearer token
 * from Discord's OAuth flow instead of the user's own token.
 *
 * @param tokenProvider returns the current access token, refreshed first when [forceRefresh] is set
 * or when it is about to expire; null once the user is logged out.
 */
class DiscordRpc(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
) {
    private val gateway = DiscordGateway(tokenProvider)

    // Bounded, so a stalled artwork request cannot hold the presence sender open.
    private val http =
        HttpClient(getEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    private val json = Json { ignoreUnknownKeys = true }

    // Lookup and fetch under one lock, so presence updates for the same artwork never send it twice.
    private val artworkCache = mutableMapOf<String, String>()
    private val artworkMutex = Mutex()

    fun isRpcRunning(): Boolean = gateway.isConnected()

    fun closeRPC() {
        gateway.close()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun updateSong(
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        song: SongEntity,
    ) = runCatching {
        val now = Clock.System.now().toEpochMilliseconds()
        // Discord draws the progress bar from these two timestamps, so they are in wall-clock time
        // and scaled by the playback speed.
        val startTime = now - (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val endTime = now + ((durationMillis - currentPlaybackTimeMillis) / playbackSpeed).toLong()
        val buttons =
            listOf(
                "Listen on SimpMusic" to "https://simpmusic.org/app/watch?v=${song.videoId}",
                "Visit SimpMusic" to "https://github.com/maxrave-dev/SimpMusic",
            )
        gateway.sendPresence(
            Presence(
                activities =
                    listOf(
                        Activity(
                            name = APP_NAME,
                            details = song.title,
                            state = song.artistName?.joinToString(", "),
                            type = TYPE_LISTENING,
                            timestamps = Timestamps(start = startTime, end = endTime),
                            assets =
                                Assets(
                                    largeImage = song.thumbnails?.let { externalImage(it) },
                                    smallImage = externalImage(APP_ICON),
                                    largeText = song.albumName,
                                    smallText = song.artistName?.firstOrNull(),
                                ),
                            buttons = buttons.map { it.first },
                            metadata = Metadata(buttonUrls = buttons.map { it.second }),
                            applicationId = discordClientId(),
                        ),
                    ),
                since = now,
            ),
        )
    }

    /** Discord shows external images only through its media proxy, as an `mp:` asset path. */
    private suspend fun externalImage(url: String): String =
        artworkMutex.withLock {
            artworkCache[url] ?: (fetchExternalAsset(url)?.also { artworkCache[url] = it } ?: url)
        }

    private suspend fun fetchExternalAsset(url: String): String? {
        val token = tokenProvider(false) ?: return null
        return try {
            val response =
                http.post("https://discord.com/api/v9/applications/${discordClientId()}/external-assets") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(ExternalAssetRequest.serializer(), ExternalAssetRequest(listOf(url))))
                }
            if (!response.status.isSuccess()) {
                Logger.w(TAG, "external-assets: HTTP ${response.status.value}")
                return null
            }
            json
                .decodeFromString(ListSerializer(ExternalAsset.serializer()), response.bodyAsText())
                .firstOrNull()
                ?.externalAssetPath
                ?.let { "mp:$it" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "external-assets failed: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DiscordRpc"
        const val APP_NAME = "SimpMusic"
        const val TYPE_LISTENING = 2
        const val USER_AGENT = "Discord-Android/314013;RNA"
        const val APP_ICON =
            "https://fra.cloud.appwrite.io/v1/storage/buckets/683f1f620010ba0fa5b1/files/69007bc8001a28a7cea8/view?project=67ec0369002bd8a96885"
    }
}
