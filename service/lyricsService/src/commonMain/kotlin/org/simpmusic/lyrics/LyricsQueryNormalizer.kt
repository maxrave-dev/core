package org.simpmusic.lyrics

object LyricsQueryNormalizer {
    private val PAREN_NOISE_REGEX =
        Regex(
            """\s*[\(\[]\s*(?:""" +
                """official(?:\s+(?:music|lyric|lyrics|audio|visual|hd|4k|video))*\s*(?:video|audio|visualizer|mv)?|""" +
                """(?:music|lyric|lyrics)\s+video|""" +
                """lyrics?|audio|visualizer|mv|""" +
                """remaster(?:ed)?(?:\s+\d{4})?|""" +
                """\d{4}\s+remaster(?:ed)?|""" +
                """explicit|clean|hd|4k""" +
                """)\s*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )

    private val PAREN_FEAT_REGEX =
        Regex(
            """\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring|cùng với|con|mukana|avec|合作音乐人:)\s+[^\)\]]+[\)\]]""",
            RegexOption.IGNORE_CASE,
        )

    private val TRAILING_DASH_NOISE_REGEX =
        Regex(
            """\s*[-–—]\s*(?:""" +
                """official(?:\s+(?:music|lyric|lyrics|audio|visual))*\s*(?:video|audio|visualizer|mv)?|""" +
                """(?:music|lyric|lyrics)\s+video|""" +
                """lyrics?|audio|visualizer|topic|""" +
                """remaster(?:ed)?(?:\s+\d{4})?|""" +
                """\d{4}\s+remaster(?:ed)?""" +
                """)\s*$""",
            RegexOption.IGNORE_CASE,
        )

    private val TRAILING_FEAT_REGEX =
        Regex(
            """\s+(?:feat\.?|ft\.?|featuring)\s+.+$""",
            RegexOption.IGNORE_CASE,
        )

    private val ARTIST_TOPIC_REGEX =
        Regex("""\s*[-–—]\s*topic\s*$""", RegexOption.IGNORE_CASE)

    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

    fun cleanTrackTitle(rawTitle: String): String {
        val trimmed = rawTitle.trim()
        if (trimmed.isEmpty()) return ""

        var cleaned = trimmed
        // Iterate because titles often chain tags like "(feat. X) [Official Video]"
        repeat(2) {
            cleaned = PAREN_NOISE_REGEX.replace(cleaned, " ")
            cleaned = PAREN_FEAT_REGEX.replace(cleaned, " ")
            cleaned = TRAILING_DASH_NOISE_REGEX.replace(cleaned, "")
        }
        cleaned = TRAILING_FEAT_REGEX.replace(cleaned, "")
        cleaned = MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
        return cleaned.ifEmpty { trimmed }
    }

    fun cleanArtistName(rawArtist: String): String {
        val trimmed = rawArtist.trim()
        if (trimmed.isEmpty()) return ""

        var cleaned = ARTIST_TOPIC_REGEX.replace(trimmed, "")
        cleaned = PAREN_FEAT_REGEX.replace(cleaned, " ")
        cleaned = TRAILING_FEAT_REGEX.replace(cleaned, "")
        cleaned = MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
        return cleaned.ifEmpty { trimmed }
    }

    fun buildQueryCandidates(
        rawTrack: String,
        rawArtist: String,
    ): List<Pair<String, String>> {
        val trimmedTrack = rawTrack.trim()
        val trimmedArtist = rawArtist.trim()
        val cleanedTrack = cleanTrackTitle(trimmedTrack)
        val cleanedArtist = cleanArtistName(trimmedArtist)
        val candidates = LinkedHashSet<Pair<String, String>>(3)
        candidates.add(cleanedTrack to cleanedArtist)

        val primaryArtist =
            cleanArtistName(
                cleanedArtist
                    .split(',', '&', '•', '/')
                    .firstOrNull()
                    .orEmpty()
                    .trim(),
            )
        if (primaryArtist.isNotEmpty() && primaryArtist != cleanedArtist) {
            candidates.add(cleanedTrack to primaryArtist)
        }

        val rawPair = trimmedTrack to trimmedArtist
        if (rawPair !in candidates) {
            candidates.add(rawPair)
        }
        return candidates.toList()
    }
}
