package com.maxrave.domain.data.model.importdata

import kotlinx.serialization.Serializable

/**
 * The file the SimpMusic web converter produces from a Spotify export or another client's backup.
 *
 * The web side has already matched every track to a YouTube Music videoId, so nothing here is
 * looked up again — the app only parses this and writes rows. The converter caps a file at 10,000
 * songs and 500 playlists, which is what lets the whole thing be decoded in one pass.
 *
 * See docs/import-format-v1.md for the field-by-field contract.
 */
@Serializable
data class ImportData(
    val songs: List<ImportSong> = emptyList(),
    val playlists: List<ImportPlaylist> = emptyList(),
)

/**
 * One track. [videoId] is unique across the whole file — playlists reference songs by id only.
 *
 * [artistId] is positionally aligned with [artistName]. A shorter-but-non-null [artistId] would be
 * read past its end when a stored song is turned back into a track, so the importer drops the id
 * list entirely whenever the two sizes disagree.
 */
@Serializable
data class ImportSong(
    val videoId: String,
    val title: String,
    val artistName: List<String>? = null,
    val artistId: List<String>? = null,
    val albumName: String? = null,
    val albumId: String? = null,
    val duration: String = "",
    val durationSeconds: Int = 0,
    val isExplicit: Boolean = false,
    val thumbnails: String? = null,
    val videoType: String = "",
)

/**
 * One playlist, created locally and never synced to YouTube.
 *
 * [videoIds] carries the track order. An id with no matching entry in [ImportData.songs] is skipped
 * rather than written, because `pair_song_local_playlist` has a foreign key to `song.videoId`.
 */
@Serializable
data class ImportPlaylist(
    val title: String,
    val thumbnail: String? = null,
    val videoIds: List<String> = emptyList(),
)

/**
 * What an import actually wrote, reported to the user when it finishes.
 *
 * [skippedEntries] counts playlist entries whose videoId had no song in the file — a converter bug
 * rather than a user error, but worth surfacing so a half-empty playlist is not a mystery.
 */
data class ImportResult(
    val playlistsCreated: Int,
    val songsImported: Int,
    val skippedEntries: Int,
)
