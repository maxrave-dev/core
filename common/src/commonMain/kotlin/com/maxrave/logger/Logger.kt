package com.maxrave.logger

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.concurrent.Volatile
import kotlin.time.Clock

object Logger {
    private val logger = Logger

    // Tags suppressed at all log levels. Add a tag here to silence its logs globally.
    private val mutedTags =
        setOf(
            "DiscordWebSocket",
        )

    private fun isMuted(tag: String): Boolean = tag in mutedTags

    const val APP_LOG = "app"
    const val ERROR_LOG = "error"
    private const val APP_LOG_FILES = 3
    private const val ERROR_LOG_FILES = 2

    private val fileQueue = SerialLogQueue()

    @Volatile
    private var fileWriters: List<ClearableFileLogWriter> = emptyList()

    /** The folder [enableFileLogging] writes to; null while the log only reaches Logcat/console. */
    @Volatile
    var logDirectory: String? = null
        private set

    /**
     * Mirrors the log onto disk under [directory]: Info and above into [APP_LOG], Error alone into
     * [ERROR_LOG]. Debug stays on Logcat/console only. Called once at startup by each app.
     *
     * Writes run on a dedicated serial background queue so callers never wait on the disk, and each
     * log rolls over by size so the folder stays bounded (~4 MB).
     */
    fun enableFileLogging(directory: String) {
        if (logDirectory != null) return
        val path = Path(directory)
        // Ensure directory exists before first write.
        runCatching { SystemFileSystem.createDirectories(path) }
        logDirectory = directory
        val writers =
            listOf(
                ClearableFileLogWriter(path, APP_LOG, rollOnSize = 1_000_000, maxLogFiles = APP_LOG_FILES, minSeverity = Severity.Info, fileQueue),
                ClearableFileLogWriter(path, ERROR_LOG, rollOnSize = 512_000, maxLogFiles = ERROR_LOG_FILES, minSeverity = Severity.Error, fileQueue),
            )
        fileWriters = writers
        logger.addLogWriter(*writers.toTypedArray())
    }

    /**
     * The files behind [name] ([APP_LOG] or [ERROR_LOG]), oldest first, whether they exist yet or
     * not; empty before [enableFileLogging]. `<name>.log` is the live file, `<name>-N.log` older.
     */
    fun logFiles(name: String): List<String> {
        val directory = logDirectory ?: return emptyList()
        val count = if (name == ERROR_LOG) ERROR_LOG_FILES else APP_LOG_FILES
        return (count - 1 downTo 0).map { index ->
            Path(directory, if (index == 0) "$name.log" else "$name-$index.log").toString()
        }
    }

    fun d(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.d(
            tag = tag,
            message = {
                message
            },
        )
    }

    fun i(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.i(tag = tag, message = { message })
    }

    fun w(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.w(tag = tag, message = { message })
    }

    fun e(
        tag: String,
        message: String,
        e: Throwable? = null,
    ) {
        if (isMuted(tag)) return
        logger.e(throwable = e, tag = tag, message = { message })
    }

    /**
     * Closes open log file sinks and deletes all live and rolled log files on disk. Subsequent
     * logs open fresh files cleanly on the serial writer thread.
     */
    fun flushLogFiles() {
        fileWriters.forEach { it.flush() }
    }

    fun clearLogFiles() {
        fileWriters.forEach { it.clear() }
    }
}

/**
 * Rolling file log writer serialized on [queue] so writes never block callers and [clear] can
 * close open file handles and delete every log file on disk without unlinked-inode leaks.
 */
private class ClearableFileLogWriter(
    private val directory: Path,
    private val name: String,
    private val rollOnSize: Long,
    private val maxLogFiles: Int,
    private val minSeverity: Severity,
    private val queue: SerialLogQueue,
) : LogWriter() {
    private val livePath = pathForIndex(0)
    private var currentSink: Sink? = null
    private var currentSize: Long = SystemFileSystem.metadataOrNull(livePath)?.size ?: 0L
    private var pendingFlushEntries = 0
    private var pendingFlushBytes = 0
    private var bytesSinceDiskCheck = 0

    override fun isLoggable(
        tag: String,
        severity: Severity,
    ): Boolean = severity >= minSeverity

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val timestamp = Clock.System.now().toString()
        val levelChar = severity.name.firstOrNull() ?: '-'
        val entry =
            buildString {
                append(timestamp)
                    .append(' ')
                    .append(levelChar)
                    .append('/')
                    .append(tag)
                    .append(": ")
                    .appendLine(message)
                if (throwable != null) {
                    appendLine(throwable.stackTraceToString())
                }
            }
        queue.post { writeEntry(entry, flushNow = severity >= Severity.Error) }
    }

    fun flush() {
        queue.runAndAwait {
            runCatching { currentSink?.flush() }
            pendingFlushEntries = 0
            pendingFlushBytes = 0
        }
    }

    fun clear() {
        queue.runAndAwait {
            closeSink()
            currentSize = 0L
            for (index in (maxLogFiles - 1) downTo 0) {
                runCatching { SystemFileSystem.delete(pathForIndex(index), mustExist = false) }
            }
        }
    }

    private fun writeEntry(
        entry: String,
        flushNow: Boolean = false,
    ) {
        try {
            // Re-check metadata on disk only periodically (or when size nears rollOnSize),
            // instead of running a filesystem stat() + flush() for every single log line.
            val checkDisk = bytesSinceDiskCheck >= 32 * 1024 || currentSize > rollOnSize
            val diskSize = if (checkDisk) SystemFileSystem.metadataOrNull(livePath)?.size ?: 0L else 0L
            if (checkDisk) bytesSinceDiskCheck = 0
            if (maxOf(currentSize, diskSize) > rollOnSize) {
                closeSink()
                rollFiles()
                currentSize = 0L
            }
            if (currentSink == null) {
                runCatching { SystemFileSystem.createDirectories(directory) }
                currentSize = SystemFileSystem.metadataOrNull(livePath)?.size ?: 0L
                currentSink = SystemFileSystem.sink(livePath, append = true).buffered()
                pendingFlushEntries = 0
                pendingFlushBytes = 0
            }
            val sink = currentSink ?: return
            val entryBytes = entry.encodeToByteArray().size
            sink.writeString(entry)
            currentSize += entryBytes
            bytesSinceDiskCheck += entryBytes
            pendingFlushEntries++
            pendingFlushBytes += entryBytes
            if (flushNow || pendingFlushEntries >= 16 || pendingFlushBytes >= 4096) {
                sink.flush()
                pendingFlushEntries = 0
                pendingFlushBytes = 0
            }
        } catch (_: Throwable) {
            closeSink()
            currentSize = SystemFileSystem.metadataOrNull(livePath)?.size ?: 0L
        }
    }

    private fun rollFiles() {
        runCatching { SystemFileSystem.delete(pathForIndex(maxLogFiles - 1), mustExist = false) }
        for (index in (maxLogFiles - 2) downTo 0) {
            val source = pathForIndex(index)
            val target = pathForIndex(index + 1)
            if (SystemFileSystem.exists(source)) {
                runCatching { SystemFileSystem.atomicMove(source, target) }
            }
        }
    }

    private fun closeSink() {
        runCatching { currentSink?.flush() }
        runCatching { currentSink?.close() }
        pendingFlushEntries = 0
        pendingFlushBytes = 0
        currentSink = null
    }

    private fun pathForIndex(index: Int): Path =
        Path(directory, if (index == 0) "$name.log" else "$name-$index.log")
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}