package com.maxrave.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.maxrave.common.DB_NAME
import java.io.File

actual fun getDatabaseBuilder(): RoomDatabase.Builder<MusicDatabase> {
    val dbFile = File(System.getProperty("java.io.tmpdir"), DB_NAME)
    return Room.databaseBuilder<MusicDatabase>(
        name = dbFile.absolutePath,
    )
}

actual fun getDatabasePath(): String {
    val dbFile = File(System.getProperty("java.io.tmpdir"), DB_NAME)
    return dbFile.absolutePath
}