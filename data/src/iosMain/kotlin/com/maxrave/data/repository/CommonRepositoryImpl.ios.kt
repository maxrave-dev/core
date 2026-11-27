package com.maxrave.data.repository

import com.maxrave.data.repository.BackupRestoreConfig
import com.maxrave.data.repository.BackupResult
import com.maxrave.data.repository.RestoreResult
import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.restore.RestoreProgress
import com.maxrave.logger.Logger
import com.eygraber.uri.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun setProxyAuthenticator(username: String, password: String) {
    // iOS does not support java.net.Authenticator; SOCKS proxy auth is not available
}

actual fun clearProxyAuthenticator() {
    // No-op on iOS
}

actual fun getCookies(url: String, packageName: String): CookieItem = CookieItem(url, emptyList())

actual suspend fun backupNative(
    uri: Uri,
    config: BackupRestoreConfig,
    progressCallback: ((RestoreProgress) -> Unit)?
): BackupResult {
    Logger.w("BackupRestore", "Backup not supported on iOS")
    return BackupResult.Failure("Backup not supported on iOS", null)
}

actual suspend fun restoreNative(
    uri: Uri,
    config: BackupRestoreConfig,
    progressCallback: ((RestoreProgress) -> Unit)?
): RestoreResult {
    Logger.w("BackupRestore", "Restore not supported on iOS")
    return RestoreResult.Failure("Restore not supported on iOS", null)
}