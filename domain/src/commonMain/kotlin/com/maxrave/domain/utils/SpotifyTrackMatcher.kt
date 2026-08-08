package com.maxrave.domain.utils

import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.spotify.SpotifyTrack

/**
 * Ports spotube's `SourcedTrack.rankResults` matching heuristic (spotube's own
 * Spotify-track -> YouTube-candidate scoring, see `sourced_track.dart`) so a Spotify
 * track can be matched against a YouTube Music search result with the same fuzziness
 * spotube uses: no ISRC, no phonetic/edit-distance matching - just title/artist
 * substring containment plus an "official audio/video" bonus.
 */
private val officialMusicRegex = Regex("official (video|audio|music video|lyric video|visualizer)", RegexOption.IGNORE_CASE)

/**
 * Returns the highest-scoring [SongsResult] for [track] among [candidates], or `null`
 * if [candidates] is empty. Ties keep the earlier (higher search-rank) candidate.
 */
fun rankSongMatches(
    track: SpotifyTrack,
    candidates: List<SongsResult>,
): SongsResult? =
    candidates.maxByOrNull { candidate -> scoreCandidate(track, candidate) }

private fun scoreCandidate(
    track: SpotifyTrack,
    candidate: SongsResult,
): Int {
    val title = candidate.title.orEmpty()
    val candidateArtistNames = candidate.artists?.mapNotNull { it.name }.orEmpty()

    var score = 0

    // +1 if any Spotify artist name case-matches one of the candidate's own artists/channel.
    if (track.artists.any { artist -> candidateArtistNames.any { it.equals(artist, ignoreCase = true) } }) {
        score += 1
    }

    // +1 if the candidate's title contains an artist name.
    val titleContainsArtist = track.artists.any { artist -> artist.isNotBlank() && title.contains(artist, ignoreCase = true) }
    if (titleContainsArtist) {
        score += 1
    }

    // +3 if the candidate's title contains the track name.
    val titleContainsTrackName = track.title.isNotBlank() && title.contains(track.title, ignoreCase = true)
    if (titleContainsTrackName) {
        score += 3
    }

    // +1 if the title looks like an official upload.
    val isOfficial = officialMusicRegex.containsMatchIn(title)
    if (isOfficial) {
        score += 1
    }

    // +2 combo bonus for official + exact track name.
    if (isOfficial && titleContainsTrackName) {
        score += 2
    }

    return score
}
