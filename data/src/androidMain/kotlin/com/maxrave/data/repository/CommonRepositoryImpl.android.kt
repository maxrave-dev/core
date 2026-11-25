package com.maxrave.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.webkit.CookieManager
import com.maxrave.common.Config
import com.maxrave.common.DB_NAME
import com.maxrave.common.DOWNLOAD_EXOPLAYER_FOLDER
import com.maxrave.common.EXOPLAYER_DB_NAME
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.restore.RestoreProgress
import com.maxrave.logger.Logger
import com.maxrave.media3.di.stopService
import com.maxrave.simpmusic.canvas.CANVAS_DIRECTORY
import com.maxrave.simpmusic.viewModel.RestoreResult
import com.maxrave.simpmusic.viewModel.BackupResult
import com.maxrave.simpmusic.viewModel.BackupContents
import com.maxrave.simpmusic.viewModel.BackupRestoreConfig
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
import com.eygraber.uri.Uri
import com.eygraber.uri.toAndroidUri

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

actual fun getCookies(url: String, packageName: String): CookieItem {
    return try {
        val projection = arrayOf(
            CookieItem.HOST,
            CookieItem.EXPIRY,
            CookieItem.PATH,
            CookieItem.NAME,
            CookieItem.VALUE,
            CookieItem.SECURE,
        )
        CookieManager.getInstance().flush()
        val cookieList = mutableListOf<CookieItem.Content>()
        val dbPath = File("/data/data/${packageName}/").walkTopDown().find { it.name == "Cookies" }
            ?: throw Exception("Cookies File not found!")
        val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.query("cookies", projection, null, null, null, null, null).run {
            while (moveToNext()) {
                val expiry = getLong(getColumnIndexOrThrow(CookieItem.EXPIRY))
                val name = getString(getColumnIndexOrThrow(CookieItem.NAME))
                val value = getString(getColumnIndexOrThrow(CookieItem.VALUE))
                val path = getString(getColumnIndexOrThrow(CookieItem.PATH))
                val secure = getLong(getColumnIndexOrThrow(CookieItem.SECURE)) == 1L
                val hostKey = getString(getColumnIndexOrThrow(CookieItem.HOST))
                val host = if (hostKey[0] != '.') ".$hostKey" else hostKey
                cookieList.add(
                    CookieItem.Content(
                        domain = host,
                        name = name,
                        value = value,
                        isSecure = secure,
                        expiresUtc = expiry,
                        hostKey = host,
                        path = path,
                    ),
                )
            }
            close()
        }
        db.close()
        CookieItem(url, cookieList)
    } catch (e: Exception) {
        e.printStackTrace()
        CookieItem(url, emptyList())
    }
}

actual suspend fun backupNative(
    uri: com.eygraber.uri.Uri,
    config: BackupRestoreConfig,
    progressCallback: ((RestoreProgress) -> Unit)?
): BackupResult {
    val application: Context = getKoin().get()
    val applicationContext = application.applicationContext

    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    return try {
        report(RestoreProgress.Started("Backup in progress"))

        var downloadFileCount = 0
        var artworkFileCount = 0
        var canvasFileCount = 0

        applicationContext.contentResolver.openOutputStream(uri.toAndroidUri())?.use { outputStream ->
            outputStream.buffered().use { bufferedOutput ->
                ZipOutputStream(bufferedOutput).use { zipOutputStream ->
                    // Phase 1: Settings
                    report(RestoreProgress.Step("Backing up settings...", 1, 5))
                    val settingsFile = File(application.filesDir, "datastore/$SETTINGS_FILENAME.preferences_pb")
                    if (settingsFile.exists()) {
                        zipOutputStream.putNextEntry(ZipEntry("datastore/$SETTINGS_FILENAME.preferences_pb"))
                        settingsFile.inputStream().buffered().use { inputStream ->
                            inputStream.copyTo(zipOutputStream)
                        }
                        zipOutputStream.closeEntry()
                    }

                    // Phase 2: Main Database
                    report(RestoreProgress.Step("Backing up main database...", 2, 5))
                    runBlocking(Dispatchers.IO) {
                        localDataSource.checkpoint()
                    }
                    val dbPath = getKoin().get<MusicDatabase>().getDatabasePath()
                    FileInputStream(dbPath).use { inputStream ->
                        zipOutputStream.putNextEntry(ZipEntry("databases/main/simpmusic.db"))
                        inputStream.copyTo(zipOutputStream)
                        zipOutputStream.closeEntry()
                    }

                    // Phase 3-5: Downloaded content
                    if (config.includeDownloads) {
                        // ExoPlayer Database
                        report(RestoreProgress.Step("Backing up download database...", 3, 5))
                        val exoPlayerDb = application.getDatabasePath(EXOPLAYER_DB_NAME)
                        if (exoPlayerDb.exists()) {
                            zipOutputStream.putNextEntry(ZipEntry("databases/exoplayer/exoplayer_internal.db"))
                            exoPlayerDb.inputStream().buffered().use { inputStream ->
                                inputStream.copyTo(zipOutputStream)
                            }
                            zipOutputStream.closeEntry()
                        }

                        // Downloads folder
                        report(RestoreProgress.Step("Backing up downloaded files...", 4, 5))
                        val downloadFolder = File(application.filesDir, DOWNLOAD_EXOPLAYER_FOLDER)
                        if (downloadFolder.exists() && downloadFolder.isDirectory) {
                            downloadFileCount = backupFolder(downloadFolder, "media/downloads", zipOutputStream)
                        }

                        // Artwork folder
                        if (config.includeArtwork) {
                            val artworkFolder = File(application.filesDir, "artwork")
                            if (artworkFolder.exists() && artworkFolder.isDirectory) {
                                artworkFileCount = backupFolder(artworkFolder, "media/artwork", zipOutputStream)
                            }
                        }

                        // Canvas folder
                        if (config.includeCanvas) {
                            val canvasFolder = File(application.filesDir, CANVAS_DIRECTORY)
                            if (canvasFolder.exists() && canvasFolder.isDirectory) {
                                canvasFileCount = backupFolder(canvasFolder, "media/canvas", zipOutputStream)
                            }
                        }
                    }
                }
            }
        }

        report(RestoreProgress.Completed("Backup created successfully"))

        BackupResult.Success(
            BackupContents(
                hasSettings = true,
                hasMainDatabase = true,
                hasExoPlayerDatabase = config.includeDownloads,
                downloadFileCount = downloadFileCount,
                artworkFileCount = artworkFileCount,
                canvasFileCount = canvasFileCount,
            )
        )
    } catch (e: Exception) {
        Logger.e("BackupRestore", "Backup failed", e)
        BackupResult.Failure("Backup failed: ${e.message}", e)
    }
}

actual suspend fun restoreNative(
    uri: com.eygraber.uri.Uri,
    config: BackupRestoreConfig,
    progressCallback: ((RestoreProgress) -> Unit)?
): RestoreResult {
    val application: Context = getKoin().get()
    val applicationContext = application.applicationContext

    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    return try {
        report(RestoreProgress.Started("Restore in progress"))

        // Phase 0: Stop media service
        report(RestoreProgress.Step("Stopping media services...", 0, 6))
        stopService(application)

        // Phase 1: Close Room database
        report(RestoreProgress.Step("Preparing databases...", 1, 6))
        runBlocking(Dispatchers.IO) {
            localDataSource.checkpoint()
            closeDatabase()
        }

        // Phase 2: Extract and restore all files
        var downloadFileCount = 0
        var artworkFileCount = 0
        var canvasFileCount = 0
        var exoPlayerDbRestored = false

        // First pass: count entries for progress
        val entries = mutableListOf<ZipEntry>()
        val tempStream = applicationContext.contentResolver.openInputStream(uri.toAndroidUri())?.zipInputStream()
        if (tempStream != null) {
            var tempEntry: ZipEntry? = tempStream.nextEntry()
            while (tempEntry != null) {
                if (tempEntry.name == "databases/main/simpmusic.db" ||
                    tempEntry.name == "databases/exoplayer/exoplayer_internal.db" ||
                    tempEntry.name.startsWith("media/downloads/") ||
                    tempEntry.name.startsWith("media/artwork/") ||
                    tempEntry.name.startsWith("media/canvas/") ||
                    tempEntry.name == "datastore/$SETTINGS_FILENAME.preferences_pb") {
                    entries.add(tempEntry)
                }
                tempEntry = tempStream.nextEntry()
            }
            tempStream.close()
        }
        val totalEntries = entries.size
        var currentEntry = 0

        // Reopen stream for restoration
        applicationContext.contentResolver.openInputStream(uri.toAndroidUri())?.use { inputStream ->
            inputStream.zipInputStream().use { zipInputStream ->
                while (true) {
                    val entry: java.util.zip.ZipEntry? = zipInputStream.nextEntry()
                    if (entry == null) break

                    currentEntry++
                    report(RestoreProgress.Step(
                        "Restoring ${entry.name} ($currentEntry/$totalEntries)",
                        currentEntry, totalEntries
                    ))

                    Logger.d("BackupRestore", "Processing entry: ${entry.name}")
                    when {
                        entry.name == "datastore/$SETTINGS_FILENAME.preferences_pb" -> {
                            val settingsFile = File(application.filesDir, "datastore/$SETTINGS_FILENAME.preferences_pb")
                            settingsFile.parentFile?.mkdirs()
                            settingsFile.outputStream().use { outputStream ->
                                zipInputStream.copyTo(outputStream)
                            }
                        }

                        entry.name == "databases/main/simpmusic.db" -> {
                            val dbPath = getKoin().get<MusicDatabase>().getDatabasePath()
                            File(dbPath).delete()
                            File("$dbPath-wal").delete()
                            File("$dbPath-shm").delete()
                            FileOutputStream(dbPath).use { outputStream ->
                                zipInputStream.copyTo(outputStream)
                            }
                            // Force filesystem sync
                            runBlocking(Dispatchers.IO) {
                                File(dbPath).absoluteFile.parentFile?.let { dir ->
                                    val fos = FileOutputStream(File(dir, ".sync"))
                                    fos.fd.sync()
                                    fos.close()
                                    File(dir, ".sync").delete()
                                }
                            }
                        }

                        entry.name == "databases/exoplayer/exoplayer_internal.db" -> {
                            val exoDbPath = application.getDatabasePath(EXOPLAYER_DB_NAME).path
                            File(exoDbPath).delete()
                            File("$exoDbPath-wal").delete()
                            File("$exoDbPath-shm").delete()
                            FileOutputStream(exoDbPath).use { outputStream ->
                                zipInputStream.copyTo(outputStream)
                            }
                            runBlocking(Dispatchers.IO) {
                                File(exoDbPath).absoluteFile.parentFile?.let { dir ->
                                    val fos = FileOutputStream(File(dir, ".sync"))
                                    fos.fd.sync()
                                    fos.close()
                                    File(dir, ".sync").delete()
                                }
                            }
                            exoPlayerDbRestored = true
                        }

                        entry.name.startsWith("media/downloads/") -> {
                            val relativePath = entry.name.removePrefix("media/downloads/")
                            val targetFile = File(application.filesDir, DOWNLOAD_EXOPLAYER_FOLDER) / relativePath
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { outputStream ->
                                zipInputStream.copyTo(outputStream)
                            }
                            downloadFileCount++
                        }

                        entry.name.startsWith("media/artwork/") -> {
                            val relativePath = entry.name.removePrefix("media/artwork/")
                            val targetFile = File(application.filesDir, "artwork") / relativePath
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { outputStream ->
                                zipInputStream.copyTo(outputStream)
                            }
                            artworkFileCount++
                        }

                        entry.name.startsWith("media/canvas/") -> {
                            val relativePath = entry.name.removePrefix("media/canvas/")
                            val targetFile = File(application.filesDir, CANVAS_DIRECTORY) / relativePath
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { outputStream ->
                                zipInputStream.copyTo(outputStream)
                            }
                            canvasFileCount++
                        }

                        else -> {
                            Logger.d("BackupRestore", "Unhandled entry: ${entry.name}")
                        }
                    }
                }
            }
        }

        // Phase 3: CRITICAL - Reinitialize Room database after restore
        report(RestoreProgress.Step("Reinitializing database...", 5, 6))
        val database = getKoin().get<MusicDatabase>()
        // The Koin module will need to recreate the database instance
        // For now, we verify the database can be opened
        val dbPath = database.getDatabasePath()
        val dbVerified = verifyRestoredDatabase(dbPath)
        if (!dbVerified) {
            throw IllegalStateException("Main database verification failed after restore")
        }

        // Phase 4: Verify ExoPlayer database
        if (exoPlayerDbRestored) {
            val exoDbPath = application.getDatabasePath(EXOPLAYER_DB_NAME).path
            if (File(exoDbPath).exists()) {
                val exoVerified = verifyRestoredDatabase(exoDbPath)
                if (!exoVerified) {
                    throw IllegalStateException("ExoPlayer database verification failed after restore")
                }
            }
        }

        // Phase 5: Verify downloaded files
        report(RestoreProgress.Step("Verifying downloaded files...", 6, 6))
        val downloadFolder = File(application.filesDir, DOWNLOAD_EXOPLAYER_FOLDER)
        if (downloadFolder.exists()) {
            Logger.i("BackupRestore", "Download folder restored, files verified on app startup")
        }

        // Final verification
        val downloadFolderDebug = File(application.filesDir, DOWNLOAD_EXOPLAYER_FOLDER)
        Logger.d("BackupRestore", "=== RESTORE: Download folder contents AFTER RESTORE ===")
        debugFolderContents(downloadFolderDebug)

        val artworkFolder = File(application.filesDir, "artwork")
        Logger.d("BackupRestore", "=== RESTORE: Artwork folder contents AFTER RESTORE ===")
        debugFolderContents(artworkFolder)

        val canvasFolder = File(application.filesDir, CANVAS_DIRECTORY)
        Logger.d("BackupRestore", "=== RESTORE: Canvas folder contents AFTER RESTORE ===")
        debugFolderContents(canvasFolder)

        report(RestoreProgress.Completed("Restore successful"))

        // Phase 6: Graceful restart
        withContext(Dispatchers.Main) {
            showToast("Restore successful", ToastGravity.Bottom)
            // The app will restart via the ViewModel's restartAppAfterRestore()
        }

        RestoreResult.Success(
            BackupContents(
                hasSettings = true,
                hasMainDatabase = true,
                hasExoPlayerDatabase = exoPlayerDbRestored,
                downloadFileCount = downloadFileCount,
                artworkFileCount = artworkFileCount,
                canvasFileCount = canvasFileCount,
            )
        )
    } catch (e: Exception) {
        Logger.e("BackupRestore", "Restore failed", e)
        RestoreResult.Failure("Restore failed: ${e.message}", e)
    }
}

private suspend fun verifyRestoredDatabase(dbPath: String): Boolean {
    return try {
        val file = File(dbPath)
        if (!file.exists() || file.length() == 0L) {
            Logger.w("BackupRestore", "Database file missing or empty: $dbPath")
            return false
        }
        val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        db.close()
        true
    } catch (e: Exception) {
        Logger.w("BackupRestore", "Database verification failed: ${e.message}")
        false
    }
}

private fun backupFolder(folder: File, baseName: String, zipOutputStream: ZipOutputStream): Int {
    if (!folder.exists() || !folder.isDirectory) return 0
    var fileCount = 0
    folder.listFiles()?.forEach { file ->
        if (file.isFile) {
            val entryName = "$baseName/${file.name}"
            zipOutputStream.putNextEntry(ZipEntry(entryName))
            file.inputStream().buffered().use { inputStream ->
                inputStream.copyTo(zipOutputStream)
            }
            zipOutputStream.closeEntry()
            fileCount++
        } else if (file.isDirectory) {
            fileCount += backupFolder(file, "$baseName/${file.name}", zipOutputStream)
        }
    }
    return fileCount
}

private fun debugFolderContents(folder: File, level: Int = 0) {
    if (!folder.exists()) {
        Logger.d("BackupRestore", "${"  ".repeat(level)}Folder does not exist: ${folder.absolutePath}")
        return
    }
    Logger.d("BackupRestore", "${"  ".repeat(level)}Folder: ${folder.name} (${folder.absolutePath})")
    folder.listFiles()?.forEach { file ->
        if (file.isFile) {
            Logger.d("BackupRestore", "${"  ".repeat(level + 1)}File: ${file.name} (${file.length()} bytes)")
        } else if (file.isDirectory) {
            debugFolderContents(file, level + 1)
        }
    }
}