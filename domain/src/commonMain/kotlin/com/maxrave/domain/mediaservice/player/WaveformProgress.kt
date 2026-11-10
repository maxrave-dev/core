package com.maxrave.domain.mediaservice.player

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Progress from the reader's single decode, including a decode started by AutoMix. */
class WaveformProgress {
    private class Snapshot(
        val mediaId: String,
        val bars: FloatArray,
    )

    private val latest = MutableStateFlow<Snapshot?>(null)
    private val observers = MutableStateFlow<List<Observer>>(emptyList())

    suspend fun publish(
        mediaId: String,
        bars: FloatArray,
    ) {
        if (bars.isEmpty()) return
        val snapshot = Snapshot(mediaId, bars.copyOf())
        latest.value = snapshot
        // Delivery completes before decoding continues. A busy decoder cannot starve a separate
        // collector and then erase all its pending updates at EOF.
        observers.value.forEach { it.deliver(snapshot) }
    }

    fun clear(mediaId: String) {
        latest.update { if (it?.mediaId == mediaId) null else it }
    }

    suspend fun <T> observe(
        mediaId: String,
        onProgress: (FloatArray) -> Unit,
        read: suspend () -> T,
    ): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val observer = Observer(mediaId, context, onProgress)
        observers.update { it + observer }
        try {
            latest.value?.let { observer.deliver(it) }
            return read()
        } finally {
            withContext(NonCancellable) {
                observer.close()
                observers.update { it - observer }
            }
        }
    }

    private class Observer(
        private val mediaId: String,
        private val context: CoroutineContext,
        private val onProgress: (FloatArray) -> Unit,
    ) {
        private val lock = Mutex()
        private var active = true
        private var deliveredBars = 0

        suspend fun deliver(snapshot: Snapshot) =
            lock.withLock {
                if (active && context.isActive && snapshot.mediaId == mediaId && snapshot.bars.size >= deliveredBars) {
                    deliveredBars = snapshot.bars.size
                    onProgress(snapshot.bars)
                }
            }

        suspend fun close() =
            lock.withLock {
                // Wait for an in-flight callback before returning from observe.
                active = false
            }
    }
}
