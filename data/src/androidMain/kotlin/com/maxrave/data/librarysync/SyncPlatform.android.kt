package com.maxrave.data.librarysync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.maxrave.data.db.MusicDatabase
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toPath
import org.koin.mp.KoinPlatform.getKoin
import java.io.File

// Beside settings.preferences_pb, which the backup copies; this file is never in a backup.
actual fun createLibrarySyncDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        // A file that cannot be read starts over unpaired instead of failing every synced write.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { getKoin().get<Context>().filesDir.resolve("datastore/library_sync.preferences_pb").absolutePath.toPath() },
    )

actual fun syncBackupDirectory(): String =
    getKoin()
        .get<Context>()
        .filesDir
        .resolve("sync-backups")
        .absolutePath

private const val BACKUP_TAG = "LibrarySync"
private const val BACKUPS_KEPT = 3
private const val BACKUP_EVERY_MS = 60L * 60 * 1000

/**
 * A copy of the whole database before the first merge of a pairing or a merge that removes something.
 * At most one an hour (an hour-old copy is as good a way back), three kept. A failed copy is logged
 * and the merge goes on: every change it carries is one the user made on the other device.
 */
internal suspend fun backupBeforeMerge(
    database: MusicDatabase,
    prefs: SyncPrefs,
) {
    val now = nowMillis()
    if (now - prefs.current().lastBackupAt < BACKUP_EVERY_MS) return
    try {
        val directory = File(syncBackupDirectory()).apply { mkdirs() }
        database.backupTo(File(directory, "library-$now.db").absolutePath)
        directory
            .listFiles { file -> file.name.startsWith("library-") && file.name.endsWith(".db") }
            ?.sortedByDescending { it.name }
            ?.drop(BACKUPS_KEPT)
            ?.forEach { it.delete() }
        prefs.update { it.copy(lastBackupAt = now) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Logger.e(BACKUP_TAG, "backup before merge failed", e)
    }
}
