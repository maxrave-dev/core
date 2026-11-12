package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maxrave.domain.data.type.HomeContentType
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(
    tableName = "local_playlist",
    indices = [Index(value = ["syncId"], unique = true)],
)
@Suppress("ktlint:standard:property-naming")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val thumbnail: String? = null,
    val inLibrary: LocalDateTime = now(),
    val downloadedAt: LocalDateTime? = now(),
    val downloadState: Int = DownloadState.STATE_NOT_DOWNLOADED,
    val youtubePlaylistId: String? = null,
    @ColumnInfo(name = "youtube_sync_state", defaultValue = "0")
    val syncState: Int = YouTubeSyncState.NotSynced,
    val tracks: List<String>? = null,
    /**
     * The playlist's identity across devices for library sync. [id] is autoincremented per database, so
     * the same playlist has a different [id] on every device. Null until sync first needs it.
     */
    val syncId: String? = null,
    // Only synced with YouTube playlist
    // val listSetVideoId: List<String>? = null,
) : PlaylistType,
    HomeContentType {
    override fun playlistType(): PlaylistType.Type = PlaylistType.Type.LOCAL

    object YouTubeSyncState {
        const val NotSynced = 0
        const val Syncing = 1
        const val Synced = 2
    }
}