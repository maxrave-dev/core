package com.maxrave.domain.lyrics

import kotlin.math.abs

private val MATCHER_NOISE_PAREN_REGEX =
    Regex(
        """\s*[\(\[](?:official(?:\s+(?:music|lyric|lyrics|audio|visual|hd|4k|video))*\s*(?:video|audio|visualizer|mv)?|(?:music|lyric|lyrics)\s+video|lyrics?|audio|visualizer|mv|remaster(?:ed)?(?:\s+\d{4})?|\d{4}\s+remaster(?:ed)?|explicit|clean|hd|4k|feat\.?[^\)\]]*|ft\.?[^\)\]]*|instrumental|off\s*vocal|karaoke(?:\s+version)?)[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val MATCHER_FEAT_TAIL_REGEX = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
private val MATCHER_TOPIC_REGEX = Regex("""\s*[-–—]\s*topic\s*$""", RegexOption.IGNORE_CASE)
private val MATCHER_MULTI_SPACE_REGEX = Regex("""\s+""")
private val ARTIST_SPLIT_REGEX = Regex("""(?:\s*[,&•/;]\s*|\s+(?:feat\.?|ft\.?|featuring|and|e|x)\s+)""", RegexOption.IGNORE_CASE)

/**
 * Conservative track title, artist, and duration matching shared across lyrics providers
 * (LRCLIB, Spotify, SimpMusic) so an instrumental track never receives another artist's song.
 */
object LyricsMetadataMatcher {
    fun titlesMatch(
        expectedTitle: String?,
        candidateTitle: String?,
    ): Boolean {
        if (expectedTitle.isNullOrBlank() || candidateTitle.isNullOrBlank()) return false
        val expectedNorm = normalizeTitle(expectedTitle)
        val candidateNorm = normalizeTitle(candidateTitle)
        if (expectedNorm.isEmpty() || candidateNorm.isEmpty()) return false
        if (expectedNorm == candidateNorm) return true

        // Handle YouTube-style "Artist - Title" video titles vs clean catalog titles.
        val expectedAfterDash = extractAfterDash(expectedTitle)
        if (expectedAfterDash != null && normalizeTitle(expectedAfterDash) == candidateNorm) {
            return true
        }
        val candidateAfterDash = extractAfterDash(candidateTitle)
        if (candidateAfterDash != null && normalizeTitle(candidateAfterDash) == expectedNorm) {
            return true
        }
        return false
    }

    fun artistsMatch(
        expectedArtist: String?,
        candidateArtist: String?,
        expectedTitleHint: String? = null,
    ): Boolean {
        if (expectedArtist.isNullOrBlank() || candidateArtist.isNullOrBlank()) return false
        val expectedKeys = expandArtistKeys(expectedArtist, expectedTitleHint)
        val candidateKeys = expandArtistKeys(candidateArtist, null)
        if (expectedKeys.isEmpty() || candidateKeys.isEmpty()) return false

        for (exp in expectedKeys) {
            for (cand in candidateKeys) {
                if (exp == cand) return true
                if (exp.length >= 4 && cand.length >= 4 && (exp in cand || cand in exp)) {
                    return true
                }
            }
        }
        return false
    }

    fun durationsMatch(
        expectedSeconds: Int?,
        candidateSeconds: Int?,
        toleranceSeconds: Int = 12,
    ): Boolean {
        val exp = expectedSeconds?.takeIf { it > 0 } ?: return true
        val cand = candidateSeconds?.takeIf { it > 0 } ?: return true
        return abs(exp - cand) <= toleranceSeconds
    }

    private fun extractAfterDash(raw: String): String? {
        val seps = listOf(" - ", " – ", " — ")
        for (sep in seps) {
            val idx = raw.indexOf(sep)
            if (idx > 0 && idx + sep.length < raw.length) {
                return raw.substring(idx + sep.length)
            }
        }
        return null
    }

    private fun extractBeforeDash(raw: String): String? {
        val seps = listOf(" - ", " – ", " — ")
        for (sep in seps) {
            val idx = raw.indexOf(sep)
            if (idx > 0) {
                return raw.substring(0, idx)
            }
        }
        return null
    }

    private fun normalizeTitle(raw: String): String {
        var cleaned = raw.trim()
        repeat(2) {
            cleaned = MATCHER_NOISE_PAREN_REGEX.replace(cleaned, " ")
            cleaned = MATCHER_FEAT_TAIL_REGEX.replace(cleaned, "")
        }
        cleaned = MATCHER_MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
        return normalizeAlnum(cleaned)
    }

    private fun expandArtistKeys(
        rawArtist: String,
        titleHint: String?,
    ): Set<String> {
        val keys = LinkedHashSet<String>(6)
        fun addArtistParts(source: String) {
            val noTopic = MATCHER_TOPIC_REGEX.replace(source.trim(), "").trim()
            val noFeat = MATCHER_FEAT_TAIL_REGEX.replace(noTopic, "").trim()
            val full = normalizeAlnum(noFeat)
            if (full.isNotEmpty()) keys.add(full)
            ARTIST_SPLIT_REGEX.split(noTopic).forEach { part ->
                val norm = normalizeAlnum(part)
                if (norm.isNotEmpty()) keys.add(norm)
            }
        }
        addArtistParts(rawArtist)
        if (!titleHint.isNullOrBlank()) {
            extractBeforeDash(titleHint)?.let(::addArtistParts)
        }
        return keys
    }

    internal fun normalizeAlnum(raw: String): String {
        if (raw.isBlank()) return ""
        return buildString(raw.length) {
            for (ch in raw.lowercase()) {
                val folded = foldMetadataAccent(ch)
                if (folded.isLetterOrDigit()) {
                    append(folded)
                }
            }
        }
    }
}

private fun foldMetadataAccent(ch: Char): Char =
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
