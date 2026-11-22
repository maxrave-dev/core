package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics

private val RICH_TAG_STRIP_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val NON_WORD_SPLIT_REGEX = Regex("""[\s,.;:!?'"()\[\]{}/\\|<>@#$%^&*_+=~`-]+""")

private val BRACKETED_TOKEN_REGEX = Regex("""[\(\[]\s*([^\)\]]+?)\s*[\)\]]""")

private val EXPLICIT_INSTRUMENTAL_TITLE_TAG_REGEX =
    Regex(
        """[\(\[]\s*(?:instrumental|off\s*vocal|karaoke(?:\s+version)?|no\s*vocals?|vocaless|beat\s*only|accompaniment|playback)\s*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val TRAILING_INSTRUMENTAL_DASH_REGEX =
    Regex(
        """\s*[-–—]\s*(?:instrumental|off\s*vocal|karaoke(?:\s+version)?|no\s*vocals?|beat\s*only)\s*$""",
        RegexOption.IGNORE_CASE,
    )
private val TYPE_BEAT_REGEX = Regex("""\btype\s+beat\b""", RegexOption.IGNORE_CASE)

private val ASR_SOUND_CUE_KEYWORDS =
    setOf(
        "music",
        "musica",
        "muzica",
        "musique",
        "musik",
        "muziek",
        "muzyka",
        "musiikki",
        "музыка",
        "音楽",
        "음악",
        "音乐",
        "संगीत",
        "applause",
        "aplausos",
        "applausi",
        "applaudissements",
        "aplauze",
        "laughter",
        "risos",
        "risadas",
        "rires",
        "lachen",
        "risas",
        "cheering",
        "cheers",
        "singing",
        "canto",
        "cantando",
        "vocalizing",
        "humming",
        "foreign",
        "noise",
        "noisy",
        "silence",
        "silencio",
        "ambient",
        "sound",
        "audio",
        "groan",
        "sigh",
    )

private val INSTRUMENTAL_MARKER_KEYWORDS =
    setOf(
        "instrumental",
        "intro",
        "outro",
        "interlude",
        "interludio",
        "solo",
        "break",
        "pausa",
        "pausa instrumental",
    )

private const val MAX_ASR_CUE_RATIO = 0.15f
private const val MIN_REAL_LINES_WITH_ASR_CUES = 3
private const val MIN_AVG_WORDS_WITH_ASR_CUES = 3.2f
private const val MAX_SHORT_LINE_RATIO_WITH_ASR_CUES = 0.55f

private val VALID_SINGLE_LETTER_WORDS = setOf("a", "e", "i", "o", "u", "y", "é", "à", "ó")

/**
 * Detects unambiguous non-vocal / instrumental track titles so the app does not attach
 * another artist's lyrics to beats, karaoke, or instrumental releases.
 */
object LyricsInstrumentalDetector {
    fun isExplicitlyInstrumentalTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val trimmed = title.trim()
        if (trimmed.equals("instrumental", ignoreCase = true)) return true
        if (EXPLICIT_INSTRUMENTAL_TITLE_TAG_REGEX.containsMatchIn(trimmed)) return true
        if (TRAILING_INSTRUMENTAL_DASH_REGEX.containsMatchIn(trimmed)) return true
        if (TYPE_BEAT_REGEX.containsMatchIn(trimmed)) return true
        return false
    }
}

/**
 * Guards lyrics quality against corrupted YouTube automatic speech recognition (ASR) captions
 * (both tagged `[Muzică]`/`[Music]`/`[Applause]` and tagless garbled fragments like `.`, `soha`,
 * `am`, `me f`, `de`) and instrumental-only placeholders.
 */
object LyricsContentQualityGuard {
    fun isAsrSoundCueLine(rawLine: String): Boolean =
        matchesCueOnlyLine(rawLine, ASR_SOUND_CUE_KEYWORDS)

    fun isInstrumentalOnlyMarkerLine(rawLine: String): Boolean {
        val plain = stripRichTags(rawLine)
        if (plain.isEmpty()) return false
        val withoutEmoji = plain.replace("🎶", "").replace("🎵", "")
        if (withoutEmoji.all { it == '♫' || it == '♪' || it == '•' || it == '·' || it == '-' || it.isWhitespace() }) {
            return true
        }
        return matchesCueOnlyLine(rawLine, INSTRUMENTAL_MARKER_KEYWORDS)
    }

    /**
     * Returns `true` when [lyrics] looks like genuine sung lyrics rather than empty markers,
     * pure instrumental cues, or broken ASR captions (with or without `[Muzică]`/`[Music]` tags).
     */
    fun isAcceptableContent(lyrics: Lyrics?): Boolean {
        val lines = lyrics?.lines ?: return false
        if (lyrics.error || lines.isEmpty()) return false

        var asrCueCount = 0
        var instrumentalMarkerCount = 0
        var punctuationOnlyCount = 0
        val realLines = ArrayList<String>(lines.size)

        for (line in lines) {
            val plain = stripRichTags(line.words)
            if (plain.isEmpty()) continue
            when {
                isAsrSoundCueLine(plain) -> asrCueCount++
                isInstrumentalOnlyMarkerLine(plain) -> instrumentalMarkerCount++
                hasReadableChar(plain) -> realLines.add(plain)
                else -> punctuationOnlyCount++
            }
        }

        if (realLines.isEmpty()) return false

        val wordCounts = realLines.map(::effectiveWordCount)
        val avgWords = wordCounts.sum().toFloat() / realLines.size.toFloat()
        val shortLineRatio = wordCounts.count { it <= 2 }.toFloat() / realLines.size.toFloat()

        // Rule A: Any ASR sound cue present (`[Muzică]`, `[Music]`, `[Applause]`, etc.).
        if (asrCueCount > 0) {
            val nonBlankCount = asrCueCount + instrumentalMarkerCount + punctuationOnlyCount + realLines.size
            if (asrCueCount >= 2 && asrCueCount.toFloat() / nonBlankCount.toFloat() >= MAX_ASR_CUE_RATIO) {
                return false
            }
            if (realLines.size < MIN_REAL_LINES_WITH_ASR_CUES) {
                return false
            }
            if (avgWords < MIN_AVG_WORDS_WITH_ASR_CUES) {
                return false
            }
            if (shortLineRatio >= MAX_SHORT_LINE_RATIO_WITH_ASR_CUES) {
                return false
            }
        }

        // Rule B: Tagless garbled/phonetic ASR (e.g. ".", "min tamb passa", "soha", "am", "me f", "de").
        // Guarded by uniqueRatio and non-CJK check so repetitive real choruses and CJK songs are never rejected.
        val cjkLineCount = realLines.count(::isCjkLine)
        val isCjkDominant = cjkLineCount.toFloat() / realLines.size.toFloat() >= 0.40f
        if (!isCjkDominant && realLines.size >= 5) {
            val uniqueCount = realLines.map { it.lowercase() }.distinct().size
            val uniqueRatio = uniqueCount.toFloat() / realLines.size.toFloat()
            val tinyOrFragmentCount = realLines.count(::isTinyOrFragmentLine)
            val tinyOrFragmentRatio = tinyOrFragmentCount.toFloat() / realLines.size.toFloat()
            val totalContentLines = realLines.size + punctuationOnlyCount
            val junkLineRatio = (punctuationOnlyCount + tinyOrFragmentCount).toFloat() / totalContentLines.toFloat()

            if (avgWords <= 2.3f && shortLineRatio >= 0.65f && tinyOrFragmentRatio >= 0.25f && uniqueRatio >= 0.55f) {
                return false
            }
            if (tinyOrFragmentRatio >= 0.45f && avgWords <= 2.6f && uniqueRatio >= 0.50f) {
                return false
            }
            if (junkLineRatio >= 0.45f && avgWords <= 2.5f && uniqueRatio >= 0.50f) {
                return false
            }
        }

        return true
    }

    /**
     * Strips isolated YouTube closed-caption audio cue lines (like `[Music]` at 00:02) from
     * otherwise acceptable human-subtitled lyrics so they do not clutter playback.
     */
    fun stripAsrSoundCueLines(lyrics: Lyrics): Lyrics {
        val lines = lyrics.lines ?: return lyrics
        if (lines.size <= 1) return lyrics
        var removedAny = false
        val filtered =
            lines.filter { line ->
                val drop = isAsrSoundCueLine(line.words)
                if (drop) removedAny = true
                !drop
            }
        if (!removedAny || filtered.isEmpty()) return lyrics
        return lyrics.copy(lines = filtered)
    }

    private fun isTinyOrFragmentLine(plainLine: String): Boolean {
        val tokens = NON_WORD_SPLIT_REGEX.split(plainLine).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val letterCount = plainLine.count { it.isLetterOrDigit() }
        if (letterCount <= 3) return true
        // Truncated phonetic fragment containing a dangling single consonant (e.g. "me f", "t b").
        if (tokens.size <= 3 && tokens.any { it.length == 1 && it.lowercase() !in VALID_SINGLE_LETTER_WORDS }) {
            return true
        }
        return false
    }

    private fun isCjkLine(plainLine: String): Boolean =
        plainLine.count { ch ->
            val code = ch.code
            code in 0x3040..0x30FF ||
                code in 0x4E00..0x9FFF ||
                code in 0xAC00..0xD7AF
        } >= 2

    private fun matchesCueOnlyLine(
        rawLine: String,
        keywords: Set<String>,
    ): Boolean {
        val plain = stripRichTags(rawLine)
        if (plain.isEmpty()) return false
        val matches = BRACKETED_TOKEN_REGEX.findAll(plain).toList()
        if (matches.isEmpty()) return false
        val remainder = BRACKETED_TOKEN_REGEX.replace(plain, "").trim()
        if (remainder.any { it.isLetterOrDigit() }) return false
        return matches.all { match ->
            val inner = foldCueText(match.groupValues[1])
            inner.isNotEmpty() &&
                (inner in keywords || inner.split(' ').all { token -> token in keywords })
        }
    }

    private fun foldCueText(raw: String): String =
        buildString(raw.length) {
            var prevSpace = true
            for (ch in raw.replace("🎶", "").replace("🎵", "").lowercase()) {
                val folded = foldAccent(ch)
                when {
                    folded.isLetterOrDigit() -> {
                        append(folded)
                        prevSpace = false
                    }
                    folded == '♫' || folded == '♪' -> Unit
                    !prevSpace -> {
                        append(' ')
                        prevSpace = true
                    }
                }
            }
        }.trim()

    private fun stripRichTags(text: String): String =
        RICH_TAG_STRIP_REGEX
            .replace(text, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

    private fun hasReadableChar(text: String): Boolean =
        text.any { it.isLetterOrDigit() }

    private fun effectiveWordCount(plainLine: String): Int {
        val spaceWords = NON_WORD_SPLIT_REGEX.split(plainLine).count { it.isNotEmpty() }
        // CJK/Japanese/Korean lines rarely use spaces; prevent false fragmentation flags.
        val cjkChars =
            plainLine.count { ch ->
                val code = ch.code
                code in 0x3040..0x30FF || // Hiragana & Katakana
                    code in 0x4E00..0x9FFF || // CJK Unified Ideographs
                    code in 0xAC00..0xD7AF // Hangul Syllables
            }
        return if (cjkChars >= 4) maxOf(spaceWords, cjkChars / 2) else spaceWords
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
