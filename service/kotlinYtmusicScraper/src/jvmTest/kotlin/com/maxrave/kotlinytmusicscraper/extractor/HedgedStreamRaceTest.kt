package com.maxrave.kotlinytmusicscraper.extractor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HedgedStreamRaceTest {
    @Test
    fun fastPrimaryWinsBeforeHedgeDelayWithoutStartingFallback() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val fallbackStarted = AtomicBoolean(false)
            val fallbackCancelled = AtomicBoolean(false)
            try {
                val result =
                    racePrimaryWithHedgedFallback(
                        scope = scope,
                        hedgeDelayMs = 2_000L,
                        isCancelled = { false },
                        runPrimary = {
                            Thread.sleep(30L)
                            "primary-ok"
                        },
                        runFallback = {
                            fallbackStarted.set(true)
                            "fallback-ok"
                        },
                        onCancelFallback = { fallbackCancelled.set(true) },
                    )
                assertEquals("primary-ok", result)
                assertFalse(fallbackStarted.get())
                assertTrue(fallbackCancelled.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun hedgedFallbackWinsWhenPrimaryStallsAndCancelsPrimaryImmediately() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val primaryCancelled = AtomicBoolean(false)
            val primaryGate = CountDownLatch(1)
            try {
                val startedAt = System.currentTimeMillis()
                val result =
                    racePrimaryWithHedgedFallback(
                        scope = scope,
                        hedgeDelayMs = 80L,
                        isCancelled = { false },
                        runPrimary = {
                            // Simulates a stalled primary (e.g. 9s pipepipe.dev hang) until cancelled
                            primaryGate.await(5, TimeUnit.SECONDS)
                            null
                        },
                        runFallback = {
                            Thread.sleep(40L)
                            "brave-fast"
                        },
                        onCancelPrimary = {
                            primaryCancelled.set(true)
                            primaryGate.countDown()
                        },
                    )
                val elapsedMs = System.currentTimeMillis() - startedAt
                assertEquals("brave-fast", result)
                assertTrue(primaryCancelled.get())
                assertTrue(elapsedMs < 1_000L, "Expected fast hedged return, took ${elapsedMs}ms")
            } finally {
                primaryGate.countDown()
                scope.cancel()
            }
        }

    @Test
    fun failingPrimaryLaunchesFallbackImmediatelyWithoutWaitingHedgeDelay() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val startedAt = System.currentTimeMillis()
                val result =
                    racePrimaryWithHedgedFallback(
                        scope = scope,
                        hedgeDelayMs = 5_000L,
                        isCancelled = { false },
                        runPrimary = { null },
                        runFallback = { "brave-fallback" },
                    )
                val elapsedMs = System.currentTimeMillis() - startedAt
                assertEquals("brave-fallback", result)
                assertTrue(elapsedMs < 1_000L, "Expected immediate fallback launch, took ${elapsedMs}ms")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun returnsNullWhenBothBranchesFailAndAbortsOnExternalCancel() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val exhausted =
                    racePrimaryWithHedgedFallback<String>(
                        scope = scope,
                        hedgeDelayMs = 20L,
                        isCancelled = { false },
                        runPrimary = { null },
                        runFallback = { null },
                    )
                assertNull(exhausted)

                val cancelledFlag = AtomicBoolean(false)
                val primaryCancelCount = AtomicInteger(0)
                val fallbackCancelCount = AtomicInteger(0)
                val releaseGate = CountDownLatch(1)
                scope.launch {
                    Thread.sleep(50L)
                    cancelledFlag.set(true)
                }
                val aborted =
                    racePrimaryWithHedgedFallback<String>(
                        scope = scope,
                        hedgeDelayMs = 20L,
                        pollCancelMs = 20L,
                        isCancelled = { cancelledFlag.get() },
                        runPrimary = {
                            releaseGate.await(2, TimeUnit.SECONDS)
                            null
                        },
                        runFallback = {
                            releaseGate.await(2, TimeUnit.SECONDS)
                            null
                        },
                        onCancelPrimary = { primaryCancelCount.incrementAndGet() },
                        onCancelFallback = { fallbackCancelCount.incrementAndGet() },
                    )
                releaseGate.countDown()
                assertNull(aborted)
                assertTrue(primaryCancelCount.get() >= 1)
                assertTrue(fallbackCancelCount.get() >= 1)
            } finally {
                scope.cancel()
            }
        }
}
