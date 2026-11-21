package com.maxrave.kotlinytmusicscraper.extractor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Races [runPrimary] against a hedged [runFallback] started after [hedgeDelayMs] (or immediately
 * once [runPrimary] finishes without a usable result).
 *
 * Returns the first non-null winner without waiting for the losing branch to unwind (calling
 * [onCancelPrimary] / [onCancelFallback] so the loser aborts its network requests immediately).
 * Checks [isCancelled] periodically so external track switches abort promptly even if a worker
 * thread is blocked in uninterruptible I/O.
 */
internal suspend fun <T : Any> racePrimaryWithHedgedFallback(
    scope: CoroutineScope,
    hedgeDelayMs: Long,
    pollCancelMs: Long = 100L,
    isCancelled: () -> Boolean,
    runPrimary: () -> T?,
    runFallback: () -> T?,
    onCancelPrimary: () -> Unit = {},
    onCancelFallback: () -> Unit = {},
): T? {
    if (isCancelled()) return null

    val outcomes = Channel<Pair<Boolean, T?>>(capacity = 2)
    val primaryDone = CompletableDeferred<Unit>()

    scope.launch {
        val result =
            try {
                if (isCancelled()) null else runPrimary()
            } catch (_: Throwable) {
                null
            }
        primaryDone.complete(Unit)
        outcomes.trySend(true to result)
    }

    val fallbackStarter =
        scope.launch {
            if (hedgeDelayMs > 0L) {
                withTimeoutOrNull(hedgeDelayMs) {
                    primaryDone.await()
                }
            }
            if (isCancelled()) {
                outcomes.trySend(false to null)
                return@launch
            }
            val result =
                try {
                    runFallback()
                } catch (_: Throwable) {
                    null
                }
            outcomes.trySend(false to result)
        }

    var primaryFinished = false
    var fallbackFinished = false
    while (true) {
        if (isCancelled()) {
            onCancelPrimary()
            onCancelFallback()
            fallbackStarter.cancel()
            return null
        }

        val received =
            withTimeoutOrNull(pollCancelMs) {
                outcomes.receive()
            } ?: continue

        val (fromPrimary, value) = received
        if (fromPrimary) {
            primaryFinished = true
        } else {
            fallbackFinished = true
        }

        if (value != null) {
            if (fromPrimary) {
                onCancelFallback()
                fallbackStarter.cancel()
            } else {
                onCancelPrimary()
            }
            return value
        }

        if (primaryFinished && fallbackFinished) {
            return null
        }
    }
}
