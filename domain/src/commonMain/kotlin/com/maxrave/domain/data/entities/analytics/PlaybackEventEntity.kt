package com.maxrave.domain.data.entities.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(
    tableName = "playback_event",
    indices = [Index(value = ["syncId"], unique = true)],
)
data class PlaybackEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val timestamp: LocalDateTime = now(),
    val videoId: String = "",
    val albumBrowseId: String? = null,
    val durationSecond: Long = 0, // 0 - 100
    val listenedSecond: Long = 0, // in seconds
    /** Identifies this play on every device library sync has carried it to; null on rows written before sync existed. */
    val syncId: String? = null,
    /** The device the play happened on, as library sync names it; null means this device. */
    val deviceId: String? = null,
)