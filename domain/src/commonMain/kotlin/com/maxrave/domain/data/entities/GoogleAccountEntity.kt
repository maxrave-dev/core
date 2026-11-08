package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GoogleAccountEntity(
    @PrimaryKey(autoGenerate = false)
    val email: String = "",
    val name: String = "",
    val thumbnailUrl: String = "",
    val pageId: String? = null,
    // `authuser` index of the Google account owning this channel inside the cookie's
    // browser session. @ColumnInfo(defaultValue) is REQUIRED: Room AutoMigration adds this
    // NOT NULL column and needs a SQL default to backfill existing rows.
    @ColumnInfo(defaultValue = "0")
    val authUser: Int = 0,
    val cache: String? = null,
    val isUsed: Boolean = false,
    val netscapeCookie: String? = null,
)