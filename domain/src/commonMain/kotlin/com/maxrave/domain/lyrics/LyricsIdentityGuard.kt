package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics

private const val MAX_HEADER_INSPECT_LINES = 5
private const val MAX_HEADER_SYNC_TIME_MS = 15_000L
private const val MAX_STRICT_HEADER_TIME_MS = 6_000L

private val RICH_TAG_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val ALL_PAREN_GROUP_REGEX = Regex("""\s*[\(\[（［][^\)\]）］]*[\)\]）］]""")
private val DASH_HEADER_SEPARATORS = listOf(" - ", " – ", " — ", " － ")

private val EXPLICIT_TITLE_TAG_PREFIXES =
    listOf("title:", "song:", "track:", "título:", "titulo:", "música:", "musica:", "曲名:", "歌名:")
private val EXPLICIT_ARTIST_TAG_PREFIXES =
    listOf("artist:", "artista:", "singer:", "performer:", "歌手:", "演唱:")

private val SECTION_LABELS =
    setOf(
        "intro",
        "verse",
        "chorus",
        "pre-chorus",
        "bridge",
        "outro",
        "refrão",
        "refrao",
        "estrofe",
        "parte",
        "part",
        "act",
        "interlude",
        "instrumental",
    )

private val PLACEHOLDER_METADATA =
    setOf("unknown", "untitled", "null", "n/a", "none", "various artists")

enum class TextualConsensus {
    MATCH,
    CONFLICT,
    INCONCLUSIVE,
}

/**
 * Guards lyrics against wrong-song mismatches across providers and local cache:
 * 1. Detects embedded/header identity contradictions (`Wrong Title - Wrong Artist`,
 *    bilingual CJK headers such as `Lamento - Antonio Carlos Jobim（安东尼奥·卡洛斯·乔宾）`,
 *    or explicit `Title:` / `Artist:` metadata lines).
 * 2. Computes cross-provider vocabulary consensus so blind `RICH_SYNCED` providers
 *    (like BetterLyrics) cannot overwrite verified `LRCLIB` / `Spotify` lyrics with a
 *    completely different song.
 */
object LyricsIdentityGuard {
    /**
     * Returns `true` when [lyrics] carries an unambiguous metadata header or embedded tag
     * that contradicts [expectedTitle] / [expectedArtist].
     */
    fun contradictsTrack(
        lyrics: Lyrics?,
        expectedTitle: String?,
        expectedArtist: String?,
    ): Boolean {
        val lines = lyrics?.lines ?: return false
        if (lines.isEmpty()) return false
        if (expectedTitle.isNullOrBlank() && expectedArtist.isNullOrBlank()) return false

        val isUnsynced = lyrics.syncType != "LINE_SYNCED" && lyrics.syncType != "RICH_SYNCED"
        var nonBlankSeen = 0

        for (i in 0 until minOf(lines.size, MAX_HEADER_INSPECT_LINES)) {
            val line = lines[i]
            val plain =
                normalizeFullwidth(RICH_TAG_REGEX.replace(line.words, " "))
                    .replace(MULTI_SPACE_REGEX, " ")
                    .trim()
            if (plain.isEmpty() || plain == "♫" || plain == "♪") continue

            val startMs = line.startTimeMs.toLongOrNull() ?: 0L
            if (!isUnsynced && startMs > MAX_HEADER_SYNC_TIME_MS) break

            if (checkExplicitTagContradiction(plain, expectedTitle, expectedArtist)) {
                return true
            }

            if (nonBlankSeen <= 1 && (isUnsynced || startMs <= MAX_STRICT_HEADER_TIME_MS)) {
                if (checkDashHeaderContradiction(plain, expectedTitle, expectedArtist, isFirstNonBlank = nonBlankSeen == 0)) {
                    return true
                }
            }

            nonBlankSeen++
            if (nonBlankSeen >= 3) break
        }
        return false
    }

    /**
     * Returns `true` when embedded LRC/TTML tags (`[ti:]`, `[ar:]`, `<ttm:title>`, `<ttm:agent>`)
     * directly contradict [expectedTitle] or [expectedArtist].
     */
    fun embeddedTagsContradictTrack(
        embeddedTitle: String?,
        embeddedArtist: String?,
        expectedTitle: String?,
        expectedArtist: String?,
    ): Boolean {
        val cleanTitle = embeddedTitle?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in PLACEHOLDER_METADATA }
        if (cleanTitle != null && !expectedTitle.isNullOrBlank()) {
            if (!titlesCompatible(expectedTitle, cleanTitle)) {
                return true
            }
        }
        val cleanArtist = embeddedArtist?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in PLACEHOLDER_METADATA }
        if (cleanArtist != null && !expectedArtist.isNullOrBlank()) {
            if (!LyricsMetadataMatcher.artistsMatch(expectedArtist, cleanArtist, expectedTitle)) {
                return true
            }
        }
        return false
    }

    /**
     * Compares normalized token vocabulary between two lyrics candidates.
     *
     * Returns [TextualConsensus.CONFLICT] only when both sheets have substantial lyrics (`>= 6` tokens)
     * and share negligible vocabulary (different song), [TextualConsensus.MATCH] when they align on
     * the same song, or [TextualConsensus.INCONCLUSIVE] when either sheet is too short to judge.
     */
    fun checkTextualConsensus(
        first: Lyrics?,
        second: Lyrics?,
    ): TextualConsensus {
        val tokensA = extractLyricsTokens(first)
        val tokensB = extractLyricsTokens(second)
        if (tokensA.size < 6 || tokensB.size < 6) {
            return TextualConsensus.INCONCLUSIVE
        }

        var overlap = 0
        val smaller = if (tokensA.size <= tokensB.size) tokensA else tokensB
        val larger = if (tokensA.size <= tokensB.size) tokensB else tokensA
        for (token in smaller) {
            if (token in larger) overlap++
        }

        val minContainment = overlap.toFloat() / smaller.size.toFloat()
        val unionSize = tokensA.size + tokensB.size - overlap
        val jaccard = if (unionSize > 0) overlap.toFloat() / unionSize.toFloat() else 0f

        return if (minContainment >= 0.25f || jaccard >= 0.18f) {
            TextualConsensus.MATCH
        } else {
            TextualConsensus.CONFLICT
        }
    }

    private fun checkExplicitTagContradiction(
        plain: String,
        expectedTitle: String?,
        expectedArtist: String?,
    ): Boolean {
        val lower = plain.lowercase()
        for (prefix in EXPLICIT_TITLE_TAG_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val value = plain.substring(prefix.length).trim()
                if (value.length >= 2 &&
                    value.lowercase() !in PLACEHOLDER_METADATA &&
                    !expectedTitle.isNullOrBlank() &&
                    !titlesCompatible(expectedTitle, value)
                ) {
                    return true
                }
            }
        }
        for (prefix in EXPLICIT_ARTIST_TAG_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val value = plain.substring(prefix.length).trim()
                if (value.length >= 2 &&
                    value.lowercase() !in PLACEHOLDER_METADATA &&
                    !expectedArtist.isNullOrBlank() &&
                    !LyricsMetadataMatcher.artistsMatch(expectedArtist, value, expectedTitle)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun checkDashHeaderContradiction(
        plain: String,
        expectedTitle: String?,
        expectedArtist: String?,
        isFirstNonBlank: Boolean,
    ): Boolean {
        for (sep in DASH_HEADER_SEPARATORS) {
            val idx = plain.indexOf(sep)
            if (idx <= 0 || idx + sep.length >= plain.length) continue
            val left = plain.substring(0, idx).trim()
            val right = plain.substring(idx + sep.length).trim()
            if (left.length !in 2..65 || right.length !in 2..85) continue

            val leftLower = left.lowercase()
            if (SECTION_LABELS.any { leftLower == it || leftLower.startsWith("$it ") }) continue

            val leftMatchesTitle = !expectedTitle.isNullOrBlank() && titlesCompatible(expectedTitle, left)
            val rightMatchesTitle = !expectedTitle.isNullOrBlank() && titlesCompatible(expectedTitle, right)
            val leftMatchesArtist =
                !expectedArtist.isNullOrBlank() && LyricsMetadataMatcher.artistsMatch(expectedArtist, left, expectedTitle)
            val rightMatchesArtist =
                !expectedArtist.isNullOrBlank() && LyricsMetadataMatcher.artistsMatch(expectedArtist, right, expectedTitle)

            // Header already matches the expected track.
            if ((leftMatchesTitle && rightMatchesArtist) || (leftMatchesArtist && rightMatchesTitle)) {
                return false
            }

            // Strong structural metadata cues: parenthetical bilingual alias (e.g. CJK in parens)
            // or non-Latin metadata mismatch, or clean first-line `Title - Artist` shape.
            val hasBilingualParen =
                ('(' in right && ')' in right) || ('(' in left && ')' in left)
            val isStrictFirstLineHeader =
                isFirstNonBlank &&
                    plain.length <= 80 &&
                    !plain.endsWith(',') &&
                    !plain.endsWith(';') &&
                    '?' !in plain &&
                    '!' !in plain &&
                    wordCount(left) in 1..5 &&
                    wordCount(right) in 1..7

            if (!hasBilingualParen && !isStrictFirstLineHeader) continue

            // Neither side matches the expected title at all -> another song's header.
            if (!expectedTitle.isNullOrBlank() && !leftMatchesTitle && !rightMatchesTitle) {
                return true
            }
            // Title matches one side, but the other side explicitly names a different artist.
            if (hasBilingualParen && !expectedArtist.isNullOrBlank()) {
                if ((leftMatchesTitle && !rightMatchesArtist) || (rightMatchesTitle && !leftMatchesArtist)) {
                    return true
                }
            }
        }
        return false
    }

    internal fun titlesCompatible(
        expectedTitle: String,
        candidateTitle: String,
    ): Boolean {
        if (LyricsMetadataMatcher.titlesMatch(expectedTitle, candidateTitle)) return true
        val expCore = LyricsMetadataMatcher.normalizeAlnum(ALL_PAREN_GROUP_REGEX.replace(expectedTitle, " ").trim())
        val candCore = LyricsMetadataMatcher.normalizeAlnum(ALL_PAREN_GROUP_REGEX.replace(candidateTitle, " ").trim())
        return expCore.length >= 3 && expCore == candCore
    }

    private fun extractLyricsTokens(lyrics: Lyrics?): Set<String> {
        val lines = lyrics?.lines ?: return emptySet()
        val tokens = HashSet<String>(64)
        val wordBuf = StringBuilder()

        fun flushWord() {
            if (wordBuf.length >= 2) {
                tokens.add(wordBuf.toString())
            }
            wordBuf.setLength(0)
        }

        for (line in lines) {
            val plain = RICH_TAG_REGEX.replace(line.words, " ")
            for (ch in plain.lowercase()) {
                val folded = foldConsensusAccent(ch)
                when {
                    isCjkChar(folded) -> {
                        flushWord()
                        tokens.add(folded.toString())
                    }
                    folded.isLetterOrDigit() -> wordBuf.append(folded)
                    else -> flushWord()
                }
            }
            flushWord()
        }
        return tokens
    }

    private fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||
            code in 0x4E00..0x9FFF ||
            code in 0xAC00..0xD7AF
    }

    private fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else if (!ch.isWhitespace()) {
                inWord = false
            } else {
                inWord = false
            }
        }
        return count
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
}

private fun foldConsensusAccent(ch: Char): Char =
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
