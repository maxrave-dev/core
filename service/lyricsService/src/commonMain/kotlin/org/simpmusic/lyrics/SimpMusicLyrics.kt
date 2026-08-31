package org.simpmusic.lyrics

import com.maxrave.ktorext.curl.CurlLogger
import com.maxrave.ktorext.encoding.brotli
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.am.AMTokenManager
import org.simpmusic.lyrics.models.request.LyricsBody
import org.simpmusic.lyrics.models.request.TranslatedLyricsBody
import org.simpmusic.lyrics.models.request.VoteBody

class SimpMusicLyrics {
    private var httpClient = createClient()
    var proxy: ProxyConfig? = null
        set(value) {
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    private val baseUrl = "https://api-lyrics.simpmusic.org/v1/"

    private val amTokenManager = AMTokenManager()

    private fun createClient() =
        HttpClient(getEngine()) {
            expectSuccess = false
            followRedirects = true
            install(HttpCache)
            install(CurlLogger) {
                logger = { Logger.d("SimpMusicLyrics", it) }
            }
            install(HttpSend) {
                maxSendCount = 100
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }
            defaultRequest {
                url("https://api-lyrics.simpmusic.org/v1")
            }
            if (proxy != null) {
                engine {
                    proxy = this@SimpMusicLyrics.proxy
                }
            }
        }

    private fun HttpRequestBuilder.buildDefaultHeaders(
        timestamp: String? = null,
        hmac: String? = null,
    ) {
        headers {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "SimpMusicLyrics/1.0")
            header(HttpHeaders.ContentType, "application/json")
            timestamp?.let {
                header("X-Timestamp", it)
            }
            hmac?.let {
                header("X-HMAC", it)
            }
        }
    }

    private fun HttpRequestBuilder.buildAMHeaders(token: String) {
        headers {
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header(
                HttpHeaders.UserAgent,
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0",
            )
            header(HttpHeaders.Accept, "application/json")
        }
    }

    suspend fun findLyricsByVideoId(videoId: String) =
        httpClient.get(baseUrl + videoId) {
            buildDefaultHeaders()
        }

    suspend fun findTranslatedLyrics(
        videoId: String,
        language: String,
    ) = httpClient.get(baseUrl + "translated/$videoId/$language") {
        buildDefaultHeaders()
    }

    suspend fun insertLyrics(
        lyricsBody: LyricsBody,
        hmacTimestamp: Pair<String, String>,
    ) = httpClient.post {
        buildDefaultHeaders(
            timestamp = hmacTimestamp.second,
            hmac = hmacTimestamp.first,
        )
        setBody(lyricsBody)
    }

    suspend fun insertTranslatedLyrics(
        translatedLyricsBody: TranslatedLyricsBody,
        hmacTimestamp: Pair<String, String>,
    ) = httpClient.post(baseUrl + "translated") {
        buildDefaultHeaders(
            timestamp = hmacTimestamp.second,
            hmac = hmacTimestamp.first,
        )
        setBody(translatedLyricsBody)
    }

    suspend fun voteLyrics(
        id: String,
        upvote: Boolean,
        hmacTimestamp: Pair<String, String>,
    ) = httpClient.post(baseUrl + "vote") {
        buildDefaultHeaders(
            timestamp = hmacTimestamp.second,
            hmac = hmacTimestamp.first,
        )
        setBody(
            VoteBody(
                id = id,
                vote = if (upvote) 1 else 0, // 1 for upvote, 0 for downvote
            ),
        )
    }

    suspend fun voteTranslatedLyrics(
        id: String,
        upvote: Boolean,
        hmacTimestamp: Pair<String, String>,
    ) = httpClient.post(baseUrl + "translated/vote") {
        buildDefaultHeaders(
            timestamp = hmacTimestamp.second,
            hmac = hmacTimestamp.first,
        )
        setBody(
            VoteBody(
                id = id,
                vote = if (upvote) 1 else 0, // 1 for upvote, 0 for downvote
            ),
        )
    }

    suspend fun searchLrclibLyrics(
        q_track: String,
        q_artist: String,
    ) = httpClient.get("https://lrclib.net/api/search") {
        buildDefaultHeaders()
        parameter("q", "$q_artist $q_track")
    }

    suspend fun searchBetterLyrics(
        q_track: String,
        q_artist: String,
        durationSeconds: Int?,
    ) = httpClient.get("https://lyrics-api.boidu.dev/getLyrics") {
        buildDefaultHeaders()
        parameter("s", q_track)
        parameter("a", q_artist)
        durationSeconds?.let {
            parameter("d", it)
        }
    }

    suspend fun searchAMArtist(
        name: String,
        limit: Int,
    ): HttpResponse {
        val response = requestAMSearch(name, limit, amTokenManager.getToken(httpClient))
        // Expired bearer -> refresh the token once and retry on a fresh request.
        if (response.status.value == 401) {
            amTokenManager.clearToken()
            return requestAMSearch(name, limit, amTokenManager.getToken(httpClient))
        }
        return response
    }

    private suspend fun requestAMSearch(
        name: String,
        limit: Int,
        token: String,
    ) = httpClient.get("https://amp-api-edge.music.apple.com/v1/catalog/us/search") {
        parameter("term", name)
        parameter("types", "artists")
        parameter("fields[artists]", "url,name,artwork")
        parameter("art[url]", "f")
        parameter("format[resources]", "map")
        parameter("extend", "artistUrl")
        parameter("l", "en-US")
        parameter("limit", limit)
        parameter("platform", "web")
        buildAMHeaders(token)
    }

    suspend fun getAMArtist(id: String): HttpResponse {
        val response = requestAMArtist(id, amTokenManager.getToken(httpClient))
        // Expired bearer -> refresh the token once and retry on a fresh request.
        if (response.status.value == 401) {
            amTokenManager.clearToken()
            return requestAMArtist(id, amTokenManager.getToken(httpClient))
        }
        return response
    }

    private suspend fun requestAMArtist(
        id: String,
        token: String,
    ) = httpClient.get("https://amp-api.music.apple.com/v1/catalog/us/artists/$id") {
        parameter("art[url]", "c,f")
        parameter("extend", "editorialArtwork,hero,keyColor")
        parameter("format[resources]", "map")
        parameter("l", "en-US")
        parameter("platform", "web")
        buildAMHeaders(token)
    }

    /**
     * Search the AM catalog for an album. `extend=editorialVideo` fills the animated artwork in
     * on the search response itself, so — unlike the artist flow — no per-album follow-up request
     * is needed. Mirrors [searchAMArtist], including the refresh-once-on-401 retry.
     */
    suspend fun searchAMAlbum(
        query: String,
        limit: Int,
    ): HttpResponse {
        val response = requestAMAlbumSearch(query, limit, amTokenManager.getToken(httpClient))
        // Expired bearer -> refresh the token once and retry on a fresh request.
        if (response.status.value == 401) {
            amTokenManager.clearToken()
            return requestAMAlbumSearch(query, limit, amTokenManager.getToken(httpClient))
        }
        return response
    }

    private suspend fun requestAMAlbumSearch(
        query: String,
        limit: Int,
        token: String,
    ) = httpClient.get("https://amp-api-edge.music.apple.com/v1/catalog/us/search") {
        parameter("term", query)
        parameter("types", "albums")
        // Narrowing to the fields actually read drops ~23% off the response without losing
        // editorialVideo, and this runs once per played track.
        parameter("fields[albums]", "name,artistName,url,artwork,editorialVideo")
        parameter("format[resources]", "map")
        parameter("extend", "editorialVideo")
        parameter("l", "en-US")
        parameter("limit", limit)
        parameter("platform", "web")
        buildAMHeaders(token)
    }

    /**
     * Find the album a *track* belongs to, and bring that album's animated artwork back with it.
     *
     * Searching `types=albums` with a track title cannot do this: AM answers with the singles that
     * share the title — "Blinding Lights - Single" rather than "After Hours" — and a single never
     * carries animated artwork, so the search succeeds while the result is always empty. Asking for
     * songs and including their albums returns the parent album itself, already extended.
     */
    suspend fun searchAMSongs(
        query: String,
        limit: Int,
    ): HttpResponse {
        val response = requestAMSongSearch(query, limit, amTokenManager.getToken(httpClient))
        // Expired bearer -> refresh the token once and retry on a fresh request.
        if (response.status.value == 401) {
            amTokenManager.clearToken()
            return requestAMSongSearch(query, limit, amTokenManager.getToken(httpClient))
        }
        return response
    }

    private suspend fun requestAMSongSearch(
        query: String,
        limit: Int,
        token: String,
    ) = httpClient.get("https://amp-api-edge.music.apple.com/v1/catalog/us/search") {
        parameter("term", query)
        parameter("types", "songs")
        parameter("include[songs]", "albums")
        parameter("format[resources]", "map")
        parameter("extend", "editorialVideo")
        parameter("l", "en-US")
        parameter("limit", limit)
        parameter("platform", "web")
        buildAMHeaders(token)
    }

    /**
     * Fetch an animated-artwork playlist off Apple's media CDN. Deliberately without the AM headers:
     * this host takes no bearer, serves no DRM and needs no origin — sending them only invites the
     * edge to treat the request as something it is not.
     */
    suspend fun fetchAMHlsPlaylist(url: String): HttpResponse = httpClient.get(url)
}
