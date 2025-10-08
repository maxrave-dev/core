package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(tableName = "notification")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelId: String,
    val thumbnail: String? = null,
    val name: String,
    val single: List<Map<String, String>> = listOf(),
    val album: List<Map<String, String>> = listOf(),
    val time: LocalDateTime = now(),
)