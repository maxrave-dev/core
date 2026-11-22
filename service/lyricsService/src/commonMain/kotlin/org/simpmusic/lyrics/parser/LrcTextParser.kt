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


private val TTML_P_OPEN_REGEX = Regex("""<p(?=\s|>)([^>]*)>""", RegexOption.IGNORE_CASE)
private val TTML_P_CLOSE_REGEX = Regex("""</p\s*>""", RegexOption.IGNORE_CASE)
private val TTML_SPAN_TAG_REGEX = Regex("""<(/?)span(?=\s|>|/)([^>]*)>""", RegexOption.IGNORE_CASE)
private val TTML_BEGIN_ATTR_REGEX = Regex("""\bbegin\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val TTML_END_ATTR_REGEX = Regex("""\bend\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val TTML_IGNORED_ROLE_REGEX =
    Regex("""\b(?:ttm:)?role\s*=\s*["']\s*(?:x-translation|x-roman)\s*["']""", RegexOption.IGNORE_CASE)
private val TTML_IGNORED_SPAN_REGEX =
    Regex(
        """<span\b[^>]*\b(?:ttm:)?role\s*=\s*["']\s*(?:x-translation|x-roman)\s*["'][^>]*>[\s\S]*?</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
private val TTML_BR_TAG_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val TTML_ANY_TAG_REGEX = Regex("""<[^>]*>""")

private class TtmlSpanFrame(
    val openTagStartOffset: Int,
    val openEndOffset: Int,
    val beginMs: Long?,
    val endMs: Long?,
    val ignored: Boolean,
    var hasChildSpan: Boolean = false,
)

private data class TtmlLeafSpan(
    val rawStartOffset: Int,
    val openEndOffset: Int,
    val closeStartOffset: Int,
    val rawEndOffset: Int,
    val beginMs: Long,
    val endMs: Long?,
)

private fun extractTtmlLeafSpans(innerContent: String): List<TtmlLeafSpan> {
    if ("span" !in innerContent.lowercase()) return emptyList()
    val leaves = ArrayList<TtmlLeafSpan>()
    val stack = ArrayList<TtmlSpanFrame>()
    var ignoredDepth = 0

    for (tagMatch in TTML_SPAN_TAG_REGEX.findAll(innerContent)) {
        val isClosing = tagMatch.groupValues[1].isNotEmpty()
        val rawTail = tagMatch.groupValues[2]
        if (!isClosing) {
            val isSelfClosing = rawTail.trimEnd().endsWith("/")
            val hasIgnoredRole = TTML_IGNORED_ROLE_REGEX.containsMatchIn(rawTail)
            if (!hasIgnoredRole && stack.isNotEmpty() && ignoredDepth == 0) {
                stack.last().hasChildSpan = true
            }
            if (isSelfClosing) continue

            val spanIgnored = hasIgnoredRole || ignoredDepth > 0
            if (spanIgnored) ignoredDepth++
            val beginMs =
                if (!spanIgnored) {
                    TTML_BEGIN_ATTR_REGEX.find(rawTail)?.groupValues?.get(1)?.let(::parseTtmlTime)
                } else {
                    null
                }
            val endMs =
                if (!spanIgnored) {
                    TTML_END_ATTR_REGEX.find(rawTail)?.groupValues?.get(1)?.let(::parseTtmlTime)
                } else {
                    null
                }
            stack.add(
                TtmlSpanFrame(
                    openTagStartOffset = tagMatch.range.first,
                    openEndOffset = tagMatch.range.last + 1,
                    beginMs = beginMs,
                    endMs = endMs,
                    ignored = spanIgnored,
                ),
            )
        } else if (stack.isNotEmpty()) {
            val frame = stack.removeAt(stack.lastIndex)
            if (frame.ignored) {
                if (ignoredDepth > 0) ignoredDepth--
                continue
            }
            if (!frame.hasChildSpan && frame.beginMs != null) {
                leaves.add(
                    TtmlLeafSpan(
                        rawStartOffset = frame.openTagStartOffset,
                        openEndOffset = frame.openEndOffset,
                        closeStartOffset = tagMatch.range.first,
                        rawEndOffset = tagMatch.range.last + 1,
                        beginMs = frame.beginMs,
                        endMs = frame.endMs,
                    ),
                )
            }
        }
    }
    return leaves
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

    for (pOpen in TTML_P_OPEN_REGEX.findAll(data)) {
        val attrs = pOpen.groupValues[1]
        if (attrs.trimEnd().endsWith("/")) continue
        if (TTML_IGNORED_ROLE_REGEX.containsMatchIn(attrs)) continue

        val pClose = TTML_P_CLOSE_REGEX.find(data, pOpen.range.last + 1) ?: continue
        val innerContent = data.substring(pOpen.range.last + 1, pClose.range.first)

        val attrBegin = TTML_BEGIN_ATTR_REGEX.find(attrs)?.groupValues?.get(1)?.let(::parseTtmlTime)
        val attrEnd = TTML_END_ATTR_REGEX.find(attrs)?.groupValues?.get(1)?.let(::parseTtmlTime)

        val spans = extractTtmlLeafSpans(innerContent)
        if (spans.isNotEmpty()) {
            val tokens = ArrayList<Pair<Long, StringBuilder>>(spans.size)
            var lastTokenSpanEnd = -1
            var lastSpanEndedWithSpace = false
            var minSpanBegin = Long.MAX_VALUE
            var maxSpanEnd = 0L

            for (span in spans) {
                if (span.beginMs < minSpanBegin) minSpanBegin = span.beginMs
                if (span.endMs != null && span.endMs > maxSpanEnd) maxSpanEnd = span.endMs

                val rawSlice = TTML_BR_TAG_REGEX.replace(innerContent.substring(span.openEndOffset, span.closeStartOffset), " ")
                val plain = decodeHtmlEntities(TTML_ANY_TAG_REGEX.replace(rawSlice, ""))
                val trimmed = plain.trim()

                if (trimmed.isEmpty()) {
                    if (plain.any { it.isWhitespace() }) {
                        lastSpanEndedWithSpace = true
                    }
                    lastTokenSpanEnd = span.rawEndOffset
                    continue
                }

                val gap =
                    if (lastTokenSpanEnd >= 0 && lastTokenSpanEnd <= span.rawStartOffset) {
                        innerContent.substring(lastTokenSpanEnd, span.rawStartOffset)
                    } else {
                        ""
                    }
                val gapHasSpace = gap.any { it.isWhitespace() } || TTML_BR_TAG_REGEX.containsMatchIn(gap)
                val startsWithSpace = plain.firstOrNull()?.isWhitespace() == true
                val isSeparateWord = tokens.isEmpty() || lastSpanEndedWithSpace || gapHasSpace || startsWithSpace

                if (isSeparateWord) {
                    tokens.add(span.beginMs to StringBuilder(trimmed))
                } else {
                    tokens.last().second.append(trimmed)
                }
                lastSpanEndedWithSpace = plain.lastOrNull()?.isWhitespace() == true
                lastTokenSpanEnd = span.rawEndOffset
            }

            val lineBegin = attrBegin ?: if (minSpanBegin != Long.MAX_VALUE) minSpanBegin else null
            val lineEnd = attrEnd ?: if (maxSpanEnd > 0L) maxSpanEnd else lineBegin ?: 0L
            if (lineBegin != null && tokens.isNotEmpty()) {
                hasWordTiming = true
                val words =
                    tokens.joinToString(" ") { (startMs, text) ->
                        "<${formatMsToLrc(startMs)}>$text"
                    }
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = words,
                    ),
                )
            }
        } else if (attrBegin != null) {
            val withoutIgnored = TTML_IGNORED_SPAN_REGEX.replace(innerContent, "")
            val withLineSpaces = TTML_BR_TAG_REGEX.replace(withoutIgnored, " ")
            val plainText = decodeHtmlEntities(TTML_ANY_TAG_REGEX.replace(withLineSpaces, "")).trim()
            if (plainText.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = attrBegin.toString(),
                        endTimeMs = (attrEnd ?: attrBegin).toString(),
                        syllables = listOf(),
                        words = plainText,
                    ),
                )
            }
        }
    }

    val sortedLines = linesLyrics.sortedBy { it.startTimeMs.toLongOrNull() ?: 0L }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = sortedLines,
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

data class EmbeddedLyricsMetadata(
    val title: String? = null,
    val artist: String? = null,
    val durationSeconds: Int? = null,
)

private val TTML_META_TITLE_REGEX = Regex("""<ttm:title\b[^>]*>(.*?)</ttm:title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TTML_META_AGENT_PERSON_REGEX =
    Regex(
        """<ttm:agent\b[^>]*type\s*=\s*["']person["'][^>]*>.*?<ttm:name\b[^>]*>(.*?)</ttm:name>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private val TTML_BODY_DUR_REGEX = Regex("""<body\b[^>]*\bdur\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

fun extractLrcEmbeddedMetadata(data: String?): EmbeddedLyricsMetadata {
    if (data.isNullOrBlank()) return EmbeddedLyricsMetadata()
    var title: String? = null
    var artist: String? = null
    var durationSec: Int? = null

    for (rawLine in data.lineSequence()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        val match = LRC_ID_TAG_REGEX.matchEntire(trimmed) ?: continue
        val key = match.groupValues[1].trim().lowercase()
        val value = decodeHtmlEntities(match.groupValues[2]).trim()
        if (value.isEmpty()) continue
        when (key) {
            "ti", "title" -> if (title == null) title = value
            "ar", "artist" -> if (artist == null) artist = value
            "length" -> {
                if (durationSec == null) {
                    val sec = parseTtmlDurationSeconds(value)
                    if (sec != null && sec > 0) durationSec = sec
                }
            }
        }
        if (title != null && artist != null && durationSec != null) break
    }
    return EmbeddedLyricsMetadata(title = title, artist = artist, durationSeconds = durationSec)
}

fun extractTtmlEmbeddedMetadata(ttml: String?): EmbeddedLyricsMetadata {
    if (ttml.isNullOrBlank()) return EmbeddedLyricsMetadata()
    val rawTitle =
        TTML_META_TITLE_REGEX
            .find(ttml)
            ?.groupValues
            ?.get(1)
            ?.let { decodeHtmlEntities(it.replace(Regex("<[^>]+>"), "").trim()) }
            ?.takeIf { it.isNotEmpty() }
    val rawArtist =
        TTML_META_AGENT_PERSON_REGEX
            .find(ttml)
            ?.groupValues
            ?.get(1)
            ?.let { decodeHtmlEntities(it.replace(Regex("<[^>]+>"), "").trim()) }
            ?.takeIf { it.isNotEmpty() }
    val durationSec =
        TTML_BODY_DUR_REGEX
            .find(ttml)
            ?.groupValues
            ?.get(1)
            ?.let(::parseTtmlDurationSeconds)
    return EmbeddedLyricsMetadata(
        title = rawTitle,
        artist = rawArtist,
        durationSeconds = durationSec,
    )
}

private fun parseTtmlDurationSeconds(raw: String): Int? {
    val cleaned = raw.trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.endsWith("ms", ignoreCase = true)) {
        val ms = cleaned.dropLast(2).trim().toDoubleOrNull() ?: return null
        return (ms / 1000.0).toInt().takeIf { it > 0 }
    }
    if (cleaned.endsWith("s", ignoreCase = true) && ':' !in cleaned) {
        val sec = cleaned.dropLast(1).trim().toDoubleOrNull() ?: return null
        return sec.toInt().takeIf { it > 0 }
    }
    val ms = parseTtmlTime(cleaned)
    return (ms / 1000L).toInt().takeIf { it > 0 }
}