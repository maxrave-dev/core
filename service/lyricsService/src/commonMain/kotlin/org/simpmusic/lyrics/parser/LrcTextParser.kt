package org.simpmusic.lyrics.parser

import com.maxrave.domain.extension.decodeHtmlEntities
import com.maxrave.logger.Logger
import org.simpmusic.lyrics.domain.Lyrics

private const val TAG = "LrcTextParser"

private val LRC_ID_TAG_REGEX = Regex("""\[([A-Za-z#][^:\]]*):(.*)\]""")
private val LRC_LINE_TIME_LIKE_REGEX = Regex("""\[\d+:\d[\d:.]*\]""")
private val LRC_LINE_TIME_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
private val RICH_SYNC_LINE_REGEX = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\](.+)$""")
private val RICH_SYNC_WORD_TAG_REGEX = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
private val VOICE_MARKER_REGEX = Regex("""^v\d+:""")

private fun parseFractionMs(fraction: String): Long =
    when (fraction.length) {
        0 -> 0L
        1 -> (fraction.toLongOrNull() ?: 0L) * 100L
        2 -> (fraction.toLongOrNull() ?: 0L) * 10L
        else -> (fraction.take(3).toLongOrNull() ?: 0L)
    }

private fun MatchResult.parseLrcTimeMs(): Long? {
    val minutes = groupValues[1].toLongOrNull() ?: return null
    val seconds = groupValues[2].toLongOrNull() ?: return null
    if (seconds >= 60L) return null
    return minutes * 60_000L + seconds * 1000L + parseFractionMs(groupValues[3])
}

/**
 * Tolerant line-level LRC parser:
 * - Supports `[m:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mmm:ss.xxx]`, and `[mm:ss:xx]`
 *   with proper sub-second scaling (`1 -> *100`, `2 -> *10`, `3 -> ms`).
 * - Supports multiple timestamps per line, `[offset:+/-ms]`, and empty timed lines (`♫`).
 * - Ignores metadata ID tags and returns lines sorted ascending by timestamp.
 */
fun parseLrc(data: String): Lyrics {
    if (data.isBlank()) {
        return Lyrics(lyrics = Lyrics.LyricsX(lines = emptyList(), syncType = "LINE_SYNCED"))
    }
    var offsetMs = 0L
    val parsed = ArrayList<Pair<Long, String>>()

    for (rawLine in data.lines()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue

        val tagMatch = LRC_ID_TAG_REGEX.matchEntire(trimmed)
        if (tagMatch != null) {
            val (key, value) = tagMatch.destructured
            if (key.equals("offset", ignoreCase = true)) {
                value.trim().toLongOrNull()?.let { offsetMs = it }
            }
            continue
        }

        val times = ArrayList<Long>(2)
        var badTime = false
        var pos = 0
        while (true) {
            var probe = pos
            while (probe < trimmed.length && trimmed[probe] == ' ' && times.isNotEmpty()) {
                probe++
            }
            val likeMatch = LRC_LINE_TIME_LIKE_REGEX.find(trimmed, probe)?.takeIf { it.range.first == probe } ?: break
            val ms = LRC_LINE_TIME_REGEX.matchEntire(likeMatch.value)?.parseLrcTimeMs()
            if (ms == null) {
                badTime = true
            } else {
                times.add(ms)
            }
            pos = likeMatch.range.last + 1
        }
        if (badTime || times.isEmpty()) continue

        val rest = trimmed.substring(pos)
        val content = if (rest.isBlank()) "♫" else decodeHtmlEntities(rest.trimStart())
        times.forEach { rawMs ->
            parsed.add(rawMs to content)
        }
    }

    val linesLyrics =
        parsed
            .map { (rawMs, content) ->
                val shifted = (rawMs - offsetMs).coerceAtLeast(0L)
                shifted to content
            }.sortedBy { it.first }
            .map { (timeInMillis, content) ->
                Lyrics.LyricsX.Line(
                    endTimeMs = "0",
                    startTimeMs = timeInMillis.toString(),
                    syllables = listOf(),
                    words = content,
                )
            }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "LINE_SYNCED",
            ),
    )
}

fun parseSyncedLyrics(data: String): Lyrics = parseLrc(data)

fun parseRichSyncLyrics(data: String): Lyrics {
    // Unescape JSON string if needed (remove quotes and replace \n with actual newlines)
    val unescapedData =
        data
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

    val lines = unescapedData.lines()
    var offsetMs = 0L
    val lyricsLines = ArrayList<String>(lines.size)

    for (rawLine in lines) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        val tagMatch = LRC_ID_TAG_REGEX.matchEntire(trimmed)
        if (tagMatch != null) {
            val (key, value) = tagMatch.destructured
            if (key.equals("offset", ignoreCase = true)) {
                value.trim().toLongOrNull()?.let { offsetMs = it }
            }
            continue
        }
        lyricsLines.add(trimmed)
    }

    val parsedEntries = ArrayList<Pair<Long, String>>(lyricsLines.size)
    var hasWordTiming = false
    var failedCount = 0

    lyricsLines.forEach { line ->
        val matchResult = RICH_SYNC_LINE_REGEX.matchEntire(line)
        if (matchResult != null) {
            val rawLineMs = matchResult.parseLrcTimeMs()
            if (rawLineMs == null) {
                failedCount++
                return@forEach
            }
            var content =
                matchResult.groupValues[4]
                    .trimStart()
                    .replace(VOICE_MARKER_REGEX, "")
                    .trimStart()

            if (content.isNotBlank()) {
                val containsWordTags = RICH_SYNC_WORD_TAG_REGEX.containsMatchIn(content)
                if (containsWordTags) {
                    hasWordTiming = true
                    if (offsetMs != 0L) {
                        content =
                            RICH_SYNC_WORD_TAG_REGEX.replace(content) { wordMatch ->
                                val rawWordMs = wordMatch.parseLrcTimeMs() ?: return@replace wordMatch.value
                                val shiftedWordMs = (rawWordMs - offsetMs).coerceAtLeast(0L)
                                "<${formatMsToLrc(shiftedWordMs)}>"
                            }
                    }
                }
                val shiftedLineMs = (rawLineMs - offsetMs).coerceAtLeast(0L)
                parsedEntries.add(shiftedLineMs to content)
            }
        } else {
            failedCount++
        }
    }

    val linesLyrics =
        parsedEntries
            .sortedBy { it.first }
            .map { (timeInMillis, content) ->
                Lyrics.LyricsX.Line(
                    endTimeMs = "0",
                    startTimeMs = timeInMillis.toString(),
                    syllables = listOf(),
                    words = content,
                )
            }

    Logger.d(TAG, "parseRichSyncLyrics parsed=${linesLyrics.size} skipped=$failedCount hasWordTiming=$hasWordTiming")

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = if (hasWordTiming) "RICH_SYNCED" else "LINE_SYNCED",
            ),
    )
}


/**
 * Parse TTML (Timed Text Markup Language) lyrics from BetterLyrics.
 * Supports both line-synced and word-by-word synced lyrics.
 *
 * TTML format: `<p begin="M:SS.mmm" end="M:SS.mmm">` contains `<span begin="..." end="...">word</span>`
 * If spans with timing exist → word-by-word (RICH_SYNCED)
 * If no spans → line-synced (LINE_SYNCED)
 */
fun parseTtmlLyrics(data: String): Lyrics {
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    var hasWordTiming = false

    // Match each <p ...>...</p> element (use [\s\S] instead of . with DOT_MATCHES_ALL)
    val pRegex = Regex("""<p\s[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>([\s\S]*?)</p>""")
    // Match each <span ...>word</span> element
    val spanRegex = Regex("""<span\s[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</span>""")

    for (pMatch in pRegex.findAll(data)) {
        val lineBegin = parseTtmlTime(pMatch.groupValues[1])
        val lineEnd = parseTtmlTime(pMatch.groupValues[2])
        val innerContent = pMatch.groupValues[3]

        val spans = spanRegex.findAll(innerContent).toList()

        if (spans.isNotEmpty()) {
            hasWordTiming = true
            // Build word-by-word content with <MM:SS.mm> timing format for rich sync
            val wordParts = StringBuilder()
            for (span in spans) {
                val spanBegin = parseTtmlTime(span.groupValues[1])
                val word = span.groupValues[3].trim()
                if (word.isNotEmpty()) {
                    val beginFormatted = formatMsToLrc(spanBegin)
                    wordParts.append("<$beginFormatted>$word ")
                }
            }
            val words = wordParts.toString().trimEnd()
            if (words.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = words,
                    ),
                )
            }
        } else {
            // No spans — extract plain text (strip any remaining tags)
            val plainText = innerContent.replace(Regex("<[^>]*>"), "").trim()
            if (plainText.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = plainText,
                    ),
                )
            }
        }
    }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = if (hasWordTiming) "RICH_SYNCED" else "LINE_SYNCED",
            ),
    )
}

/**
 * Parse TTML time format to milliseconds.
 * Supports: "M:SS.mmm", "MM:SS.mmm", "H:MM:SS.mmm", "SS.mmm"
 */
private fun parseTtmlTime(time: String): Long {
    val parts = time.split(":")
    return when (parts.size) {
        3 -> {
            val hours = parts[0].toLongOrNull() ?: 0L
            val minutes = parts[1].toLongOrNull() ?: 0L
            val secParts = parts[2].split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis
        }
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: 0L
            val secParts = parts[1].split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            minutes * 60_000L + seconds * 1000L + millis
        }
        else -> {
            val secParts = time.split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            seconds * 1000L + millis
        }
    }
}

private fun parseMillisPart(part: String?): Long {
    if (part.isNullOrEmpty()) return 0L
    val value = part.toLongOrNull() ?: return 0L
    return when (part.length) {
        1 -> value * 100
        2 -> value * 10
        3 -> value
        else -> value
    }
}

private fun formatMsToLrc(ms: Long): String {
    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000L
    val centis = (ms % 1000L) / 10L
    val m = if (minutes < 10) "0$minutes" else "$minutes"
    val s = if (seconds < 10) "0$seconds" else "$seconds"
    val c = if (centis < 10) "0$centis" else "$centis"
    return "$m:$s.$c"
}

fun parseUnsyncedLyrics(data: String): Lyrics {
    if (data.isBlank()) {
        return Lyrics(
            lyrics =
                Lyrics.LyricsX(
                    lines = emptyList(),
                    syncType = "UNSYNCED",
                ),
        )
    }
    val lines = data.lines()
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>(lines.size)
    lines.forEach { line ->
        linesLyrics.add(
            Lyrics.LyricsX.Line(
                endTimeMs = "0",
                startTimeMs = "0",
                syllables = listOf(),
                words = line,
            ),
        )
    }
    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "UNSYNCED",
            ),
    )
}