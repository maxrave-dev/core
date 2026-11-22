package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.lyrics.LyricsTranslationResult
import com.maxrave.domain.lyrics.LyricsTranslationSyncValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeLyricsTranslatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun normalizesPortugueseToPtBrForEdgeAndPtForGoogle() {
        assertEquals("pt-BR", normalizeEdgeTargetLanguage("pt"))
        assertEquals("pt-BR", normalizeEdgeTargetLanguage("pt-br"))
        assertEquals("pt-BR", normalizeEdgeTargetLanguage("pt_BR"))
        assertEquals("pt-PT", normalizeEdgeTargetLanguage("pt-PT"))
        assertEquals("pt", normalizeGoogleTargetLanguage("pt-BR"))
        assertEquals("pt", normalizeBaseLanguage("pt-BR"))
        assertEquals("he", normalizeBaseLanguage("iw"))
    }

    @Test
    fun groupsStanzasAcrossEnjambmentAndBreaksOnLongPausesAndInstrumentals() {
        val lines =
            listOf(
                line("1000", "You were standing with your girlfriends in the street"),
                line("4000", "Falling back on forever"),
                line("6500", "And for a second there we'd"),
                line("7800", "won"),
                line("9000", "♫"),
                line("15000", "Miss Atomic Bomb"),
                line("22000", "Making out we've got the radio on"),
            )

        val stanzas = groupIntoStanzas(lines)
        assertEquals(3, stanzas.size)
        assertEquals(4, stanzas[0].lines.size)
        assertEquals(listOf(0, 1, 2, 3), stanzas[0].lines.map { it.originalIndex })
        assertEquals(1, stanzas[1].lines.size)
        assertEquals(5, stanzas[1].lines.first().originalIndex)
        // Gap 15000 -> 22000 (7000ms > 3500ms) starts a new stanza
        assertEquals(1, stanzas[2].lines.size)
        assertEquals(6, stanzas[2].lines.first().originalIndex)
    }

    @Test
    fun buildsAndExtractsEscapedHtmlStanzaAndReorderedSplitSpans() {
        val html = buildStanzaHtml(listOf("Rock & Roll <3", "We'd \"won\""))
        assertEquals(
            """<span id="0">Rock &amp; Roll &lt;3</span> <span id="1">We&#39;d &quot;won&quot;</span>""",
            html,
        )

        // Simulate Microsoft NMT reordering a split span with duplicate id="0"
        val translatedHtml =
            """<span id="0">E por um segundo</span> <span id="1">ganhamos</span> <span id="0">lá</span>"""
        val extracted = extractStanzaHtmlLines(translatedHtml, expectedCount = 2)
        assertEquals(listOf("E por um segundo lá", "ganhamos"), extracted)

        // Missing id returns null so per-stanza plain fallback triggers
        assertNull(extractStanzaHtmlLines("""<span id="0">Only first</span>""", expectedCount = 2))
    }

    @Test
    fun deduplicatesRepeatedChorusesAndReusesCachedJwt() =
        runBlocking {
            val transport = FakeTransport(token = fakeJwt(expSeconds = 2_000L))
            transport.edgeHtmlHandler = { texts, target ->
                assertEquals("pt-BR", target)
                // Two identical choruses separated by instrumental line must be sent ONLY ONCE!
                assertEquals(1, texts.size)
                200 to
                    """[
                    {"detectedLanguage":{"language":"en","score":1.0},
                     "translations":[{"text":"<span id=\"0\">Você estava na rua</span> <span id=\"1\">E por um segundo ganhamos</span>","to":"pt-BR"}]}
                ]"""
            }

            val translator = FreeLyricsTranslator(nowSecondsProvider = { 1_000L })
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "You were in the street"),
                            line("3000", "And for a second we won"),
                            line("5000", "♫"),
                            line("12000", "You were in the street"),
                            line("14000", "And for a second we won"),
                        ),
                )

            val firstResult = translator.translateWithTransport(transport, lyrics, "pt")
            val translated = assertIs<LyricsTranslationResult.Translated>(firstResult)
            val outLines = translated.lyrics.lines!!
            assertEquals(5, outLines.size)
            assertEquals("Você estava na rua", outLines[0].words)
            assertEquals("E por um segundo ganhamos", outLines[1].words)
            assertEquals("", outLines[2].words)
            assertEquals("Você estava na rua", outLines[3].words)
            assertEquals("E por um segundo ganhamos", outLines[4].words)
            assertFalse(LyricsTranslationSyncValidator.isOutOfSync(lyrics, translated.lyrics))

            // Second call before JWT expiry reuses cached token (tokenFetchCount remains 1).
            translator.translateWithTransport(transport, lyrics, "pt")
            assertEquals(1, transport.tokenFetchCount)
            assertEquals(2, transport.edgeHtmlCalls)
        }

    @Test
    fun returnsAlreadyInTargetLanguageWhenLyricsAreAlreadyPortuguese() {
        runBlocking {
            val transport = FakeTransport(token = fakeJwt(expSeconds = 2_000L))
            transport.edgeHtmlHandler = { _, _ ->
                200 to
                    """[
                    {"detectedLanguage":{"language":"pt","score":1.0},
                     "translations":[{"text":"<span id=\"0\">Evidências do meu amor</span>","to":"pt-BR"}]}
                ]"""
            }

            val translator = FreeLyricsTranslator(nowSecondsProvider = { 1_000L })
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines = listOf(line("1000", "Evidências do meu amor")),
                )

            val result = translator.translateWithTransport(transport, lyrics, "pt")
            assertIs<LyricsTranslationResult.AlreadyInTargetLanguage>(result)
        }
    }

    @Test
    fun refreshesExpiredTokenOn401AndSelfHealsDroppedHtmlSpanWithPlainFallback() =
        runBlocking {
            var edgeCallCount = 0
            val transport = FakeTransport(token = fakeJwt(expSeconds = 2_000L))
            transport.edgeHtmlHandler = { _, _ ->
                edgeCallCount++
                if (edgeCallCount == 1) {
                    401 to "Unauthorized"
                } else {
                    // Drop id="1" to trigger per-stanza plain fallback!
                    200 to
                        """[
                        {"detectedLanguage":{"language":"en","score":1.0},
                         "translations":[{"text":"<span id=\"0\">Primeira linha</span>","to":"pt-BR"}]}
                    ]"""
                }
            }
            transport.edgePlainHandler = { texts, _ ->
                assertEquals(listOf("First line", "Second line"), texts)
                200 to
                    """[
                    {"translations":[{"text":"Primeira linha","to":"pt-BR"}]},
                    {"translations":[{"text":"Segunda linha","to":"pt-BR"}]}
                ]"""
            }

            val translator = FreeLyricsTranslator(nowSecondsProvider = { 1_000L })
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines = listOf(line("1000", "First line"), line("2500", "Second line")),
                )

            val result = translator.translateWithTransport(transport, lyrics, "pt")
            val translated = assertIs<LyricsTranslationResult.Translated>(result)
            assertEquals(2, transport.tokenFetchCount)
            assertEquals(1, transport.edgePlainCalls)
            assertEquals(listOf("Primeira linha", "Segunda linha"), translated.lyrics.lines!!.map { it.words })
        }

    @Test
    fun fallsBackToGoogleGtxAndThenClients5WhenEdgeFails() =
        runBlocking {
            val transport = FakeTransport(token = fakeJwt(expSeconds = 2_000L))
            transport.edgeHtmlHandler = { _, _ -> 429 to "Too Many Requests" }
            transport.googleGtxHandler = { _, _ ->
                // Simulate mismatched line count in gtx -> triggers clients5 fallback
                200 to """[[["Somente uma linha","First\nSecond",null,null,1]],null,"en"]"""
            }
            transport.googleClients5Handler = { texts, target ->
                assertEquals("pt", target)
                assertEquals(listOf("Hello world", "Good night"), texts)
                200 to """[["Olá mundo","en"],["Boa noite","en"]]"""
            }

            val translator = FreeLyricsTranslator(nowSecondsProvider = { 1_000L })
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines = listOf(line("1000", "Hello world"), line("2000", "Good night")),
                )

            val result = translator.translateWithTransport(transport, lyrics, "pt")
            val translated = assertIs<LyricsTranslationResult.Translated>(result)
            assertEquals(listOf("Olá mundo", "Boa noite"), translated.lyrics.lines!!.map { it.words })
            assertEquals("en", translated.detectedSourceLanguage)
        }

    @Test
    fun parsesGoogleSingleResponseCleanly() {
        val body = """[[["Linha um\n","Line one\n",null,null,1],["Linha dois","Line two",null,null,1]],null,"en"]"""
        val parsed = parseGoogleSingleResponse(json, body)
        assertEquals(listOf("Linha um", "Linha dois"), parsed?.lines)
        assertEquals("en", parsed?.detectedLanguage)
    }

    @Test
    fun stripsRichSyncMarkersBeforeTranslating() {
        assertEquals(
            "Hello world",
            stripRichSyncMarkers("<00:01.20>Hello <00:01.80>world"),
        )
        assertFalse(isTranslatableLyricLine("♫ ♪ •••"))
        assertTrue(isTranslatableLyricLine("Ah"))
    }

    private fun line(
        startMs: String,
        words: String,
    ) = Line(
        startTimeMs = startMs,
        endTimeMs = (startMs.toLong() + 1000L).toString(),
        syllables = null,
        words = words,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun fakeJwt(expSeconds: Long): String {
        val header = Base64.UrlSafe.encode("""{"alg":"HS256"}""".encodeToByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"exp":$expSeconds}""".encodeToByteArray()).trimEnd('=')
        return "$header.$payload.sig"
    }

    private class FakeTransport(
        private val token: String,
    ) : FreeTranslationTransport {
        var tokenFetchCount = 0
        var edgeHtmlCalls = 0
        var edgePlainCalls = 0
        var edgeHtmlHandler: (List<String>, String) -> Pair<Int, String> = { _, _ -> 500 to "" }
        var edgePlainHandler: (List<String>, String) -> Pair<Int, String> = { _, _ -> 500 to "" }
        var googleGtxHandler: (String, String) -> Pair<Int, String> = { _, _ -> 500 to "" }
        var googleClients5Handler: (List<String>, String) -> Pair<Int, String> = { _, _ -> 500 to "" }

        override suspend fun fetchEdgeToken(): String {
            tokenFetchCount++
            return token
        }

        override suspend fun translateEdge(
            token: String,
            targetLanguage: String,
            texts: List<String>,
            isHtml: Boolean,
        ): Pair<Int, String> =
            if (isHtml) {
                edgeHtmlCalls++
                edgeHtmlHandler(texts, targetLanguage)
            } else {
                edgePlainCalls++
                edgePlainHandler(texts, targetLanguage)
            }

        override suspend fun translateGoogleGtx(
            targetLanguage: String,
            text: String,
        ): Pair<Int, String> = googleGtxHandler(text, targetLanguage)

        override suspend fun translateGoogleClients5(
            targetLanguage: String,
            texts: List<String>,
        ): Pair<Int, String> = googleClients5Handler(texts, targetLanguage)
    }
}
