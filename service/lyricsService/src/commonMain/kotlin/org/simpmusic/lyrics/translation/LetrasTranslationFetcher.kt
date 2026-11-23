package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.extension.decodeHtmlEntities
import com.maxrave.domain.lyrics.LyricsHeaderSanitizer
import com.maxrave.domain.lyrics.LyricsTranslationResult
import com.maxrave.logger.Logger
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

private const val TAG = "LetrasTranslator"
private const val LETRAS_BASE_URL = "https://www.letras.mus.br"
private const val LETRAS_CONNECT_TIMEOUT_MS = 2_500L
private const val LETRAS_REQUEST_TIMEOUT_MS = 3_500L

private val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private val NOISE_TITLE_PAREN_REGEX =
    Regex(
        """\s*[\(\[](?:official\s*(?:music\s*)?(?:video|audio|lyric\s*video|visualizer)|lyrics?|hd|4k|remastered(?:\s+\d{4})?|explicit|clean|radio\s*edit|single\s*version|album\s*version|feat\.?[^\)\]]*|ft\.?[^\)\]]*|part\.?[^\)\]]*|participa[çc][ãa]o[^\)\]]*)[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val FEAT_TAIL_REGEX = Regex("""\s+(?:feat\.?|ft\.?|featuring|part\.?)\s+.*$""", RegexOption.IGNORE_CASE)
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val LETRAS_URL_HOST_REGEX = Regex("""^https?://(?:www\.)?letras\.mus\.br""", RegexOption.IGNORE_CASE)

internal sealed interface LetrasPageFetchResult {
    data class Ok(
        val finalPath: String,
        val html: String,
    ) : LetrasPageFetchResult

    data object NotFound : LetrasPageFetchResult

    data object BlockedOrUnavailable : LetrasPageFetchResult
}

internal fun interface LetrasTransport {
    suspend fun get(url: String): LetrasPageFetchResult
}

internal data class LetrasParsedTranslationPage(
    val canonicalHref: String?,
    val headingTitle: String,
    val pairs: List<BilingualLinePair>,
)

internal data class LetrasParsedOriginalPage(
    val canonicalHref: String?,
    val headingTitle: String,
    val lines: List<String>,
)

/**
 * Fetches human-curated Brazilian Portuguese translations from Letras.mus.br directly from the
 * user's device connection (avoiding datacenter IP blocks) and aligns them monotonically onto
 * the original synced/unsynced [Lyrics].
 *
 * Lookup flow:
 * 1. Probe direct `/<artist>/<title>/traducao.html` candidate URLs (1 HTTP request on hit).
 * 2. Check final path (detecting Letras's 302 redirect back to original lyrics when untranslated),
 *    canonical link, and `<h1>` title match.
 * 3. On 404/alias miss (never on 403/timeout), scan `/<artist>/` catalog links to resolve numeric-ID
 *    pages (e.g. `/michael-jackson/63736/traducao.html`) or punctuation variants.
 */
internal class LetrasTranslationFetcher {
    suspend fun fetchTranslation(
        httpClient: HttpClient,
        songTitle: String,
        artistName: String,
        originalLyrics: Lyrics,
        targetLanguage: String,
    ): LyricsTranslationResult.Translated? =
        fetchTranslation(
            transport = ktorLetrasTransport(httpClient),
            songTitle = songTitle,
            artistName = artistName,
            originalLyrics = originalLyrics,
            targetLanguage = targetLanguage,
        )

    suspend fun fetchTranslation(
        transport: LetrasTransport,
        songTitle: String,
        artistName: String,
        originalLyrics: Lyrics,
        targetLanguage: String,
    ): LyricsTranslationResult.Translated? {
        if (normalizeBaseLanguage(targetLanguage) != "pt") return null
        if (songTitle.isBlank() || artistName.isBlank()) return null

        val artistSlugs = buildLetrasArtistSlugs(artistName)
        val titleVariants = buildLetrasTitleVariants(songTitle)
        if (artistSlugs.isEmpty() || titleVariants.isEmpty()) return null

        val expectedTitleKeys = buildExpectedTitleKeys(titleVariants)
        val triedPaths = HashSet<String>(6)

        // 1. Direct `/<artist>/<title>/traducao.html` fast path (handles ~85%+ of songs in 1 request).
        for (artistSlug in artistSlugs) {
            val directSlugs = titleVariants.map(::toLetrasSlug).filter { it.isNotEmpty() }.distinct()
            for (titleSlug in directSlugs) {
                val path = "/$artistSlug/$titleSlug/traducao.html"
                if (!triedPaths.add(path)) continue
                when (val result = transport.get("$LETRAS_BASE_URL$path")) {
                    is LetrasPageFetchResult.Ok -> {
                        val matched =
                            tryAlignLetrasTranslationPage(
                                result = result,
                                artistSlug = artistSlug,
                                expectedTitleKeys = expectedTitleKeys,
                                originalLyrics = originalLyrics,
                            )
                        if (matched != null) return matched
                    }

                    LetrasPageFetchResult.NotFound -> Unit
                    LetrasPageFetchResult.BlockedOrUnavailable -> return null
                }
            }
        }

        // 2. Artist catalog fallback (resolves numeric IDs like `/michael-jackson/63736/` and aliases).
        for (artistSlug in artistSlugs.take(2)) {
            val catalogPath = "/$artistSlug/"
            when (val catalogResult = transport.get("$LETRAS_BASE_URL$catalogPath")) {
                is LetrasPageFetchResult.Ok -> {
                    val normalizedCatalogPath = normalizeLetrasPath(catalogResult.finalPath)
                    if (normalizedCatalogPath != catalogPath) continue

                    val candidatePaths =
                        parseLetrasCatalogLinks(
                            html = catalogResult.html,
                            artistSlug = artistSlug,
                            expectedTitleKeys = expectedTitleKeys,
                        )
                    for (songTradPath in candidatePaths) {
                        if (!triedPaths.add(songTradPath)) continue
                        when (val songResult = transport.get("$LETRAS_BASE_URL$songTradPath")) {
                            is LetrasPageFetchResult.Ok -> {
                                val matched =
                                    tryAlignLetrasTranslationPage(
                                        result = songResult,
                                        artistSlug = artistSlug,
                                        expectedTitleKeys = expectedTitleKeys,
                                        originalLyrics = originalLyrics,
                                    )
                                if (matched != null) return matched
                            }

                            LetrasPageFetchResult.NotFound -> Unit
                            LetrasPageFetchResult.BlockedOrUnavailable -> return null
                        }
                    }
                }

                LetrasPageFetchResult.NotFound -> Unit
                LetrasPageFetchResult.BlockedOrUnavailable -> return null
            }
        }

        return null
    }

    suspend fun fetchOriginalLyrics(
        httpClient: HttpClient,
        songTitle: String,
        artistName: String,
    ): Lyrics? =
        fetchOriginalLyrics(
            transport = ktorLetrasTransport(httpClient),
            songTitle = songTitle,
            artistName = artistName,
        )

    suspend fun fetchOriginalLyrics(
        transport: LetrasTransport,
        songTitle: String,
        artistName: String,
    ): Lyrics? {
        if (songTitle.isBlank() || artistName.isBlank()) return null

        val artistSlugs = buildLetrasArtistSlugs(artistName)
        val titleVariants = buildLetrasTitleVariants(songTitle)
        if (artistSlugs.isEmpty() || titleVariants.isEmpty()) return null

        val expectedTitleKeys = buildExpectedTitleKeys(titleVariants)
        val triedPaths = HashSet<String>(6)

        // 1. Direct `/<artist>/<title>/` lookup (1 HTTP request on hit).
        for (artistSlug in artistSlugs) {
            val directSlugs = titleVariants.map(::toLetrasSlug).filter { it.isNotEmpty() }.distinct()
            for (titleSlug in directSlugs) {
                val path = "/$artistSlug/$titleSlug/"
                if (!triedPaths.add(path)) continue
                when (val result = transport.get("$LETRAS_BASE_URL$path")) {
                    is LetrasPageFetchResult.Ok -> {
                        val matched =
                            tryBuildLetrasOriginalLyrics(
                                result = result,
                                artistSlug = artistSlug,
                                expectedTitleKeys = expectedTitleKeys,
                            )
                        if (matched != null) return matched
                    }

                    LetrasPageFetchResult.NotFound -> Unit
                    LetrasPageFetchResult.BlockedOrUnavailable -> return null
                }
            }
        }

        // 2. Artist catalog fallback (resolves numeric IDs and `(part. ...)` slug aliases).
        for (artistSlug in artistSlugs.take(2)) {
            val catalogPath = "/$artistSlug/"
            when (val catalogResult = transport.get("$LETRAS_BASE_URL$catalogPath")) {
                is LetrasPageFetchResult.Ok -> {
                    val normalizedCatalogPath = normalizeLetrasPath(catalogResult.finalPath)
                    if (normalizedCatalogPath != catalogPath) continue

                    val candidatePaths =
                        parseLetrasCatalogSongPaths(
                            html = catalogResult.html,
                            artistSlug = artistSlug,
                            expectedTitleKeys = expectedTitleKeys,
                        )
                    for (songPath in candidatePaths) {
                        if (!triedPaths.add(songPath)) continue
                        when (val songResult = transport.get("$LETRAS_BASE_URL$songPath")) {
                            is LetrasPageFetchResult.Ok -> {
                                val matched =
                                    tryBuildLetrasOriginalLyrics(
                                        result = songResult,
                                        artistSlug = artistSlug,
                                        expectedTitleKeys = expectedTitleKeys,
                                    )
                                if (matched != null) return matched
                            }

                            LetrasPageFetchResult.NotFound -> Unit
                            LetrasPageFetchResult.BlockedOrUnavailable -> return null
                        }
                    }
                }

                LetrasPageFetchResult.NotFound -> Unit
                LetrasPageFetchResult.BlockedOrUnavailable -> return null
            }
        }

        return null
    }

    private fun tryBuildLetrasOriginalLyrics(
        result: LetrasPageFetchResult.Ok,
        artistSlug: String,
        expectedTitleKeys: Set<String>,
    ): Lyrics? {
        val finalPath = normalizeLetrasPath(result.finalPath) ?: return null
        if (!isValidLetrasOriginalPath(finalPath, artistSlug)) return null

        val parsed = parseLetrasOriginalLyricsPage(result.html)
        if (parsed.lines.isEmpty()) return null

        val canonicalPath = parsed.canonicalHref?.let(::normalizeLetrasPath)
        if (canonicalPath != null && !isValidLetrasOriginalPath(canonicalPath, artistSlug)) {
            return null
        }

        if (parsed.headingTitle.isNotBlank() && !letrasTitleMatches(expectedTitleKeys, parsed.headingTitle)) {
            return null
        }

        val cleanedLines =
            parsed.lines
                .asSequence()
                .filterNot(::isLetrasSectionHeaderLine)
                .map { LyricsHeaderSanitizer.cleanTranslatedLinePunctuation(it) }
                .filter { it.isNotEmpty() }
                .toList()
        if (cleanedLines.isEmpty()) return null

        return Lyrics(
            error = false,
            lines =
                cleanedLines.map { words ->
                    Line(
                        startTimeMs = "0",
                        endTimeMs = "0",
                        syllables = null,
                        words = words,
                    )
                },
            syncType = "UNSYNCED",
        )
    }

    private fun tryAlignLetrasTranslationPage(
        result: LetrasPageFetchResult.Ok,
        artistSlug: String,
        expectedTitleKeys: Set<String>,
        originalLyrics: Lyrics,
    ): LyricsTranslationResult.Translated? {
        val finalPath = normalizeLetrasPath(result.finalPath) ?: return null
        if (!isValidLetrasTranslationPath(finalPath, artistSlug)) return null

        val parsed = parseLetrasTranslationPage(result.html)
        if (parsed.pairs.isEmpty()) return null

        val canonicalPath = parsed.canonicalHref?.let(::normalizeLetrasPath)
        if (canonicalPath != null && !isValidLetrasTranslationPath(canonicalPath, artistSlug)) {
            return null
        }

        if (parsed.headingTitle.isNotBlank() && !letrasTitleMatches(expectedTitleKeys, parsed.headingTitle)) {
            return null
        }

        val aligned = alignHumanPairsToLyrics(originalLyrics, parsed.pairs) ?: return null
        return LyricsTranslationResult.Translated(
            lyrics = aligned,
            detectedSourceLanguage = null,
            sourceEngine = "letras",
        )
    }

    private fun ktorLetrasTransport(httpClient: HttpClient): LetrasTransport =
        LetrasTransport { url ->
            try {
                val response =
                    httpClient.get(url) {
                        header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                        header(
                            HttpHeaders.Accept,
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        )
                        header(HttpHeaders.AcceptLanguage, "pt-BR,pt;q=0.9,en-US;q=0.8")
                        timeout {
                            connectTimeoutMillis = LETRAS_CONNECT_TIMEOUT_MS
                            requestTimeoutMillis = LETRAS_REQUEST_TIMEOUT_MS
                            socketTimeoutMillis = LETRAS_REQUEST_TIMEOUT_MS
                        }
                    }
                val code = response.status.value
                when {
                    code in 200..299 -> {
                        val finalUrl = response.request.url
                        val host = finalUrl.host.lowercase()
                        if (host != "www.letras.mus.br" && host != "letras.mus.br") {
                            return@LetrasTransport LetrasPageFetchResult.NotFound
                        }
                        if (finalUrl.parameters.names().isNotEmpty()) {
                            return@LetrasTransport LetrasPageFetchResult.NotFound
                        }
                        val path = normalizeLetrasPath(finalUrl.encodedPath)
                            ?: return@LetrasTransport LetrasPageFetchResult.NotFound
                        LetrasPageFetchResult.Ok(
                            finalPath = path,
                            html = response.bodyAsText(),
                        )
                    }

                    code == 404 || code == 410 -> LetrasPageFetchResult.NotFound
                    else -> {
                        Logger.d(TAG, "Letras blocked/unavailable ($code) at $url")
                        LetrasPageFetchResult.BlockedOrUnavailable
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.d(TAG, "Letras fetch aborted ($url): ${t.message}")
                LetrasPageFetchResult.BlockedOrUnavailable
            }
        }
}

private val LETRAS_ARTIST_SPLIT_REGEX = Regex("""\s*(?:[,&•/]|feat\.?|ft\.?|featuring|part\.?)\s*""", RegexOption.IGNORE_CASE)
private val LETRAS_SECTION_HEADER_REGEX =
    Regex(
        """^\[\s*(?:(?:pr[eé])[-\s]?)?(?:refr[ãa]o|verso|estrofe|ponte|intro(?:du[çc][ãa]o)?|outro|final|solo|interl[úu]dio|interlude|chorus|verse|bridge|hook|instrumental|breakdown|drop)(?:\s+(?:\d+|[ivx]+|final|principal))?\s*:?\s*\]$""",
        RegexOption.IGNORE_CASE,
    )

internal fun isLetrasSectionHeaderLine(line: String): Boolean =
    LETRAS_SECTION_HEADER_REGEX.matches(line.trim())

internal fun buildLetrasArtistSlugs(artistName: String): List<String> {
    val primaryClean =
        artistName
            .let { FEAT_TAIL_REGEX.replace(it, "") }
            .split(',', '&', '•', '/')
            .firstOrNull()
            .orEmpty()
            .trim()
    val individualArtists =
        LETRAS_ARTIST_SPLIT_REGEX
            .split(artistName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
    return buildList {
        add(toLetrasSlug(primaryClean))
        for (artist in individualArtists) {
            add(toLetrasSlug(artist))
        }
        add(toLetrasSlug(artistName))
    }.filter { it.isNotEmpty() }.distinct()
}

internal fun buildLetrasTitleVariants(songTitle: String): List<String> {
    val stripped = NOISE_TITLE_PAREN_REGEX.replace(FEAT_TAIL_REGEX.replace(songTitle, ""), "").trim()
    val beforeDash = stripped.substringBefore(" - ").trim()
    return listOf(stripped, beforeDash, songTitle.trim())
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun buildExpectedTitleKeys(titleVariants: List<String>): Set<String> =
    titleVariants.map(::toLetrasTitleKey).filterTo(LinkedHashSet()) { it.isNotEmpty() }

internal fun toLetrasSlug(raw: String): String {
    if (raw.isBlank()) return ""
    val out = StringBuilder(raw.length)
    var prevHyphen = true
    for (ch in raw.lowercase()) {
        val folded = foldLetrasAccent(ch)
        if (folded in 'a'..'z' || folded in '0'..'9') {
            out.append(folded)
            prevHyphen = false
        } else if (!prevHyphen) {
            out.append('-')
            prevHyphen = true
        }
    }
    if (out.isNotEmpty() && out[out.lastIndex] == '-') {
        out.setLength(out.length - 1)
    }
    return out.toString()
}

internal fun toLetrasTitleKey(raw: String): String {
    if (raw.isBlank()) return ""
    return buildString(raw.length) {
        for (ch in raw.lowercase()) {
            val folded = foldLetrasAccent(ch)
            if (folded in 'a'..'z' || folded in '0'..'9') {
                append(folded)
            }
        }
    }
}

internal fun letrasTitleMatches(
    expectedTitleKeys: Set<String>,
    foundTitle: String,
): Boolean {
    if (expectedTitleKeys.isEmpty()) return false
    val directKey = toLetrasTitleKey(foundTitle)
    if (directKey in expectedTitleKeys) return true
    val cleanedFound = NOISE_TITLE_PAREN_REGEX.replace(FEAT_TAIL_REGEX.replace(foundTitle, ""), "").trim()
    val cleanedKey = toLetrasTitleKey(cleanedFound)
    return cleanedKey.isNotEmpty() && cleanedKey in expectedTitleKeys
}

internal fun normalizeLetrasPath(rawHrefOrPath: String): String? {
    val trimmed = rawHrefOrPath.trim()
    if (trimmed.isEmpty()) return null
    val withoutHost =
        when {
            trimmed.startsWith("/") -> trimmed
            LETRAS_URL_HOST_REGEX.containsMatchIn(trimmed) -> LETRAS_URL_HOST_REGEX.replace(trimmed, "")
            else -> return null
        }
    if ('?' in withoutHost || '#' in withoutHost) return null
    return withoutHost.ifEmpty { "/" }
}

internal fun isValidLetrasTranslationPath(
    path: String,
    expectedArtistSlug: String,
): Boolean {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    return segments.size == 3 &&
        segments[0].equals(expectedArtistSlug, ignoreCase = true) &&
        segments[1].isNotEmpty() &&
        segments[2].equals("traducao.html", ignoreCase = true)
}

internal fun parseLetrasTranslationPage(html: String): LetrasParsedTranslationPage {
    var canonicalHref: String? = null
    val h1Buf = StringBuilder()
    var inFirstH1 = false
    var seenFirstH1 = false

    var divDepth = 0
    var spanDepth = 0
    var stackedContainerDivDepth = -1
    var verseSpanDepth = -1
    var romanSpanDepth = -1
    var tradSpanDepth = -1

    val origBuf = StringBuilder()
    val tradBuf = StringBuilder()
    val pairs = ArrayList<BilingualLinePair>()

    val parser =
        KsoupHtmlParser(
            handler =
                object : KsoupHtmlHandler {
                    override fun onOpenTag(
                        name: String,
                        attributes: Map<String, String>,
                        isImplied: Boolean,
                    ) {
                        val tag = name.lowercase()
                        when (tag) {
                            "link" -> {
                                if (canonicalHref == null &&
                                    attributes["rel"]?.trim()?.equals("canonical", ignoreCase = true) == true
                                ) {
                                    canonicalHref = attributes["href"]?.trim()
                                }
                            }

                            "h1" -> {
                                if (!seenFirstH1) {
                                    inFirstH1 = true
                                }
                            }

                            "div" -> {
                                divDepth++
                                if (stackedContainerDivDepth == -1) {
                                    val classes = splitClassList(attributes["class"])
                                    if ("lyric-stackedtranslation" in classes || "lyric-stackedTranslation".lowercase() in classes) {
                                        stackedContainerDivDepth = divDepth
                                    }
                                }
                            }

                            "span" -> {
                                if (stackedContainerDivDepth > 0) {
                                    spanDepth++
                                    val classes = splitClassList(attributes["class"])
                                    if (verseSpanDepth == -1) {
                                        if ("verse" in classes) {
                                            verseSpanDepth = spanDepth
                                            origBuf.setLength(0)
                                            tradBuf.setLength(0)
                                            romanSpanDepth = -1
                                            tradSpanDepth = -1
                                        }
                                    } else {
                                        if (romanSpanDepth == -1 && "romanization" in classes) {
                                            romanSpanDepth = spanDepth
                                        } else if (romanSpanDepth == -1 && tradSpanDepth == -1) {
                                            tradSpanDepth = spanDepth
                                        }
                                    }
                                }
                            }

                            "br" -> {
                                if (inFirstH1) h1Buf.append(' ')
                                if (romanSpanDepth > 0) origBuf.append(' ')
                                if (tradSpanDepth > 0) tradBuf.append(' ')
                            }
                        }
                    }

                    override fun onText(text: String) {
                        if (inFirstH1) {
                            h1Buf.append(text)
                        }
                        if (verseSpanDepth > 0) {
                            when {
                                romanSpanDepth > 0 -> origBuf.append(text)
                                tradSpanDepth > 0 -> tradBuf.append(text)
                            }
                        }
                    }

                    override fun onCloseTag(
                        name: String,
                        isImplied: Boolean,
                    ) {
                        val tag = name.lowercase()
                        when (tag) {
                            "h1" -> {
                                if (inFirstH1) {
                                    inFirstH1 = false
                                    seenFirstH1 = true
                                }
                            }

                            "div" -> {
                                if (divDepth == stackedContainerDivDepth) {
                                    stackedContainerDivDepth = -1
                                }
                                if (divDepth > 0) divDepth--
                            }

                            "span" -> {
                                if (stackedContainerDivDepth > 0 && spanDepth > 0) {
                                    if (spanDepth == romanSpanDepth) {
                                        romanSpanDepth = -1
                                    }
                                    if (spanDepth == tradSpanDepth) {
                                        tradSpanDepth = -1
                                    }
                                    if (spanDepth == verseSpanDepth) {
                                        val orig = cleanParsedText(origBuf)
                                        val trad = cleanParsedText(tradBuf)
                                        if (orig.isNotEmpty() && trad.isNotEmpty() && isTranslatableLyricLine(orig)) {
                                            pairs.add(BilingualLinePair(original = orig, translated = trad))
                                        }
                                        verseSpanDepth = -1
                                    }
                                    spanDepth--
                                }
                            }
                        }
                    }
                },
        )
    parser.write(html)
    parser.end()

    return LetrasParsedTranslationPage(
        canonicalHref = canonicalHref,
        headingTitle = cleanParsedText(h1Buf),
        pairs = pairs,
    )
}

internal fun isValidLetrasOriginalPath(
    path: String,
    expectedArtistSlug: String,
): Boolean {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    return segments.size == 2 &&
        segments[0].equals(expectedArtistSlug, ignoreCase = true) &&
        segments[1].isNotEmpty() &&
        !segments[1].equals("traducao.html", ignoreCase = true)
}

internal fun parseLetrasOriginalLyricsPage(html: String): LetrasParsedOriginalPage {
    var canonicalHref: String? = null
    val h1Buf = StringBuilder()
    var inFirstH1 = false
    var seenFirstH1 = false

    var divDepth = 0
    var lyricContainerDivDepth = -1
    var inParagraph = false
    var skipDepth = 0

    val lineBuf = StringBuilder()
    val lines = ArrayList<String>()

    fun flushLine() {
        val cleaned = cleanParsedText(lineBuf)
        lineBuf.setLength(0)
        if (cleaned.isNotEmpty()) {
            lines.add(cleaned)
        }
    }

    val parser =
        KsoupHtmlParser(
            handler =
                object : KsoupHtmlHandler {
                    override fun onOpenTag(
                        name: String,
                        attributes: Map<String, String>,
                        isImplied: Boolean,
                    ) {
                        val tag = name.lowercase()
                        if (tag == "script" || tag == "style" || tag == "noscript") {
                            skipDepth++
                            return
                        }
                        if (skipDepth > 0) return

                        when (tag) {
                            "link" -> {
                                if (canonicalHref == null &&
                                    attributes["rel"]?.trim()?.equals("canonical", ignoreCase = true) == true
                                ) {
                                    canonicalHref = attributes["href"]?.trim()
                                }
                            }

                            "h1" -> {
                                if (!seenFirstH1) {
                                    inFirstH1 = true
                                }
                            }

                            "div" -> {
                                divDepth++
                                if (lyricContainerDivDepth == -1) {
                                    val classes = splitClassList(attributes["class"])
                                    if ("lyric-original" in classes || "lyric-tra" in classes || "cnt-letra" in classes) {
                                        lyricContainerDivDepth = divDepth
                                    }
                                }
                            }

                            "p" -> {
                                if (lyricContainerDivDepth > 0) {
                                    inParagraph = true
                                    lineBuf.setLength(0)
                                }
                            }

                            "br" -> {
                                if (inFirstH1) h1Buf.append(' ')
                                if (inParagraph) flushLine()
                            }
                        }
                    }

                    override fun onText(text: String) {
                        if (skipDepth > 0) return
                        if (inFirstH1) {
                            h1Buf.append(text)
                        }
                        if (inParagraph) {
                            lineBuf.append(text)
                        }
                    }

                    override fun onCloseTag(
                        name: String,
                        isImplied: Boolean,
                    ) {
                        val tag = name.lowercase()
                        if (tag == "script" || tag == "style" || tag == "noscript") {
                            if (skipDepth > 0) skipDepth--
                            return
                        }
                        if (skipDepth > 0) return

                        when (tag) {
                            "h1" -> {
                                if (inFirstH1) {
                                    inFirstH1 = false
                                    seenFirstH1 = true
                                }
                            }

                            "p" -> {
                                if (inParagraph) {
                                    flushLine()
                                    inParagraph = false
                                }
                            }

                            "div" -> {
                                if (divDepth == lyricContainerDivDepth) {
                                    if (inParagraph) {
                                        flushLine()
                                        inParagraph = false
                                    }
                                    lyricContainerDivDepth = -1
                                }
                                if (divDepth > 0) divDepth--
                            }
                        }
                    }
                },
        )
    parser.write(html)
    parser.end()

    return LetrasParsedOriginalPage(
        canonicalHref = canonicalHref,
        headingTitle = cleanParsedText(h1Buf),
        lines = lines,
    )
}

internal fun parseLetrasCatalogLinks(
    html: String,
    artistSlug: String,
    expectedTitleKeys: Set<String>,
): List<String> =
    parseLetrasCatalogSongPaths(html, artistSlug, expectedTitleKeys)
        .map { songPath -> "${songPath.trimEnd('/')}/traducao.html" }

internal fun parseLetrasCatalogSongPaths(
    html: String,
    artistSlug: String,
    expectedTitleKeys: Set<String>,
): List<String> {
    if (expectedTitleKeys.isEmpty()) return emptyList()

    var currentHref: String? = null
    var currentTitleAttr: String? = null
    val anchorTextBuf = StringBuilder()
    val results = ArrayList<String>(2)
    val seen = HashSet<String>()

    val parser =
        KsoupHtmlParser(
            handler =
                object : KsoupHtmlHandler {
                    override fun onOpenTag(
                        name: String,
                        attributes: Map<String, String>,
                        isImplied: Boolean,
                    ) {
                        if (name.equals("a", ignoreCase = true)) {
                            currentHref = attributes["href"]
                            currentTitleAttr = attributes["title"]
                            anchorTextBuf.setLength(0)
                        }
                    }

                    override fun onText(text: String) {
                        if (currentHref != null) {
                            anchorTextBuf.append(text)
                        }
                    }

                    override fun onCloseTag(
                        name: String,
                        isImplied: Boolean,
                    ) {
                        if (!name.equals("a", ignoreCase = true)) return
                        val rawHref = currentHref
                        val titleAttr = currentTitleAttr
                        currentHref = null
                        currentTitleAttr = null
                        if (rawHref.isNullOrBlank()) return

                        val normalizedPath = normalizeLetrasPath(rawHref) ?: return
                        val segments = normalizedPath.trim('/').split('/').filter { it.isNotEmpty() }
                        if (segments.size != 2 || !segments[0].equals(artistSlug, ignoreCase = true)) {
                            return
                        }
                        val songSlug = segments[1]
                        if (songSlug.isEmpty() || songSlug.equals("traducao.html", ignoreCase = true)) {
                            return
                        }

                        val candidateTitle =
                            cleanParsedText(titleAttr.orEmpty()).ifEmpty { cleanParsedText(anchorTextBuf) }
                        if (candidateTitle.isEmpty()) return

                        if (letrasTitleMatches(expectedTitleKeys, candidateTitle)) {
                            val songPath = "/${segments[0]}/$songSlug/"
                            if (seen.add(songPath)) {
                                results.add(songPath)
                            }
                        }
                    }
                },
        )
    parser.write(html)
    parser.end()
    return results.take(2)
}

private fun splitClassList(classAttr: String?): Set<String> {
    if (classAttr.isNullOrBlank()) return emptySet()
    return classAttr
        .lowercase()
        .split(' ', '\t', '\n', '\r')
        .filterTo(HashSet()) { it.isNotEmpty() }
}

private fun cleanParsedText(raw: CharSequence): String =
    decodeHtmlEntities(raw.toString())
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()

private fun foldLetrasAccent(ch: Char): Char =
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
