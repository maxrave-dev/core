package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.lyrics.LyricsTranslationResult
import com.maxrave.domain.lyrics.LyricsTranslationSyncValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
    fun groupsStanzasAndDetectsGenuineEnjambmentWithoutMergingIndependentLines() {
        val lines =
            listOf(
                line("1000", "Meet cute so soon too good to be true love"),
                line("4000", "But we make up any sign from above"),
                line("6500", "And for a second there we'd"),
                line("7800", "won"),
                line("9000", "♫"),
                line("15000", "Miss Atomic Bomb"),
            )

        val stanzas = groupIntoStanzas(lines)
        assertEquals(2, stanzas.size)
        val firstSegments = buildLogicalSegments(stanzas[0].lines.map { preNormalizeForTranslation(it.cleanedText, "pt") })
        // Line 0 and Line 1 stay separate; Line 2 ("we'd") + Line 3 ("won") merge as enjambment!
        assertEquals(3, firstSegments.size)
        assertEquals("romantic meet-cute so soon too good to be true love", firstSegments[0].mergedText)
        assertEquals("But we make up any sign from heaven", firstSegments[1].mergedText)
        assertEquals("And for a second there we'd won", firstSegments[2].mergedText)
        assertEquals(2, firstSegments[2].lineCount)
    }

    @Test
    fun expandsEnjambmentTranslationProportionallyBackAcrossOriginalLines() {
        val parts = splitTranslatedProportionally("E por um segundo aí tínhamos vencido", listOf("And for a second there we'd", "won"))
        assertEquals(2, parts.size)
        assertEquals("E por um segundo aí tínhamos vencido", parts.joinToString(" "))
        assertTrue(parts[0].isNotBlank() && parts[1].isNotBlank())

        // Sentence boundaries ("?") and dangling prepositions ("de") must never split mid-question.
        val loversRock =
            splitTranslatedProportionally(
                translated = "Você está cansada de mim? Gostaria de estar?",
                originalParts = listOf("Are you sick of me?", "Would you like to be?"),
            )
        assertEquals(listOf("Você está cansada de mim?", "Gostaria de estar?"), loversRock)
    }

    @Test
    fun deduplicatesRepeatedChorusesAndUsesZeroAuthEdgeEndpoint() {
        runBlocking {
            val transport = FakeTransport()
            transport.edgeHandler = { texts, target ->
                assertEquals("pt-BR", target)
                // Identical stanzas separated by instrumental line must be sent only once!
                assertEquals(1, texts.size)
                assertEquals("You were in the street\nAnd for a second we'd won", texts[0])
                200 to
                    """[
                    {"detectedLanguage":{"language":"en","score":1.0},
                     "translations":[{"text":"Você estava na rua\nE por um segundo ganhamos","to":"pt"}]}
                ]"""
            }

            val translator = FreeLyricsTranslator()
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "You were in the street"),
                            line("3000", "And for a second we'd"),
                            line("4000", "won"),
                            line("5000", "♫"),
                            line("12000", "You were in the street"),
                            line("14000", "And for a second we'd"),
                            line("15000", "won"),
                        ),
                )

            val result = translator.translateWithTransport(transport, lyrics, "pt")
            val translated = assertIs<LyricsTranslationResult.Translated>(result)
            assertEquals("microsoft", translated.sourceEngine)
            val outLines = translated.lyrics.lines!!
            assertEquals(7, outLines.size)
            assertEquals("Você estava na rua", outLines[0].words)
            assertFalse(outLines[1].words.isBlank())
            assertFalse(outLines[2].words.isBlank())
            assertEquals("", outLines[3].words)
            assertEquals(outLines[0].words, outLines[4].words)
            assertEquals(outLines[1].words, outLines[5].words)
            assertEquals(outLines[2].words, outLines[6].words)
            assertFalse(LyricsTranslationSyncValidator.isOutOfSync(lyrics, translated.lyrics))
            assertEquals(1, transport.edgeCalls)
        }
    }

    @Test
    fun returnsAlreadyInTargetLanguageWhenLyricsAreAlreadyPortuguese() {
        runBlocking {
            val transport = FakeTransport()
            transport.edgeHandler = { _, _ ->
                200 to
                    """[
                    {"detectedLanguage":{"language":"pt","score":1.0},
                     "translations":[{"text":"Evidências do meu amor","to":"pt"}]}
                ]"""
            }

            val translator = FreeLyricsTranslator()
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
    fun selfHealsLineCountMismatchWithinEdgeWithoutFailing() {
        runBlocking {
            var edgeCall = 0
            val transport = FakeTransport()
            transport.edgeHandler = { texts, _ ->
                edgeCall++
                if (edgeCall == 1) {
                    // Return only 1 line instead of 2 to trigger clean segment-array retry.
                    200 to
                        """[
                        {"detectedLanguage":{"language":"en","score":1.0},
                         "translations":[{"text":"Primeira linha junta Segunda linha","to":"pt"}]}
                    ]"""
                } else {
                    assertEquals(listOf("First line.", "Second line"), texts)
                    200 to
                        """[
                        {"translations":[{"text":"Primeira linha","to":"pt"}]},
                        {"translations":[{"text":"Segunda linha","to":"pt"}]}
                    ]"""
                }
            }

            val translator = FreeLyricsTranslator()
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines = listOf(line("1000", "First line."), line("2500", "Second line")),
                )

            val result = translator.translateWithTransport(transport, lyrics, "pt")
            val translated = assertIs<LyricsTranslationResult.Translated>(result)
            assertEquals("microsoft", translated.sourceEngine)
            assertEquals(2, transport.edgeCalls)
            assertEquals("Primeira linha", translated.lyrics.lines!![0].words)
            assertEquals("Segunda linha", translated.lyrics.lines!![1].words)
        }
    }

    @Test
    fun fallsBackToGoogleClients5WithStanzaHtmlContextWhenEdgeFails() {
        runBlocking {
            val transport = FakeTransport()
            transport.edgeHandler = { _, _ -> 503 to "Service Unavailable" }
            transport.googleClients5Handler = { texts, target ->
                assertEquals("pt", target)
                // First attempt sends HTML spans per stanza.
                assertEquals(1, texts.size)
                assertTrue(texts[0].contains("<span id=\"0\">Hello world</span>"))
                assertTrue(texts[0].contains("<span id=\"1\">Good night</span>"))
                200 to """[["<span id=\"0\">Olá mundo</span><br><span id=\"1\">Boa noite</span>","en"]]"""
            }

            val translator = FreeLyricsTranslator()
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines = listOf(line("1000", "Hello world"), line("2000", "Good night")),
                )

            val result = translator.translateWithTransport(transport, lyrics, "pt")
            val translated = assertIs<LyricsTranslationResult.Translated>(result)
            assertEquals("google", translated.sourceEngine)
            assertEquals(listOf("Olá mundo", "Boa noite"), translated.lyrics.lines!!.map { it.words })
            assertEquals("en", translated.detectedSourceLanguage)
        }
    }

    @Test
    fun vagalumeParserAndAlignerProducesHumanPtBrTranslationAndRejectsLowCoverage() {
        val sampleHtml =
            """
            <div id=lyricsPair class=songPair>
              <div class="orig pair songTitle line0"><p>Miss Atomic Bomb</div>
              <div class="orig break line1"><p><br></div>
              <div class="orig pair line2"><p>You were standing with your girlfriends in the street</div>
              <div class="orig pair line3"><p>And for a second there we'd won</div>
              <div class="orig pair line4"><p>Yeah we were innocent and young</div>
              <div class="trad pair songTitle line0"><p>Senhorita Bomba Atômica</div>
              <div class="trad break line1"><p><br></div>
              <div class="trad pair line2"><p>Você estava com suas amigas na rua</div>
              <div class="trad pair line3"><p>E por um segundo, ganhamos</div>
              <div class="trad pair line4"><p>Sim, éramos inocentes e jovens</div>
            </div>
            """.trimIndent()

        val pairs = parseVagalumePairs(sampleHtml)
        assertEquals(3, pairs.size)
        assertEquals("And for a second there we'd won", pairs[1].original)
        assertEquals("E por um segundo, ganhamos", pairs[1].translated)

        val lrc =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "You were standing with your girlfriends in the street"),
                        line("4000", "And for a second there we'd won"),
                        line("7000", "Yeah we were innocent and young"),
                    ),
            )
        val aligned = alignHumanPairsToLyrics(lrc, pairs)
        assertEquals(
            listOf(
                "Você estava com suas amigas na rua",
                "E por um segundo, ganhamos",
                "Sim, éramos inocentes e jovens",
            ),
            aligned?.lines?.map { it.words },
        )

        val unrelated =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "Completely different lyrics"),
                        line("2000", "Another unrelated line"),
                        line("3000", "Third unrelated line"),
                    ),
            )
        assertNull(alignHumanPairsToLyrics(unrelated, pairs))

        // Merged 2-line human pair should split across both lines cleanly and strip trailing punctuation.
        val mergedPairs =
            listOf(
                BilingualLinePair(
                    original = "After all I spent looking around for you here you laid down",
                    translated = "Depois de tudo que passei procurando por você aqui você deitou,",
                ),
                BilingualLinePair(
                    original = "The secrets inside your chest and I see nothing",
                    translated = "Os segredos dentro do seu peito e não vejo nada.",
                ),
            )
        val splitOriginal =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "After all I spent looking around for you"),
                        line("6800", "Here you laid down"),
                        line("12900", "The secrets inside your chest and I see nothing"),
                    ),
            )
        val mergedAligned = alignHumanPairsToLyrics(splitOriginal, mergedPairs)
        assertEquals(3, mergedAligned?.lines?.size)
        assertTrue(mergedAligned!!.lines!!.all { it.words.isNotBlank() })
        assertFalse(mergedAligned.lines!![1].words.endsWith(","))
        assertFalse(mergedAligned.lines!![2].words.endsWith("."))
    }

    @Test
    fun stripsRichSyncMarkersAndNormalizesSlang() {
        assertEquals("Hello world", stripRichSyncMarkers("<00:01.20>Hello <00:01.80>world"))
        assertEquals("I am going to love you because you are running", preNormalizeForTranslation("I am gonna love you 'cause you are runnin'", "pt"))
        assertEquals("gonna love runnin'", preNormalizeForTranslation("gonna love runnin'", "en"))
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

    private class FakeTransport : FreeTranslationTransport {
        var edgeCalls = 0
        var edgeHandler: (List<String>, String) -> Pair<Int, String> = { _, _ -> 500 to "" }
        var googleGtxHandler: (String, String) -> Pair<Int, String> = { _, _ -> 500 to "" }
        var googleClients5Handler: (List<String>, String) -> Pair<Int, String> = { _, _ -> 500 to "" }

        override suspend fun translateEdge(
            targetLanguage: String,
            texts: List<String>,
        ): Pair<Int, String> {
            edgeCalls++
            return edgeHandler(texts, targetLanguage)
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

    @Test
    fun combinesUpToFourHumanPairsIntoSingleLongSyncedLine() {
        val pairs =
            listOf(
                BilingualLinePair("Oh yeah", "Oh sim"),
                BilingualLinePair("She gets lonely", "Ela fica sozinha"),
                BilingualLinePair("and she wants", "e quer"),
                BilingualLinePair("to hold me close", "me abraçar forte"),
                BilingualLinePair("Every single night", "Todas as noites"),
            )
        val original =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "Oh yeah she gets lonely and she wants to hold me close"),
                        line("5000", "Every single night"),
                    ),
            )

        val aligned = alignHumanPairsToLyrics(original, pairs)
        val lines = aligned?.lines
        assertEquals(2, lines?.size)
        assertEquals("Oh sim, ela fica sozinha e quer, me abraçar forte", lines!![0].words)
        assertEquals("Todas as noites", lines[1].words)

        // High-confidence token coverage gain (+0.03..+0.07 score lift where span adds tail tokens)
        val subtlePairs =
            listOf(
                BilingualLinePair(
                    original = "She gets lonely inside her empty room all night",
                    translated = "Ela fica sozinha no quarto vazio a noite toda",
                ),
                BilingualLinePair(
                    original = "and cries",
                    translated = "e chora",
                ),
            )
        val subtleOrig =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "She gets lonely inside her empty room all night and cries"),
                    ),
            )
        val subtleAligned = alignHumanPairsToLyrics(subtleOrig, subtlePairs)
        assertEquals(
            "Ela fica sozinha no quarto vazio a noite toda e chora",
            subtleAligned?.lines?.single()?.words,
        )
    }

    @Test
    fun fillMissingTranslatedLinesTranslatesOnlyBlankGapsAndSkipsNetworkWhenComplete() {
        runBlocking {
            val original =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "First matched line"),
                            line("3000", "Missing middle verse"),
                            line("5000", "Last matched line"),
                        ),
                )
            val completeHuman =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "Primeira linha"),
                            line("3000", "Verso do meio"),
                            line("5000", "Última linha"),
                        ),
                )

            val translator = FreeLyricsTranslator()
            val noOpTransport = FakeTransport()
            val unchanged =
                fillMissingTranslatedLinesWithTransport(
                    transport = noOpTransport,
                    originalLyrics = original,
                    humanTranslatedLyrics = completeHuman,
                    targetLanguage = "pt",
                    freeLyricsTranslator = translator,
                )
            assertEquals(0, noOpTransport.edgeCalls)
            assertEquals(completeHuman.lines, unchanged.lines)

            val partialHuman =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "Primeira linha"),
                            line("3000", ""),
                            line("5000", "Última linha"),
                        ),
                )
            val fillTransport =
                FakeTransport().apply {
                    edgeHandler = { texts, _ ->
                        assertEquals(listOf("Missing middle verse"), texts)
                        200 to """[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"Verso perdido do meio.","to":"pt"}]}]"""
                    }
                }
            val filled =
                fillMissingTranslatedLinesWithTransport(
                    transport = fillTransport,
                    originalLyrics = original,
                    humanTranslatedLyrics = partialHuman,
                    targetLanguage = "pt",
                    freeLyricsTranslator = translator,
                )
            assertEquals(1, fillTransport.edgeCalls)
            assertEquals(
                listOf("Primeira linha", "Verso perdido do meio", "Última linha"),
                filled.lines!!.map { it.words },
            )
        }
    }

    @Test
    fun buildsMultiArtistLetrasSlugsAndStripsSectionHeadersFromOriginalLyrics() {
        runBlocking {
            val slugs = buildLetrasArtistSlugs("Remi Wolf, Dominic Fike")
            assertEquals(listOf("remi-wolf", "dominic-fike", "remi-wolf-dominic-fike"), slugs)

            val fetcher = LetrasTranslationFetcher()
            val transport =
                LetrasTransport { url ->
                    if (url.endsWith("/artist-b/duet-song/")) {
                        LetrasPageFetchResult.Ok(
                            finalPath = "/artist-b/duet-song/",
                            html =
                                """
                                <html>
                                <head><link rel="canonical" href="https://www.letras.mus.br/artist-b/duet-song/" /></head>
                                <body>
                                  <h1>Duet Song</h1>
                                  <div class="lyric-original">
                                    <p>[Refrão]<br>First sung line,<br>(Ooh yeah)<br>[Outro]<br>Final line.</p>
                                  </div>
                                </body>
                                </html>
                                """.trimIndent(),
                        )
                    } else {
                        LetrasPageFetchResult.NotFound
                    }
                }
            val lyrics = fetcher.fetchOriginalLyrics(transport, "Duet Song", "Artist A, Artist B")
            assertEquals(
                listOf("First sung line", "(Ooh yeah)", "Final line"),
                lyrics?.lines?.map { it.words },
            )
        }
    }
}
