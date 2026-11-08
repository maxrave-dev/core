package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.entities.DownloadState.STATE_NOT_DOWNLOADED
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(tableName = "song")
data class SongEntity(
    @PrimaryKey(autoGenerate = false) val videoId: String = "",
    val albumId: String? = null,
    val albumName: String? = null,
    val artistId: List<String>? = null,
    val artistName: List<String>? = null,
    val duration: String,
    val durationSeconds: Int,
    val isAvailable: Boolean,
    val isExplicit: Boolean,
    val likeStatus: String,
    val thumbnails: String? = null,
    val title: String,
    val videoType: String,
    /**
     * Read by nothing that decides anything; the mappers only copy it. Smart downloads use it as
     * their marker: a downloaded song holding [SMART_DOWNLOAD] was fetched automatically and may be
     * removed again, and one without it was downloaded by the user.
     */
    val category: String?,
    val resultType: String?,
    val liked: Boolean = false,
    val totalPlayTime: Long = 0,
    val downloadState: Int = STATE_NOT_DOWNLOADED,
    val favoriteAt: LocalDateTime? = now(),
    val downloadedAt: LocalDateTime? = now(),
    val inLibrary: LocalDateTime = now(),
    val canvasUrl: String? = null,
    val canvasThumbUrl: String? = null,
) : RecentlyType {
    override fun objectType(): RecentlyType.Type = RecentlyType.Type.SONG

    companion object {
        /** [category] of a song Smart downloads fetched on their own. */
        const val SMART_DOWNLOAD = "SMART_DOWNLOAD"
    }
}