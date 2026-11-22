package org.simpmusic.lyrics.translation

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetrasTranslationFetcherTest {
    @Test
    fun buildsSlugsAndRejectsWrongSongVersions() {
        assertEquals("manic-pixie-dream-girl", toLetrasSlug("Manic Pixie Dream Girl"))
        assertEquals("don-t-stop-me-now", toLetrasSlug("Don't Stop Me Now"))
        assertEquals("guns-n-roses", toLetrasSlug("Guns N' Roses"))

        val titleVariants = buildLetrasTitleVariants("Manic Pixie Dream Girl (Official Video)")
        assertEquals(listOf("Manic Pixie Dream Girl", "Manic Pixie Dream Girl (Official Video)"), titleVariants)

        val keys = buildExpectedTitleKeys(listOf("Window Songs"))
        assertTrue(letrasTitleMatches(keys, "Window Songs"))
        assertTrue(letrasTitleMatches(keys, "Window Songs (Official Video)"))
        assertFalse(letrasTitleMatches(keys, "Window Songs (Pepsi Version)"))
    }

    @Test
    fun parsesStackedTranslationWithEntitiesAndBothSpanOrders() {
        val html =
            """
            <!doctype html>
            <html>
            <head>
              <link rel="canonical" href="https://www.letras.mus.br/easha/manic-pixie-dream-girl/traducao.html">
            </head>
            <body>
              <h1>Manic Pixie Dream Girl</h1>
              <div class="lyric-stackedTranslation">
                <p>
                  <span class="verse">
                    <span>Amor fofo, tão cedo &amp; bom demais pra ser verdade</span>
                    <span class="romanization">Meet cute, so soon, too good to be true love</span>
                  </span><br>
                  <span class="verse">
                    <span class="romanization">I&#x27;ll be your manic pixie dream girl</span>
                    <span>Eu serei a garota dos seus sonhos</span>
                  </span>
                </p>
              </div>
            </body>
            </html>
            """.trimIndent()

        val parsed = parseLetrasTranslationPage(html)
        assertEquals("https://www.letras.mus.br/easha/manic-pixie-dream-girl/traducao.html", parsed.canonicalHref)
        assertEquals("Manic Pixie Dream Girl", parsed.headingTitle)
        assertEquals(2, parsed.pairs.size)
        assertEquals("Meet cute, so soon, too good to be true love", parsed.pairs[0].original)
        assertEquals("Amor fofo, tão cedo & bom demais pra ser verdade", parsed.pairs[0].translated)
        assertEquals("I'll be your manic pixie dream girl", parsed.pairs[1].original)
        assertEquals("Eu serei a garota dos seus sonhos", parsed.pairs[1].translated)
    }

    @Test
    fun fetchesDirectTranslationAndSkipsNonPortuguese() {
        runBlocking {
            val fetcher = LetrasTranslationFetcher()
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "Meet cute, so soon, too good to be true love"),
                            line("5000", "But we make up any sign from above"),
                            line("9000", "I'll be your manic pixie dream girl"),
                        ),
                )

            var requests = 0
            val transport =
                LetrasTransport { url ->
                    requests++
                    assertEquals("https://www.letras.mus.br/easha/manic-pixie-dream-girl/traducao.html", url)
                    LetrasPageFetchResult.Ok(
                        finalPath = "/easha/manic-pixie-dream-girl/traducao.html",
                        html =
                            """
                            <html>
                            <head><link rel="canonical" href="https://www.letras.mus.br/easha/manic-pixie-dream-girl/traducao.html"></head>
                            <body>
                              <h1>Manic Pixie Dream Girl</h1>
                              <div class="lyric-stackedTranslation"><p>
                                <span class="verse"><span>Encontro fofo, tão cedo, amor bom demais pra ser verdade</span><span class="romanization">Meet cute, so soon, too good to be true love</span></span>
                                <span class="verse"><span>Mas inventamos qualquer sinal vindo de cima</span><span class="romanization">But we make up any sign from above</span></span>
                                <span class="verse"><span>Eu serei a garota dos seus sonhos</span><span class="romanization">I'll be your manic pixie dream girl</span></span>
                              </p></div>
                            </body>
                            </html>
                            """.trimIndent(),
                    )
                }

            // Non-Portuguese targets make 0 network requests.
            assertNull(fetcher.fetchTranslation(transport, "Manic Pixie Dream Girl", "EASHA", lyrics, "es"))
            assertEquals(0, requests)

            val result = fetcher.fetchTranslation(transport, "Manic Pixie Dream Girl", "EASHA", lyrics, "pt-BR")
            assertNotNull(result)
            assertEquals("letras", result.sourceEngine)
            assertEquals(1, requests)
            assertEquals(
                listOf(
                    "Encontro fofo, tão cedo, amor bom demais pra ser verdade",
                    "Mas inventamos qualquer sinal vindo de cima",
                    "Eu serei a garota dos seus sonhos",
                ),
                result.lyrics.lines!!.map { it.words },
            )
        }
    }

    @Test
    fun fallsBackToArtistCatalogForNumericIdUrlsAndRejectsWrongAliasRedirects() {
        runBlocking {
            val fetcher = LetrasTranslationFetcher()
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines =
                        listOf(
                            line("1000", "The rain is singing at my window"),
                            line("4000", "I leave a little light for you"),
                        ),
                )

            val requested = ArrayList<String>()
            val transport =
                LetrasTransport { url ->
                    requested.add(url)
                    when (url) {
                        "https://www.letras.mus.br/night-letters/window-songs/traducao.html" ->
                            // Simulate alias returning a wrong version title
                            LetrasPageFetchResult.Ok(
                                finalPath = "/night-letters/window-songs/traducao.html",
                                html =
                                    """
                                    <html><head><link rel="canonical" href="https://www.letras.mus.br/night-letters/window-songs/traducao.html"></head>
                                    <body><h1>Window Songs (Pepsi Version)</h1>
                                    <div class="lyric-stackedTranslation"><p>
                                      <span class="verse"><span>Versão errada</span><span class="romanization">The rain is singing at my window</span></span>
                                      <span class="verse"><span>Versão errada 2</span><span class="romanization">I leave a little light for you</span></span>
                                    </p></div></body></html>
                                    """.trimIndent(),
                            )

                        "https://www.letras.mus.br/night-letters/" ->
                            LetrasPageFetchResult.Ok(
                                finalPath = "/night-letters/",
                                html =
                                    """
                                    <html><body>
                                      <h1>Night Letters</h1>
                                      <a title="Window Songs (Pepsi Version)" href="/night-letters/window-songs/">Window Songs (Pepsi Version)</a>
                                      <a title="Window Songs" href="/night-letters/123/">Window Songs</a>
                                    </body></html>
                                    """.trimIndent(),
                            )

                        "https://www.letras.mus.br/night-letters/123/traducao.html" ->
                            LetrasPageFetchResult.Ok(
                                finalPath = "/night-letters/123/traducao.html",
                                html =
                                    """
                                    <html><head><link rel="canonical" href="https://www.letras.mus.br/night-letters/123/traducao.html"></head>
                                    <body><h1>Window Songs</h1>
                                    <div class="lyric-stackedTranslation"><p>
                                      <span class="verse"><span>A chuva canta na minha janela</span><span class="romanization">The rain is singing at my window</span></span>
                                      <span class="verse"><span>Deixo uma pequena luz para você</span><span class="romanization">I leave a little light for you</span></span>
                                    </p></div></body></html>
                                    """.trimIndent(),
                            )

                        else -> LetrasPageFetchResult.NotFound
                    }
                }

            val result = fetcher.fetchTranslation(transport, "Window Songs", "Night Letters", lyrics, "pt")
            assertNotNull(result)
            assertEquals("letras", result.sourceEngine)
            assertEquals(
                listOf("A chuva canta na minha janela", "Deixo uma pequena luz para você"),
                result.lyrics.lines!!.map { it.words },
            )
            assertTrue("https://www.letras.mus.br/night-letters/123/traducao.html" in requested)
        }
    }

    @Test
    fun rejectsUntranslatedRedirectBackToOriginalAndStopsFastOnBlock() {
        runBlocking {
            val fetcher = LetrasTranslationFetcher()
            val lyrics =
                Lyrics(
                    error = false,
                    syncType = "LINE_SYNCED",
                    lines = listOf(line("1000", "Hello world")),
                )

            // 1. 302 redirect back to original `/artist/song/` (without `traducao.html`) -> ignored safely
            val redirectTransport =
                LetrasTransport { url ->
                    if (url.endsWith("/traducao.html")) {
                        LetrasPageFetchResult.Ok(
                            finalPath = "/artist/song/",
                            html = "<html><body><h1>Song</h1></body></html>",
                        )
                    } else {
                        LetrasPageFetchResult.NotFound
                    }
                }
            assertNull(fetcher.fetchTranslation(redirectTransport, "Song", "Artist & Guest", lyrics, "pt"))

            // 2. 403 Akamai block -> stops after 1 request so fallback runs immediately
            var blockCalls = 0
            val blockTransport =
                LetrasTransport {
                    blockCalls++
                    LetrasPageFetchResult.BlockedOrUnavailable
                }
            assertNull(fetcher.fetchTranslation(blockTransport, "Song (Official Video)", "Artist & Guest", lyrics, "pt"))
            assertEquals(1, blockCalls)
        }
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
}
