package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.type.ArtistType
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(tableName = "artist")
data class ArtistEntity(
    @PrimaryKey(autoGenerate = false)
    val channelId: String,
    val name: String,
    val thumbnails: String?,
    val followed: Boolean = false,
    val followedAt: LocalDateTime? = now(),
    val inLibrary: LocalDateTime = now(),
) : RecentlyType,
    ArtistType {
    override fun objectType() = RecentlyType.Type.ARTIST
}