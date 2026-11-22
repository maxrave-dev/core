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
private val TITLE_ARTIST_SEPARATORS = listOf(" - ", " – ", " — ", " | ", " / ", " • ", " : ")
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

        val normTitle = normalizeComparable(stripNoiseSuffixes(songTitle.orEmpty()))
        val primaryArtists = splitArtists(artistName.orEmpty())
        val isUnsynced = lyrics.syncType != "LINE_SYNCED" && lyrics.syncType != "RICH_SYNCED"

        val dropIndices = HashSet<Int>(4)
        val plainTexts =
            originalLines.map { line ->
                stripRichSyncTags(line.words)
            }

        // 1. Consecutive [Title, Artist] or [Artist, Title] pair at the very start (<= 5s).
        if (normTitle.isNotEmpty() && primaryArtists.isNotEmpty() && originalLines.size >= 3) {
            val t0 = originalLines[0].startTimeMs.toLongOrNull() ?: 0L
            val t1 = originalLines[1].startTimeMs.toLongOrNull() ?: 0L
            if (isUnsynced || (t0 <= HEADER_PAIR_MAX_TIME_MS && t1 <= HEADER_PAIR_MAX_TIME_MS)) {
                val p0 = normalizeComparable(stripNoiseSuffixes(plainTexts[0]))
                val p1 = normalizeComparable(stripNoiseSuffixes(plainTexts[1]))
                val pairDirect = p0 == normTitle && matchesAnyArtist(p1, primaryArtists)
                val pairSwapped = matchesAnyArtist(p0, primaryArtists) && p1 == normTitle
                if (pairDirect || pairSwapped) {
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
                normTitle.isNotEmpty() &&
                primaryArtists.isNotEmpty() &&
                isTitleArtistHeaderLine(plain, normTitle, primaryArtists)
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

    private fun isTitleArtistHeaderLine(
        plainLine: String,
        normTitle: String,
        artists: List<String>,
    ): Boolean {
        val cleaned = stripNoiseSuffixes(plainLine)
        for (sep in TITLE_ARTIST_SEPARATORS) {
            val idx = cleaned.indexOf(sep)
            if (idx > 0 && idx + sep.length < cleaned.length) {
                val left = normalizeComparable(cleaned.substring(0, idx))
                val right = normalizeComparable(cleaned.substring(idx + sep.length))
                if (left.isEmpty() || right.isEmpty()) continue
                if ((left == normTitle && matchesAnyArtist(right, artists)) ||
                    (matchesAnyArtist(left, artists) && right == normTitle)
                ) {
                    return true
                }
            }
        }
        // Also handle compact "Title-Artist" / "Title - Artist" with irregular spacing.
        val compactDashIdx = cleaned.indexOfFirst { it == '-' || it == '–' || it == '—' }
        if (compactDashIdx > 0 && compactDashIdx + 1 < cleaned.length) {
            val left = normalizeComparable(cleaned.substring(0, compactDashIdx))
            val right = normalizeComparable(cleaned.substring(compactDashIdx + 1))
            if ((left == normTitle && matchesAnyArtist(right, artists)) ||
                (matchesAnyArtist(left, artists) && right == normTitle)
            ) {
                return true
            }
        }
        // Handle "<Title> by <Artist>" header lines.
        val byIndex = cleaned.lowercase().indexOf(" by ")
        if (byIndex > 0 && byIndex + 4 < cleaned.length) {
            val left = normalizeComparable(cleaned.substring(0, byIndex))
            val right = normalizeComparable(cleaned.substring(byIndex + 4))
            if (left == normTitle && matchesAnyArtist(right, artists)) {
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

    private fun splitArtists(artistName: String): List<String> {
        if (artistName.isBlank()) return emptyList()
        val results = ArrayList<String>(4)
        val fullNorm = normalizeComparable(stripNoiseSuffixes(artistName))
        if (fullNorm.isNotEmpty()) results.add(fullNorm)
        artistName
            .split(',', '&', '•', '/')
            .map { normalizeComparable(stripNoiseSuffixes(it)) }
            .filter { it.isNotEmpty() }
            .forEach { if (it !in results) results.add(it) }
        return results
    }

    private fun matchesAnyArtist(
        candidateNorm: String,
        artists: List<String>,
    ): Boolean {
        if (candidateNorm.isEmpty()) return false
        return artists.any { candidateNorm == it }
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
