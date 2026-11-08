package com.maxrave.logger

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Message
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

    /** The folder [enableFileLogging] writes to; null while the log only reaches Logcat/console. */
    var logDirectory: String? = null
        private set

    /**
     * Mirrors the log onto disk under [directory]: Info and above into [APP_LOG], Error alone into
     * [ERROR_LOG]. Debug stays on Logcat/console only. Called once at startup by each app.
     *
     * Kermit's RollingFileLogWriter writes on a thread of its own, so a caller never waits on the
     * disk, and rolls each file over by size, so the folder never grows past about 4 MB.
     */
    fun enableFileLogging(directory: String) {
        if (logDirectory != null) return
        val path = Path(directory)
        // The writer opens its file but never creates the folder, and a missing one only drops lines.
        runCatching { SystemFileSystem.createDirectories(path) }
        logDirectory = directory
        logger.addLogWriter(
            LevelFileWriter(path, APP_LOG, rollOnSize = 1_000_000, maxLogFiles = APP_LOG_FILES, minSeverity = Severity.Info),
            LevelFileWriter(path, ERROR_LOG, rollOnSize = 512_000, maxLogFiles = ERROR_LOG_FILES, minSeverity = Severity.Error),
        )
    }

    /**
     * The files behind [name] ([APP_LOG] or [ERROR_LOG]), oldest first, whether they exist yet or
     * not; empty before [enableFileLogging]. RollingFileLogWriter's own naming: `<name>.log` is the
     * live file, `<name>-N.log` the older ones.
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
}

/** A rolling log file that takes [minSeverity] and above only. */
private class LevelFileWriter(
    directory: Path,
    name: String,
    rollOnSize: Long,
    maxLogFiles: Int,
    private val minSeverity: Severity,
) : RollingFileLogWriter(
    config =
        RollingFileLogWriterConfig(
            logFileName = name,
            logFilePath = directory,
            rollOnSize = rollOnSize,
            maxLogFiles = maxLogFiles,
        ),
    messageStringFormatter = LogLineFormatter,
) {
    override fun isLoggable(
        tag: String,
        severity: Severity,
    ): Boolean = severity >= minSeverity
}

/**
 * One entry per line, logcat-style: `I/Tag: message`. The writer puts the timestamp in front and a
 * throwable's stack trace on the lines after, which is the shape the App log screen parses.
 */
private object LogLineFormatter : MessageStringFormatter {
    override fun formatMessage(
        severity: Severity?,
        tag: Tag?,
        message: Message,
    ): String = "${severity?.name?.first() ?: '-'}/${tag?.tag.orEmpty()}: ${message.message}"
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}