package com.maxrave.domain.repository

import com.maxrave.domain.data.model.importdata.ImportResult
import kotlinx.coroutines.flow.Flow

/**
 * Writes a file produced by the SimpMusic web converter into the local database.
 *
 * The web side already resolved every track to a YouTube Music videoId, so this does no network
 * work at all — it parses, then writes songs and local playlists in batches.
 */
interface ImportRepository {
    /**
     * @param json the whole file, already read into memory. Bounded by the converter's 10,000
     * song / 500 playlist caps.
     * @param invalidFileMessage what to report when the file cannot be parsed or carries no data.
     * Passed in rather than built here because string resources live in the app module — the same
     * shape as [LocalPlaylistRepository.addTrackToLocalPlaylist].
     */
    fun import(
        json: String,
        invalidFileMessage: String,
    ): Flow<ImportProgress>
}

/**
 * Progress of one import.
 *
 * [com.maxrave.domain.utils.LocalResource.Loading] carries no payload, so it cannot say "X of Y
 * songs"; this is the smallest type that can, and it keeps the same success/error shape the rest of
 * the app already collects.
 */
sealed interface ImportProgress {
    /** Reading and parsing the file. No counts are known yet. */
    data object Preparing : ImportProgress

    /** [processed] of [total] songs written. Emitted once per batch, not once per song. */
    data class Importing(
        val processed: Int,
        val total: Int,
    ) : ImportProgress

    data class Success(
        val result: ImportResult,
    ) : ImportProgress

    data class Error(
        val message: String,
    ) : ImportProgress
}
