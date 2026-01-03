package com.simpmusic.localmusic

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_songs")
data class SongEntity(
    @PrimaryKey val id: String, // unique id (e.g., generated hash or GUID)
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "album") val album: String?,
    @ColumnInfo(name = "duration") val durationMs: Long?,
    @ColumnInfo(name = "file_path") val filePath: String, // app-local path or content Uri string
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "art_uri") val artUri: String? // path or content uri for album art
)
