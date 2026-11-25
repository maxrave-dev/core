package com.maxrave.data.repository

import com.maxrave.domain.restore.RestoreProgress

/**
 * Data structure representing what gets backed up.
 * Each field represents a folder or file in the backup zip.
 */
data class BackupContents(
    val hasSettings: Boolean = false,
    val hasMainDatabase: Boolean = false,
    val hasExoPlayerDatabase: Boolean = false,
    val downloadFileCount: Int = 0,
    val artworkFileCount: Int = 0,
    val canvasFileCount: Int = 0,
)

/**
 * Result of a backup operation.
 */
sealed interface BackupResult {
    data class Success(val contents: BackupContents) : BackupResult
    data class Failure(val message: String, val cause: Throwable?) : BackupResult
}

/**
 * Result of a restore operation.
 */
sealed interface RestoreResult {
    data class Success(val restoredContents: BackupContents) : RestoreResult
    data class Failure(val message: String, val cause: Throwable?) : RestoreResult
}

/**
 * Configuration for backup/restore operations.
 */
data class BackupRestoreConfig(
    val includeDownloads: Boolean = true,
    val includeArtwork: Boolean = true,
    val includeCanvas: Boolean = true,
)

/**
 * Folder names used in backup zip structure.
 */
object BackupFolders {
    const val SETTINGS = "datastore"
    const val MAIN_DATABASE = "databases/main"
    const val EXOPLAYER_DATABASE = "databases/exoplayer"
    const val DOWNLOADS = "media/downloads"
    const val ARTWORK = "media/artwork"
    const val CANVAS = "media/canvas"
    
    const val SETTINGS_FILE = "$SETTINGS_FILE_NAME.preferences_pb"
    const val SETTINGS_FILE_NAME = "simpmusic_settings"
    const val MAIN_DB_FILE = "simpmusic.db"
    const val EXOPLAYER_DB_FILE = "exoplayer_internal.db"
}

/**
 * File names for backup/restore.
 */
object BackupFiles {
    const val SETTINGS_FILE_NAME = "simpmusic_settings"
    const val MAIN_DB_FILE = "simpmusic.db"
    const val EXOPLAYER_DB_FILE = "exoplayer_internal.db"
}