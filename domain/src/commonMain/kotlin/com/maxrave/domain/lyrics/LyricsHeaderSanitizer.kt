package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics

private const val HEADER_TITLE_ARTIST_MAX_TIME_MS = 15_000L
private const val HEADER_PAIR_MAX_TIME_MS = 5_000L
private const val HEAD_SCAN_LIMIT = 4
private const val CREDIT_HEAD_SCAN_LIMIT = 5
private const val CREDIT_TAIL_SCAN_LIMIT = 2

private val RICH_SYNC_TIME_TAG_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val NOISE_SUFFIX_REGEX =
    Regex(
        """\s*[\(\[](?:official\s*(?:music\s*)?(?:video|audio|lyric\s*video|visualizer)|lyrics?|hd|4k|remastered(?:\s+\d{4})?|explicit|clean|radio\s*edit|single\s*version|album\s*version|feat\.?[^\)\]]*|ft\.?[^\)\]]*)[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val FEAT_TAIL_REGEX = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
private val TITLE_ARTIST_SEPARATORS = listOf(" - ", " – ", " — ", " － ", " | ", " / ", " ／ ", " • ", " : ", " ： ")
private val PAREN_GROUP_REGEX = Regex("""[\(\[（［]([^\)\]）］]+)[\)\]）］]""")
private val ARTIST_SPLIT_CHARS_REGEX = Regex("""[,&•/／・、;]""")
private val EXPLICIT_CREDIT_PREFIXES =
    listOf(
        "lyrics by:",
        "lyrics by ",
        "lyric by:",
        "lyric by ",
        "written by:",
        "written by ",
        "composed by:",
        "composed by ",
        "produced by:",
        "produced by ",
        "arranged by:",
        "arranged by ",
        "synced by:",
        "synced by ",
        "lrc by:",
        "lrc by ",
        "letra:",
        "letra por:",
        "letra por ",
        "composição:",
        "composicao:",
        "compositor:",
        "tradução por:",
        "traducao por:",
        "title:",
        "artist:",
        "album:",
        "作词",
        "作曲",
        "编曲",
        "制作",
        "歌词",
        "词：",
        "词:",
        "曲：",
        "曲:",
    )

/**
 * Strips leading/trailing metadata header lines (e.g. `Manic Pixie Dream Girl - EASHA` at 00:00,
 * bilingual headers like `少女A (Young Girl A) - 椎名もた (ぽわぽわP) / 鏡音鈴 (鏡音リン)`,
 * consecutive `Title` / `Artist` header pairs, or `Lyrics by:` / `Written by:` credits) that
 * community LRC/RichSync providers sometimes embed as timed lines.
 *
 * Designed to run off the Main thread in `Dispatchers.Default` and be strictly conservative so real
 * sung choruses that happen to contain the song title are never removed.
 */
object LyricsHeaderSanitizer {
    fun sanitize(
        lyrics: Lyrics,
        songTitle: String?,
        artistName: String?,
    ): Lyrics {
        val originalLines = lyrics.lines
        if (originalLines.isNullOrEmpty() || originalLines.size <= 1) return lyrics

        val titleVariants = extractTitleVariants(songTitle.orEmpty())
        val primaryArtists = splitArtists(artistName.orEmpty())
        val isUnsynced = lyrics.syncType != "LINE_SYNCED" && lyrics.syncType != "RICH_SYNCED"

        val dropIndices = HashSet<Int>(4)
        val plainTexts =
            originalLines.map { line ->
                stripRichSyncTags(line.words)
            }

        // 1. Consecutive [Title, Artist] or [Artist, Title] pair at the very start (<= 5s).
        if (titleVariants.isNotEmpty() && primaryArtists.isNotEmpty() && originalLines.size >= 3) {
            val t0 = originalLines[0].startTimeMs.toLongOrNull() ?: 0L
            val t1 = originalLines[1].startTimeMs.toLongOrNull() ?: 0L
            if (isUnsynced || (t0 <= HEADER_PAIR_MAX_TIME_MS && t1 <= HEADER_PAIR_MAX_TIME_MS)) {
                val p0IsTitle = matchesTitleVariants(plainTexts[0], titleVariants)
                val p1IsArtist = matchesAnyArtistText(plainTexts[1], primaryArtists)
                val p0IsArtist = matchesAnyArtistText(plainTexts[0], primaryArtists)
                val p1IsTitle = matchesTitleVariants(plainTexts[1], titleVariants)
                if ((p0IsTitle && p1IsArtist) || (p0IsArtist && p1IsTitle)) {
                    dropIndices.add(0)
                    dropIndices.add(1)
                }
            }
        }

        // 2. Combined "Title - Artist" / "Artist - Title" or explicit credits near the start.
        val headLimit = minOf(originalLines.size - 1, CREDIT_HEAD_SCAN_LIMIT)
        for (i in 0 until headLimit) {
            if (i in dropIndices) continue
            val plain = plainTexts[i]
            if (plain.isEmpty() || plain == "♫" || plain == "♪") continue
            val startMs = originalLines[i].startTimeMs.toLongOrNull() ?: 0L

            if (isExplicitCreditLine(plain) && (isUnsynced || startMs <= HEADER_TITLE_ARTIST_MAX_TIME_MS * 2)) {
                dropIndices.add(i)
                continue
            }

            if (i < HEAD_SCAN_LIMIT &&
                (isUnsynced || startMs <= HEADER_TITLE_ARTIST_MAX_TIME_MS) &&
                titleVariants.isNotEmpty() &&
                primaryArtists.isNotEmpty() &&
                isTitleArtistHeaderLine(plain, titleVariants, primaryArtists)
            ) {
                dropIndices.add(i)
            }
        }

        // 3. Explicit technical credits in the trailing 2 lines (e.g. "Synced by X").
        val tailStart = (originalLines.size - CREDIT_TAIL_SCAN_LIMIT).coerceAtLeast(headLimit)
        for (i in tailStart until originalLines.size) {
            if (isExplicitCreditLine(plainTexts[i])) {
                dropIndices.add(i)
            }
        }

        if (dropIndices.isEmpty() || dropIndices.size >= originalLines.size) {
            return lyrics
        }

        val cleanedLines =
            originalLines.filterIndexed { index, _ -> index !in dropIndices }
        return if (cleanedLines.isEmpty()) {
            lyrics
        } else {
            lyrics.copy(lines = cleanedLines)
        }
    }

    /**
     * Strips isolated trailing punctuation (`,`, `;`, `.`, `、`, `，`, `；`, `。`) from translated
     * lyric lines while preserving expressive punctuation (`?`, `!`, `...`, `…`).
     */
    fun cleanTranslatedLinePunctuation(text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return result
        while (result.isNotEmpty()) {
            val last = result.last()
            val shouldStrip =
                when (last) {
                    ',', ';', '、', '，', '；', '。' -> true
                    '.' -> !result.endsWith("..")
                    else -> false
                }
            if (!shouldStrip) break
            result = result.dropLast(1).trimEnd()
        }
        return result
    }

    fun sanitizeTranslatedLyrics(lyrics: Lyrics): Lyrics {
        val lines = lyrics.lines ?: return lyrics
        var changed = false
        val cleaned =
            lines.map { line ->
                val updated = cleanTranslatedLinePunctuation(line.words)
                if (updated != line.words) {
                    changed = true
                    line.copy(words = updated)
                } else {
                    line
                }
            }
        return if (changed) lyrics.copy(lines = cleaned) else lyrics
    }

    private fun isTitleArtistHeaderLine(
        plainLine: String,
        titleVariants: Set<String>,
        artists: Set<String>,
    ): Boolean {
        val normalizedLine = normalizeFullwidth(plainLine)
        for (sep in TITLE_ARTIST_SEPARATORS) {
            var searchFrom = 0
            while (searchFrom < normalizedLine.length) {
                val idx = normalizedLine.indexOf(sep, searchFrom)
                if (idx <= 0 || idx + sep.length >= normalizedLine.length) break
                val left = normalizedLine.substring(0, idx)
                val right = normalizedLine.substring(idx + sep.length)
                if ((matchesTitleVariants(left, titleVariants) && matchesAnyArtistText(right, artists)) ||
                    (matchesAnyArtistText(left, artists) && matchesTitleVariants(right, titleVariants))
                ) {
                    return true
                }
                searchFrom = idx + sep.length
            }
        }
        // Also handle compact "Title-Artist" / "Title - Artist" with irregular spacing.
        for (i in 1 until normalizedLine.length - 1) {
            val ch = normalizedLine[i]
            if (ch == '-' || ch == '–' || ch == '—') {
                val left = normalizedLine.substring(0, i)
                val right = normalizedLine.substring(i + 1)
                if ((matchesTitleVariants(left, titleVariants) && matchesAnyArtistText(right, artists)) ||
                    (matchesAnyArtistText(left, artists) && matchesTitleVariants(right, titleVariants))
                ) {
                    return true
                }
            }
        }
        // Handle "<Title> by <Artist>" header lines.
        val byIndex = normalizedLine.lowercase().indexOf(" by ")
        if (byIndex > 0 && byIndex + 4 < normalizedLine.length) {
            val left = normalizedLine.substring(0, byIndex)
            val right = normalizedLine.substring(byIndex + 4)
            if (matchesTitleVariants(left, titleVariants) && matchesAnyArtistText(right, artists)) {
                return true
            }
        }
        return false
    }

    private fun isExplicitCreditLine(plainLine: String): Boolean {
        val lower = plainLine.trim().lowercase()
        if (lower.isEmpty()) return false
        return EXPLICIT_CREDIT_PREFIXES.any { prefix -> lower.startsWith(prefix) }
    }

    private fun extractTitleVariants(rawTitle: String): Set<String> {
        if (rawTitle.isBlank()) return emptySet()
        val normalized = normalizeFullwidth(rawTitle)
        val stripped = stripNoiseSuffixes(normalized)
        val variants = LinkedHashSet<String>(4)
        normalizeComparable(stripped).takeIf { it.isNotEmpty() }?.let(variants::add)
        // Extract outside-parentheses and inside-parentheses variants (e.g. "少女A (Young Girl A)").
        val outside = PAREN_GROUP_REGEX.replace(stripped, " ").trim()
        normalizeComparable(outside).takeIf { it.isNotEmpty() }?.let(variants::add)
        PAREN_GROUP_REGEX.findAll(stripped).forEach { match ->
            val inside = stripNoiseSuffixes(match.groupValues[1])
            normalizeComparable(inside).takeIf { it.length >= 2 }?.let(variants::add)
        }
        return variants
    }

    private fun matchesTitleVariants(
        candidateText: String,
        expectedVariants: Set<String>,
    ): Boolean {
        if (expectedVariants.isEmpty() || candidateText.isBlank()) return false
        val candidateVariants = extractTitleVariants(candidateText)
        return candidateVariants.any { it in expectedVariants }
    }

    private fun splitArtists(artistName: String): Set<String> {
        if (artistName.isBlank()) return emptySet()
        val normalized = normalizeFullwidth(artistName)
        val results = LinkedHashSet<String>(6)
        fun addToken(token: String) {
            val cleaned = stripNoiseSuffixes(token)
            normalizeComparable(cleaned).takeIf { it.isNotEmpty() }?.let(results::add)
            val outside = PAREN_GROUP_REGEX.replace(cleaned, " ").trim()
            normalizeComparable(outside).takeIf { it.isNotEmpty() }?.let(results::add)
            PAREN_GROUP_REGEX.findAll(cleaned).forEach { match ->
                normalizeComparable(stripNoiseSuffixes(match.groupValues[1]))
                    .takeIf { it.length >= 2 }
                    ?.let(results::add)
            }
        }
        addToken(normalized)
        ARTIST_SPLIT_CHARS_REGEX.split(normalized).forEach(::addToken)
        return results
    }

    private fun matchesAnyArtistText(
        candidateText: String,
        expectedArtists: Set<String>,
    ): Boolean {
        if (expectedArtists.isEmpty() || candidateText.isBlank()) return false
        val candidateParts = splitArtists(candidateText)
        return candidateParts.any { it in expectedArtists }
    }

    private fun normalizeFullwidth(text: String): String =
        buildString(text.length) {
            for (ch in text) {
                append(
                    when (ch) {
                        '（' -> '('
                        '）' -> ')'
                        '［' -> '['
                        '］' -> ']'
                        '／' -> '/'
                        '－' -> '-'
                        '：' -> ':'
                        '　' -> ' '
                        else -> ch
                    },
                )
            }
        }

    internal fun stripRichSyncTags(text: String): String =
        RICH_SYNC_TIME_TAG_REGEX
            .replace(text, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

    private fun stripNoiseSuffixes(text: String): String =
        NOISE_SUFFIX_REGEX
            .replace(text, "")
            .replace(FEAT_TAIL_REGEX, "")
            .trim()

    internal fun normalizeComparable(text: String): String {
        if (text.isBlank()) return ""
        return buildString(text.length) {
            for (ch in text.lowercase()) {
                val folded = foldAccent(ch)
                if (folded.isLetterOrDigit()) {
                    append(folded)
                }
            }
        }
    }
}

private fun foldAccent(ch: Char): Char =
    when (ch) {
        'á', 'à', 'â', 'ã', 'ä', 'å', 'ă' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'õ', 'ö', 'ø' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ç' -> 'c'
        'ñ' -> 'n'
        'ş', 'ș' -> 's'
        'ţ', 'ț' -> 't'
        'ý', 'ÿ' -> 'y'
        else -> ch
    }
