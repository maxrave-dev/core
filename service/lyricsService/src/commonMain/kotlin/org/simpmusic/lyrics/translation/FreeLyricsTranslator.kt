package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.lyrics.LyricsTranslationResult
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "FreeLyricsTranslator"

private const val EDGE_AUTH_URL = "https://edge.microsoft.com/translate/auth"
private const val EDGE_TRANSLATE_URL = "https://api-edge.cognitive.microsofttranslator.com/translate"
private const val GOOGLE_GTX_URL = "https://translate.googleapis.com/translate_a/single"
private const val GOOGLE_CLIENTS5_URL = "https://clients5.google.com/translate_a/t"
private const val EDGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

private const val MAX_STANZA_LINES = 5
private const val MAX_STANZA_CHARS = 420
private const val STANZA_BREAK_GAP_MS = 3_500L
private const val EDGE_MAX_BATCH_ITEMS = 80
private const val EDGE_MAX_BATCH_CHARS = 35_000
private const val GOOGLE_MAX_BATCH_CHARS = 3_200
private const val TOKEN_EXPIRY_SAFETY_SECONDS = 60L
private const val DEFAULT_TOKEN_TTL_SECONDS = 480L
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 10_000L

private val RICH_SYNC_TAG_REGEX = Regex("""<\d{1,2}:\d{2}(?:\.\d{1,3})?>""")
private val SPAN_TAG_REGEX = Regex("""<span\s+id=["']?(\d+)["']?[^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ANY_HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")

internal data class TranslatableLine(
    val originalIndex: Int,
    val cleanedText: String,
    val startTimeMs: Long?,
)

internal data class LyricStanza(
    val lines: List<TranslatableLine>,
) {
    val dedupeKey: String = lines.joinToString("\u0001") { it.cleanedText }
}

internal data class EdgeTranslationItem(
    val text: String,
    val detectedLanguage: String?,
)

internal data class GoogleBatchTranslation(
    val lines: List<String>,
    val detectedLanguage: String?,
)

internal interface FreeTranslationTransport {
    suspend fun fetchEdgeToken(): String

    suspend fun translateEdge(
        token: String,
        targetLanguage: String,
        texts: List<String>,
        isHtml: Boolean,
    ): Pair<Int, String>

    suspend fun translateGoogleGtx(
        targetLanguage: String,
        text: String,
    ): Pair<Int, String>

    suspend fun translateGoogleClients5(
        targetLanguage: String,
        texts: List<String>,
    ): Pair<Int, String>
}

@Serializable
private data class EdgeTextPayload(
    @SerialName("Text")
    val text: String,
)

/**
 * Free, zero-API-key neural lyrics translator designed to produce natural, context-aware
 * translations (especially pt-BR) while preserving 100% 1-to-1 line timestamp alignment.
 *
 * Strategy:
 * 1. Primary: Microsoft Edge Neural Translator (`to=pt-BR` + `textType=html`), sending
 *    deduplicated 4-5 line stanzas wrapped in `<span id="k">...</span>` so the neural model
 *    resolves enjambment (sentences split across consecutive lyrics lines) with full stanza
 *    context. Any stanza whose inline tags are dropped falls back to plain-text JSON array
 *    alignment within the same engine.
 * 2. Fallback: Google Translate (`translate_a/single` -> `clients5`) in deduplicated batches.
 *
 * Reuses the caller's existing [HttpClient] (zero extra engines/thread pools).
 */
@OptIn(ExperimentalTime::class)
internal class FreeLyricsTranslator(
    private val nowSecondsProvider: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val tokenMutex = Mutex()
    private var cachedEdgeToken: String? = null
    private var cachedEdgeTokenExpiresAtSeconds: Long = 0L

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun translate(
        httpClient: HttpClient,
        lyrics: Lyrics,
        targetLanguage: String,
    ): LyricsTranslationResult =
        translateWithTransport(
            transport = KtorFreeTranslationTransport(httpClient, json),
            lyrics = lyrics,
            targetLanguage = targetLanguage,
        )

    internal suspend fun translateWithTransport(
        transport: FreeTranslationTransport,
        lyrics: Lyrics,
        targetLanguage: String,
    ): LyricsTranslationResult {
        val originalLines = lyrics.lines.orEmpty()
        if (originalLines.isEmpty()) {
            throw IllegalStateException("No lyrics lines to translate")
        }

        val stanzas = groupIntoStanzas(originalLines)
        if (stanzas.isEmpty()) {
            throw IllegalStateException("No translatable lyrics lines found")
        }

        val normalizedEdgeTarget = normalizeEdgeTargetLanguage(targetLanguage)
        val normalizedGoogleTarget = normalizeGoogleTargetLanguage(targetLanguage)
        val targetBase = normalizeBaseLanguage(targetLanguage)

        // Deduplicate repeated stanzas (choruses) so we send ~35-45% less text.
        val uniqueStanzas = LinkedHashMap<String, LyricStanza>()
        stanzas.forEach { stanza ->
            if (!uniqueStanzas.containsKey(stanza.dedupeKey)) {
                uniqueStanzas[stanza.dedupeKey] = stanza
            }
        }
        val uniqueList = uniqueStanzas.values.toList()

        // 1) Primary: Microsoft Edge Neural Translator with HTML stanza context.
        val edgeResult =
            try {
                translateViaEdge(transport, uniqueList, normalizedEdgeTarget, targetBase)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.w(TAG, "Edge neural translation failed, falling back to Google: ${t.message}")
                null
            }

        if (edgeResult != null) {
            return when (edgeResult) {
                is StanzaTranslationOutcome.AlreadyInTarget ->
                    LyricsTranslationResult.AlreadyInTargetLanguage(edgeResult.detectedLanguage)

                is StanzaTranslationOutcome.Success ->
                    LyricsTranslationResult.Translated(
                        lyrics = buildTranslatedLyrics(lyrics, stanzas, edgeResult.translatedByKey),
                        detectedSourceLanguage = edgeResult.detectedLanguage,
                    )
            }
        }

        // 2) Fallback: Google Translate HTTP (gtx -> clients5).
        val googleResult = translateViaGoogle(transport, uniqueList, normalizedGoogleTarget, targetBase)
        return when (googleResult) {
            is StanzaTranslationOutcome.AlreadyInTarget ->
                LyricsTranslationResult.AlreadyInTargetLanguage(googleResult.detectedLanguage)

            is StanzaTranslationOutcome.Success ->
                LyricsTranslationResult.Translated(
                    lyrics = buildTranslatedLyrics(lyrics, stanzas, googleResult.translatedByKey),
                    detectedSourceLanguage = googleResult.detectedLanguage,
                )
        }
    }

    private sealed interface StanzaTranslationOutcome {
        data class AlreadyInTarget(
            val detectedLanguage: String,
        ) : StanzaTranslationOutcome

        data class Success(
            val translatedByKey: Map<String, List<String>>,
            val detectedLanguage: String?,
        ) : StanzaTranslationOutcome
    }

    private suspend fun getEdgeToken(
        transport: FreeTranslationTransport,
        forceRefresh: Boolean = false,
    ): String =
        tokenMutex.withLock {
            val now = nowSecondsProvider()
            val cached = cachedEdgeToken
            if (!forceRefresh && cached != null && now < cachedEdgeTokenExpiresAtSeconds - TOKEN_EXPIRY_SAFETY_SECONDS) {
                return@withLock cached
            }
            val fresh = transport.fetchEdgeToken().trim()
            if (fresh.isEmpty() || !fresh.contains('.')) {
                throw IllegalStateException("Invalid Edge translator token")
            }
            val exp = parseJwtExpirySeconds(fresh) ?: (now + DEFAULT_TOKEN_TTL_SECONDS)
            cachedEdgeToken = fresh
            cachedEdgeTokenExpiresAtSeconds = exp
            fresh
        }

    private suspend fun executeEdgeRequest(
        transport: FreeTranslationTransport,
        targetLanguage: String,
        texts: List<String>,
        isHtml: Boolean,
    ): List<EdgeTranslationItem> {
        var token = getEdgeToken(transport, forceRefresh = false)
        var (status, body) = transport.translateEdge(token, targetLanguage, texts, isHtml)
        if (status == 401 || status == 403) {
            token = getEdgeToken(transport, forceRefresh = true)
            val retry = transport.translateEdge(token, targetLanguage, texts, isHtml)
            status = retry.first
            body = retry.second
        }
        if (status !in 200..299) {
            throw IllegalStateException("Edge translator HTTP $status")
        }
        return parseEdgeTranslateResponse(json, body)
    }

    private suspend fun translateViaEdge(
        transport: FreeTranslationTransport,
        uniqueStanzas: List<LyricStanza>,
        targetLanguage: String,
        targetBase: String,
    ): StanzaTranslationOutcome {
        val htmlInputs = uniqueStanzas.map { buildStanzaHtml(it.lines.map { l -> l.cleanedText }) }
        val items = ArrayList<EdgeTranslationItem>(htmlInputs.size)

        // Chunk safely under Microsoft Translator's per-request limits (usually 1 single POST!).
        var cursor = 0
        while (cursor < htmlInputs.size) {
            val batch = ArrayList<String>()
            var batchChars = 0
            while (cursor < htmlInputs.size &&
                batch.size < EDGE_MAX_BATCH_ITEMS &&
                (batch.isEmpty() || batchChars + htmlInputs[cursor].length <= EDGE_MAX_BATCH_CHARS)
            ) {
                val entry = htmlInputs[cursor]
                batch.add(entry)
                batchChars += entry.length
                cursor++
            }
            val responseItems = executeEdgeRequest(transport, targetLanguage, batch, isHtml = true)
            if (responseItems.size != batch.size) {
                throw IllegalStateException("Edge HTML response count mismatch: ${responseItems.size} != ${batch.size}")
            }
            items.addAll(responseItems)
        }

        // Weighted dominant source language check so songs already in pt-BR aren't duplicated.
        val dominantLang = detectDominantLanguage(uniqueStanzas, items.map { it.detectedLanguage })
        if (dominantLang != null && normalizeBaseLanguage(dominantLang) == targetBase) {
            return StanzaTranslationOutcome.AlreadyInTarget(dominantLang)
        }

        val translatedByKey = HashMap<String, List<String>>(uniqueStanzas.size)
        val failedStanzas = ArrayList<LyricStanza>()

        uniqueStanzas.forEachIndexed { index, stanza ->
            val rawHtml = items[index].text
            val extracted = extractStanzaHtmlLines(rawHtml, stanza.lines.size)
            if (extracted != null) {
                translatedByKey[stanza.dedupeKey] = extracted
            } else {
                failedStanzas.add(stanza)
            }
        }

        // Self-healing per-stanza fallback: if any stanza dropped a <span> id, translate just
        // those lines via Microsoft plain-text JSON array so alignment stays 100% exact.
        if (failedStanzas.isNotEmpty()) {
            val flatLines = failedStanzas.flatMap { it.lines.map { l -> l.cleanedText } }
            val plainTranslations = ArrayList<String>(flatLines.size)
            var flatCursor = 0
            while (flatCursor < flatLines.size) {
                val batch = ArrayList<String>()
                var batchChars = 0
                while (flatCursor < flatLines.size &&
                    batch.size < EDGE_MAX_BATCH_ITEMS &&
                    (batch.isEmpty() || batchChars + flatLines[flatCursor].length <= EDGE_MAX_BATCH_CHARS)
                ) {
                    val lineText = flatLines[flatCursor]
                    batch.add(lineText)
                    batchChars += lineText.length
                    flatCursor++
                }
                val plainItems = executeEdgeRequest(transport, targetLanguage, batch, isHtml = false)
                if (plainItems.size != batch.size) {
                    throw IllegalStateException("Edge plain response count mismatch")
                }
                plainItems.forEach { plainTranslations.add(it.text.trim()) }
            }
            var offset = 0
            failedStanzas.forEach { stanza ->
                val count = stanza.lines.size
                translatedByKey[stanza.dedupeKey] = plainTranslations.subList(offset, offset + count).toList()
                offset += count
            }
        }

        return StanzaTranslationOutcome.Success(
            translatedByKey = translatedByKey,
            detectedLanguage = dominantLang,
        )
    }

    private suspend fun translateViaGoogle(
        transport: FreeTranslationTransport,
        uniqueStanzas: List<LyricStanza>,
        targetLanguage: String,
        targetBase: String,
    ): StanzaTranslationOutcome {
        val translatedByKey = HashMap<String, List<String>>(uniqueStanzas.size)
        val detectedWeights = HashMap<String, Int>()

        // Batch stanzas together separated by newlines, keeping under safe payload size.
        var cursor = 0
        while (cursor < uniqueStanzas.size) {
            val batchStanzas = ArrayList<LyricStanza>()
            val batchLines = ArrayList<String>()
            var chars = 0
            while (cursor < uniqueStanzas.size) {
                val stanza = uniqueStanzas[cursor]
                val stanzaChars = stanza.lines.sumOf { it.cleanedText.length + 1 }
                if (batchStanzas.isNotEmpty() && chars + stanzaChars > GOOGLE_MAX_BATCH_CHARS) {
                    break
                }
                batchStanzas.add(stanza)
                stanza.lines.forEach { batchLines.add(it.cleanedText) }
                chars += stanzaChars
                cursor++
            }

            val result = translateGoogleBatch(transport, targetLanguage, batchLines)
            val detected = result.detectedLanguage
            if (!detected.isNullOrBlank()) {
                val base = normalizeBaseLanguage(detected)
                detectedWeights[base] = (detectedWeights[base] ?: 0) + chars
            }

            var lineOffset = 0
            batchStanzas.forEach { stanza ->
                val count = stanza.lines.size
                translatedByKey[stanza.dedupeKey] = result.lines.subList(lineOffset, lineOffset + count).toList()
                lineOffset += count
            }
        }

        val totalWeight = detectedWeights.values.sum()
        val topLang = detectedWeights.maxByOrNull { it.value }
        val dominantLang = topLang?.key
        if (topLang != null && totalWeight > 0 && topLang.key == targetBase && topLang.value.toFloat() / totalWeight >= 0.8f) {
            return StanzaTranslationOutcome.AlreadyInTarget(topLang.key)
        }

        return StanzaTranslationOutcome.Success(
            translatedByKey = translatedByKey,
            detectedLanguage = dominantLang,
        )
    }

    private suspend fun translateGoogleBatch(
        transport: FreeTranslationTransport,
        targetLanguage: String,
        lines: List<String>,
    ): GoogleBatchTranslation {
        val joined = lines.joinToString("\n")
        val gtxAttempt =
            try {
                val (status, body) = transport.translateGoogleGtx(targetLanguage, joined)
                if (status in 200..299) {
                    val parsed = parseGoogleSingleResponse(json, body)
                    if (parsed != null && parsed.lines.size == lines.size) {
                        parsed
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        if (gtxAttempt != null) return gtxAttempt

        // Fallback to clients5 (returns 1 element per input string array item, so line count is guaranteed!).
        val (status, body) = transport.translateGoogleClients5(targetLanguage, lines)
        if (status !in 200..299) {
            throw IllegalStateException("Google translator HTTP $status")
        }
        val parsed =
            parseGoogleClients5Response(json, body, lines.size)
                ?: throw IllegalStateException("Failed to parse Google translator response")
        return parsed
    }

    internal fun clearTokenCache() {
        cachedEdgeToken = null
        cachedEdgeTokenExpiresAtSeconds = 0L
    }
}

private class KtorFreeTranslationTransport(
    private val httpClient: HttpClient,
    private val json: Json,
) : FreeTranslationTransport {
    override suspend fun fetchEdgeToken(): String {
        val response =
            httpClient.get(EDGE_AUTH_URL) {
                header(HttpHeaders.UserAgent, EDGE_USER_AGENT)
                header(HttpHeaders.CacheControl, "no-cache")
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Edge auth HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    override suspend fun translateEdge(
        token: String,
        targetLanguage: String,
        texts: List<String>,
        isHtml: Boolean,
    ): Pair<Int, String> {
        val bodyJson = json.encodeToString(texts.map { EdgeTextPayload(it) })
        val response =
            httpClient.post(EDGE_TRANSLATE_URL) {
                parameter("api-version", "3.0")
                parameter("to", targetLanguage)
                if (isHtml) {
                    parameter("textType", "html")
                }
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.UserAgent, EDGE_USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        return response.status.value to response.bodyAsText()
    }

    override suspend fun translateGoogleGtx(
        targetLanguage: String,
        text: String,
    ): Pair<Int, String> {
        val response =
            httpClient.post(GOOGLE_GTX_URL) {
                parameter("client", "gtx")
                parameter("sl", "auto")
                parameter("tl", targetLanguage)
                parameter("dt", "t")
                header(HttpHeaders.UserAgent, EDGE_USER_AGENT)
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("q", text)
                        },
                    ),
                )
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        return response.status.value to response.bodyAsText()
    }

    override suspend fun translateGoogleClients5(
        targetLanguage: String,
        texts: List<String>,
    ): Pair<Int, String> {
        val response =
            httpClient.post(GOOGLE_CLIENTS5_URL) {
                parameter("client", "dict-chrome-ex")
                parameter("sl", "auto")
                parameter("tl", targetLanguage)
                header(HttpHeaders.UserAgent, EDGE_USER_AGENT)
                setBody(
                    FormDataContent(
                        Parameters.build {
                            texts.forEach { append("q", it) }
                        },
                    ),
                )
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        return response.status.value to response.bodyAsText()
    }
}

internal fun normalizeEdgeTargetLanguage(language: String): String {
    val cleaned = language.trim().replace('_', '-')
    if (cleaned.isEmpty()) return "pt-BR"
    return when (cleaned.lowercase()) {
        "pt", "pt-br" -> "pt-BR"
        "pt-pt" -> "pt-PT"
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hk", "zh-hant" -> "zh-Hant"
        "iw", "he" -> "he"
        "in", "id" -> "id"
        else -> cleaned
    }
}

internal fun normalizeGoogleTargetLanguage(language: String): String {
    val cleaned = language.trim().replace('_', '-')
    if (cleaned.isEmpty()) return "pt"
    return when (cleaned.lowercase()) {
        "pt", "pt-br" -> "pt"
        "zh", "zh-hans" -> "zh-CN"
        "zh-hant" -> "zh-TW"
        "iw" -> "he"
        "in" -> "id"
        else -> cleaned
    }
}

internal fun normalizeBaseLanguage(language: String): String {
    val primary = language.trim().lowercase().replace('_', '-').substringBefore('-')
    return when (primary) {
        "iw" -> "he"
        "in" -> "id"
        else -> primary
    }
}

internal fun stripRichSyncMarkers(text: String): String =
    RICH_SYNC_TAG_REGEX
        .replace(text, " ")
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()

internal fun isTranslatableLyricLine(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.any { it.isLetter() }
}

internal fun groupIntoStanzas(lines: List<Line>): List<LyricStanza> {
    val stanzas = ArrayList<LyricStanza>()
    val current = ArrayList<TranslatableLine>(MAX_STANZA_LINES)
    var currentChars = 0
    var lastTimeMs: Long? = null

    fun flush() {
        if (current.isNotEmpty()) {
            stanzas.add(LyricStanza(current.toList()))
            current.clear()
            currentChars = 0
        }
    }

    lines.forEachIndexed { index, line ->
        val cleaned = stripRichSyncMarkers(line.words)
        if (!isTranslatableLyricLine(cleaned)) {
            flush()
            lastTimeMs = null
            return@forEachIndexed
        }

        val ts = line.startTimeMs.toLongOrNull()
        val gapExceeded =
            ts != null &&
                lastTimeMs != null &&
                (ts - lastTimeMs!!) > STANZA_BREAK_GAP_MS

        if (current.isNotEmpty() &&
            (gapExceeded || current.size >= MAX_STANZA_LINES || currentChars + cleaned.length > MAX_STANZA_CHARS)
        ) {
            flush()
        }

        current.add(TranslatableLine(originalIndex = index, cleanedText = cleaned, startTimeMs = ts))
        currentChars += cleaned.length
        lastTimeMs = ts
    }

    flush()
    return stanzas
}

internal fun escapeHtmlText(text: String): String =
    buildString(text.length + 8) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

internal fun unescapeHtmlText(text: String): String =
    text
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

internal fun buildStanzaHtml(lines: List<String>): String =
    lines.mapIndexed { index, text ->
        """<span id="$index">${escapeHtmlText(text)}</span>"""
    }.joinToString(" ")

internal fun extractStanzaHtmlLines(
    translatedHtml: String,
    expectedCount: Int,
): List<String>? {
    if (expectedCount <= 0) return emptyList()
    val buckets = Array(expectedCount) { ArrayList<String>(1) }
    SPAN_TAG_REGEX.findAll(translatedHtml).forEach { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (id in 0 until expectedCount) {
            val inner =
                unescapeHtmlText(ANY_HTML_TAG_REGEX.replace(match.groupValues[2], ""))
                    .replace(MULTI_SPACE_REGEX, " ")
                    .trim()
            if (inner.isNotEmpty()) {
                buckets[id].add(inner)
            }
        }
    }
    val result = ArrayList<String>(expectedCount)
    for (i in 0 until expectedCount) {
        if (buckets[i].isEmpty()) return null
        result.add(buckets[i].joinToString(" "))
    }
    return result
}

internal fun detectDominantLanguage(
    stanzas: List<LyricStanza>,
    detectedLanguages: List<String?>,
): String? {
    val weights = HashMap<String, Int>()
    var totalWeight = 0
    stanzas.forEachIndexed { index, stanza ->
        val detected = detectedLanguages.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val base = normalizeBaseLanguage(detected)
        val chars = stanza.lines.sumOf { it.cleanedText.length }.coerceAtLeast(1)
        weights[base] = (weights[base] ?: 0) + chars
        totalWeight += chars
    }
    val top = weights.maxByOrNull { it.value } ?: return null
    return if (totalWeight > 0 && top.value.toFloat() / totalWeight >= 0.75f) top.key else null
}

internal fun buildTranslatedLyrics(
    original: Lyrics,
    stanzas: List<LyricStanza>,
    translatedByKey: Map<String, List<String>>,
): Lyrics {
    val originalLines = original.lines.orEmpty()
    val translatedByIndex = HashMap<Int, String>(originalLines.size)

    stanzas.forEach { stanza ->
        val translatedLines = translatedByKey[stanza.dedupeKey] ?: return@forEach
        stanza.lines.forEachIndexed { localIdx, origLine ->
            val translated = translatedLines.getOrNull(localIdx)?.trim().orEmpty()
            if (translated.isNotEmpty() && !isSameTextNormalized(origLine.cleanedText, translated)) {
                translatedByIndex[origLine.originalIndex] = translated
            }
        }
    }

    // Keep exact 1-to-1 line array & timestamps so LyricsUiPrep can use the O(N) fast-path
    // and LyricsTranslationSyncValidator always sees 0 ms drift.
    val outputLines =
        originalLines.mapIndexed { index, line ->
            Line(
                startTimeMs = line.startTimeMs,
                endTimeMs = line.endTimeMs,
                syllables = null,
                words = translatedByIndex[index].orEmpty(),
            )
        }

    val outputSyncType =
        when (original.syncType) {
            "RICH_SYNCED", "LINE_SYNCED" -> "LINE_SYNCED"
            else -> original.syncType
        }

    return Lyrics(
        error = false,
        lines = outputLines,
        syncType = outputSyncType,
        simpMusicLyrics = null,
    )
}

private fun isSameTextNormalized(
    a: String,
    b: String,
): Boolean {
    fun norm(s: String): String =
        buildString(s.length) {
            for (ch in s.lowercase()) {
                if (ch.isLetterOrDigit()) append(ch)
            }
        }
    val na = norm(a)
    return na.isNotEmpty() && na == norm(b)
}

internal fun parseEdgeTranslateResponse(
    json: Json,
    body: String,
): List<EdgeTranslationItem> {
    val root = json.parseToJsonElement(body).jsonArray
    return root.map { elem ->
        val obj = elem.jsonObject
        val detected =
            obj["detectedLanguage"]
                ?.jsonObject
                ?.get("language")
                ?.jsonPrimitive
                ?.contentOrNull
        val text =
            obj["translations"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw IllegalStateException("Missing translation text in Edge response")
        EdgeTranslationItem(text = text, detectedLanguage = detected)
    }
}

internal fun parseGoogleSingleResponse(
    json: Json,
    body: String,
): GoogleBatchTranslation? {
    val root = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
    val segments = root.getOrNull(0) as? JsonArray ?: return null
    val combined =
        buildString {
            segments.forEach { seg ->
                val part = (seg as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull
                if (part != null) append(part)
            }
        }
    if (combined.isEmpty()) return null
    val detected = (root.getOrNull(2) as? JsonPrimitive)?.contentOrNull
    val lines = combined.split("\n").map { it.trim() }
    return GoogleBatchTranslation(lines = lines, detectedLanguage = detected)
}

internal fun parseGoogleClients5Response(
    json: Json,
    body: String,
    expectedCount: Int,
): GoogleBatchTranslation? {
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
    val arr = root as? JsonArray ?: return null
    if (expectedCount == 1 && arr.size == 2 && arr[0] is JsonPrimitive && arr[1] is JsonPrimitive) {
        return GoogleBatchTranslation(
            lines = listOf(arr[0].jsonPrimitive.content.trim()),
            detectedLanguage = arr[1].jsonPrimitive.contentOrNull,
        )
    }
    if (arr.size != expectedCount) return null
    val lines = ArrayList<String>(expectedCount)
    val detectedCounts = HashMap<String, Int>()
    for (entry: JsonElement in arr) {
        when (entry) {
            is JsonPrimitive -> lines.add(entry.content.trim())
            is JsonArray -> {
                val text = entry.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return null
                lines.add(text.trim())
                val lang = entry.getOrNull(1)?.jsonPrimitive?.contentOrNull
                if (!lang.isNullOrBlank()) {
                    val base = normalizeBaseLanguage(lang)
                    detectedCounts[base] = (detectedCounts[base] ?: 0) + 1
                }
            }
            else -> return null
        }
    }
    val topDetected = detectedCounts.maxByOrNull { it.value }?.key
    return GoogleBatchTranslation(lines = lines, detectedLanguage = topDetected)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun parseJwtExpirySeconds(jwt: String): Long? =
    runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val remainder = payload.length % 4
        val padded = if (remainder == 0) payload else payload + "=".repeat(4 - remainder)
        val decoded = Base64.UrlSafe.decode(padded).decodeToString()
        Json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
