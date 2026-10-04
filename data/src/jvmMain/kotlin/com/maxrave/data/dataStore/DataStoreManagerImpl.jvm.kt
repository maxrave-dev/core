package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.data.io.getHomeFolderPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

actual fun createDataStoreInstance(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage =
            ReadsWaitForWritesStorage(
                FileStorage(PreferencesFileSerializer) {
                    File(getHomeFolderPath(listOf(".simpmusic")), "$SETTINGS_FILENAME.preferences_pb").absoluteFile
                },
            ),
    )

/**
 * Makes a disk read of the settings file wait while a save is replacing it. Without this, Windows
 * users crash with "Unable to rename settings.preferences_pb.tmp" (#2357, #2400, #2521 and others).
 *
 * DataStore 1.2.1 saves by writing `settings.preferences_pb.tmp` and moving it over the real file,
 * and on Windows that move starts by deleting the real file. A read that arrives mid-save finds its
 * cache behind and goes to disk, and DataStore lets it run without waiting (its `readScope` only
 * tryLocks). The read opens the real file with `FileInputStream`, which the JDK opens without
 * `FILE_SHARE_DELETE`, so Windows refuses the delete and the save throws. POSIX lets a file be
 * deleted or renamed over while it is open, which is why only Windows ever crashed. Still unfixed
 * upstream as of 1.3.0-alpha11 (b/203087070).
 *
 * The lock cannot deadlock because DataStore enters the connection only through `readData()` and
 * `writeScope`, never one inside the other, and always takes its own coordinator lock before this
 * one. Re-check both when bumping DataStore.
 */
private class ReadsWaitForWritesStorage<T>(
    private val delegate: Storage<T>,
) : Storage<T> {
    override fun createConnection(): StorageConnection<T> {
        val connection = delegate.createConnection()
        val lock = Mutex()
        return object : StorageConnection<T> by connection {
            override suspend fun <R> readScope(block: suspend ReadScope<T>.(locked: Boolean) -> R): R =
                lock.withLock { connection.readScope(block) }

            override suspend fun writeScope(block: suspend WriteScope<T>.() -> Unit) =
                lock.withLock { connection.writeScope(block) }
        }
    }
}
