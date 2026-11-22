package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.extension.decodeHtmlEntities
import com.maxrave.domain.lyrics.LyricsHeaderSanitizer
import com.maxrave.domain.lyrics.LyricsTranslationResult
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "VagalumeTranslator"
private const val VAGALUME_BASE_URL = "https://www.vagalume.com.br"
private const val VAGALUME_CONNECT_TIMEOUT_MS = 2_500L
private const val VAGALUME_REQUEST_TIMEOUT_MS = 3_500L
private const val MIN_COVERAGE_RATIO = 0.88f
private const val MIN_PAIR_SIMILARITY = 0.58f
private const val MIN_MERGED_PAIR_SIMILARITY = 0.68f

private val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private val NOISE_TITLE_PAREN_REGEX =
    Regex(
        """\s*[\(\[](?:official\s*(?:music\s*)?(?:video|audio|lyric\s*video|visualizer)|lyrics?|hd|4k|remastered(?:\s+\d{4})?|explicit|clean|radio\s*edit|single\s*version|album\s*version|feat\.?[^\)\]]*|ft\.?[^\)\]]*)[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val FEAT_TAIL_REGEX = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
private val PAIR_DIV_REGEX =
    Regex(
        """<div\s+class=["']?(orig|trad)\s+(?:pair\s+)?line(\d+)["']?\s*><p>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")

internal data class BilingualLinePair(
    val original: String,
    val translated: String,
)

/**
 * Fetches human-curated Brazilian Portuguese translations from Vagalume (`vagalume.com.br`)
 * and aligns them monotonically onto the synced LRC/RichSync [Lyrics] lines.
 *
 * Strictly opt-in for Portuguese targets (`pt` / `pt-BR`), uses short timeouts (`3.5s`),
 * reuses the caller's existing [HttpClient], and rejects matches below 70% line coverage so
 * mismatched song edits fall cleanly through to the next translation engine.
 */
internal class VagalumeTranslationFetcher {
    suspend fun fetchTranslation(
        httpClient: HttpClient,
        songTitle: String,
        artistName: String,
        originalLyrics: Lyrics,
        targetLanguage: String,
    ): LyricsTranslationResult.Translated? {
        if (normalizeBaseLanguage(targetLanguage) != "pt") return null
        if (songTitle.isBlank() || artistName.isBlank()) return null

        val urls = buildCandidateUrls(artistName, songTitle)
        for (url in urls) {
            val html =
                try {
                    val response =
                        httpClient.get(url) {
                            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            header(HttpHeaders.AcceptLanguage, "pt-BR,pt;q=0.9,en-US;q=0.8")
                            timeout {
                                connectTimeoutMillis = VAGALUME_CONNECT_TIMEOUT_MS
                                requestTimeoutMillis = VAGALUME_REQUEST_TIMEOUT_MS
                                socketTimeoutMillis = VAGALUME_REQUEST_TIMEOUT_MS
                            }
                        }
                    if (response.status.value in 200..299) {
                        response.bodyAsText()
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.d(TAG, "Vagalume fetch skipped ($url): ${t.message}")
                    null
                }
            if (html.isNullOrBlank()) continue

            val pairs = parseVagalumePairs(html)
            if (pairs.isEmpty()) continue

            val aligned = alignHumanPairsToLyrics(originalLyrics, pairs)
            if (aligned != null) {
                return LyricsTranslationResult.Translated(
                    lyrics = aligned,
                    detectedSourceLanguage = null,
                    sourceEngine = "vagalume",
                )
            }
        }
        return null
    }
}

internal fun buildCandidateUrls(
    artistName: String,
    songTitle: String,
): List<String> {
    val cleanArtist =
        artistName
            .let { FEAT_TAIL_REGEX.replace(it, "") }
            .split(',', '&', '•', '/')
            .firstOrNull()
            .orEmpty()
            .trim()
    val fullArtistSlug = toVagalumeSlug(artistName)
    val primaryArtistSlug = toVagalumeSlug(cleanArtist)

    val strippedTitle = NOISE_TITLE_PAREN_REGEX.replace(FEAT_TAIL_REGEX.replace(songTitle, ""), "").trim()
    val beforeDashTitle = strippedTitle.substringBefore(" - ").trim()
    val titleSlugs =
        listOf(
            toVagalumeSlug(strippedTitle),
            toVagalumeSlug(beforeDashTitle),
        ).filter { it.isNotEmpty() }.distinct()

    val artistSlugs =
        listOf(primaryArtistSlug, fullArtistSlug)
            .filter { it.isNotEmpty() }
            .distinct()

    val urls = ArrayList<String>(4)
    for (artistSlug in artistSlugs) {
        for (titleSlug in titleSlugs) {
            urls.add("$VAGALUME_BASE_URL/$artistSlug/$titleSlug-traducao.html")
        }
    }
    return urls.take(3)
}

internal fun toVagalumeSlug(raw: String): String {
    if (raw.isBlank()) return ""
    val normalized =
        buildString(raw.length) {
            for (ch in raw.lowercase()) {
                when (val folded = foldVagalumeAccent(ch)) {
                    '\'', '’' -> Unit
                    in 'a'..'z', in '0'..'9' -> append(folded)
                    else -> append('-')
                }
            }
        }
    return normalized
        .replace(Regex("-+"), "-")
        .trim('-')
}

internal fun parseVagalumePairs(html: String): List<BilingualLinePair> {
    val marker = html.indexOf("lyricsPair")
    val scope = if (marker >= 0) html.substring(marker) else html
    val origById = LinkedHashMap<Int, String>()
    val tradById = LinkedHashMap<Int, String>()

    PAIR_DIV_REGEX.findAll(scope).forEach { match ->
        val kind = match.groupValues[1].lowercase()
        val lineId = match.groupValues[2].toIntOrNull() ?: return@forEach
        val rawInner = match.groupValues[3]
        val text =
            decodeHtmlEntities(
                HTML_TAG_REGEX.replace(rawInner, " "),
            ).replace(MULTI_SPACE_REGEX, " ").trim()
        if (text.isNotEmpty()) {
            if (kind == "orig") {
                origById[lineId] = text
            } else {
                tradById[lineId] = text
            }
        }
    }

    val pairs = ArrayList<BilingualLinePair>(origById.size)
    for ((lineId, orig) in origById) {
        val trad = tradById[lineId] ?: continue
        if (isTranslatableLyricLine(orig) && trad.isNotBlank()) {
            pairs.add(BilingualLinePair(original = orig, translated = trad))
        }
    }
    return pairs
}

internal fun alignHumanPairsToLyrics(
    originalLyrics: Lyrics,
    pairs: List<BilingualLinePair>,
): Lyrics? {
    val lines = originalLyrics.lines ?: return null
    if (lines.isEmpty() || pairs.isEmpty()) return null

    val pairNorms = pairs.map { normalizeForMatch(it.original) }
    val pairTokens = pairNorms.map { tokenizeForMatch(it) }

    val matchedTranslations = arrayOfNulls<String>(lines.size)
    var totalTranslatable = 0
    var matchedCount = 0
    var cursor = 0

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val cleanOrig = stripRichSyncMarkers(line.words)
        if (!isTranslatableLyricLine(cleanOrig)) {
            index++
            continue
        }
        totalTranslatable++

        val normOrig = normalizeForMatch(cleanOrig)
        val tokensOrig = tokenizeForMatch(normOrig)
        if (normOrig.isEmpty()) {
            index++
            continue
        }

        // 1. Prefer a monotonic forward window match so repeated choruses align chronologically.
        var bestIdx = -1
        var bestScore = 0f
        val forwardEnd = min(pairs.size - 1, cursor + 10)
        for (p in cursor..forwardEnd) {
            val score = lineSimilarity(normOrig, tokensOrig, pairNorms[p], pairTokens[p])
            if (score > bestScore) {
                bestScore = score
                bestIdx = p
            }
        }
        // 2. If not found just ahead (chorus repeat/reordered section), scan the entire list.
        if (bestScore < 0.78f) {
            for (p in pairs.indices) {
                val score = lineSimilarity(normOrig, tokensOrig, pairNorms[p], pairTokens[p])
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = p
                }
            }
        }

        // 3. Check whether 2 (or 3) consecutive human pairs combine into this single original line
        // (common when Letras breaks one synced verse into two short visual lines, e.g. Remi Wolf's Liz).
        if (bestScore < 0.94f) {
            var bestSpanStart = -1
            var bestSpanLen = 0
            var bestSpanScore = bestScore

            fun scanMultiSpan(rangeStart: Int, rangeEndExclusive: Int) {
                for (span in 2..3) {
                    val lastStart = rangeEndExclusive - span
                    if (lastStart < rangeStart) continue
                    for (p in rangeStart..lastStart) {
                        val combinedPairNorm =
                            buildString {
                                for (k in 0 until span) {
                                    if (isNotEmpty()) append(' ')
                                    append(pairNorms[p + k])
                                }
                            }.trim()
                        val combinedPairTokens = HashSet<String>(pairTokens[p].size * span)
                        for (k in 0 until span) {
                            combinedPairTokens.addAll(pairTokens[p + k])
                        }
                        val score = lineSimilarity(normOrig, tokensOrig, combinedPairNorm, combinedPairTokens)
                        if (score > bestSpanScore) {
                            bestSpanScore = score
                            bestSpanStart = p
                            bestSpanLen = span
                        }
                    }
                }
            }

            scanMultiSpan(cursor, min(pairs.size, forwardEnd + 3))
            if (bestSpanScore < 0.85f) {
                scanMultiSpan(0, pairs.size)
            }

            if (bestSpanStart >= 0 && bestSpanScore >= 0.78f && bestSpanScore > bestScore + 0.08f) {
                val parts =
                    (0 until bestSpanLen).map { offset ->
                        pairs[bestSpanStart + offset].translated
                    }
                matchedTranslations[index] = joinHumanTranslatedParts(parts)
                matchedCount++
                if (bestSpanStart in cursor..forwardEnd) {
                    cursor = (bestSpanStart + bestSpanLen).coerceAtMost(pairs.lastIndex)
                }
                index++
                continue
            }
        }

        // 4. Check whether two consecutive original lines were combined into a single human pair.
        var mergedHandled = false
        if (bestScore < 0.76f && index + 1 < lines.size) {
            val nextClean = stripRichSyncMarkers(lines[index + 1].words)
            if (isTranslatableLyricLine(nextClean)) {
                val combinedNorm = normalizeForMatch("$cleanOrig $nextClean")
                val combinedTokens = tokenizeForMatch(combinedNorm)
                var mergedIdx = -1
                var mergedScore = 0f
                for (p in cursor..forwardEnd) {
                    val s = lineSimilarity(combinedNorm, combinedTokens, pairNorms[p], pairTokens[p])
                    if (s > mergedScore) {
                        mergedScore = s
                        mergedIdx = p
                    }
                }
                if (mergedScore < 0.78f) {
                    for (p in pairs.indices) {
                        val s = lineSimilarity(combinedNorm, combinedTokens, pairNorms[p], pairTokens[p])
                        if (s > mergedScore) {
                            mergedScore = s
                            mergedIdx = p
                        }
                    }
                }
                if (mergedIdx >= 0 && mergedScore >= MIN_MERGED_PAIR_SIMILARITY && mergedScore > bestScore + 0.08f) {
                    val splitParts =
                        splitTranslatedProportionally(
                            translated = pairs[mergedIdx].translated,
                            originalParts = listOf(cleanOrig, nextClean),
                        )
                    matchedTranslations[index] = LyricsHeaderSanitizer.cleanTranslatedLinePunctuation(splitParts[0])
                    matchedTranslations[index + 1] = LyricsHeaderSanitizer.cleanTranslatedLinePunctuation(splitParts[1])
                    totalTranslatable++
                    matchedCount += 2
                    if (mergedIdx in cursor..forwardEnd) {
                        cursor = (mergedIdx + 1).coerceAtMost(pairs.lastIndex)
                    }
                    index += 2
                    mergedHandled = true
                }
            }
        }
        if (mergedHandled) continue

        if (bestIdx >= 0 && bestScore >= MIN_PAIR_SIMILARITY) {
            matchedTranslations[index] = LyricsHeaderSanitizer.cleanTranslatedLinePunctuation(pairs[bestIdx].translated)
            matchedCount++
            if (bestIdx in cursor..forwardEnd) {
                cursor = (bestIdx + 1).coerceAtMost(pairs.lastIndex)
            }
        }
        index++
    }

    if (totalTranslatable == 0) return null
    val coverage = matchedCount.toFloat() / totalTranslatable.toFloat()
    if (coverage < MIN_COVERAGE_RATIO) return null

    val outputLines =
        lines.mapIndexed { index, line ->
            Line(
                startTimeMs = line.startTimeMs,
                endTimeMs = line.endTimeMs,
                syllables = null,
                words = matchedTranslations[index].orEmpty(),
            )
        }
    val outputSyncType =
        when (originalLyrics.syncType) {
            "RICH_SYNCED", "LINE_SYNCED" -> "LINE_SYNCED"
            else -> originalLyrics.syncType
        }
    return Lyrics(
        error = false,
        lines = outputLines,
        syncType = outputSyncType,
        simpMusicLyrics = null,
    )
}

private fun lineSimilarity(
    aNorm: String,
    aTokens: Set<String>,
    bNorm: String,
    bTokens: Set<String>,
): Float {
    if (aNorm == bNorm) return 1.0f
    if (aTokens.isEmpty() || bTokens.isEmpty()) return 0f
    val intersection = aTokens.count { it in bTokens }
    val union = aTokens.size + bTokens.size - intersection
    val jaccard = if (union > 0) intersection.toFloat() / union.toFloat() else 0f

    // Fast length-bounded normalized Levenshtein when lengths are comparable.
    val maxLen = max(aNorm.length, bNorm.length)
    val lenDiff = abs(aNorm.length - bNorm.length)
    val editScore =
        if (maxLen > 0 && lenDiff <= maxLen * 0.45f && maxLen <= 140) {
            1f - (levenshteinDistance(aNorm, bNorm).toFloat() / maxLen.toFloat())
        } else {
            0f
        }
    return max(jaccard, editScore)
}

private fun levenshteinDistance(
    a: String,
    b: String,
): Int {
    val m = a.length
    val n = b.length
    var prev = IntArray(n + 1) { it }
    var curr = IntArray(n + 1)
    for (i in 1..m) {
        curr[0] = i
        val ca = a[i - 1]
        for (j in 1..n) {
            val cost = if (ca == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[n]
}

private fun normalizeForMatch(text: String): String =
    buildString(text.length) {
        var prevSpace = true
        for (ch in text.lowercase()) {
            val folded = foldVagalumeAccent(ch)
            if (folded.isLetterOrDigit()) {
                append(folded)
                prevSpace = false
            } else if (!prevSpace) {
                append(' ')
                prevSpace = true
            }
        }
    }.trim()

private fun tokenizeForMatch(normalized: String): Set<String> =
    normalized.split(' ').filterTo(HashSet()) { it.isNotEmpty() }

private val SPACE_JOIN_CONJUNCTIONS =
    setOf("e", "mas", "ou", "nem", "porque", "pois", "quando", "então", "entao", "desde", "exceto", "senão", "senao")

private val CONTINUATION_LOWERCASE_WORDS =
    SPACE_JOIN_CONJUNCTIONS +
        setOf(
            "não", "nao", "sim", "assim", "como", "se", "que", "só", "so", "já", "ja",
            "ainda", "agora", "eu", "ela", "ele", "elas", "eles", "você", "voce", "nós", "nos",
            "isso", "isto", "aquilo", "tudo", "nada", "ninguém", "ninguem", "sempre", "nunca",
            "chorando", "esperando", "tentando", "olhando", "pensando", "muito", "pouco",
        )

internal fun joinHumanTranslatedParts(parts: List<String>): String {
    var acc = ""
    for (raw in parts) {
        val cleaned = LyricsHeaderSanitizer.cleanTranslatedLinePunctuation(raw)
        if (cleaned.isEmpty()) continue
        if (acc.isEmpty()) {
            acc = cleaned
            continue
        }
        val endsSentence = acc.endsWith("?") || acc.endsWith("!") || acc.endsWith("...") || acc.endsWith("…")
        if (endsSentence) {
            acc = "$acc $cleaned"
            continue
        }
        val firstWordEnd = cleaned.indexOfFirst { !it.isLetter() }.let { if (it < 0) cleaned.length else it }
        val firstWordLower = cleaned.substring(0, firstWordEnd).lowercase()
        val normalizedPart =
            if (firstWordLower in CONTINUATION_LOWERCASE_WORDS && cleaned[0].isUpperCase()) {
                cleaned[0].lowercaseChar() + cleaned.substring(1)
            } else {
                cleaned
            }
        acc =
            if (firstWordLower in SPACE_JOIN_CONJUNCTIONS) {
                "$acc $normalizedPart"
            } else {
                "$acc, $normalizedPart"
            }
    }
    return LyricsHeaderSanitizer.cleanTranslatedLinePunctuation(acc)
}

private fun foldVagalumeAccent(ch: Char): Char =
    when (ch) {
        'á', 'à', 'â', 'ã', 'ä', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'õ', 'ö', 'ø' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ç' -> 'c'
        'ñ' -> 'n'
        'ý', 'ÿ' -> 'y'
        else -> ch
    }
