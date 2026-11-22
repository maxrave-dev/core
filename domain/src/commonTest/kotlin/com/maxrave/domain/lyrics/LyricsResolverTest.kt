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

    @Test
    fun confirmedInstrumentalCancelsProvidersAndEmitsConfirmedInstrumental() =
        runBlocking {
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        when (source) {
                            LyricsSource.LRCLIB -> {
                                delay(10L)
                                ProviderFetchOutcome.ConfirmedInstrumental
                            }
                            LyricsSource.SPOTIFY -> {
                                delay(200L)
                                ProviderFetchOutcome.Success(lineSynced("Wrong artist lyrics"))
                            }
                            else -> ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 20L,
                )

            val events = withTimeout(2_000L) { resolver.resolve(request(allowSpotify = true)).toList() }
            val exhausted = events.single() as LyricsResolutionEvent.Exhausted
            assertTrue(exhausted.definitiveNotFound)
            assertTrue(exhausted.confirmedInstrumental)
        }

    @Test
    fun instrumentalTitleSkipsNetworkUnlessUserProtected() =
        runBlocking {
            var queries = 0
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { _, _ ->
                        queries++
                        ProviderFetchOutcome.Success(lineSynced("Should not be fetched"))
                    },
                )

            val req = request().copy(title = "Bloom (Instrumental)")
            val events = withTimeout(1_000L) { resolver.resolve(req).toList() }
            assertEquals(0, queries)
            val exhausted = events.single() as LyricsResolutionEvent.Exhausted
            assertTrue(exhausted.confirmedInstrumental)
        }

    @Test
    fun fallsBackToLetrasStaticLyricsOnlyWhenSyncedProvidersFindNothing() =
        runBlocking {
            val queried = mutableListOf<LyricsSource>()
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        queried.add(source)
                        if (source == LyricsSource.LETRAS) {
                            ProviderFetchOutcome.Success(unsynced("Static Letras verse"))
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 20L,
                )

            val events = withTimeout(2_000L) { resolver.resolve(request()).toList() }
            val found = events.single() as LyricsResolutionEvent.Found
            assertEquals(LyricsSource.LETRAS, found.resolution.source)
            assertEquals("UNSYNCED", found.resolution.lyrics.syncType)
            assertTrue(LyricsSource.LETRAS in queried)
        }

    @Test
    fun neverQueriesLetrasWhenConfirmedInstrumental() =
        runBlocking {
            val queried = mutableListOf<LyricsSource>()
            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        queried.add(source)
                        if (source == LyricsSource.LRCLIB) {
                            ProviderFetchOutcome.ConfirmedInstrumental
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 20L,
                )

            val events = withTimeout(2_000L) { resolver.resolve(request()).toList() }
            val exhausted = events.single() as LyricsResolutionEvent.Exhausted
            assertTrue(exhausted.confirmedInstrumental)
            assertFalse(LyricsSource.LETRAS in queried)
        }

    @Test
    fun upgradesOverflowingCachedSyncedLyricsWithDurationFittingResult() =
        runBlocking {
            val overflowingCache =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "18770", endTimeMs = "20830", syllables = null, words = "Eles se perguntam quando que eu vou parar"),
                            Line(startTimeMs = "142160", endTimeMs = "145820", syllables = null, words = "I searched to find a love within"),
                        ),
                )
            val cleanSynced =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "10380", endTimeMs = "13090", syllables = null, words = "Eles se perguntam quando que eu vou parar"),
                            Line(startTimeMs = "136650", endTimeMs = "139650", syllables = null, words = "I searched to find a love within"),
                        ),
                )
            val resolver =
                LyricsResolver(
                    readCache = { overflowingCache },
                    fetchSource = { source, _ ->
                        if (source == LyricsSource.LRCLIB) {
                            ProviderFetchOutcome.Success(cleanSynced)
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 20L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver
                        .resolve(
                            request(allowUpgradeScan = false).copy(durationSeconds = 141),
                        ).toList()
                }
            assertEquals(2, events.size)
            val second = events[1] as LyricsResolutionEvent.Found
            assertEquals(LyricsSource.LRCLIB, second.resolution.source)
            assertEquals("10380", second.resolution.lyrics.lines?.first()?.startTimeMs)
        }

    @Test
    fun blocksFastWrongBetterLyricsWhenVerifiedLrclibDisagrees() =
        runBlocking {
            val lrclibOnde =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "17600", endTimeMs = "22790", syllables = null, words = "E por falar em saudade, onde anda você?"),
                            Line(startTimeMs = "22790", endTimeMs = "27230", syllables = null, words = "Onde andam os seus olhos que a gente não vê?"),
                            Line(startTimeMs = "27230", endTimeMs = "32000", syllables = null, words = "Onde anda esse corpo que me deixou morto de tanto prazer?"),
                        ),
                )
            val wrongBetterLamento =
                Lyrics(
                    error = false,
                    syncType = "RICH_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "5000", endTimeMs = "8000", syllables = null, words = "<00:05.00>Não <00:05.50>posso <00:06.00>esquecer <00:07.00>ninguém"),
                            Line(startTimeMs = "8000", endTimeMs = "11000", syllables = null, words = "<00:08.00>O <00:08.40>teu <00:09.00>olhar <00:09.80>longe <00:10.40>dos <00:10.80>olhos <00:11.00>meus"),
                            Line(startTimeMs = "11000", endTimeMs = "16000", syllables = null, words = "<00:11.50>Ai <00:12.00>meu <00:13.00>viver <00:14.00>triste <00:15.00>solidão"),
                        ),
                )

            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        when (source) {
                            LyricsSource.BETTER_LYRICS -> {
                                delay(5L)
                                ProviderFetchOutcome.Success(wrongBetterLamento)
                            }
                            LyricsSource.LRCLIB -> {
                                delay(40L)
                                ProviderFetchOutcome.Success(lrclibOnde)
                            }
                            else -> ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 10L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver
                        .resolve(
                            request().copy(
                                title = "Onde Anda Você",
                                artist = "Toquinho, Vinícius de Moraes",
                                durationSeconds = 167,
                            ),
                        ).toList()
                }.filterIsInstance<LyricsResolutionEvent.Found>()

            val finalFound = events.single()
            assertEquals(LyricsSource.LRCLIB, finalFound.resolution.source)
            assertEquals("LINE_SYNCED", finalFound.resolution.lyrics.syncType)
        }

    @Test
    fun promotesFastBetterLyricsWhenVerifiedLrclibConfirmsSameSong() =
        runBlocking {
            val lrclibOnde =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "17600", endTimeMs = "22790", syllables = null, words = "E por falar em saudade, onde anda você?"),
                            Line(startTimeMs = "22790", endTimeMs = "27230", syllables = null, words = "Onde andam os seus olhos que a gente não vê?"),
                            Line(startTimeMs = "27230", endTimeMs = "32000", syllables = null, words = "Onde anda esse corpo que me deixou morto"),
                        ),
                )
            val matchingBetterRich =
                Lyrics(
                    error = false,
                    syncType = "RICH_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "17600", endTimeMs = "22790", syllables = null, words = "<00:17.60>E <00:18.00>por <00:18.50>falar <00:19.00>em <00:19.60>saudade, <00:20.80>onde <00:21.50>anda <00:22.00>você?"),
                            Line(startTimeMs = "22790", endTimeMs = "27230", syllables = null, words = "<00:22.79>Onde <00:23.30>andam <00:23.90>os <00:24.20>seus <00:24.80>olhos <00:25.40>que <00:25.70>a <00:26.00>gente <00:26.60>não <00:27.00>vê?"),
                            Line(startTimeMs = "27230", endTimeMs = "32000", syllables = null, words = "<00:27.23>Onde <00:27.80>anda <00:28.40>esse <00:29.00>corpo"),
                        ),
                )

            val resolver =
                LyricsResolver(
                    readCache = { null },
                    fetchSource = { source, _ ->
                        when (source) {
                            LyricsSource.BETTER_LYRICS -> {
                                delay(5L)
                                ProviderFetchOutcome.Success(matchingBetterRich)
                            }
                            LyricsSource.LRCLIB -> {
                                delay(40L)
                                ProviderFetchOutcome.Success(lrclibOnde)
                            }
                            else -> ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 10L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver
                        .resolve(
                            request().copy(
                                title = "Onde Anda Você",
                                artist = "Toquinho, Vinícius de Moraes",
                                durationSeconds = 167,
                            ),
                        ).toList()
                }.filterIsInstance<LyricsResolutionEvent.Found>()

            assertEquals(LyricsSource.BETTER_LYRICS, events.last().resolution.source)
            assertEquals("RICH_SYNCED", events.last().resolution.lyrics.syncType)
        }

    @Test
    fun discardsContaminatedCacheWhenHeaderContradictsTrack() =
        runBlocking {
            val contaminatedCache =
                Lyrics(
                    error = false,
                    syncType = "RICH_SYNCED",
                    lines =
                        listOf(
                            Line(startTimeMs = "0", endTimeMs = "3000", syllables = null, words = "<00:00.50>Lamento - Antonio Carlos Jobim（安东尼奥·卡洛斯·乔宾）"),
                            Line(startTimeMs = "3000", endTimeMs = "6000", syllables = null, words = "<00:03.50>Não posso esquecer"),
                        ),
                )
            val lrclibClean = lineSynced("E por falar em saudade, onde anda você?")
            val resolver =
                LyricsResolver(
                    readCache = { contaminatedCache },
                    fetchSource = { source, _ ->
                        if (source == LyricsSource.LRCLIB) {
                            ProviderFetchOutcome.Success(lrclibClean)
                        } else {
                            ProviderFetchOutcome.DefinitiveNotFound
                        }
                    },
                    providerTimeoutMs = 500L,
                    hedgeDelayMs = 10L,
                )

            val events =
                withTimeout(2_000L) {
                    resolver
                        .resolve(
                            request(allowUpgradeScan = false).copy(
                                title = "Onde Anda Você",
                                artist = "Toquinho, Vinícius de Moraes",
                                durationSeconds = 167,
                            ),
                        ).toList()
                }.filterIsInstance<LyricsResolutionEvent.Found>()

            val found = events.single()
            assertEquals(LyricsSource.LRCLIB, found.resolution.source)
            assertFalse(found.resolution.fromCache)
        }
}
