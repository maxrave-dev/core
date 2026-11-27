package com.maxrave.domain.repository

import com.maxrave.data.repository.BackupContents
import com.maxrave.data.repository.BackupRestoreConfig
import com.maxrave.data.repository.BackupResult
import com.maxrave.data.repository.RestoreResult
import com.maxrave.domain.data.entities.NotificationEntity
import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.data.model.library.LibraryOverview
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.restore.RestoreProgress
import kotlinx.coroutines.flow.Flow
import com.eygraber.uri.Uri

interface CommonRepository {
    fun init(cookiePath: String, dataStoreManager: DataStoreManager, isDevBuild: Boolean = false)

    /** The configured AI provider's model IDs, from its `GET /models`. Fails when it has none to list. */
    suspend fun getAiModels(): Result<List<String>>

    // Database
    fun closeDatabase()

    fun getDatabasePath(): String?

    suspend fun databaseDaoCheckpoint()

    // Recently data
    fun getAllRecentData(): Flow<List<RecentlyType>>

    /** Counts and newest artwork for the Your library cards; re-emits whenever any of them changes. */
    fun getLibraryOverview(): Flow<LibraryOverview>

    // Notifications
    suspend fun insertNotification(notificationEntity: NotificationEntity)

    suspend fun getAllNotifications(): Flow<List<NotificationEntity>?>

    suspend fun isNotificationExists(link: String): Boolean

    suspend fun deleteNotification(id: Long)

    suspend fun writeTextToFile(text: String, filePath: String): Boolean

    suspend fun getCookiesFromInternalDatabase(url: String, packageName: String): CookieItem

    // Backup & Restore
    suspend fun backupTo(
        uri: Uri,
        config: BackupRestoreConfig,
        progressCallback: ((RestoreProgress) -> Unit)? = null
    ): BackupResult

    suspend fun restoreFrom(
        uri: Uri,
        config: BackupRestoreConfig,
        progressCallback: ((RestoreProgress) -> Unit)? = null
    ): RestoreResult
}