package com.maxrave.data.browserlogin

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Only disposable copies are opened by SQLite. No source DB connection or checkpoint is created. */
internal class BrowserCookieSnapshot(
    private val root: Path = Paths.get(System.getProperty("java.io.tmpdir"), "simpmusic-browser-login"),
) {
    fun <T> read(source: Path, block: (SQLiteConnection) -> T): T = withDirectory { directory ->
        val files = listOf(source, Paths.get("$source-wal"), Paths.get("$source-shm"))
        repeat(3) {
            val before = files.map(::stamp)
            if (before.first() == null) throw BrowserLoginException(BrowserLoginFailure.NO_COOKIES)
            if (before.filterNotNull().sumOf { it.size } > MAX_COPY_BYTES) {
                throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
            }
            val destination = directory.resolve("cookies.sqlite")
            clearContents(directory)
            var copied = true
            files.forEachIndexed { index, path ->
                if (before[index] != null) {
                    val target = directory.resolve("cookies.sqlite" + listOf("", "-wal", "-shm")[index])
                    try {
                        copyFile(path, target)
                    } catch (_: java.nio.file.NoSuchFileException) {
                        copied = false // A checkpoint or rotation raced the copy; take a fresh set.
                    }
                }
            }
            if (copied && before == files.map(::stamp)) {
                // Copying a live file set is best-effort, not an atomic snapshot. Reject an invalid
                // copy rather than reading partial data; retries never rewrite the browser's files.
                val database = try {
                    BundledSQLiteDriver().open(destination.toString(), SQLITE_OPEN_READWRITE)
                } catch (_: SQLiteException) {
                    null
                }
                if (database != null) {
                    database.use {
                        val valid = try {
                            it.prepare("PRAGMA quick_check(1)").use { statement ->
                                statement.step() && statement.getText(0) == "ok"
                            }
                        } catch (_: SQLiteException) {
                            false
                        }
                        if (valid) return@withDirectory block(it)
                    }
                }
            }
        }
        throw BrowserLoginException(BrowserLoginFailure.DATABASE_BUSY)
    }

    fun <T> readBytes(source: Path, block: (ByteArray) -> T): T = withDirectory { directory ->
        if (Files.size(source) > MAX_COPY_BYTES) throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        val copy = directory.resolve("cookies.binarycookies")
        copyFile(source, copy)
        val bytes = Files.readAllBytes(copy)
        try {
            block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> withDirectory(block: (Path) -> T): T {
        val posix = root.fileSystem.supportedFileAttributeViews().contains("posix")
        try {
            if (posix) {
                Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectory(root)
            }
        } catch (_: FileAlreadyExistsException) {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
        }
        if (posix) {
            val owner = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            if (Files.getOwner(root, NOFOLLOW_LINKS) != owner) throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
        }
        privatePermissions(root, directory = true)
        val lockPath = root.resolve("lock")
        if (Files.isSymbolicLink(lockPath)) throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
        FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
            privatePermissions(lockPath, directory = false)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw BrowserLoginException(BrowserLoginFailure.DATABASE_BUSY)
            lock.use {
                // A held file lock prevents this sweep from touching another active import. Only
                // UUID directories under our private root qualify, never arbitrary temp files.
                Files.list(root).use { entries ->
                    entries.filter { it.fileName.toString().matches(IMPORT_DIRECTORY) }.forEach(::deleteTree)
                }
                val directory = root.resolve("import-${UUID.randomUUID()}")
                if (posix) {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                } else {
                    Files.createDirectory(directory)
                }
                try {
                    return block(directory)
                } finally {
                    deleteTree(directory)
                }
            }
        }
    }

    private fun stamp(path: Path): Stamp? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (!attributes.isRegularFile) throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        Stamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    private fun privatePermissions(path: Path, directory: Boolean) {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        }
    }

    private fun copyFile(source: Path, target: Path) {
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                privatePermissions(target, directory = false)
                val buffer = ByteArray(8_192)
                var copied = 0L
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_COPY_BYTES) throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
                        output.write(buffer, 0, count)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        }
    }

    private fun clearContents(directory: Path) {
        Files.list(directory).use { entries -> entries.forEach(::deleteTree) }
    }

    private fun deleteTree(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            Files.walk(path).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private data class Stamp(val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)

    private companion object {
        const val MAX_COPY_BYTES = 128L * 1024 * 1024
        val IMPORT_DIRECTORY = Regex("import-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
