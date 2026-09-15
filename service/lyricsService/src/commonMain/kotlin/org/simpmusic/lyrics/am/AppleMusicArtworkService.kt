package org.simpmusic.lyrics.am

import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

private const val TAG = "AppleMusicArtworkService"

class AppleMusicArtworkService(
    private val httpClient: HttpClient,
) {
    /**
     * Resolves artwork and motion video for an album.
     * The album name and the author name must match the iTunes API response.
     */
    suspend fun getAlbumArtwork(
        albumName: String,
        artistName: String,
        country: String = "us",
    ): AppleMusicArtworkResult? =
        runCatching {
            if (albumName.isBlank()) return null

            val queries = buildSearchQueries(albumName, artistName)
            var matchedItem: ITunesItem? = null

            for (query in queries) {
                val results = searchITunes(query, entity = "album", country = country, limit = 10)
                matchedItem = results.firstOrNull { item ->
                    isExactOrNormalizedMatch(albumName, item.collectionName) &&
                        isExactOrNormalizedMatch(artistName, item.artistName)
                }
                if (matchedItem != null) break
            }

            if (matchedItem == null) {
                Logger.d(TAG, "No exact match for album '$albumName' by '$artistName'")
                return null
            }

            parseItemResult(matchedItem)
        }.getOrElse { e ->
            Logger.e(TAG, "Error resolving album artwork for '$albumName' by '$artistName': ${e.message}")
            null
        }

    /**
     * Resolves artwork and motion video for a song.
     * 1. If album name is present, attempts album search first.
     * 2. If album is not present or yields no result, searches by song name and artist name.
     * Both song name and artist name must match.
     */
    suspend fun getSongArtwork(
        songTitle: String,
        artistName: String,
        albumName: String? = null,
        country: String = "us",
    ): AppleMusicArtworkResult? =
        runCatching {
            // 1. If album name is present, try resolving via album first
            if (!albumName.isNullOrBlank()) {
                val albumResult = getAlbumArtwork(albumName = albumName, artistName = artistName, country = country)
                if (albumResult != null && albumResult.hasMotion) {
                    return albumResult
                }
            }

            // 2. Search song directly: both song name and artist name must match
            if (songTitle.isBlank() || artistName.isBlank()) return null

            val queries = buildSearchQueries(songTitle, artistName)
            var matchedItem: ITunesItem? = null

            for (query in queries) {
                val results = searchITunes(query, entity = "song", country = country, limit = 10)
                matchedItem = results.firstOrNull { item ->
                    isExactOrNormalizedMatch(songTitle, item.trackName) &&
                        isExactOrNormalizedMatch(artistName, item.artistName)
                }
                if (matchedItem != null) break
            }

            if (matchedItem == null) {
                Logger.d(TAG, "No match for song '$songTitle' by '$artistName'")
                return null
            }

            parseItemResult(matchedItem)
        }.getOrElse { e ->
            Logger.e(TAG, "Error resolving song artwork for '$songTitle' by '$artistName': ${e.message}")
            null
        }


    private suspend fun searchITunes(
        term: String,
        entity: String,
        country: String = "us",
        limit: Int = 10,
    ): List<ITunesItem> =
        runCatching {
            val response =
                httpClient.get("https://itunes.apple.com/search") {
                    parameter("term", term)
                    parameter("entity", entity)
                    parameter("country", country)
                    parameter("limit", limit)
                    headers {
                        header(HttpHeaders.Accept, "application/json, text/javascript, */*")
                        header(
                            HttpHeaders.UserAgent,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                        )
                    }
                }
            if (response.status.value in 200..299) {
                val jsonText = response.bodyAsText()
                val jsonParser = kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
                jsonParser.decodeFromString<ITunesSearchResponse>(jsonText).results
            } else {
                emptyList()
            }
        }.onFailure { e ->
            Logger.e(TAG, "searchITunes failed: ${e.message}")
            e.printStackTrace()
        }.getOrDefault(emptyList())

    private suspend fun parseItemResult(item: ITunesItem): AppleMusicArtworkResult {
        val pageUrls = listOfNotNull(item.collectionViewUrl, item.trackViewUrl)
        var motions: Map<String, String> = emptyMap()

        for (url in pageUrls) {
            try {
                val html = fetchWebPageHtml(url)
                if (html.isNotBlank()) {
                    motions = extractPageVideos(html)
                    if (motions.isNotEmpty()) break
                }
            } catch (e: Exception) {
                Logger.d(TAG, "Failed to scrape motion video from $url: ${e.message}")
            }
        }

        val squareUrl = motions["motionDetailSquare"] ?: motions["motionSquare"]
        val tallUrl = motions["motionDetailTall"] ?: motions["motionTall"]
        val directMp4 = deriveDirectMp4(tallUrl ?: squareUrl)
        val bestMotion = tallUrl ?: squareUrl ?: directMp4
        val hasMotion = !bestMotion.isNullOrBlank()

        val staticUrl = buildStaticArtworkUrl(item.artworkUrl100, size = 1200)

        return AppleMusicArtworkResult(
            found = true,
            hasMotion = hasMotion,
            trackId = item.trackId,
            albumId = item.collectionId,
            trackName = item.trackName,
            artistName = item.artistName,
            albumName = item.collectionName,
            staticArtworkUrl = staticUrl,
            squareMotionUrl = squareUrl,
            tallMotionUrl = tallUrl,
            directMp4Url = directMp4,
            bestMotionUrl = bestMotion,
            appleMusicUrl = item.trackViewUrl ?: item.collectionViewUrl,
        )
    }

    private suspend fun fetchWebPageHtml(url: String): String =
        runCatching {
            httpClient.get(url) {
                headers {
                    header(
                        HttpHeaders.UserAgent,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    )
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                }
            }.bodyAsText()
        }.getOrDefault("")

    private fun extractPageVideos(html: String): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val keys = listOf("motionDetailSquare", "motionSquare", "motionDetailTall", "motionTall")
        for (key in keys) {
            val idx = html.indexOf("\"$key\"")
            if (idx != -1) {
                val chunk = html.substring(idx, minOf(html.length, idx + 8000))
                val regex = Regex(""""video"\s*:\s*"(https:[^"\\]+?\.m3u8[^"\\]*)"""")
                val match = regex.find(chunk)
                if (match != null) {
                    found[key] = match.groupValues[1]
                }
            }
        }
        return found
    }

    private fun deriveDirectMp4(hlsUrl: String?): String? {
        if (hlsUrl.isNullOrBlank()) return null
        return if (hlsUrl.contains(".m3u8")) {
            hlsUrl.substringBeforeLast(".m3u8") + "-.mp4"
        } else {
            null
        }
    }

    private fun buildStaticArtworkUrl(
        thumbUrl: String?,
        size: Int = 1200,
        format: String = "jpg",
    ): String? {
        if (thumbUrl.isNullOrBlank()) return null
        val match = Regex("""/(?:[0-9]+x[0-9]+bb|source/[0-9]+x[0-9]+bb)\.(?:jpg|png|webp|jpeg)""").find(thumbUrl)
        return if (match != null) {
            val base = thumbUrl.substring(0, match.range.first)
            "$base/${size}x${size}bb.$format"
        } else {
            thumbUrl
        }
    }

    private fun buildSearchQueries(
        titleOrAlbum: String,
        artist: String,
    ): List<String> {
        val cleanTitle = cleanSpecialPunctuation(titleOrAlbum)
        val cleanArtist = cleanSpecialPunctuation(artist)
        return listOfNotNull(
            "$cleanArtist $cleanTitle".trim(),
            "$cleanTitle $cleanArtist".trim(),
            cleanTitle.trim(),
            titleOrAlbum.trim(),
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun cleanSpecialPunctuation(str: String): String =
        str.replace(Regex("""[\(\[\{][^\)\]\}]*[\)\]\}]"""), "")
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun isExactOrNormalizedMatch(
        target: String?,
        candidate: String?,
    ): Boolean {
        if (target.isNullOrBlank() || candidate.isNullOrBlank()) return false
        val t = target.trim()
        val c = candidate.trim()
        if (t.equals(c, ignoreCase = true)) return true

        val normT = cleanSpecialPunctuation(t).lowercase()
        val normC = cleanSpecialPunctuation(c).lowercase()
        if (normT.isNotEmpty() && normT == normC) return true

        // Check if one contains the other when non-empty
        if (normT.isNotEmpty() && normC.isNotEmpty()) {
            if (normT.startsWith(normC) || normC.startsWith(normT)) return true
        }

        return false
    }
}
