package com.maxrave.data.librarysync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import createDataStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

// iOS takes no part in library sync; the file only exists so the shared recorder has state to read,
// and it stays unpaired, so nothing is ever recorded.
@OptIn(ExperimentalForeignApi::class)
private fun documents(): String {
    val directory: NSURL? =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
    return requireNotNull(directory).path!!
}

actual fun createLibrarySyncDataStore(): DataStore<Preferences> = createDataStore(producePath = { documents() + "/library_sync.preferences_pb" })

actual fun syncBackupDirectory(): String = documents() + "/sync-backups"
