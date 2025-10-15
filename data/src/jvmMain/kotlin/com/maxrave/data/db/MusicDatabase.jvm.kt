package com.maxrave.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.maxrave.common.DB_NAME
import com.maxrave.data.io.getHomeFolderPath
import java.io.File

actual fun getDatabaseBuilder(): RoomDatabase.Builder<MusicDatabase> {
    return Room.databaseBuilder<MusicDatabase>(
        name = getDatabasePath()
    )
}

actual fun getDatabasePath(): String {
    val dbFile = File(getHomeFolderPath(listOf(".simpmusic", "db")), DB_NAME)
    return dbFile.absolutePath
}