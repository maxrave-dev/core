package com.maxrave.data.repository

import com.maxrave.common.DB_NAME
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.io.getHomeFolderPath
import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.restore.RestoreProgress
import com.maxrave.simpmusic.viewModel.BackupResult
import com.maxrave.simpmusic.viewModel.BackupContents
import com.maxrave.simpmusic.viewModel.BackupRestoreConfig
import com.maxrave.simpmusic.viewModel.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import org.koin.mp.KoinPlatform.getKoin
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.net.Authenticator
import java.net.PasswordAuthentication
import kotlin.system.exitProcess
import com.eygraber.uri.Uri

actual fun setProxyAuthenticator(username: String, password: String) {
    Authenticator.setDefault(
        object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType == RequestorType.PROXY) {
                    return PasswordAuthentication(username, password.toCharArray())
                }
                return null
            }
        },
    )
}

actual fun clearProxyAuthenticator() {
    Authenticator.setDefault(null)
}

actual fun getCookies(url: String, packageName: String): CookieItem = CookieItem(url, emptyList())

actual suspend fun backupNative(
    uri: com.eygraber.uri.Uri,
    config: BackupRestoreConfig,
    progressCallback: ((RestoreProgress) -> Unit)?
): BackupResult {
    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    return try {
        report(RestoreProgress.Started("Backup in progress"))

        FileOutputStream(File(uri.toString())).use { fileOutput ->
            fileOutput.buffered().use { bufferedOutput ->
                ZipOutputStream(bufferedOutput).use { zipOutputStream ->
                    // Phase 1: Settings
                    report(RestoreProgress.Step("Backing up settings...", 1, 2))
                    val settingsFile = File(getHomeFolderPath(listOf(".simpmusic")), "$SETTINGS_FILENAME.preferences_pb")
                    if (settingsFile.exists()) {
                        zipOutputStream.putNextEntry(ZipEntry("datastore/$SETTINGS_FILENAME.preferences_pb"))
                        settingsFile.inputStream().buffered().use { inputStream ->
                            inputStream.copyTo(zipOutputStream)
                        }
                        zipOutputStream.closeEntry()
                    }

                    // Phase 2: Main Database
                    report(RestoreProgress.Step("Backing up database...", 2, 2))
                    runBlocking(Dispatchers.IO) {
                        localDataSource.checkpoint()
                    }
                    val dbPath = getKoin().get<MusicDatabase>().getDatabasePath()
                    FileInputStream(dbPath).use { inputStream ->
                        zipOutputStream.putNextEntry(ZipEntry("databases/main/simpmusic.db"))
                        inputStream.copyTo(zipOutputStream)
                        zipOutputStream.closeEntry()
                    }
                }
            }
        }

        report(RestoreProgress.Completed("Backup created successfully"))

        BackupResult.Success(
            BackupContents(
                hasSettings = true,
                hasMainDatabase = true,
                hasExoPlayerDatabase = false,
                downloadFileCount = 0,
                artworkFileCount = 0,
                canvasFileCount = 0,
            )
        )
    } catch (e: Exception) {
        BackupResult.Failure("Backup failed: ${e.message}", e)
    }
}

actual suspend fun restoreNative(
    uri: com.eygraber.uri.Uri,
    config: BackupRestoreConfig,
    progressCallback: ((RestoreProgress) -> Unit)?
): RestoreResult {
    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    return try {
        report(RestoreProgress.Started("Restore in progress"))

        ZipInputStream(FileInputStream(File(uri.toString()))).use { zipInputStream ->
            var entry: java.util.zip.ZipEntry? = zipInputStream.nextEntry()
            while (entry != null) {
                report(RestoreProgress.Step("Restoring ${entry.name}", 1, 2))
                Logger.d("BackupRestore", "Processing entry: ${entry.name}")
                when {
                    entry.name == "datastore/$SETTINGS_FILENAME.preferences_pb" -> {
                        val settingsFile = File(getHomeFolderPath(listOf(".simpmusic")), "$SETTINGS_FILENAME.preferences_pb")
                        settingsFile.parentFile?.mkdirs()
                        settingsFile.outputStream().use { outputStream ->
                            zipInputStream.copyTo(outputStream)
                        }
                    }
                    entry.name == "databases/main/simpmusic.db" -> {
                        report(RestoreProgress.Step("Restoring database...", 1, 2))
                        runBlocking(Dispatchers.IO) {
                            localDataSource.checkpoint()
                        }
                        val dbPath = getKoin().get<MusicDatabase>().getDatabasePath()
                        FileOutputStream(dbPath).use { outputStream ->
                            zipInputStream.copyTo(outputStream)
                        }
                    }
                    else -> {
                        Logger.d("BackupRestore", "Unhandled entry: ${entry.name}")
                    }
                }
                entry = zipInputStream.nextEntry()
            }
            report(RestoreProgress.Step("Verifying database...", 2, 2))
            val dbPath = getKoin().get<MusicDatabase>().getDatabasePath()
            if (!File(dbPath).exists()) {
                throw IllegalStateException("Database not found after restore")
            }
            report(RestoreProgress.Completed("Restore successful"))

            withContext(Dispatchers.Main) {
                showToast("Restore successful", ToastGravity.Bottom)
                showToast("App will restart to apply changes", ToastGravity.Bottom)
                exitProcess(0)
            }
        }

        RestoreResult.Success(
            BackupContents(
                hasSettings = true,
                hasMainDatabase = true,
                hasExoPlayerDatabase = false,
                downloadFileCount = 0,
                artworkFileCount = 0,
                canvasFileCount = 0,
            )
        )
    } catch (e: Exception) {
        Logger.e("BackupRestore", "Restore failed", e)
        RestoreResult.Failure("Restore failed: ${e.message}", e)
    }
}