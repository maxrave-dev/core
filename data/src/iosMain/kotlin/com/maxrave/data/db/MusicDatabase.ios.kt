package com.maxrave.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.maxrave.common.DB_NAME
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual fun getDatabaseBuilder(): RoomDatabase.Builder<MusicDatabase> {
    val dbFilePath = documentDirectory() + "/$DB_NAME"
    return Room.databaseBuilder<MusicDatabase>(
        name = dbFilePath,
    )
}

@OptIn(ExperimentalForeignApi::class)
fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}

actual fun getDatabasePath(): String {
    return documentDirectory() + "/$DB_NAME"
}