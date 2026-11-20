package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.downloader.CancellableCall
import dev.maxrave.pipepipe.extractor.downloader.Downloader
import dev.maxrave.pipepipe.extractor.downloader.Request
import dev.maxrave.pipepipe.extractor.downloader.Response
import dev.maxrave.pipepipe.extractor.exceptions.ParsingException
import dev.maxrave.pipepipe.extractor.exceptions.ReCaptchaException
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tracks active OkHttp calls per extraction token so cancelling a skipped/superseded track's
 * coroutine aborts its blocking NewPipe / BravePipe / HEAD calls immediately instead of holding
 * sockets and pool slots until timeouts expire.
 */
internal object ExtractorCallRegistry {
    private class Entry(
        val isCancelled: () -> Boolean,
    ) {
        @Volatile
        var cancelled: Boolean = false
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    }

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val currentToken = ThreadLocal<Long?>()

    fun <T> runWithToken(
        token: Long,
        isCancelled: () -> Boolean,
        block: () -> T,
    ): T {
        if (token == 0L) return block()
        val previous = currentToken.get()
        entries[token] = Entry(isCancelled)
        currentToken.set(token)
        return try {
            block()
        } finally {
            if (previous != null) currentToken.set(previous) else currentToken.remove()
            entries.remove(token)
        }
    }

    fun cancel(token: Long) {
        if (token == 0L) return
        val entry = entries[token] ?: return
        entry.cancelled = true
        entry.calls.forEach { call ->
            try {
                call.cancel()
            } catch (_: Throwable) {
            }
        }
    }

    fun isCurrentCancelled(): Boolean {
        val token = currentToken.get() ?: return false
        val entry = entries[token] ?: return false
        return entry.cancelled || entry.isCancelled()
    }

    fun activeToken(): Long? = currentToken.get()

    fun registerCall(
        token: Long?,
        call: Call,
    ): Boolean {
        if (token == null) return true
        val entry = entries[token] ?: return true
        if (entry.cancelled || entry.isCancelled()) {
            entry.cancelled = true
            call.cancel()
            return false
        }
        entry.calls.add(call)
        if (entry.cancelled || entry.isCancelled()) {
            entry.cancelled = true
            entry.calls.remove(call)
            call.cancel()
            return false
        }
        return true
    }

    fun unregisterCall(
        token: Long?,
        call: Call,
    ) {
        if (token == null) return
        entries[token]?.calls?.remove(call)
    }
}

/**
 * One connection pool underneath both extractor downloaders (PipePipe here, BravePipe in
 * BraveNewPipeUtils). They only ever talk to the same hosts, so separate clients meant
 * separate pools — and a fresh DNS + TLS handshake for a stage whose predecessor had just
 * warmed the same host up in another pool. Clients derived with [OkHttpClient.newBuilder]
 * share the base's connection pool and dispatcher; only the proxy differs.
 */
private val extractorBaseClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            },
        ).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
}

internal fun extractorHttpClient(proxy: Proxy?): OkHttpClient = extractorBaseClient.newBuilder().proxy(proxy).build()

class NewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client = extractorHttpClient(proxy)

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val token = ExtractorCallRegistry.activeToken()
        if (ExtractorCallRegistry.isCurrentCancelled()) {
            throw IOException("Extraction cancelled")
        }
        val call = client.newCall(buildOkHttpRequest(request))
        if (!ExtractorCallRegistry.registerCall(token, call)) {
            throw IOException("Extraction cancelled")
        }
        val response =
            try {
                call.execute()
            } finally {
                ExtractorCallRegistry.unregisterCall(token, call)
            }

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        return response.toNewPipeResponse()
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun executeAsync(
        request: Request,
        callback: AsyncCallback?,
    ): CancellableCall {
        val token = ExtractorCallRegistry.activeToken()
        val call = client.newCall(buildOkHttpRequest(request))
        val cancellable = CancellableCall(call)
        if (!ExtractorCallRegistry.registerCall(token, call)) {
            cancellable.setFinished()
            callback?.onError(IOException("Extraction cancelled"))
            return cancellable
        }
        call.enqueue(
            object : okhttp3.Callback {
                override fun onFailure(
                    call: okhttp3.Call,
                    e: IOException,
                ) {
                    ExtractorCallRegistry.unregisterCall(token, call)
                    cancellable.setFinished()
                    callback?.onError(e)
                }

                override fun onResponse(
                    call: okhttp3.Call,
                    response: okhttp3.Response,
                ) {
                    try {
                        if (response.code == 429) {
                            response.close()
                            callback?.onError(
                                ReCaptchaException("reCaptcha Challenge requested", request.url()),
                            )
                            return
                        }
                        callback?.onSuccess(response.toNewPipeResponse())
                    } catch (e: Exception) {
                        callback?.onError(e)
                    } finally {
                        ExtractorCallRegistry.unregisterCall(token, call)
                        cancellable.setFinished()
                    }
                }
            },
        )
        return cancellable
    }

    private fun okhttp3.Response.toNewPipeResponse(): Response {
        val rawBytes = body?.bytes() ?: ByteArray(0)
        return Response(
            code,
            message,
            headers.toMultimap(),
            rawBytes.toString(Charsets.UTF_8),
            rawBytes,
            request.url.toString(),
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

class NewPipeUtils(
    downloader: Downloader,
) {
    init {
        NewPipe.init(downloader)
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> =
        runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): String? =
        try {
            Logger.d("NewPipeUtils", "Getting stream url: ${format.url ?: format.signatureCipher}")
            val url =
                format.url ?: format.signatureCipher?.let { signatureCipher ->
                    val params = parseQueryString(signatureCipher)
                    val obfuscatedSignature =
                        params["s"]
                            ?: throw ParsingException("Could not parse cipher signature")
                    val signatureParam =
                        params["sp"]
                            ?: throw ParsingException("Could not parse cipher signature parameter")
                    val url =
                        params["url"]?.let { URLBuilder(it) }
                            ?: throw ParsingException("Could not parse cipher url")
                    url.parameters[signatureParam] =
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                            videoId,
                            obfuscatedSignature,
                        )
                    url.toString()
                } ?: throw ParsingException("Could not find format url")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
}