package com.maxrave.logger

/** Single-threaded serial background queue for log file writes and clears. */
internal expect class SerialLogQueue() {
    fun post(task: () -> Unit)

    fun runAndAwait(task: () -> Unit)
}
