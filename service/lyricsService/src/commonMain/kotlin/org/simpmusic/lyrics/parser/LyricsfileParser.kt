package org.simpmusic.lyrics.parser

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.simpmusic.lyrics.domain.Lyrics
import org.simpmusic.lyrics.models.response.LrclibObject

private const val MAX_LYRICSFILE_CHARACTERS = 1_000_000
// The existing rich-sync readers accept exactly two minute digits.
private const val MAX_RICH_SYNC_TIME_MS = 100 * 60_000L

private val lyricsfileYaml =
    Yaml(
        configuration =
            YamlConfiguration(
                strictMode = false,
                anchorsAndAliases = AnchorsAndAliases.Forbidden,
                codePointLimit = MAX_LYRICSFILE_CHARACTERS,
                maxNestingDepth = 12,
            ),
    )

/** Prefer Lyricsfile without changing the legacy field fallbacks or record selection. */
internal fun parseLrclibLyrics(record: LrclibObject): Lyrics? {
    record.lyricsfile?.let { source ->
        parseLyricsfile(source)?.let { return it }
    }
    return when {
        !record.syncedLyrics.isNullOrEmpty() -> parseSyncedLyrics(record.syncedLyrics)
        !record.plainLyrics.isNullOrEmpty() -> parseUnsyncedLyrics(record.plainLyrics)
        else -> null
    }
}

/**
 * Decode Lyricsfile 1.0 into the same model used by the other providers.
 * YAML is request-local input, not a new persisted lyrics format.
 */
internal fun parseLyricsfile(source: String): Lyrics? {
    // Bound UTF-16 input before parsing as well as the engine's Unicode code-point budget.
    if (source.length > MAX_LYRICSFILE_CHARACTERS || source.isBlank()) return null
    val document =
        try {
            lyricsfileYaml.decodeFromString(LyricsfileDocument.serializer(), source)
        } catch (_: SerializationException) {
            // Do not log YAML exception messages: they can include the source lyrics.
            return null
        }
    if (document.version != "1.0" || document.metadata.instrumental) return null

    val mappedLines = document.lines.orEmpty().mapNotNull { it.toLyricsLine() }.sortedBy { it.first.startTimeMs.toLong() }
    if (mappedLines.isNotEmpty()) {
        return Lyrics(
            lyrics =
                Lyrics.LyricsX(
                    lines = mappedLines.map { it.first },
                    syncType = if (mappedLines.any { it.second }) "RICH_SYNCED" else "LINE_SYNCED",
                ),
        )
    }
    return document.plain?.takeIf { it.isNotBlank() }?.let { parseUnsyncedLyrics(it) }
}

private fun LyricsfileLine.toLyricsLine(): Pair<Lyrics.LyricsX.Line, Boolean>? {
    if (startMs < 0) return null
    val timedWords =
        words?.takeIf { candidates ->
            text.isNotBlank() &&
                candidates.isNotEmpty() &&
                candidates.all { word ->
                    word.startMs >= 0 &&
                        word.startMs < MAX_RICH_SYNC_TIME_MS &&
                        (word.endMs == null || word.endMs >= word.startMs)
                } &&
                candidates.zipWithNext().all { (previous, next) -> previous.startMs <= next.startMs } &&
                candidates.joinToString("") { it.text } == text
        }
    val content =
        if (timedWords != null) {
            buildString {
                timedWords.forEach { word ->
                    append('<')
                    append(word.startMs.toRichSyncTimestamp())
                    append('>')
                    append(word.text)
                }
            }
        } else {
            text.ifBlank { "♫" }
        }
    return Lyrics.LyricsX.Line(
        startTimeMs = startMs.toString(),
        // Do not invent an end at the next line: Lyricsfile allows overlapping lines.
        // Zero is the existing model's missing-end sentinel, handled by the renderer.
        endTimeMs = (endMs?.takeIf { it >= startMs } ?: 0L).toString(),
        syllables = emptyList(),
        words = content,
    ) to (timedWords != null)
}

private fun Long.toRichSyncTimestamp(): String =
    (this / 60_000L).toString().padStart(2, '0') + ":" +
        ((this % 60_000L) / 1000L).toString().padStart(2, '0') + "." +
        (this % 1000L).toString().padStart(3, '0')

@Serializable
private data class LyricsfileDocument(
    val version: String,
    val metadata: LyricsfileMetadata,
    val lines: List<LyricsfileLine>? = null,
    val plain: String? = null,
)

@Serializable
private data class LyricsfileMetadata(
    val title: String,
    val artist: String,
    val instrumental: Boolean = false,
)

@Serializable
private data class LyricsfileLine(
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long? = null,
    val words: List<LyricsfileWord>? = null,
)

@Serializable
private data class LyricsfileWord(
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long? = null,
)
