package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.Run

/**
 * Classifies the `runs` and `flexColumns` of a song row the way YouTube Music actually describes
 * them, instead of guessing from displayed text.
 *
 * ## Why this exists
 * A song row's columns are NOT at fixed indices — a plain playlist, a collaborative playlist (which
 * inserts a duration column), an album track list and an unavailable item all lay them out
 * differently. Assuming "artists are always column 1" then forces a second wrong guess: the column
 * really holds `Artist • Album • 1.2M plays`, so the view count has to be filtered back out by
 * matching a localised word. That filter breaks on every locale it was not written for, and the
 * play count leaks into the artist name.
 *
 * The fix is to stop guessing. Every linked run carries a `pageType`
 * (`MUSIC_PAGE_TYPE_ARTIST`, `MUSIC_PAGE_TYPE_ALBUM`, ...) that states what it is, so the column
 * can be identified from structure. Once the artist column is identified correctly it contains only
 * artists, and no view-count filtering is needed at all.
 *
 * Ported from `sigma67/ytmusicapi` — `parsers/songs.py` (`parse_song_run`, `parse_views`) and
 * `parsers/playlists.py` (the flex-column scan in `parse_playlist_item`), which is the reference
 * implementation for reading YouTube Music's private API.
 */

/** What a single run inside a song row turned out to be. */
internal enum class SongRunType {
    ALBUM,
    ARTIST,
    DURATION,
    YEAR,
    VIEWS,
}

/** `3:45` or `1:23:45`. */
private val DURATION_REGEX = Regex("^(\\d+:)*\\d+:\\d+$")

/** A bare four-digit release year. */
private val YEAR_REGEX = Regex("^\\d{4}$")

private val LATIN_LETTER_REGEX = Regex("[a-zA-Z]")

/**
 * The localised "views" word plus any bidirectional control marks, up to the first digit —
 * e.g. `조회수 17억회`, `播放次數：4505`.
 *
 * Spelled with \u escapes deliberately. The class ends in a fullwidth colon followed by two ranges
 * of INVISIBLE bidi controls (LRM/RLM, then LRE..RLO); written literally they show up as nothing at
 * all in a diff, and a single stray copy-paste would silently change what the pattern matches.
 */
private val VIEWS_PREFIX_REGEX = Regex("^\\D*?[\\s:\uFF1A\u200E-\u200F\u202A-\u202E]")

/** Non-breaking space; some locales use it to glue the number to its magnitude ("1,7 Mrd. Aufrufe"). */
private const val NBSP = '\u00A0'

/**
 * Returns the view count contained in [text], or null when [text] is not a view count.
 *
 * The two guards are what make this safe across locales, and both are load-bearing:
 *  - The prefix is only stripped when the text has NO Latin letters. In a Latin script an artist
 *    like "Maroon 5" is indistinguishable from a count whose leading word was just removed.
 *  - A bare ASCII token with no space is an artist, not a count — otherwise "2Pac" parses as one.
 */
internal fun parseViews(text: String): String? {
    var value = text
    var prefixStripped = false

    if (!LATIN_LETTER_REGEX.containsMatchIn(value)) {
        val stripped = VIEWS_PREFIX_REGEX.replaceFirst(value, "")
        prefixStripped = stripped != value
        value = stripped
    }

    if (value.firstOrNull()?.isDigit() != true) return null
    if (!prefixStripped && value.all { it.code < 128 } && !value.contains(' ')) return null

    // CJK has no separator at all ("3406万回視聴"), so keep at most the number and its magnitude.
    val head = value.substringBefore(' ').split(NBSP)
    return head.take(2).joinToString(NBSP.toString())
}

/**
 * Decides what a single run represents.
 *
 * A run carrying a `browseEndpoint` names itself: `MPRE...` (or a `release_detail` browse id) is an
 * album, anything else linked is an artist. Only unlinked runs need the text-shaped checks, and
 * those are ordered cheapest-and-most-certain first.
 */
internal fun classifySongRun(run: Run): SongRunType {
    val browseId = run.navigationEndpoint?.browseEndpoint?.browseId
    if (browseId != null) {
        return if (browseId.startsWith("MPRE") || browseId.contains("release_detail")) {
            SongRunType.ALBUM
        } else {
            SongRunType.ARTIST
        }
    }

    val text = run.text
    return when {
        DURATION_REGEX.matches(text) -> SongRunType.DURATION
        YEAR_REGEX.matches(text) -> SongRunType.YEAR
        parseViews(text) != null -> SongRunType.VIEWS
        // An unlinked run that is none of the above is an artist without a channel.
        else -> SongRunType.ARTIST
    }
}

/**
 * Converts a displayed duration to seconds: `4:05` → 245, `1:23:45` → 5025.
 *
 * Walks the parts right to left multiplying by 1, 60, 3600, which is what makes hour-long tracks
 * work — the previous `parts[0] * 60 + parts[1]` read a `1:23:45` as 83 seconds, and threw outright
 * on any string without a separator because it indexed a one-element list.
 *
 * Returns 0 for anything unparseable; a missing duration is normal on unavailable rows.
 */
internal fun parseDurationSeconds(text: String?): Int {
    val parts = text?.trim()?.takeIf { it.isNotEmpty() }?.split(':', '.') ?: return 0
    var seconds = 0
    var multiplier = 1
    for (part in parts.asReversed()) {
        val value = part.trim().toIntOrNull() ?: return 0
        seconds += value * multiplier
        multiplier *= 60
    }
    return seconds
}

/**
 * Which flex column holds what, for one song row. A null index means the row does not carry that
 * field — a track with no album link is normal, so callers must treat null as "absent", not "bug".
 */
internal data class SongColumns(
    val titleIndex: Int? = null,
    val artistIndex: Int? = null,
    val albumIndex: Int? = null,
    val durationIndex: Int? = null,
)

/**
 * Locates the columns of a song row by reading each column's own `pageType`, rather than assuming
 * an order.
 *
 * Mirrors the scan in `ytmusicapi`'s `parse_playlist_item`, including both artist fallbacks:
 * a non-clickable artist has no endpoint at all and shows up as the first unclassifiable column,
 * and a non-music video (a podcast episode, say) lists channels instead of artists, where the LAST
 * channel is the one to use.
 */
internal fun findSongColumns(data: MusicResponsiveListItemRenderer): SongColumns {
    var titleIndex: Int? = null
    var artistIndex: Int? = null
    var albumIndex: Int? = null
    var durationIndex: Int? = null
    var unrecognizedIndex: Int? = null
    val userChannelIndexes = mutableListOf<Int>()

    for (index in data.flexColumns.indices) {
        val run =
            data.flexColumns[index]
                .musicResponsiveListItemFlexColumnRenderer
                .text
                ?.runs
                ?.firstOrNull()
                ?: continue
        val endpoint = run.navigationEndpoint

        if (endpoint == null) {
            when (classifySongRun(run)) {
                SongRunType.DURATION -> durationIndex = index
                // Remember only the first one; later unlinked columns are metadata, not artists.
                else -> if (unrecognizedIndex == null) unrecognizedIndex = index
            }
            continue
        }

        if (endpoint.watchEndpoint != null) {
            titleIndex = index
            continue
        }

        when (
            endpoint.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType
        ) {
            MUSIC_PAGE_TYPE_ARTIST -> artistIndex = index
            MUSIC_PAGE_TYPE_ALBUM, MUSIC_PAGE_TYPE_AUDIOBOOK -> albumIndex = index
            MUSIC_PAGE_TYPE_USER_CHANNEL -> userChannelIndexes.add(index)
        }
    }

    if (artistIndex == null) artistIndex = unrecognizedIndex
    if (artistIndex == null) artistIndex = userChannelIndexes.lastOrNull()

    return SongColumns(
        titleIndex = titleIndex,
        artistIndex = artistIndex,
        albumIndex = albumIndex,
        durationIndex = durationIndex,
    )
}

/**
 * Reads the album out of the column [findSongColumns] identified, name included.
 *
 * The name is right there in the column's own text. Pulling the album from the row's context menu
 * instead only yields a browse id, which is why the name previously had to be filled in with the
 * placeholder string "Album" — and that placeholder then travelled all the way out to MediaSession
 * metadata and any external scrobbler reading it.
 */
internal fun parseSongAlbumAt(
    data: MusicResponsiveListItemRenderer,
    index: Int?,
): Album? {
    val run =
        data.flexColumns
            .getOrNull(index ?: return null)
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull() ?: return null
    val browseId = run.navigationEndpoint?.browseEndpoint?.browseId ?: return null
    return Album(id = browseId, name = run.text)
}

/**
 * Finds the view count anywhere in the row, or null when the row shows none.
 *
 * The count is not in a column of its own — it is the trailing run of the subtitle column, after
 * the artists and the album ("Charli xcx • Brat • 8M views"). Scanning every column and asking
 * [classifySongRun] avoids caring which column that turned out to be.
 *
 * Returns just the number and its magnitude ("8M"), matching what the UI appends its own label to.
 */
internal fun parseSongViews(data: MusicResponsiveListItemRenderer): String? {
    for (column in data.flexColumns) {
        val runs = column.musicResponsiveListItemFlexColumnRenderer.text?.runs ?: continue
        for (i in runs.indices step 2) {
            if (classifySongRun(runs[i]) == SongRunType.VIEWS) return parseViews(runs[i].text)
        }
    }
    return null
}

/**
 * Reads the artists out of the column [findSongColumns] identified.
 *
 * Odd runs are separators (" • "), so only even ones carry values. Runs that turn out to be a
 * duration, a year or a view count are dropped — they can only appear here when the row had no
 * dedicated artist column and the fallback picked a mixed column, and dropping them is what keeps
 * "1.2M plays" out of the artist name without matching any localised word.
 */
internal fun parseSongArtistsAt(
    data: MusicResponsiveListItemRenderer,
    index: Int?,
): List<Artist> {
    val runs =
        data.flexColumns
            .getOrNull(index ?: return emptyList())
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs ?: return emptyList()

    val artists = mutableListOf<Artist>()
    for (i in runs.indices step 2) {
        val run = runs[i]
        if (classifySongRun(run) != SongRunType.ARTIST) continue
        artists.add(
            Artist(
                id = run.navigationEndpoint?.browseEndpoint?.browseId,
                name = run.text,
            ),
        )
    }
    return artists
}
