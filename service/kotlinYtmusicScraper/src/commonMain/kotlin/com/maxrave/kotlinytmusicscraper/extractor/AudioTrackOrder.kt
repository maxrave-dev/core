package com.maxrave.kotlinytmusicscraper.extractor

private val XTAGS = Regex("[?&]xtags=([^&]+)")
private val CLEN = Regex("[?&]clen=(\\d+)")

/**
 * A video with several audio tracks (dubbed podcasts, MrBeast-style channels) lists every one of
 * them under the SAME itag, ordered by language code — so matching by itag alone plays whichever
 * language sorts first. Measured on `0e3GPea1Tyg`: 25 tracks per itag, Arabic first, the original
 * English at position 23. Each googlevideo URL names its own track in the `xtags` query value, e.g.
 * `acont=original:drc=1:lang=en-US` or `acont=dubbed-auto:lang=vi`.
 *
 * Stable-sorts the (itag, url) pairs so that, per itag, the first match is a track in
 * [preferredLanguage], then the original, then everything else. A single-track video carries no
 * `xtags` at all, ranks as original and keeps its order — songs are unaffected.
 */
internal fun List<Pair<Int, String>>.orderByAudioTrack(preferredLanguage: String?): List<Pair<Int, String>> =
    sortedBy { (_, url) -> audioTrackRank(url, preferredLanguage) }

internal fun audioTrackRank(
    url: String,
    preferredLanguage: String?,
): Int {
    val tags =
        XTAGS
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.replace("%3D", "=", ignoreCase = true)
            ?.replace("%3A", ":", ignoreCase = true)
            ?.split(':')
            ?.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            .orEmpty()
    val acont = tags["acont"]
    val lang = tags["lang"]?.substringBefore('-')
    return when {
        // "descriptive" is narration for blind viewers — never what a language preference means.
        !preferredLanguage.isNullOrBlank() &&
            acont != "descriptive" &&
            lang.equals(preferredLanguage.substringBefore('-'), ignoreCase = true) -> 0
        acont == null || acont == "original" -> 1
        else -> 2
    }
}

/**
 * Byte length of the exact file [url] points at. The stream is requested with `range=0-<length>`,
 * so the length must come from the chosen URL — the player response lists every language under one
 * itag, and each dub has its own size.
 */
internal fun contentLengthOf(url: String): Long? = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull()
