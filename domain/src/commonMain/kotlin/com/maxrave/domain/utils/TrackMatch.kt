package com.maxrave.domain.utils

/**
 * How well a search result from a lyrics or canvas provider names the song being played, 0 to 1; 0 means
 * another song or another recording, and the result must not be used however close its length is.
 *
 * - Version: the words naming a recording ([trackVersionTags]) must be the same on both sides. A remix
 *   often runs within seconds of the original, so length alone let "Told You So" (187 s) stand in for
 *   "Told You So (Brooks Remix)" (184 s).
 * - Title: the share of title words both have (Dice), at least [MIN_TITLE_SIMILARITY]. A featured artist's
 *   part ("feat. …") and words such as "Official Video" are left out, since providers write them differently.
 * - Artist: when both sides name artists, at least one must be the same person (a cover of the same title
 *   by someone else is another recording).
 *
 * The score is the title share, so a caller picks the highest among results that pass.
 */
fun trackMatchScore(
    title: String,
    artists: List<String>,
    candidateTitle: String,
    candidateArtists: List<String>,
): Float {
    if (trackVersionTags(title) != trackVersionTags(candidateTitle)) return 0f
    val similarity = dice(titleWords(title), titleWords(candidateTitle))
    if (similarity < MIN_TITLE_SIMILARITY) return 0f
    val mine = artists.filter { it.isNotBlank() }
    val theirs = candidateArtists.filter { it.isNotBlank() }
    if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.none { a -> theirs.any { b -> sameName(a, b) } }) return 0f
    return similarity
}

/**
 * The words in a title that say which recording it is: "Told You So (Brooks Remix)" gives {remix},
 * "Song - Live at Wembley" gives {live}. "Remastered" is left out on purpose: a remaster is the same recording.
 */
fun trackVersionTags(title: String): Set<String> = words(title).filter { it in VERSION_WORDS }.toSet()

/** Title words to compare, up to a featured-artist part and without words providers add around a title. */
private fun titleWords(title: String): Set<String> =
    words(title)
        .takeWhile { it !in FEATURING_WORDS }
        .filter { it !in DECORATION_WORDS }
        .toSet()

private fun sameName(
    a: String,
    b: String,
): Boolean = dice(words(a).toSet(), words(b).toSet()) >= MIN_NAME_SIMILARITY

private fun dice(
    a: Set<String>,
    b: Set<String>,
): Float = if (a.isEmpty() || b.isEmpty()) 0f else 2f * a.intersect(b).size / (a.size + b.size)

private fun words(text: String): List<String> =
    text
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }

private const val MIN_TITLE_SIMILARITY = 0.6f
private const val MIN_NAME_SIMILARITY = 0.5f

private val FEATURING_WORDS = setOf("feat", "ft", "featuring")
private val DECORATION_WORDS = setOf("official", "video", "audio", "lyric", "lyrics", "mv", "hd", "visualizer")
private val VERSION_WORDS =
    setOf(
        "remix",
        "remixed",
        "mix",
        "edit",
        "live",
        "acoustic",
        "unplugged",
        "instrumental",
        "karaoke",
        "cover",
        "demo",
        "extended",
        "vip",
        "bootleg",
        "rework",
        "flip",
        "slowed",
        "sped",
        "reverb",
        "nightcore",
        "orchestral",
    )
