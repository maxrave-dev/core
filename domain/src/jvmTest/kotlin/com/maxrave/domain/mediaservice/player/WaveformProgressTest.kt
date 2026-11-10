package com.maxrave.domain.mediaservice.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformProgressTest {
    @Test
    fun deliversDuringNonSuspendingDecodeBeforeEofAndClear() =
        runBlocking {
            val progress = WaveformProgress()
            val received = mutableListOf<Int>()
            progress.observe("track", { received.add(it.size) }) {
                // No yield/delay/receive: the real decoder can stay in native/read loops.
                progress.publish("track", floatArrayOf(0.25f))
                assertEquals(listOf(1), received)
                progress.publish("track", floatArrayOf(0.25f, 1f))
                assertEquals(listOf(1, 2), received)
                progress.clear("track")
            }
            assertEquals(listOf(1, 2), received)
        }

    @Test
    fun deliversGrowingPrefixesBeforeReadCompletes() =
        runBlocking {
            withTimeout(2_000L) {
                val progress = WaveformProgress()
                val received = Channel<FloatArray>(Channel.UNLIMITED)
                val result =
                    progress.observe("track", { received.trySend(it) }) {
                        progress.publish("track", floatArrayOf(0.25f))
                        assertContentEquals(floatArrayOf(0.25f), received.receive())
                        progress.publish("track", floatArrayOf(0.25f, 1f))
                        assertContentEquals(floatArrayOf(0.25f, 1f), received.receive())
                        "complete"
                    }
                assertEquals("complete", result)
            }
        }

    @Test
    fun joinsAlreadyRunningDecodeWithoutStartingAnother() =
        runBlocking {
            withTimeout(2_000L) {
                val progress = WaveformProgress()
                val received = Channel<FloatArray>(Channel.UNLIMITED)
                val continueDecode = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val producer =
                    launch {
                        progress.publish("track", floatArrayOf(0.5f))
                        continueDecode.await()
                        progress.publish("track", floatArrayOf(0.5f, 1f))
                        finished.complete(Unit)
                    }
                yield()
                progress.observe("track", { received.trySend(it) }) {
                    assertContentEquals(floatArrayOf(0.5f), received.receive())
                    continueDecode.complete(Unit)
                    assertContentEquals(floatArrayOf(0.5f, 1f), received.receive())
                    finished.await()
                }
                producer.join()
            }
        }

    @Test
    fun isolatesTrackAndCopiesPublishedBuffers() =
        runBlocking {
            withTimeout(2_000L) {
                val progress = WaveformProgress()
                val source = floatArrayOf(0.5f)
                progress.publish("track", source)
                source[0] = 0f
                val received = mutableListOf<FloatArray>()
                progress.observe("track", { received.add(it) }) {
                    assertContentEquals(floatArrayOf(0.5f), received.single())
                    progress.publish("other", floatArrayOf(1f))
                    yield()
                    assertEquals(1, received.size)
                }
                progress.publish("track", floatArrayOf(1f))
                yield()
                assertEquals(1, received.size)
            }
        }

    @Test
    fun cancellationDetachesObserverAndClearRemovesStaleProgress() =
        runBlocking {
            withTimeout(2_000L) {
                val progress = WaveformProgress()
                val started = CompletableDeferred<Unit>()
                val received = mutableListOf<FloatArray>()
                val observer =
                    launch {
                        progress.observe("track", { received.add(it) }) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    }
                started.await()
                observer.cancelAndJoin()
                progress.publish("track", floatArrayOf(1f))
                yield()
                assertTrue(received.isEmpty())
                progress.clear("other")
                progress.observe("track", { received.add(it) }) { assertEquals(1, received.size) }
                progress.clear("track")
                received.clear()
                progress.observe("track", { received.add(it) }) { assertTrue(received.isEmpty()) }
            }
        }
}
