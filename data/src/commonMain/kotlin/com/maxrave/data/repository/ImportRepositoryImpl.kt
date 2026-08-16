package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.importdata.ImportData
import com.maxrave.domain.data.model.importdata.ImportPlaylist
import com.maxrave.domain.data.model.importdata.ImportResult
import com.maxrave.domain.data.model.importdata.ImportSong
import com.maxrave.domain.repository.ImportProgress
import com.maxrave.domain.repository.ImportRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json

private const val TAG = "ImportRepositoryImpl"

/**
 * How many songs go into one transaction.
 *
 * No DAO method takes a list and there is no ambient transaction, so writing 10,000 songs one at a
 * time means 10,000 commits. Batching is the whole performance story here.
 */
private const val SONG_BATCH_SIZE = 500

internal class ImportRepositoryImpl(
    private val localDataSource: LocalDataSource,
) : ImportRepository {
    /**
     * Same posture as the Room converters: unknown keys are dropped rather than rejected, so a file
     * written by a newer converter still imports.
     */
    private val jsonFormat =
        Json {
            ignoreUnknownKeys = true
        }

    override fun import(
        json: String,
        invalidFileMessage: String,
    ): Flow<ImportProgress> =
        flow {
            emit(ImportProgress.Preparing)

            val data =
                runCatching { jsonFormat.decodeFromString<ImportData>(json) }
                    .getOrElse { throwable ->
                        Logger.e(TAG, "import: cannot parse file - ${throwable.message}")
                        emit(ImportProgress.Error(invalidFileMessage))
                        return@flow
                    }

            // A file that parses but carries nothing is almost always the wrong file: with unknown
            // keys ignored, any JSON object decodes into an empty ImportData. Reporting that as an
            // error beats reporting a successful import of zero songs.
            if (data.songs.isEmpty() && data.playlists.isEmpty()) {
                Logger.e(TAG, "import: file has no songs and no playlists")
                emit(ImportProgress.Error(invalidFileMessage))
                return@flow
            }

            runCatching {
                val songsById = data.songs.associateBy { it.videoId }
                val total = data.songs.size

                var written = 0
                data.songs.chunked(SONG_BATCH_SIZE).forEach { chunk ->
                    localDataSource.insertSongs(chunk.map { it.toSongEntity() })
                    written += chunk.size
                    emit(ImportProgress.Importing(processed = written, total = total))
                }

                var playlistsCreated = 0
                var skippedEntries = 0
                data.playlists.forEach { playlist ->
                    // Dropping ids with no song row is what keeps the foreign key on
                    // pair_song_local_playlist satisfied; positions are then taken from the index
                    // in the filtered list so they stay contiguous from 0.
                    val videoIds = playlist.videoIds.filter { songsById.containsKey(it) }
                    skippedEntries += playlist.videoIds.size - videoIds.size
                    val playlistId =
                        localDataSource.insertLocalPlaylistWithTracks(
                            localPlaylist = playlist.toLocalPlaylistEntity(videoIds),
                            videoIds = videoIds,
                        )
                    if (playlistId != -1L) playlistsCreated++
                }

                ImportResult(
                    playlistsCreated = playlistsCreated,
                    songsImported = written,
                    skippedEntries = skippedEntries,
                )
            }.onSuccess { result ->
                Logger.i(TAG, "import: $result")
                emit(ImportProgress.Success(result))
            }.onFailure { throwable ->
                Logger.e(TAG, "import: failed while writing - ${throwable.message}")
                emit(ImportProgress.Error(throwable.message ?: invalidFileMessage))
            }
        }.flowOn(Dispatchers.IO)
}

/**
 * Mirrors `Track.toSongEntity()`, filling the runtime-only columns with the values an imported
 * track starts life with: not liked, never played, not downloaded, available.
 *
 * [ImportSong.artistId] is kept only when it lines up one-to-one with [ImportSong.artistName].
 * `SongEntity.toTrack()` walks the name list and indexes into the id list, so a non-null id list
 * that is shorter would be read past its end.
 *
 * [ImportSong.videoType] arrives from a file this app did not write, so it is normalized rather
 * than trusted: a real `MUSIC_VIDEO_TYPE_*` is kept, anything else becomes the empty "unknown"
 * the column already uses, and the playback path fills it in from the API on first play.
 */
private fun ImportSong.toSongEntity(): SongEntity =
    SongEntity(
        videoId = videoId,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId?.takeIf { it.size == (artistName?.size ?: 0) },
        artistName = artistName,
        duration = duration,
        durationSeconds = durationSeconds,
        isAvailable = true,
        isExplicit = isExplicit,
        likeStatus = "",
        thumbnails = thumbnails,
        title = title,
        videoType = MusicVideoType.normalize(videoType) ?: "",
        category = null,
        resultType = null,
        liked = false,
        totalPlayTime = 0,
        downloadState = 0,
    )

/**
 * Imported playlists are local-only: no YouTube id, no sync state, nothing to download yet.
 *
 * [videoIds] is the already-filtered track list, written to the `tracks` column once rather than
 * updated per track.
 */
private fun ImportPlaylist.toLocalPlaylistEntity(videoIds: List<String>): LocalPlaylistEntity =
    LocalPlaylistEntity(
        title = title,
        thumbnail = thumbnail,
        tracks = videoIds,
    )
