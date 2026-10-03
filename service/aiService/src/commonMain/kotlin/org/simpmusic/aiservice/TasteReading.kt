package org.simpmusic.aiservice

import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

/**
 * The rules a taste reading is written under.
 *
 * An early draft recited the listener's own figures back ("149 hours, one song in eight…") and read
 * as statistics rather than taste, so the ban on numbers is the first rule, not a style note. The genre rule exists because the model only knows the artists it was
 * trained on: a song released after that is judged by its title and its neighbours, never invented.
 */
internal const val TASTE_SYSTEM_PROMPT = """You describe a person's music taste from their own listening data.

Write about TASTE, not statistics:
- Read the data for what it says about the person: the sounds and genres they lean towards, the moods and themes they look for, how they listen, and what that suggests about them as a listener.
- Never repeat numbers, counts, percentages, hours or dates from the data.
- Name a genre or style only for artists you clearly know. For artists or songs you do not know, judge only from the data: the titles and the artists they sit next to. Never invent facts about an artist or a song.
- Song titles can hint at themes. Present that as an impression, not as a fact.
- When the data says there is no earlier history, every artist is new by definition: do not praise the listener as an explorer for that.

Tone: warm and playful, lightly teasing, like a friend who knows music. Never insulting.

Write in the language given as "Language" and reply with only this JSON object:
{"headline": "...", "summary": "..."}
- headline: a short persona for this listener, at most 40 characters, no full stop at the end.
- summary: three short paragraphs separated by a blank line, about 120 to 160 words in total. First the sound and the genres they lean towards, then the moods and themes they keep choosing, then how they listen and what it all says about them, ending on a playful tease."""

/** The listener's data as the model reads it: plain lines, names first, behaviour as words. */
internal fun TasteInput.toPromptData(): String =
    buildString {
        appendLine("Language: $language")
        appendLine("Earlier listening history: ${if (hasEarlierHistory) "yes" else "none, this is the first year on record"}")
        appendLine("Most played artists, most first: ${topArtists.joinToString(", ")}")
        if (topSongs.isNotEmpty()) {
            appendLine("Most played songs, most first:")
            topSongs.forEach { appendLine("- ${it.title} — ${it.artists}") }
        }
        if (topAlbums.isNotEmpty()) {
            appendLine("Most played albums, most first:")
            topAlbums.forEach { album ->
                append("- ${album.title} — ${album.artists}")
                album.year?.let { append(" ($it)") }
                appendLine()
            }
        }
        val parts = listOf("night", "morning", "afternoon", "evening")
        appendLine(
            "Listening by part of the day: " +
                parts.indices.joinToString(", ") { "${parts[it]} ${percent(dayParts.getOrElse(it) { 0f })}" },
        )
        appendLine("Replays the same songs: ${level(replay)}")
        appendLine("Sticks to a few artists: ${level(concentration)}")
        if (previousTopArtists.isNotEmpty()) {
            appendLine("Most played artists in the twelve months before: ${previousTopArtists.joinToString(", ")}")
        }
    }

private fun percent(share: Float): String = "${(share * 100).roundToInt()}%"

private fun level(value: Float): String =
    when {
        value >= 0.66f -> "a lot"
        value >= 0.33f -> "somewhat"
        else -> "rarely"
    }

private val lenientJson = Json { isLenient = true }

/**
 * The reading out of whatever the model answered.
 *
 * Lenient on purpose: asked without a response format, models wrap the object in a ``` fence or
 * put a sentence before it, and taking the outermost `{…}` covers both. An answer without both a
 * headline and a summary is rejected rather than shown half empty.
 */
internal fun parseTasteReading(raw: String): TasteReading? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj =
        runCatching { lenientJson.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }
            .getOrNull() ?: return null
    val headline = (obj["headline"] as? JsonPrimitive)?.contentOrNull?.trim()?.trimEnd('.')
    val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.trim()
    if (headline.isNullOrEmpty() || summary.isNullOrEmpty()) return null
    return TasteReading(headline = headline, summary = summary)
}
