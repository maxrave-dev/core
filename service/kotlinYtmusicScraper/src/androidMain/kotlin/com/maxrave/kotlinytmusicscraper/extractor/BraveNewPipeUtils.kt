package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.logger.Logger
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy

internal fun List<Pair<Int, String>>.hasPlayableAudio(): Boolean =
    any { it.first in ITAG.AUDIO && it.second.isNotBlank() }

private val streamHealthCheckClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .build()
}

/**
 * Check a standard audio URL first. A video or Premium-only format must not decide whether
 * the whole song can play. Only a definitive rejection warrants trying another decoder;
 * a timeout or server error leaves validation to the player's actual media request.
 */
internal fun List<Pair<Int, String>>.checkAudioStream(
    videoId: String,
    tier: String,
): Boolean {
    val audioPriority = listOf(ITAG.AUDIO_OPUS_MEDIUM, ITAG.AUDIO_OPUS_LOW, ITAG.AUDIO_OPUS_HIGH, ITAG.AUDIO_AAC_HIGH)
    val candidate =
        filter { it.first in ITAG.AUDIO && it.second.isNotBlank() }
            .minByOrNull { audioPriority.indexOf(it.first) } ?: return false
    return try {
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
                // Read just one byte, then close the response even if the CDN ignores Range.
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
}

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()

            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()

        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}