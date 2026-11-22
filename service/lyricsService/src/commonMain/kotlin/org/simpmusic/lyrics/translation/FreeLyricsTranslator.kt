package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.lyrics.LyricsTranslationResult
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormDataContent
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

private const val TAG = "FreeLyricsTranslator"

// Microsoft Edge's zero-auth public translation endpoint (replaces the discontinued /translate/auth JWT flow).
private const val EDGE_TRANSLATE_URL = "https://edge.microsoft.com/translate/translatetext"
private const val GOOGLE_GTX_URL = "https://translate.googleapis.com/translate_a/single"
private const val GOOGLE_CLIENTS5_URL = "https://clients5.google.com/translate_a/t"
private const val EDGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

private const val MAX_STANZA_LINES = 5
private const val MAX_STANZA_CHARS = 420
private const val STANZA_BREAK_GAP_MS = 3_500L
private const val EDGE_MAX_BATCH_ITEMS = 80
private const val EDGE_MAX_BATCH_CHARS = 35_000
private const val GOOGLE_MAX_BATCH_ITEMS = 40
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 10_000L

private val RICH_SYNC_TAG_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")
private val SPAN_TAG_REGEX = Regex("""<span\s+id=["']?(\d+)["']?[^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ANY_HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")

// Colloquial lyric contractions & common English pop idioms that literal NMT often garbles when translating to pt-BR.
private val EN_LYRIC_REPLACEMENTS: List<Pair<Regex, String>> =
    listOf(
        Regex("""\bmeet\s*-?\s*cute\b""", RegexOption.IGNORE_CASE) to "romantic meet-cute",
        Regex("""\bsign from above\b""", RegexOption.IGNORE_CASE) to "sign from heaven",
        Regex("""(?<!\w)(?:'|’)?cause\b""", RegexOption.IGNORE_CASE) to "because",
        Regex("""\bcuz\b""", RegexOption.IGNORE_CASE) to "because",
        Regex("""\bgonna\b""", RegexOption.IGNORE_CASE) to "going to",
        Regex("""\bwanna\b""", RegexOption.IGNORE_CASE) to "want to",
        Regex("""\bgotta\b""", RegexOption.IGNORE_CASE) to "got to",
        Regex("""\btryna\b""", RegexOption.IGNORE_CASE) to "trying to",
        Regex("""\boutta\b""", RegexOption.IGNORE_CASE) to "out of",
        Regex("""\bkinda\b""", RegexOption.IGNORE_CASE) to "kind of",
        Regex("""\bsorta\b""", RegexOption.IGNORE_CASE) to "sort of",
        Regex("""\blemme\b""", RegexOption.IGNORE_CASE) to "let me",
        Regex("""\bgimme\b""", RegexOption.IGNORE_CASE) to "give me",
        Regex("""\by['’]all\b""", RegexOption.IGNORE_CASE) to "you all",
        Regex("""\bimma\b""", RegexOption.IGNORE_CASE) to "I am going to",
        Regex("""\bain['’]t\b""", RegexOption.IGNORE_CASE) to "is not",
        Regex("""\b(\w{2,})in['’](?=\s|$|[,.!?])""", RegexOption.IGNORE_CASE) to "$1ing",
    )

private val ENJAMBMENT_TRAILING_WORDS =
    setOf(
        "a", "an", "the", "and", "or", "but", "so", "for", "of", "to", "in", "on", "at",
        "by", "with", "from", "into", "about", "through", "over", "under", "like", "as",
        "if", "when", "while", "that", "which", "who", "my", "your", "his", "her", "our",
        "their", "its", "this", "these", "those", "some", "any", "every", "no", "not",
        "be", "is", "am", "are", "was", "were", "been", "have", "has", "had",
        "we'd", "i'd", "you'd", "they'd", "he'd", "she'd", "i'm", "you're", "we're", "they're",
        "i'll", "you'll", "we'll", "they'll", "i've", "you've", "we've", "they've",
        "would", "could", "should", "will", "can", "might", "must", "never", "always",
    )

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

/**
 * A logical translation unit inside a stanza: either 1 standalone verse or 2+ verses merged
 * because of genuine enjambment (a sentence cut mid-clause across synced lines).
 */
internal data class LogicalSegment(
    val lineTexts: List<String>,
) {
    val mergedText: String = lineTexts.joinToString(" ")
    val lineCount: Int = lineTexts.size
}

internal data class EdgeTranslationItem(
    val text: String,
    val detectedLanguage: String?,
    val transSentLen: List<Int> = emptyList(),
)

internal data class GoogleBatchTranslation(
    val lines: List<String>,
    val detectedLanguage: String?,
)

internal interface FreeTranslationTransport {
    suspend fun translateEdge(
        targetLanguage: String,
        texts: List<String>,
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

/**
 * Free, zero-API-key neural lyrics translator designed for natural, context-aware translations
 * (especially `pt-BR`) while preserving 100% 1-to-1 line timestamp alignment:
 *
 * 1. **Primary:** Microsoft Edge's public zero-auth neural endpoint
 *    (`edge.microsoft.com/translate/translatetext`), grouping deduplicated stanzas and merging
 *    genuine mid-clause enjambments into complete sentences before splitting back onto the
 *    original timestamps. Colloquial English contractions & common pop idioms are lightly normalized
 *    when targeting Portuguese.
 * 2. **Fallback:** Google Translate (`translate_a/single` -> `clients5.google.com/translate_a/t`
 *    with `<span id="k">...</span>` stanza HTML context).
 *
 * Reuses the caller's existing [HttpClient] (zero extra engines/thread pools).
 */
internal class FreeLyricsTranslator {
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
            transport = KtorFreeTranslationTransport(httpClient),
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

        // 1) Primary: Microsoft Edge zero-auth neural translator with stanza & enjambment context.
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
                        sourceEngine = "microsoft",
                    )
            }
        }

        // 2) Fallback: Google Translate (gtx batch -> clients5 stanza HTML context).
        val googleResult = translateViaGoogle(transport, uniqueList, normalizedGoogleTarget, targetBase)
        return when (googleResult) {
            is StanzaTranslationOutcome.AlreadyInTarget ->
                LyricsTranslationResult.AlreadyInTargetLanguage(googleResult.detectedLanguage)

            is StanzaTranslationOutcome.Success ->
                LyricsTranslationResult.Translated(
                    lyrics = buildTranslatedLyrics(lyrics, stanzas, googleResult.translatedByKey),
                    detectedSourceLanguage = googleResult.detectedLanguage,
                    sourceEngine = "google",
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

    private suspend fun executeEdgeRequest(
        transport: FreeTranslationTransport,
        targetLanguage: String,
        texts: List<String>,
    ): List<EdgeTranslationItem> {
        val (status, body) = transport.translateEdge(targetLanguage, texts)
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
        val segmentsByStanza =
            uniqueStanzas.map { stanza ->
                val preNormalized = stanza.lines.map { preNormalizeForTranslation(it.cleanedText, targetBase) }
                buildLogicalSegments(preNormalized)
            }
        val stanzaTexts =
            segmentsByStanza.map { segments ->
                segments.joinToString("\n") { it.mergedText }
            }

        val items = ArrayList<EdgeTranslationItem>(stanzaTexts.size)
        var cursor = 0
        while (cursor < stanzaTexts.size) {
            val batch = ArrayList<String>()
            var batchChars = 0
            while (cursor < stanzaTexts.size &&
                batch.size < EDGE_MAX_BATCH_ITEMS &&
                (batch.isEmpty() || batchChars + stanzaTexts[cursor].length <= EDGE_MAX_BATCH_CHARS)
            ) {
                val entry = stanzaTexts[cursor]
                batch.add(entry)
                batchChars += entry.length
                cursor++
            }
            val responseItems = executeEdgeRequest(transport, targetLanguage, batch)
            if (responseItems.size != batch.size) {
                throw IllegalStateException("Edge response count mismatch: ${responseItems.size} != ${batch.size}")
            }
            items.addAll(responseItems)
        }

        val dominantLang = detectDominantLanguage(uniqueStanzas, items.map { it.detectedLanguage })
        if (dominantLang != null && normalizeBaseLanguage(dominantLang) == targetBase) {
            return StanzaTranslationOutcome.AlreadyInTarget(dominantLang)
        }

        val translatedByKey = HashMap<String, List<String>>(uniqueStanzas.size)
        val unresolvedIndices = ArrayList<Int>()

        uniqueStanzas.forEachIndexed { idx, stanza ->
            val segments = segmentsByStanza[idx]
            val item = items[idx]
            val extractedSegments = extractSegmentsFromText(item.text, segments.size)
            if (extractedSegments != null) {
                translatedByKey[stanza.dedupeKey] = expandSegmentsToLines(extractedSegments, segments)
            } else {
                unresolvedIndices.add(idx)
            }
        }

        // Self-healing batch fallback: translate unresolved stanzas as 1 segment per array element
        // in one extra POST (preserves exact alignment without touching the UI).
        if (unresolvedIndices.isNotEmpty()) {
            val flatSegments = unresolvedIndices.flatMap { segmentsByStanza[it] }
            val flatTexts = flatSegments.map { it.mergedText }
            val resolvedTexts = ArrayList<String>(flatTexts.size)
            var flatCursor = 0
            while (flatCursor < flatTexts.size) {
                val batch = ArrayList<String>()
                var batchChars = 0
                while (flatCursor < flatTexts.size &&
                    batch.size < EDGE_MAX_BATCH_ITEMS &&
                    (batch.isEmpty() || batchChars + flatTexts[flatCursor].length <= EDGE_MAX_BATCH_CHARS)
                ) {
                    val text = flatTexts[flatCursor]
                    batch.add(text)
                    batchChars += text.length
                    flatCursor++
                }
                val batchItems = executeEdgeRequest(transport, targetLanguage, batch)
                if (batchItems.size != batch.size) {
                    throw IllegalStateException("Edge segment fallback count mismatch")
                }
                batchItems.forEach { resolvedTexts.add(it.text.trim()) }
            }
            var offset = 0
            unresolvedIndices.forEach { stanzaIdx ->
                val stanza = uniqueStanzas[stanzaIdx]
                val segments = segmentsByStanza[stanzaIdx]
                val slice = resolvedTexts.subList(offset, offset + segments.size)
                translatedByKey[stanza.dedupeKey] = expandSegmentsToLines(slice, segments)
                offset += segments.size
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
        val segmentsByStanza =
            uniqueStanzas.map { stanza ->
                val preNormalized = stanza.lines.map { preNormalizeForTranslation(it.cleanedText, targetBase) }
                buildLogicalSegments(preNormalized)
            }

        val translatedByKey = HashMap<String, List<String>>(uniqueStanzas.size)
        val detectedWeights = HashMap<String, Int>()

        // Batch stanzas together for gtx or clients5 HTML stanza context.
        var cursor = 0
        while (cursor < uniqueStanzas.size) {
            val batchIndices = ArrayList<Int>()
            var chars = 0
            while (cursor < uniqueStanzas.size && batchIndices.size < GOOGLE_MAX_BATCH_ITEMS) {
                val stanza = uniqueStanzas[cursor]
                val stanzaChars = stanza.lines.sumOf { it.cleanedText.length + 1 }
                if (batchIndices.isNotEmpty() && chars + stanzaChars > 2_800) {
                    break
                }
                batchIndices.add(cursor)
                chars += stanzaChars
                cursor++
            }

            val htmlStanzas =
                batchIndices.map { idx ->
                    buildSegmentHtml(segmentsByStanza[idx].map { it.mergedText })
                }

            val clients5Attempt =
                try {
                    val (status, body) = transport.translateGoogleClients5(targetLanguage, htmlStanzas)
                    if (status in 200..299) {
                        parseGoogleClients5Response(json, body, htmlStanzas.size)
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }

            var resolvedWithHtml = false
            if (clients5Attempt != null && clients5Attempt.lines.size == batchIndices.size) {
                val perStanzaSegments =
                    batchIndices.mapIndexed { pos, stanzaIdx ->
                        extractStanzaHtmlLines(clients5Attempt.lines[pos], segmentsByStanza[stanzaIdx].size)
                    }
                if (perStanzaSegments.all { it != null }) {
                    resolvedWithHtml = true
                    clients5Attempt.detectedLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
                        val base = normalizeBaseLanguage(lang)
                        detectedWeights[base] = (detectedWeights[base] ?: 0) + chars
                    }
                    batchIndices.forEachIndexed { pos, stanzaIdx ->
                        val stanza = uniqueStanzas[stanzaIdx]
                        val segs = segmentsByStanza[stanzaIdx]
                        translatedByKey[stanza.dedupeKey] = expandSegmentsToLines(perStanzaSegments[pos]!!, segs)
                    }
                }
            }

            if (!resolvedWithHtml) {
                // Plain batch fallback (gtx newline -> clients5 plain segment array).
                val batchSegments = batchIndices.flatMap { idx -> segmentsByStanza[idx] }
                val batchTexts = batchSegments.map { it.mergedText }
                val plainResult = translateGooglePlainBatch(transport, targetLanguage, batchTexts)
                plainResult.detectedLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
                    val base = normalizeBaseLanguage(lang)
                    detectedWeights[base] = (detectedWeights[base] ?: 0) + chars
                }
                var offset = 0
                batchIndices.forEach { stanzaIdx ->
                    val stanza = uniqueStanzas[stanzaIdx]
                    val segs = segmentsByStanza[stanzaIdx]
                    val slice = plainResult.lines.subList(offset, offset + segs.size)
                    translatedByKey[stanza.dedupeKey] = expandSegmentsToLines(slice, segs)
                    offset += segs.size
                }
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

    private suspend fun translateGooglePlainBatch(
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
                    if (parsed != null && parsed.lines.size == lines.size) parsed else null
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        if (gtxAttempt != null) return gtxAttempt

        val (status, body) = transport.translateGoogleClients5(targetLanguage, lines)
        if (status !in 200..299) {
            throw IllegalStateException("Google translator HTTP $status")
        }
        return parseGoogleClients5Response(json, body, lines.size)
            ?: throw IllegalStateException("Failed to parse Google translator response")
    }
}

private class KtorFreeTranslationTransport(
    private val httpClient: HttpClient,
) : FreeTranslationTransport {
    override suspend fun translateEdge(
        targetLanguage: String,
        texts: List<String>,
    ): Pair<Int, String> {
        val bodyJson = Json.encodeToString(texts)
        val response =
            httpClient.post(EDGE_TRANSLATE_URL) {
                parameter("from", "")
                parameter("to", targetLanguage)
                parameter("isEnterpriseClient", "false")
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

internal fun preNormalizeForTranslation(
    text: String,
    targetBaseLanguage: String,
): String {
    if (targetBaseLanguage != "pt" || !text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
        return text
    }
    var updated = text
    for ((regex, replacement) in EN_LYRIC_REPLACEMENTS) {
        if (regex.containsMatchIn(updated)) {
            updated = regex.replace(updated, replacement)
        }
    }
    return updated.replace(MULTI_SPACE_REGEX, " ").trim()
}

internal fun isLikelyEnjambment(
    currentLine: String,
    nextLine: String,
): Boolean {
    val trimmedCurr = currentLine.trim()
    val trimmedNext = nextLine.trim()
    if (trimmedCurr.isEmpty() || trimmedNext.isEmpty()) return false
    val lastChar = trimmedCurr.last()
    if (lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == ';' || lastChar == ')') {
        return false
    }
    if (trimmedNext.first().isLowerCase()) return true
    if (lastChar == ',') return true
    val lastWord =
        trimmedCurr
            .substringAfterLast(' ')
            .trimEnd(',', ':', ';', '"', '\'')
            .lowercase()
    return lastWord in ENJAMBMENT_TRAILING_WORDS
}

internal fun buildLogicalSegments(lines: List<String>): List<LogicalSegment> {
    if (lines.isEmpty()) return emptyList()
    val segments = ArrayList<LogicalSegment>(lines.size)
    var i = 0
    while (i < lines.size) {
        val group = ArrayList<String>(2)
        group.add(lines[i])
        // Merge at most 2-3 consecutive lines that form one broken clause so splitting stays clean.
        while (i + 1 < lines.size && group.size < 3 && isLikelyEnjambment(lines[i], lines[i + 1])) {
            i++
            group.add(lines[i])
        }
        segments.add(LogicalSegment(group))
        i++
    }
    return segments
}

internal fun extractSegmentsFromText(
    translatedText: String,
    expectedSegmentCount: Int,
): List<String>? {
    if (expectedSegmentCount <= 0) return emptyList()
    if (expectedSegmentCount == 1) {
        val cleaned = translatedText.replace('\n', ' ').replace(MULTI_SPACE_REGEX, " ").trim()
        return if (cleaned.isNotEmpty()) listOf(cleaned) else null
    }
    val lines = translatedText.split('\n').map { it.trim() }
    return if (lines.size == expectedSegmentCount && lines.none { it.isEmpty() }) {
        lines
    } else {
        null
    }
}

internal fun expandSegmentsToLines(
    translatedSegments: List<String>,
    segments: List<LogicalSegment>,
): List<String> {
    val out = ArrayList<String>()
    segments.forEachIndexed { idx, seg ->
        val translated = translatedSegments.getOrNull(idx).orEmpty().trim()
        if (seg.lineCount <= 1) {
            out.add(translated)
        } else {
            out.addAll(splitTranslatedProportionally(translated, seg.lineTexts))
        }
    }
    return out
}

internal fun splitTranslatedProportionally(
    translated: String,
    originalParts: List<String>,
): List<String> {
    val partCount = originalParts.size
    if (partCount <= 1) return listOf(translated)
    if (translated.isBlank()) return List(partCount) { "" }

    val words = translated.split(MULTI_SPACE_REGEX).filter { it.isNotEmpty() }
    if (words.size < partCount) {
        // Fewer words than lines: keep full phrase on first line and empty suffix lines.
        return List(partCount) { if (it == 0) translated else "" }
    }

    val totalChars = originalParts.sumOf { it.length.coerceAtLeast(1) }.toFloat()
    val result = ArrayList<String>(partCount)
    var wordCursor = 0
    var cumulativeOriginal = 0f

    for (p in 0 until partCount - 1) {
        cumulativeOriginal += originalParts[p].length.coerceAtLeast(1)
        val targetWordRatio = cumulativeOriginal / totalChars
        val idealWordsEnd =
            (words.size * targetWordRatio)
                .toInt()
                .coerceIn(wordCursor + 1, words.size - (partCount - 1 - p))
        // Pick the split point in [wordCursor+1..min/max] closest to proportional char budget.
        var bestEnd = idealWordsEnd
        var bestDiff = Int.MAX_VALUE
        val minEnd = wordCursor + 1
        val maxEnd = words.size - (partCount - 1 - p)
        val targetTranslatedChars = (translated.length * (originalParts[p].length.coerceAtLeast(1) / totalChars)).toInt()
        var runningChars = 0
        for (end in minEnd..maxEnd) {
            runningChars += words[end - 1].length + 1
            val diff = abs(runningChars - targetTranslatedChars)
            if (diff < bestDiff) {
                bestDiff = diff
                bestEnd = end
            }
        }
        result.add(words.subList(wordCursor, bestEnd).joinToString(" "))
        wordCursor = bestEnd
    }
    result.add(words.subList(wordCursor, words.size).joinToString(" "))
    return result
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

internal fun buildSegmentHtml(segments: List<String>): String =
    segments.mapIndexed { index, text ->
        """<span id="$index">${escapeHtmlText(text)}</span>"""
    }.joinToString("<br/>")

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
        val translationObj =
            obj["translations"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?: throw IllegalStateException("Missing translations entry in Edge response")
        val text =
            translationObj["text"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw IllegalStateException("Missing translation text in Edge response")
        val transSentLen =
            translationObj["sentLen"]
                ?.jsonObject
                ?.get("transSentLen")
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                .orEmpty()
        EdgeTranslationItem(text = text, detectedLanguage = detected, transSentLen = transSentLen)
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
