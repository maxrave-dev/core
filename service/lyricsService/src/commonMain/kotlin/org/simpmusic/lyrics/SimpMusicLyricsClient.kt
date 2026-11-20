package org.simpmusic.lyrics

import com.maxrave.ktorext.crypto.Hmac
import com.maxrave.ktorext.crypto.HmacUri
import com.maxrave.logger.Logger
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.simpmusic.lyrics.am.AMAlbumResource
import org.simpmusic.lyrics.am.AMSongWithAlbum
import org.simpmusic.lyrics.am.parseAMHlsVariants
import org.simpmusic.lyrics.am.pickAMRendition
import org.simpmusic.lyrics.am.AMArtistResource
import org.simpmusic.lyrics.am.AMSearchResponse
import org.simpmusic.lyrics.models.request.LyricsBody
import org.simpmusic.lyrics.models.request.TranslatedLyricsBody
import org.simpmusic.lyrics.models.response.BaseResponse
import org.simpmusic.lyrics.models.response.BetterLyricsResponse
import org.simpmusic.lyrics.models.response.LrclibObject
import org.simpmusic.lyrics.models.response.LyricsResponse
import org.simpmusic.lyrics.models.response.TranslatedLyricsResponse
import org.simpmusic.lyrics.parser.parseLrclibLyrics
import kotlin.math.abs

private const val TAG = "SimpMusicLyricsClient"
private const val MAX_DEDUPE_CACHE_ENTRIES = 256

/** An error the SimpMusic Lyrics API answered with; [code] 404 means nobody has added that song yet. */
class SimpMusicLyricsApiException(
    val code: Int,
    message: String,
) : Exception(message)

private inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

class SimpMusicLyricsClient(
    private val lyricsService: SimpMusicLyrics = SimpMusicLyrics(),
) {
    private val algorithm = ""

    private val hmacService = Hmac("HmacSHA256", "simpmusic-lyrics")

    // Guards the shared insert/rate-limit/dedupe state below: this client is shared across callers.
    private val stateMutex = Mutex()

    private val inFlightLyricsInserts = mutableSetOf<String>()
    private val submittedLyricsByVideoId = LinkedHashMap<String, Pair<Int, LyricsResponse>>()

    private var tooManyRequest: Boolean = false

    private val inFlightTranslatedInserts = mutableSetOf<Pair<String, String>>()
    private val submittedTranslatedByKey = LinkedHashMap<Pair<String, String>, Pair<Int, TranslatedLyricsResponse>>()

    suspend fun getLyrics(videoId: String): Result<List<LyricsResponse>> =
        suspendRunCatching {
            lyricsService.findLyricsByVideoId(videoId).bodyOrThrow<List<LyricsResponse>>()
        }

    suspend fun getTranslatedLyrics(
        videoId: String,
        language: String,
    ): Result<TranslatedLyricsResponse> =
        suspendRunCatching {
            if (language.length != 2) {
                throw IllegalArgumentException("Language code must be a 2-letter code")
            }
            lyricsService.findTranslatedLyrics(videoId, language).bodyOrThrow<TranslatedLyricsResponse>()
        }

    suspend fun insertLyrics(lyricsBody: LyricsBody): Result<LyricsResponse> =
        suspendRunCatching {
            val contentHash =
                arrayOf(lyricsBody.plainLyric, lyricsBody.syncedLyrics, lyricsBody.richSyncLyrics).contentHashCode()
            var claimed = false
            stateMutex.withLock {
                if (tooManyRequest) {
                    throw IllegalStateException("Too many requests, please wait before trying again.")
                }
                val alreadySubmitted = submittedLyricsByVideoId[lyricsBody.videoId]
                if (alreadySubmitted != null && alreadySubmitted.first == contentHash) {
                    return@suspendRunCatching alreadySubmitted.second
                }
                if (!inFlightLyricsInserts.add(lyricsBody.videoId)) {
                    throw IllegalStateException("Already inserting lyrics, please wait until the current operation is complete.")
                }
                claimed = true
            }
            try {
                val hmacTimestamp =
                    hmacService.getMacTimestampPair(
                        HmacUri.BASE_HMAC_URI,
                    )
                val response = lyricsService.insertLyrics(lyricsBody, hmacTimestamp).bodyOrThrow<LyricsResponse>()
                stateMutex.withLock {
                    if (submittedLyricsByVideoId.size >= MAX_DEDUPE_CACHE_ENTRIES &&
                        !submittedLyricsByVideoId.containsKey(lyricsBody.videoId)
                    ) {
                        submittedLyricsByVideoId.keys.firstOrNull()?.let { submittedLyricsByVideoId.remove(it) }
                    }
                    submittedLyricsByVideoId[lyricsBody.videoId] = contentHash to response
                }
                response
            } finally {
                if (claimed) {
                    stateMutex.withLock {
                        inFlightLyricsInserts.remove(lyricsBody.videoId)
                    }
                }
            }
        }

    suspend fun insertTranslatedLyrics(translatedLyricsBody: TranslatedLyricsBody): Result<TranslatedLyricsResponse> =
        suspendRunCatching {
            if (translatedLyricsBody.language.length != 2) {
                throw IllegalArgumentException("Language code must be a 2-letter code")
            }
            val key = translatedLyricsBody.videoId to translatedLyricsBody.language
            val contentHash = translatedLyricsBody.translatedLyric.hashCode()
            var claimed = false
            stateMutex.withLock {
                if (tooManyRequest) {
                    throw IllegalStateException("Too many requests, please wait before trying again.")
                }
                val alreadySubmitted = submittedTranslatedByKey[key]
                if (alreadySubmitted != null && alreadySubmitted.first == contentHash) {
                    return@suspendRunCatching alreadySubmitted.second
                }
                if (!inFlightTranslatedInserts.add(key)) {
                    throw IllegalStateException("Already inserting translated lyrics, please wait until the current operation is complete.")
                }
                claimed = true
            }
            try {
                val hmacTimestamp =
                    hmacService.getMacTimestampPair(
                        HmacUri.TRANSLATED_HMAC_URI,
                    )
                val response =
                    lyricsService.insertTranslatedLyrics(translatedLyricsBody, hmacTimestamp).bodyOrThrow<TranslatedLyricsResponse>()
                stateMutex.withLock {
                    if (submittedTranslatedByKey.size >= MAX_DEDUPE_CACHE_ENTRIES &&
                        !submittedTranslatedByKey.containsKey(key)
                    ) {
                        submittedTranslatedByKey.keys.firstOrNull()?.let { submittedTranslatedByKey.remove(it) }
                    }
                    submittedTranslatedByKey[key] = contentHash to response
                }
                response
            } finally {
                if (claimed) {
                    stateMutex.withLock {
                        inFlightTranslatedInserts.remove(key)
                    }
                }
            }
        }

    suspend fun voteLyrics(
        lyricsId: String,
        upvote: Boolean,
    ): Result<LyricsResponse> =
        suspendRunCatching {
            val hmacTimestamp =
                hmacService.getMacTimestampPair(
                    HmacUri.VOTE_HMAC_URI,
                )
            lyricsService.voteLyrics(lyricsId, upvote, hmacTimestamp).bodyOrThrow<LyricsResponse>()
        }

    suspend fun voteTranslatedLyrics(
        translatedLyricsId: String,
        upvote: Boolean,
    ): Result<TranslatedLyricsResponse> =
        suspendRunCatching {
            val hmacTimestamp =
                hmacService.getMacTimestampPair(
                    HmacUri.VOTE_TRANSLATED_HMAC_URI,
                )
            lyricsService.voteTranslatedLyrics(translatedLyricsId, upvote, hmacTimestamp).bodyOrThrow<TranslatedLyricsResponse>()
        }

    suspend fun searchLrclibLyrics(
        q_track: String,
        q_artist: String,
        duration: Int?,
    ) = suspendRunCatching {
        val response =
            lyricsService.searchLrclibLyrics(
                q_track = q_track,
                q_artist = q_artist,
            )
        when (response.status.value) {
            404, 204 -> null
            in 200..299 -> {
                val rs = response.body<List<LrclibObject>>()
                val lrclibObject = selectLrclibLyricsRecord(rs, duration)
                lrclibObject?.let { parseLrclibLyrics(it) }
            }
            else -> throw SimpMusicLyricsApiException(response.status.value, "LRCLIB failed: ${response.status.value}")
        }
    }

    suspend fun searchBetterLyrics(
        q_track: String,
        q_artist: String,
        durationSeconds: Int?,
    ) = suspendRunCatching {
        val response =
            lyricsService.searchBetterLyrics(
                q_track = q_track,
                q_artist = q_artist,
                durationSeconds = durationSeconds?.takeIf { it > 0 },
            )
        when (response.status.value) {
            404, 204 -> null
            in 200..299 -> response.body<BetterLyricsResponse>().ttml
            else -> throw SimpMusicLyricsApiException(response.status.value, "BetterLyrics failed: ${response.status.value}")
        }
    }

    suspend fun searchAMArtist(
        name: String,
        limit: Int = 5,
    ): Result<List<AMArtistResource>> =
        runCatching {
            val response = lyricsService.searchAMArtist(name, limit)
            if (response.status.value !in 200..299) {
                throw Exception("AM search failed: ${response.status.value}")
            }
            val parsed = response.body<AMSearchResponse>()
            val resources = parsed.resources?.artists.orEmpty()
            // Keep the search ranking from results.data; fall back to the resources map order.
            parsed.results
                ?.artists
                ?.data
                ?.mapNotNull { resources[it.id] }
                ?: resources.values.toList()
        }

    /**
     * Fetch a single artist by id, including [AMEditorialArtwork] (name-logo PNG) and keyColor.
     * Returns null when the id is not present in the response.
     */
    suspend fun getAMArtist(id: String): Result<AMArtistResource?> =
        runCatching {
            val response = lyricsService.getAMArtist(id)
            if (response.status.value !in 200..299) {
                throw Exception("AM artist fetch failed: ${response.status.value}")
            }
            response
                .body<AMSearchResponse>()
                .resources
                ?.artists
                ?.get(id)
        }

    /**
     * Search the AM catalog for albums, ranked the way AM ranked them. Each result already carries
     * its animated artwork when it has one, so the caller needs no follow-up request.
     */
    suspend fun searchAMAlbum(
        query: String,
        limit: Int = 5,
    ): Result<List<AMAlbumResource>> =
        runCatching {
            val response = lyricsService.searchAMAlbum(query, limit)
            if (response.status.value !in 200..299) {
                throw Exception("AM album search failed: ${response.status.value}")
            }
            val parsed = response.body<AMSearchResponse>()
            val resources = parsed.resources?.albums.orEmpty()
            // Keep the search ranking from results.data; fall back to the resources map order.
            parsed.results
                ?.albums
                ?.data
                ?.mapNotNull { resources[it.id] }
                ?: resources.values.toList()
        }

    /**
     * Tracks reached by title, each paired with the album it appears on.
     *
     * The pairing comes from `relationships.albums`, never from the flat `resources.albums` map:
     * that map holds every album any hit belongs to, so reading an album out of it directly
     * attaches whichever release happens to carry artwork to whatever track was searched for.
     * Order follows the search ranking; the caller still decides which hit is the right track.
     */
    suspend fun searchAMSongsWithAlbums(
        query: String,
        limit: Int = 5,
    ): Result<List<AMSongWithAlbum>> =
        runCatching {
            val response = lyricsService.searchAMSongs(query, limit)
            if (response.status.value !in 200..299) {
                throw Exception("AM song search failed: ${response.status.value}")
            }
            val parsed = response.body<AMSearchResponse>()
            val songs = parsed.resources?.songs.orEmpty()
            val albums = parsed.resources?.albums.orEmpty()
            val ranked =
                parsed.results
                    ?.songs
                    ?.data
                    ?.mapNotNull { songs[it.id] }
                    ?: songs.values.toList()
            ranked.mapNotNull { song ->
                val albumId =
                    song.relationships
                        ?.albums
                        ?.data
                        ?.firstOrNull()
                        ?.id
                val album = albumId?.let { albums[it] } ?: return@mapNotNull null
                AMSongWithAlbum(
                    songName = song.attributes?.name,
                    artistName = song.attributes?.artistName,
                    durationInMillis = song.attributes?.durationInMillis,
                    album = album,
                )
            }
        }

    /**
     * Resolve an animated-artwork master playlist down to the single rendition worth playing, and
     * return its media-playlist url.
     *
     * Doing this here rather than leaving it to the player is what makes the two platforms agree:
     * mpv defaults to `--hls-bitrate=max` and would take the top of the ladder — on a measured
     * artwork that is 2048x2732 10-bit HEVC at 20 Mbps, 52 MB for a 22-second loop — while
     * ExoPlayer would pick by its own bandwidth estimate. Handing both a chosen rendition removes
     * the question. Returns null when the url is not a master playlist.
     */
    suspend fun selectAMRendition(
        masterUrl: String,
        minWidth: Int,
    ): Result<String?> =
        runCatching {
            val response = lyricsService.fetchAMHlsPlaylist(masterUrl)
            if (response.status.value !in 200..299) {
                throw Exception("AM playlist fetch failed: ${response.status.value}")
            }
            parseAMHlsVariants(response.bodyAsText(), masterUrl)
                .pickAMRendition(minWidth)
                ?.uri
        }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (this.status.value == 429) {
            stateMutex.withLock { tooManyRequest = true }
            Logger.e(TAG, "Too many requests: ${this.status.value}")
        } else {
            stateMutex.withLock { tooManyRequest = false }
        }
        try {
            val data = body<BaseResponse<T>>()
            if (data.error != null) {
                val error = data.error
                val message = "Error response: ${error.reason} (code: ${error.code})"
                // 404 is the normal answer for a song nobody has added lyrics for, not a failure.
                if (error.code == 404) Logger.d(TAG, message) else Logger.e(TAG, message)
                throw SimpMusicLyricsApiException(error.code, message)
            }
            return data.data ?: throw Exception("Response data is null")
        } catch (e: Exception) {
            throw e
        }
    }
}

internal fun selectLrclibLyricsRecord(
    records: List<LrclibObject>,
    duration: Int?,
): LrclibObject? {
    val validDuration = duration?.takeIf { it > 0 }
    val candidates =
        if (validDuration != null) {
            records.filter { abs(it.duration.toInt() - validDuration) <= 10 }
        } else {
            records
        }
    return candidates.firstOrNull { it.hasWordSync } ?: candidates.firstOrNull()
}
