package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeParsingHelper
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal fun List<Pair<Int, String>>.hasPlayableAudio(): Boolean =
    any { it.first in ITAG.AUDIO && it.second.isNotBlank() }

private val streamHealthCheckClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .build()
}

/**
 * Probe ordinary audio first. A rejected rendition does not rule out the other audio URLs.
 * After recovering with another rendition, return that audio alone so format selection cannot
 * choose an unverified fallback. Inconclusive network checks still allow actual playback.
 */
internal fun List<Pair<Int, String>>.playableStreams(
    videoId: String,
    tier: String,
): List<Pair<Int, String>> {
    val audioPriority = listOf(ITAG.AUDIO_OPUS_MEDIUM, ITAG.AUDIO_OPUS_LOW, ITAG.AUDIO_OPUS_HIGH, ITAG.AUDIO_AAC_HIGH)
    val candidates =
        filter { it.first in ITAG.AUDIO && it.second.isNotBlank() }
            .sortedBy { audioPriority.indexOf(it.first) }
            .distinctBy { it.second }
    var hadRejection = false
    for (candidate in candidates) {
        val accepted = try {
            val request =
                okhttp3.Request
                    .Builder()
                    .get()
                    .header("Range", "bytes=0-0")
                    .url(candidate.second)
                    .build()
            val clientName = request.url.queryParameter("c") ?: "unknown"
            streamHealthCheckClient.newCall(request).execute().use { response ->
                val rejected = response.code in 400..499 && response.code !in setOf(405, 408, 429)
                if (response.code !in 200..299) {
                    Logger.w(
                        "Extractor",
                        "PipePipe[$tier] audio probe for $videoId itag=${candidate.first} client=$clientName: HTTP ${response.code}, rejected=$rejected",
                    )
                }
                if (response.code in 200..299) {
                    response.body?.source()?.readByte()
                    Logger.i("Extractor", "PipePipe[$tier] audio probe passed for $videoId itag=${candidate.first} client=$clientName")
                }
                !rejected
            }
        } catch (e: IOException) {
            Logger.w(
                "Extractor",
                "PipePipe[$tier] audio probe inconclusive for $videoId itag=${candidate.first}: ${e.javaClass.simpleName}; trying playback",
            )
            true
        }
        if (accepted) {
            if (!hadRejection) return this
            Logger.i("Extractor", "PipePipe[$tier] recovered audio for $videoId with itag=${candidate.first}")
            val audioItags = ITAG.AUDIO + setOf(140, 249)
            return filter { it.first !in audioItags || it.second == candidate.second }
        }
        hadRejection = true
    }
    return emptyList()
}

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    @Volatile
    private var loginCookie: String? = null

    fun logIn(cookie: String?) {
        loginCookie = cookie?.takeIf { it.isNotBlank() }
    }

    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                val cookie = loginCookie
                val trustedHost = request.url.isHttps && request.url.host in setOf("www.youtube.com", "music.youtube.com")
                if (trustedHost && cookie != null) {
                    // Brave's default downloader only sends consent cookies. Keep the fallback
                    // signed in too, with SAPISIDHASH bound to the actual YouTube origin.
                    builder.header("Cookie", cookie)
                    if (request.url.encodedPath.startsWith("/youtubei/v1/")) {
                        val origin = "https://${request.url.host}"
                        runCatching { YoutubeParsingHelper.getAuthorizationHeader(cookie, origin) }
                            .onSuccess {
                                builder.header("Authorization", it)
                                builder.header("Origin", origin)
                                builder.header("X-Origin", origin)
                            }.onFailure {
                                Logger.w("Extractor", "BravePipe login header unavailable: ${it.javaClass.simpleName}")
                            }
                    }
                } else if (!trustedHost) {
                    // Also enforce this on redirects; credentials must never reach media,
                    // decoder, dislike or other third-party hosts.
                    builder.removeHeader("Cookie")
                    builder.removeHeader("Authorization")
                }
                chain.proceed(builder.build())
            }
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val response = client.newCall(buildOkHttpRequest(request)).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val body = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val builder =
            okhttp3.Request
                .Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        request.headers().forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                builder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    builder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                builder.header(headerName, headerValueList[0])
            }
        }
        return builder.build()
    }
}
