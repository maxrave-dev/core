package org.simpmusic.autoeq

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

private const val TAG = "AutoEq"

/**
 * Reads AutoEq's published results straight from the repository's raw file host.
 *
 * The project also runs an API at `autoeq.app`, but the index it serves gives a source, a rig and
 * a form to reassemble a path from, while `INDEX.md` simply states the path. It is one CDN, no
 * rate limit, and the profiles behind it are pre-computed files of a few hundred bytes.
 */
class AutoEq {
    private val httpClient =
        HttpClient(getEngine()) {
            expectSuccess = false
            followRedirects = true
            // The index is 851 kB of Markdown that gzips to about 110 kB.
            install(ContentEncoding) {
                gzip(0.9F)
            }
        }

    /**
     * Fetch the index, but only if it has changed since [etag].
     *
     * The host answers a matching `If-None-Match` with 304 and no body, which is what keeps a
     * routine freshness check down to a couple of hundred bytes. Its ETag is the content hash of
     * the file, so it moves when the file's contents do and not merely when it is committed.
     */
    suspend fun fetchIndex(etag: String?): AutoEqIndexResult =
        runCatching {
            val response =
                httpClient.get("$RESULTS_BASE/INDEX.md") {
                    if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
                }
            when {
                response.status == HttpStatusCode.NotModified -> AutoEqIndexResult.NotModified
                response.status.isSuccess() -> {
                    val entries = parseAutoEqIndex(response.bodyAsText())
                    if (entries.isEmpty()) {
                        // A 200 that parses to nothing means the file's shape moved, not that
                        // AutoEq shipped an empty index. Reporting it as a failure keeps whatever
                        // is already cached rather than replacing it with nothing.
                        AutoEqIndexResult.Failed("index parsed to zero entries")
                    } else {
                        AutoEqIndexResult.Updated(entries, response.headers[HttpHeaders.ETag])
                    }
                }
                else -> AutoEqIndexResult.Failed("HTTP ${response.status.value}")
            }
        }.getOrElse {
            Logger.e(TAG, "index fetch failed: ${it.message}")
            AutoEqIndexResult.Failed(it.message ?: "unknown error")
        }

    /**
     * Fetch one profile's fixed-band curve.
     *
     * [encodedPath] is percent-encoded already and is passed through untouched — the file sits at
     * `<folder>/<folder name> FixedBandEQ.txt`, and the folder's own last segment is reused rather
     * than re-derived from the display name so nothing has to be encoded here a second time.
     */
    suspend fun fetchFixedBandCurve(encodedPath: String): AutoEqCurveData? =
        runCatching {
            val folder = encodedPath.substringAfterLast('/')
            val response = httpClient.get("$RESULTS_BASE/$encodedPath/$folder%20FixedBandEQ.txt")
            if (!response.status.isSuccess()) {
                Logger.e(TAG, "curve fetch for $encodedPath returned ${response.status.value}")
                return null
            }
            parseAutoEqFixedBandEq(response.bodyAsText())
        }.getOrElse {
            Logger.e(TAG, "curve fetch for $encodedPath failed: ${it.message}")
            null
        }

    companion object {
        private const val RESULTS_BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    }
}
