package org.simpmusic.lyrics.parser

import com.maxrave.domain.extension.decodeHtmlEntities
import org.simpmusic.lyrics.domain.Lyrics

fun parseSyncedLyrics(data: String): Lyrics {
    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\](.+)")
    val lines = data.lines()
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    lines.map { line ->
        val matchResult = regex.matchEntire(line)
        if (matchResult != null) {
            val minutes = matchResult.groupValues[1].toLong()
            val seconds = matchResult.groupValues[2].toLong()
            val milliseconds = matchResult.groupValues[3].toLong()
            val timeInMillis = minutes * 60_000L + seconds * 1000L + milliseconds
            // Trim, don't drop a fixed first character. Most providers leave a space after
            // "]", but LRCLIB packs the text right against it — "[00:27.12]boy, you got me"
            // — so removeRange(0, 1) ate the first letter of every single line.
            val rawContent = matchResult.groupValues[4]
            val content = if (rawContent.isBlank()) "♫" else rawContent.trimStart()
            linesLyrics.add(
                Lyrics.LyricsX.Line(
                    endTimeMs = "0",
                    startTimeMs = timeInMillis.toString(),
                    syllables = listOf(),
                    words = decodeHtmlEntities(content),
                ),
            )
        }
    }
    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "LINE_SYNCED",
            ),
    )
}

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

    // Handle different line separators (Unix \n, Windows \r\n, Mac \r)
    val lines = unescapedData.lines()
    // Skip offset line if present (starts with [offset:)
    val lyricsLines =
        lines.filter { line ->
            line.isNotBlank() && !line.trim().startsWith("[offset:")
        }

    println("[parseRichSyncLyrics] Total lines: ${lines.size}, Filtered lines: ${lyricsLines.size}")
    if (lyricsLines.isNotEmpty()) {
        println("[parseRichSyncLyrics] First line sample: ${lyricsLines.first()}")
    }

    // Regex to match [MM:SS.mm] format (flexible with 1-2 digits)
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](.+)")
    // Strip a leading voice marker (e.g. "v1:", "v2:") that some lyrics
    // providers prepend before the first word timestamp, so it doesn't leak
    // into the word content and stick to the first word.
    val voiceMarkerRegex = Regex("""^v\d+:""")
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()

    lyricsLines.forEachIndexed { index, line ->
        val matchResult = regex.matchEntire(line.trim())
        if (matchResult != null) {
            val minutes = matchResult.groupValues[1].toLongOrNull() ?: 0L
            val seconds = matchResult.groupValues[2].toLongOrNull() ?: 0L
            val centiseconds = matchResult.groupValues[3].toLongOrNull() ?: 0L

            // Convert to milliseconds
            // If centiseconds has 3 digits (milliseconds), use directly
            // If 2 digits (centiseconds), multiply by 10
            val millisPart = if (matchResult.groupValues[3].length == 3) centiseconds else centiseconds * 10
            val timeInMillis = minutes * 60_000L + seconds * 1000L + millisPart

            // Keep the rich sync content as-is (with <MM:SS.mm> word format)
            val content =
                matchResult.groupValues[4]
                    .trimStart()
                    .replace(voiceMarkerRegex, "")
                    .trimStart()

            if (content.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        endTimeMs = "0",
                        startTimeMs = timeInMillis.toString(),
                        syllables = listOf(),
                        words = content,
                    ),
                )
            }
        } else {
            if (index < 3) { // Only log first 3 failed matches to avoid spam
                println("[parseRichSyncLyrics] Line $index failed to match: '${line.take(100)}'")
            }
        }
    }

    println("[parseRichSyncLyrics] Parsed ${linesLyrics.size} lines successfully")

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "RICH_SYNCED",
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

    // Extract songwriters from metadata if present
    val songwriterRegex = Regex("""<songwriter>([^<]+)</songwriter>""", RegexOption.IGNORE_CASE)
    val songwriters = songwriterRegex.findAll(data)
        .map { unescapeXml(it.groupValues[1].trim()) }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    // Match each <p ...>...</p> element regardless of attribute order
    val pRegex = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    // Match leaf <span ...>text</span> (no nested children) and capture trailing text
    val spanRegex = Regex("""<span\b([^>]*)>([^<]*)</span>([^<]*)""", RegexOption.IGNORE_CASE)
    val beginAttrRegex = Regex("""begin=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val endAttrRegex = Regex("""end=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    for (pMatch in pRegex.findAll(data)) {
        val pAttrs = pMatch.groupValues[1]
        val innerContent = pMatch.groupValues[2]

        val lineBeginStr = beginAttrRegex.find(pAttrs)?.groupValues?.get(1) ?: continue
        val lineEndStr = endAttrRegex.find(pAttrs)?.groupValues?.get(1)
        val lineBegin = parseTtmlTime(lineBeginStr)
        val lineEnd = lineEndStr?.let { parseTtmlTime(it) } ?: (lineBegin + 3000L)

        val roleRegex = Regex("""(?:role|agent|class|voice)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val roleMatch = roleRegex.find(pAttrs)?.groupValues?.get(1)?.lowercase() ?: ""
        val isPLineBg = roleMatch.contains("bg") || roleMatch.contains("background")
        val isV2 = roleMatch.contains("v2") || roleMatch.contains("voice2") || roleMatch.contains("singer2") || roleMatch == "2"

        // --- Extract inline background vocal wrappers: <span ttm:role="x-bg">...nested spans...</span> ---
        // Use balanced tag depth scanner so we don't greedily swallow subsequent main vocal spans in the same <p>.
        val bgWrappers = extractBgWrappers(innerContent)
        var bgContent: String? = null
        var mainContent = innerContent
        if (bgWrappers.isNotEmpty()) {
            val bgParts = StringBuilder()
            for (wrapper in bgWrappers.reversed()) {
                mainContent = mainContent.removeRange(wrapper.startIndex, wrapper.endIndex)
            }
            for (wrapper in bgWrappers) {
                bgParts.append(wrapper.innerContent)
            }
            bgContent = bgParts.toString()
        }

        // --- Process main content (non-bg spans) ---
        val mainSpans = spanRegex.findAll(mainContent).toList()
        if (mainSpans.isNotEmpty()) {
            hasWordTiming = true
            val wordParts = StringBuilder()
            if (isPLineBg) wordParts.append("[bg]")
            if (isV2) wordParts.append("[v2]")

            for (i in mainSpans.indices) {
                val span = mainSpans[i]
                val spanAttrs = span.groupValues[1]
                val innerText = unescapeXml(span.groupValues[2])
                val trailingRaw = unescapeXml(span.groupValues[3])
                val hasTrailingWhitespace = innerText.endsWith(" ") || trailingRaw.contains(" ") || trailingRaw.contains("\n")
                val wordText = innerText.trimEnd()
                val space = if (hasTrailingWhitespace && i < mainSpans.size - 1) " " else ""
                val spanBeginStr = beginAttrRegex.find(spanAttrs)?.groupValues?.get(1)
                val spanEndStr = endAttrRegex.find(spanAttrs)?.groupValues?.get(1)

                if (spanBeginStr != null && (wordText.isNotEmpty() || space.isNotEmpty())) {
                    val spanBegin = parseTtmlTime(spanBeginStr)
                    val beginFormatted = formatMsToLrc(spanBegin)
                    val nextSpanBeginStr = mainSpans.getOrNull(i + 1)?.let { beginAttrRegex.find(it.groupValues[1])?.groupValues?.get(1) }
                    val nextSpanBegin = nextSpanBeginStr?.let { parseTtmlTime(it) }
                    val spanEnd = spanEndStr?.let { parseTtmlTime(it) }

                    if (spanEnd != null && nextSpanBegin != null && spanEnd < nextSpanBegin) {
                        val endFormatted = formatMsToLrc(spanEnd)
                        wordParts.append("<$beginFormatted>$wordText<$endFormatted>$space")
                    } else {
                        wordParts.append("<$beginFormatted>$wordText$space")
                    }
                } else if (wordText.isNotEmpty() || space.isNotEmpty()) {
                    wordParts.append(wordText + space)
                }
            }

            val mainFirstBeginRaw = mainSpans.firstNotNullOfOrNull {
                beginAttrRegex.find(it.groupValues[1])?.groupValues?.get(1)
            }?.let { parseTtmlTime(it) } ?: lineBegin
            // If background vocals exist in the same <p>, line start should encompass them
            val mainFirstBegin = if (bgContent != null) minOf(lineBegin, mainFirstBeginRaw) else mainFirstBeginRaw
            val mainLastEnd = mainSpans.lastNotNullOfOrNull {
                endAttrRegex.find(it.groupValues[1])?.groupValues?.get(1)
            }?.let { parseTtmlTime(it) } ?: lineEnd

            // Append trailing end timestamp so the last syllable has an explicit end in ELRC
            val endFormatted = formatMsToLrc(mainLastEnd)
            wordParts.append("<$endFormatted>")

            val words = wordParts.toString().trimEnd()
            if (words.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = mainFirstBegin.toString(),
                        endTimeMs = mainLastEnd.toString(),
                        syllables = listOf(),
                        words = words,
                    ),
                )
            }
        } else if (bgContent == null) {
            // No spans at all — extract plain text (strip any remaining tags)
            val plainText = unescapeXml(mainContent.replace(Regex("<[^>]*>"), "")).trim()
            if (plainText.isNotBlank()) {
                val prefix = if (isPLineBg) "[bg]" else if (isV2) "[v2]" else ""
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = "$prefix$plainText",
                    ),
                )
            }
        }

        // --- Process background vocal content as a separate [bg] line ---
        if (bgContent != null) {
            val bgSpans = spanRegex.findAll(bgContent).toList()
            if (bgSpans.isNotEmpty()) {
                hasWordTiming = true
                val bgWordParts = StringBuilder()
                bgWordParts.append("[bg]")
                if (isV2) bgWordParts.append("[v2]")

                for (i in bgSpans.indices) {
                    val span = bgSpans[i]
                    val spanAttrs = span.groupValues[1]
                    val innerText = unescapeXml(span.groupValues[2])
                    val trailingRaw = unescapeXml(span.groupValues[3])
                    val hasTrailingWhitespace = innerText.endsWith(" ") || trailingRaw.contains(" ") || trailingRaw.contains("\n")
                    val wordText = innerText.trimEnd()
                    val space = if (hasTrailingWhitespace && i < bgSpans.size - 1) " " else ""
                    val spanBeginStr = beginAttrRegex.find(spanAttrs)?.groupValues?.get(1)
                    val spanEndStr = endAttrRegex.find(spanAttrs)?.groupValues?.get(1)

                    if (spanBeginStr != null && (wordText.isNotEmpty() || space.isNotEmpty())) {
                        val spanBegin = parseTtmlTime(spanBeginStr)
                        val beginFormatted = formatMsToLrc(spanBegin)
                        val nextSpanBeginStr = bgSpans.getOrNull(i + 1)?.let { beginAttrRegex.find(it.groupValues[1])?.groupValues?.get(1) }
                        val nextSpanBegin = nextSpanBeginStr?.let { parseTtmlTime(it) }
                        val spanEnd = spanEndStr?.let { parseTtmlTime(it) }

                        if (spanEnd != null && nextSpanBegin != null && spanEnd < nextSpanBegin) {
                            val endFormatted = formatMsToLrc(spanEnd)
                            bgWordParts.append("<$beginFormatted>$wordText<$endFormatted>$space")
                        } else {
                            bgWordParts.append("<$beginFormatted>$wordText$space")
                        }
                    } else if (wordText.isNotEmpty() || space.isNotEmpty()) {
                        bgWordParts.append(wordText + space)
                    }
                }

                // Use the bg spans' own timing for the line start/end
                val bgFirstBegin = bgSpans.firstNotNullOfOrNull {
                    beginAttrRegex.find(it.groupValues[1])?.groupValues?.get(1)
                }?.let { parseTtmlTime(it) } ?: lineBegin
                val bgLastEnd = bgSpans.lastNotNullOfOrNull {
                    endAttrRegex.find(it.groupValues[1])?.groupValues?.get(1)
                }?.let { parseTtmlTime(it) } ?: lineEnd

                // Append trailing end timestamp so the last bg syllable has an explicit end in ELRC
                val bgEndFormatted = formatMsToLrc(bgLastEnd)
                bgWordParts.append("<$bgEndFormatted>")

                val bgWords = bgWordParts.toString().trimEnd()
                if (bgWords.isNotBlank()) {
                    linesLyrics.add(
                        Lyrics.LyricsX.Line(
                            startTimeMs = bgFirstBegin.toString(),
                            endTimeMs = bgLastEnd.toString(),
                            syllables = listOf(),
                            words = bgWords,
                        ),
                    )
                }
            } else {
                val singleSpanBeginStr = bgWrappers.firstNotNullOfOrNull { beginAttrRegex.find(it.attrs)?.groupValues?.get(1) }
                val singleSpanEndStr = bgWrappers.firstNotNullOfOrNull { endAttrRegex.find(it.attrs)?.groupValues?.get(1) }
                val plainText = unescapeXml(bgContent.replace(Regex("<[^>]*>"), "")).trim()
                if (plainText.isNotBlank()) {
                    val prefix = "[bg]" + if (isV2) "[v2]" else ""
                    if (singleSpanBeginStr != null) {
                        hasWordTiming = true
                        val b = parseTtmlTime(singleSpanBeginStr)
                        val e = singleSpanEndStr?.let { parseTtmlTime(it) } ?: lineEnd
                        val bFormatted = formatMsToLrc(b)
                        val eFormatted = formatMsToLrc(e)
                        linesLyrics.add(
                            Lyrics.LyricsX.Line(
                                startTimeMs = b.toString(),
                                endTimeMs = e.toString(),
                                syllables = listOf(),
                                words = "$prefix<$bFormatted>$plainText<$eFormatted>",
                            ),
                        )
                    } else {
                        linesLyrics.add(
                            Lyrics.LyricsX.Line(
                                startTimeMs = lineBegin.toString(),
                                endTimeMs = lineEnd.toString(),
                                syllables = listOf(),
                                words = "$prefix$plainText",
                            ),
                        )
                    }
                }
            }
        }
    }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = if (hasWordTiming) "RICH_SYNCED" else "LINE_SYNCED",
            ),
        songwriters = songwriters.ifEmpty { null },
    )
}

private data class BgWrapper(
    val startIndex: Int,
    val endIndex: Int,
    val attrs: String,
    val innerContent: String,
)

private fun extractBgWrappers(text: String): List<BgWrapper> {
    val results = mutableListOf<BgWrapper>()
    val startTagRegex = Regex("""<span\b([^>]*(?:ttm:)?role=["']x-bg["'][^>]*)>""", RegexOption.IGNORE_CASE)
    val spanTagRegex = Regex("""</?span\b[^>]*>""", RegexOption.IGNORE_CASE)

    var searchPos = 0
    while (searchPos < text.length) {
        val startMatch = startTagRegex.find(text, searchPos) ?: break
        val startIdx = startMatch.range.first
        val attrs = startMatch.groupValues[1]
        val contentStart = startMatch.range.last + 1
        var depth = 1
        var pos = contentStart
        var foundEnd = -1
        var contentEnd = -1
        while (depth > 0 && pos < text.length) {
            val tagMatch = spanTagRegex.find(text, pos) ?: break
            if (tagMatch.value.startsWith("</", ignoreCase = true)) {
                depth--
                if (depth == 0) {
                    foundEnd = tagMatch.range.last + 1
                    contentEnd = tagMatch.range.first
                    break
                }
            } else {
                depth++
            }
            pos = tagMatch.range.last + 1
        }
        if (foundEnd != -1) {
            results.add(BgWrapper(startIdx, foundEnd, attrs, text.substring(contentStart, contentEnd)))
            searchPos = foundEnd
        } else {
            searchPos = contentStart
        }
    }
    return results
}

/**
 * Kotlin equivalent of Iterable.lastNotNullOfOrNull (available from Kotlin 1.5).
 */
private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? {
    for (i in lastIndex downTo 0) {
        transform(this[i])?.let { return it }
    }
    return null
}

/**
 * Parse TTML time format to milliseconds.
 * Supports: "M:SS.mmm", "MM:SS.mmm", "H:MM:SS.mmm", "SS.mmm", with or without trailing 's'
 */
private fun parseTtmlTime(time: String): Long {
    val cleanTime = time.trim().removeSuffix("s")
    val parts = cleanTime.split(":")
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
            val secParts = cleanTime.split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            seconds * 1000L + millis
        }
    }
}

private fun parseMillisPart(part: String?): Long {
    if (part.isNullOrEmpty()) return 0L
    val clean = part.filter { it.isDigit() }
    if (clean.isEmpty()) return 0L
    val padded = clean.padEnd(3, '0').take(3)
    return padded.toLongOrNull() ?: 0L
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

private fun unescapeXml(text: String): String {
    return text
        .replace("&amp;", "&")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

fun parseUnsyncedLyrics(data: String): Lyrics {
    val lines = data.lines()
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    lines.map { line ->
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