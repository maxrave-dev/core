package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsResolverTest {
    private fun unsynced(text: String) =
        Lyrics(
            lines = listOf(Line("0", "0", words = text), Line("0", "0", words = "$text 2")),
            syncType = "UNSYNCED",
        )

    private fun lineSynced(
        text: String,
        t1: String = "1000",
        t2: String = "5000",
    ) = Lyrics(
        lines = listOf(Line("0", t1, words = text), Line("0", t2, words = "$text 2")),
        syncType = "LINE_SYNCED",
    )

    private fun richSynced(text: String) =
        Lyrics(
            lines = listOf(Line("5000", "1000", words = "<00:01.00>$text <00:02.00>word")),
            syncType = "RICH_SYNCED",
        )

    private fun request(
        videoId: String = "vid1",
        preferred: LyricsSource = LyricsSource.SIMPMUSIC,
        allowSpotify: Boolean = false,
        preferCache: Boolean = true,
        allowUpgradeScan: Boolean = true,
        userProtected: Set<Int> = emptySet(),
    ) = LyricsLookupRequest(
        videoId = videoId,
        title = "Song",
        artist = "Artist",
        durationSeconds = 180,
        preferredSource = preferred,
        allowSpotify = allowSpotify,
        preferCache = preferCache,
        allowUpgradeScan = allowUpgradeScan,
        userProtectedSignatures = userProtected,
    )

    @Test
    fun orderedSourcesIncludesAllEnabledProvidersWithPreferredFirst() {
        assertEquals(
            listOf(LyricsSource.LRCLIB, LyricsSource.SIMPMUSIC, LyricsSource.BETTER_LYRICS, LyricsSource.YOUTUBE),
            LyricsResolver.orderedSources(LyricsSource.LRCLIB, allowSpotify = false),
        )
        assertEquals(
            listOf(
                LyricsSource.BETTER_LYRICS,
                LyricsSource.SIMPMUSIC,
                LyricsSource.SPOTIFY,
                LyricsSource.LRCLIB,
                LyricsSource.YOUTUBE,
            ),
            LyricsResolver.orderedSources(LyricsSource.BETTER_LYRICS, allowSpotify = true),
        )
    }

    @Test
    fun richSyncedPrimaryShortCircuitsWithoutQueryingFallbackProviders() =
        runBlocking {
            val queried = mutableListOf<LyricsSource>()
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        queried += source
                        if (source == LyricsSource.SIMPMUSIC) {
                            ProviderFetchOutcome.Success(richSynced("Primary"))
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 200L,
                )

            val events = withTimeout(2_000L) { resolver.resolve(request()).toList() }
            assertEquals(listOf(LyricsSource.SIMPMUSIC), queried)
            val found = events.single() as LyricsResolutionEvent.Found
            assertEquals(LyricsSource.SIMPMUSIC, found.resolution.source)
            assertEquals("RICH_SYNCED", found.resolution.lyrics.syncType)
        }

    @Test
    fun unsyncedPrimaryShowsImmediatelyThenUpgradesToSyncedAndRichSynced() =
        runBlocking {
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        when (source) {
                            LyricsSource.SIMPMUSIC -> ProviderFetchOutcome.Success(unsynced("Simp"))
                            LyricsSource.LRCLIB -> {
                                delay(20L)
                                ProviderFetchOutcome.Success(lineSynced("Lrclib"))
                            }
                            LyricsSource.BETTER_LYRICS -> {
                                delay(50L)
                                ProviderFetchOutcome.Success(richSynced("Better"))
                            }
                            else -> ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 300L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver.resolve(request()).toList()
                }.filterIsInstance<LyricsResolutionEvent.Found>()

            assertEquals(
                listOf(LyricsSource.SIMPMUSIC, LyricsSource.LRCLIB, LyricsSource.BETTER_LYRICS),
                events.map { it.resolution.source },
            )
            assertEquals("RICH_SYNCED", events.last().resolution.lyrics.syncType)
        }

    @Test
    fun neverDowngradesFromBetterQualityToLowerQuality() =
        runBlocking {
            val cachedRich = richSynced("Cached")
            val queried = mutableListOf<LyricsSource>()
            val resolver =
                LyricsResolver(
                    readCache = { cachedRich },
                    fetchSource = { source, _ ->
                        queried += source
                        ProviderFetchOutcome.Success(lineSynced("Downgrade"))
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 200L,
                )

            val events = withTimeout(2_000L) { resolver.resolve(request()).toList() }
            assertEquals(0, queried.size)
            val found = events.single() as LyricsResolutionEvent.Found
            assertEquals(LyricsSource.OFFLINE, found.resolution.source)
            assertEquals("RICH_SYNCED", found.resolution.lyrics.syncType)
        }

    @Test
    fun swrEmitsCacheImmediatelyAndHealsStaleCentisecondCacheOnline() =
        runBlocking {
            val staleBuggyCache = lineSynced("Line", t1 = "27012", t2 = "30005")
            val fixedOnline = lineSynced("Line", t1 = "27120", t2 = "30050")
            val resolver =
                LyricsResolver(
                    readCache = { staleBuggyCache },
                    fetchSource = { source, _ ->
                        delay(20L)
                        if (source == LyricsSource.SIMPMUSIC) {
                            ProviderFetchOutcome.Success(fixedOnline)
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 200L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver.resolve(request()).toList()
                }.filterIsInstance<LyricsResolutionEvent.Found>()

            assertEquals(2, events.size)
            assertTrue(events[0].resolution.fromCache)
            assertFalse(events[1].resolution.fromCache)
            assertEquals("27120", events[1].resolution.lyrics.lines?.first()?.startTimeMs)
        }

    @Test
    fun userProtectedCacheIsNeverOverwrittenOrUpgraded() =
        runBlocking {
            val userLyrics = unsynced("My hand-typed lyrics")
            val userSig = LyricsQuality.contentSignature(userLyrics)
            val queried = mutableListOf<LyricsSource>()
            val resolver =
                LyricsResolver(
                    readCache = { userLyrics },
                    fetchSource = { source, _ ->
                        queried += source
                        ProviderFetchOutcome.Success(richSynced("Better"))
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 200L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver.resolve(request(userProtected = setOf(userSig))).toList()
                }
            assertTrue(queried.isEmpty())
            val found = events.single() as LyricsResolutionEvent.Found
            assertTrue(found.resolution.isUserProtected)
        }

    @Test
    fun hedgedRequestDeliversFallbackBeforeSlowPrimaryTimesOut() =
        runBlocking {
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        if (source == LyricsSource.SIMPMUSIC) {
                            delay(800L)
                            ProviderFetchOutcome.DefinitiveNotFound
                        } else if (source == LyricsSource.BETTER_LYRICS) {
                            delay(10L)
                            ProviderFetchOutcome.Success(richSynced("Fast hedge"))
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 400L,
                    hedgeDelayMs = 40L,
                )

            val events = withTimeout(1_000L) { resolver.resolve(request()).toList() }
            val found = events.single() as LyricsResolutionEvent.Found
            assertEquals(LyricsSource.BETTER_LYRICS, found.resolution.source)
        }

    @Test
    fun distinguishesDefinitiveNotFoundFromTransientNetworkFailure() =
        runBlocking {
            val notFoundResolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { _, _ -> ProviderFetchOutcome.DefinitiveNotFound },
                    providerTimeoutMs = 200L,
                    hedgeDelayMs = 50L,
                )
            val offlineResolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        if (source == LyricsSource.SIMPMUSIC) {
                            ProviderFetchOutcome.TransientFailure("No internet")
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 200L,
                    hedgeDelayMs = 50L,
                )

            val e1 = withTimeout(1_000L) { notFoundResolver.resolve(request()).toList() }.single() as LyricsResolutionEvent.Exhausted
            val e2 = withTimeout(1_000L) { offlineResolver.resolve(request()).toList() }.single() as LyricsResolutionEvent.Exhausted
            assertTrue(e1.definitiveNotFound)
            assertFalse(e2.definitiveNotFound)
        }
}
