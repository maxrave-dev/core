package com.maxrave.logger

import java.util.concurrent.Executors

internal actual class SerialLogQueue {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SimpMusicLogWriter").apply { isDaemon = true }
        }

    actual fun post(task: () -> Unit) {
        executor.execute(task)
    }

    actual fun runAndAwait(task: () -> Unit) {
        executor.submit(task).get()
    }
}
