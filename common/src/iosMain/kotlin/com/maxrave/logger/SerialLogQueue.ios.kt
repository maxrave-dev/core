package com.maxrave.logger

import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

internal actual class SerialLogQueue {
    private val queue = dispatch_queue_create("com.maxrave.logger.file", null)

    actual fun post(task: () -> Unit) {
        dispatch_async(queue) { task() }
    }

    actual fun runAndAwait(task: () -> Unit) {
        dispatch_sync(queue) { task() }
    }
}
