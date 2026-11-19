package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.cipher.FaradayCipherEngine
import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogLevel
import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogger
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.exceptions.ParsingException
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeJavaScriptDecoder
import io.ktor.client.HttpClient
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

private const val TAG = "FaradayJsDecoder"
private const val JS_THREAD_STACK_BYTES = 32L * 1024L * 1024L

/**
 * Plugs [FaradayCipherEngine] into PipePipe as its local decoder, so signature and `n` challenges
 * are solved on the device instead of at `api.pipepipe.dev`.
 *
 * PipePipe keeps its own server call as the fallback: [YoutubeApiDecoder.decodeBatch] tries this
 * decoder first and drops through to the API when it throws. **Throwing is therefore the way to
 * hand a request back** — returning a half-filled result would be accepted as final and the missing
 * URLs would simply fail later, skipping the fallback entirely.
 */
internal class FaradayJsDecoder : YoutubeJavaScriptDecoder {
    /**
     * How the last decode was answered, for the log line in [Extractor].
     *
     * Process-wide rather than per-track: PipePipe hands the decoder a player id, never a video id,
     * so two tracks resolving at once cannot be told apart here. Good enough to see which path is
     * carrying the load, not for attributing a single stream.
     */
    @Volatile
    var lastOutcome: String = "unused"
        private set

    /** [lastOutcome] reduced to the three words a user can act on. */
    val lastOutcomeLabel: String
        get() =
            when {
                lastOutcome.startsWith("local") -> "local"
                lastOutcome.startsWith("fallback") -> "pipepipe.dev"
                else -> "cached"
            }

    /**
     * One thread, 32 MB of stack.
     *
     * YouTube's n-transform recurses far past what a default ~1 MB JVM thread holds, and QuickJS
     * runs it on the native stack. When it overruns, QuickJS does not reliably raise a JS error —
     * the process takes SIGSEGV with no hs_err file, because the crash is below the JVM.
     */
    private val jsThread =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(null, runnable, "QuickJs", JS_THREAD_STACK_BYTES) }
            .asCoroutineDispatcher()

    private val engine by lazy { FaradayCipherEngine(HttpClient(getEngine()), jsThread, bridgedLogger) }

    override fun getPlayerData(videoId: String): YoutubeJavaScriptDecoder.PlayerData {
        val info =
            runBlocking { engine.playerInfo() }
                ?: run {
                    lastOutcome = "fallback:no-player-metadata"
                    throw ParsingException("Faraday: no player metadata")
                }
        return YoutubeJavaScriptDecoder.PlayerData(info.playerId, info.signatureTimestamp)
    }

    override fun decodeBatch(
        playerId: String,
        signatures: List<String>?,
        throttlingParameters: List<String>?,
    ): YoutubeApiDecoder.BatchDecodeResult {
        val wantedSignatures = signatures.orEmpty().distinct()
        val wantedNParameters = throttlingParameters.orEmpty().distinct()
        val result = runBlocking { engine.decode(playerId, wantedSignatures, wantedNParameters) }

        // All or nothing: one unsolved challenge means the player table is stale or the script
        // changed shape, and both are reasons to let the whole batch go to the API rather than
        // ship a URL that will come back 403.
        val missing =
            wantedSignatures.count { it !in result.signatures } +
                wantedNParameters.count { it !in result.nParameters }
        val wanted = wantedSignatures.size + wantedNParameters.size
        if (missing > 0) {
            lastOutcome = "fallback:$missing-of-$wanted-unsolved"
            throw ParsingException("Faraday: $missing of $wanted unsolved")
        }
        lastOutcome = "local:sig=${wantedSignatures.size},n=${wantedNParameters.size}"
        Logger.d(TAG, "solved $wanted locally (sig=${wantedSignatures.size} n=${wantedNParameters.size})")
        return YoutubeApiDecoder.BatchDecodeResult(result.signatures, result.nParameters)
    }

    /**
     * Solve ONLY the given raw `signatureCipher`s with this decoder's existing engine and
     * assemble their stream URLs — the fast path in `YouTube.player`, which deciphers the few
     * formats a resolve actually picks instead of every format a full extraction carries.
     *
     * Deliberately partial, unlike [decodeBatch]: a cipher whose signature (or whose `n`) comes
     * back unsolved is simply absent from the result, because the caller walks fallback chains
     * and reads absence as "this candidate failed". Throwing here would throw away the
     * candidates that DID solve along with the ones that did not. An empty result — no player
     * metadata, nothing parseable, nothing solved — is the caller's signal to extract in full.
     */
    suspend fun decipherUrls(ciphers: List<String>): Map<String, String> {
        val wanted = ciphers.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val parsed =
            wanted.mapNotNull { cipher ->
                parseSignatureCipher(cipher)?.let { cipher to it }
            }
        if (parsed.isEmpty()) return emptyMap()
        val info = engine.playerInfo() ?: return emptyMap()
        val result =
            engine.decode(
                info.playerId,
                parsed.map { it.second.signature },
                parsed.mapNotNull { it.second.nParameter },
            )
        return parsed.mapNotNull { (cipher, parts) ->
            val signature = result.signatures[parts.signature] ?: return@mapNotNull null
            val nParameter =
                parts.nParameter?.let { challenge ->
                    result.nParameters[challenge] ?: return@mapNotNull null
                }
            runCatching { parts.assembleUrl(signature, nParameter) }
                .getOrNull()
                ?.let { url -> cipher to url }
        }.toMap()
    }

    /** Discard the cached player table and solver after the CDN rejected a URL we deciphered. */
    fun invalidate() = runBlocking { engine.invalidate() }

    /** Pay the engine's one-time setup ahead of the first decode; see [FaradayCipherEngine.warmUp]. */
    fun warmUp() = runBlocking { engine.warmUp() }

    private val bridgedLogger =
        InnerTubeLogger { event ->
            val message = event.message + event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Logger.d(TAG, message)
                InnerTubeLogLevel.INFO -> Logger.i(TAG, message)
                InnerTubeLogLevel.WARN -> Logger.w(TAG, message)
                InnerTubeLogLevel.ERROR -> Logger.e(TAG, message)
            }
        }
}
