package com.maxrave.domain.utils

/**
 * Builds a YouTube Music search query out of a track title + first artist, stripping the
 * same feature/joiner noise (`feat.`, `ft.`, `&`, `và`, `và`, etc.) that
 * `LyricsCanvasRepositoryImpl.getCanvas` already strips before searching Spotify for Canvas -
 * same idea, just aimed at YouTube Music instead.
 */
fun buildYoutubeSearchQuery(
    title: String,
    firstArtist: String?,
): String =
    "$title ${firstArtist.orEmpty()}"
        .replace(Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "), " ")
        .replace(Regex("( và | & | и | e | und |, |和| dan)"), " ")
        .replace("  ", " ")
        .replace(Regex("([()])"), "")
        .replace(".", " ")
        .replace("  ", " ")
        .trim()
