package org.simpmusic.lyrics.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsfileParserTest {
    @Test
    fun wordTimingPreservesMillisecondsAndWhitespace() {
        val result =
            assertNotNull(
                parseLyricsfile(
                    document(
                        """
                        lines:
                        - text: 'Hello 세계!'
                          start_ms: 390
                          end_ms: 2140
                          words:
                          - text: 'Hello '
                            start_ms: 390
                          - text: '세계!'
                            start_ms: 1693
                        """,
                    ),
                )?.lyrics,
            )

        assertEquals("RICH_SYNCED", result.syncType)
        val line = assertNotNull(result.lines).single()
        assertEquals("390", line.startTimeMs)
        assertEquals("2140", line.endTimeMs)
        assertEquals("<00:00.390>Hello <00:01.693>세계!", line.words)
    }

    @Test
    fun lineTimingDoesNotRequireWordsOrEndTime() {
        val result =
            assertNotNull(
                parseLyricsfile(document("lines:\n- text: Hello\n  start_ms: 18920"))?.lyrics,
            )

        assertEquals("LINE_SYNCED", result.syncType)
        val line = assertNotNull(result.lines).single()
        assertEquals("18920", line.startTimeMs)
        assertEquals("0", line.endTimeMs)
        assertEquals("Hello", line.words)
    }

    @Test
    fun plainBlockPreservesLineBreaksAndQuotedPunctuation() {
        val result =
            assertNotNull(
                parseLyricsfile(document("plain: |-\n  Hello: \"world\"\n\n  다음 줄"))?.lyrics,
            )

        assertEquals("UNSYNCED", result.syncType)
        assertEquals(listOf("Hello: \"world\"", "", "다음 줄"), result.lines?.map { it.words })
    }

    @Test
    fun plainQuotedScalarIsAlsoSupported() {
        val result = assertNotNull(parseLyricsfile(document("plain: 'Hello world'"))?.lyrics)
        assertEquals("UNSYNCED", result.syncType)
        assertEquals("Hello world", result.lines?.single()?.words)
    }

    @Test
    fun mixedWordAndLineTimingKeepsEveryLine() {
        val result =
            assertNotNull(
                parseLyricsfile(
                    document(
                        """
                        lines:
                        - text: First
                          start_ms: 1000
                          words:
                          - text: First
                            start_ms: 1000
                        - text: Second
                          start_ms: 2000
                        """,
                    ),
                )?.lyrics,
            )

        assertEquals("RICH_SYNCED", result.syncType)
        assertEquals(listOf("<00:01.000>First", "Second"), result.lines?.map { it.words })
    }

    @Test
    fun wordsWithoutSpacesDoNotGainAny() {
        val result =
            assertNotNull(
                parseLyricsfile(
                    document(
                        """
                        lines:
                        - text: 你好
                          start_ms: 1000
                          words:
                          - text: 你
                            start_ms: 1000
                          - text: 好
                            start_ms: 1500
                        """,
                    ),
                )?.lyrics,
            )

        assertEquals("<00:01.000>你<00:01.500>好", result.lines?.single()?.words)
    }

    @Test
    fun mismatchedWordsFallBackToCompleteLineText() {
        val result = assertNotNull(parseLyricsfile(wordDocument("Different", "1000"))?.lyrics)
        assertEquals("LINE_SYNCED", result.syncType)
        assertEquals("Hello", result.lines?.single()?.words)
    }

    @Test
    fun wordCanStartBeforeItsLine() {
        val result = assertNotNull(parseLyricsfile(wordDocument("Hello", "999"))?.lyrics)
        assertEquals("RICH_SYNCED", result.syncType)
        val line = assertNotNull(result.lines).single()
        assertEquals("1000", line.startTimeMs)
        assertEquals("<00:00.999>Hello", line.words)
    }

    @Test
    fun invalidWordTimingFallsBackToLineTiming() {
        for (start in listOf("-1", "6000000")) {
            val result = assertNotNull(parseLyricsfile(wordDocument("Hello", start))?.lyrics)
            assertEquals("LINE_SYNCED", result.syncType)
            assertEquals("Hello", result.lines?.single()?.words)
        }
    }

    @Test
    fun invalidWordEndTimeFallsBackToLineTiming() {
        val source = wordDocument("Hello", "1000") + "\n    end_ms: 999"
        val result = assertNotNull(parseLyricsfile(source)?.lyrics)
        assertEquals("LINE_SYNCED", result.syncType)
    }

    @Test
    fun outOfOrderWordsFallBackWithoutLosingText() {
        val result =
            assertNotNull(
                parseLyricsfile(
                    document(
                        """
                        lines:
                        - text: AB
                          start_ms: 1000
                          words:
                          - text: A
                            start_ms: 1500
                          - text: B
                            start_ms: 1200
                        """,
                    ),
                )?.lyrics,
            )
        assertEquals("LINE_SYNCED", result.syncType)
        assertEquals("AB", result.lines?.single()?.words)
    }

    @Test
    fun emptyWordsAreLineSynced() {
        val result =
            assertNotNull(parseLyricsfile(document("lines:\n- text: Hello\n  start_ms: 1000\n  words: []"))?.lyrics)
        assertEquals("LINE_SYNCED", result.syncType)
    }

    @Test
    fun invalidLineStartCanUseThePlainVersion() {
        val result =
            assertNotNull(
                parseLyricsfile(document("lines:\n- text: Hello\n  start_ms: -1\nplain: 'Plain fallback'"))?.lyrics,
            )
        assertEquals("UNSYNCED", result.syncType)
        assertEquals("Plain fallback", result.lines?.single()?.words)
    }

    @Test
    fun invalidLineEndDoesNotDiscardTheLine() {
        val result =
            assertNotNull(
                parseLyricsfile(document("lines:\n- text: Hello\n  start_ms: 1000\n  end_ms: 999"))?.lyrics,
            )
        assertEquals("0", result.lines?.single()?.endTimeMs)
    }

    @Test
    fun equalAndOverlappingLinesKeepTheirOwnEndTimes() {
        val result =
            assertNotNull(
                parseLyricsfile(
                    document(
                        """
                        lines:
                        - text: A
                          start_ms: 1000
                          end_ms: 4000
                        - text: B
                          start_ms: 1000
                          end_ms: 2500
                        """,
                    ),
                )?.lyrics,
            )
        assertEquals(listOf("A", "B"), result.lines?.map { it.words })
        assertEquals(listOf("4000", "2500"), result.lines?.map { it.endTimeMs })
    }

    @Test
    fun unsortedLinesAreStablyOrderedByStartTime() {
        val result =
            assertNotNull(
                parseLyricsfile(document("lines:\n- text: B\n  start_ms: 2000\n- text: A\n  start_ms: 1000"))?.lyrics,
            )
        assertEquals(listOf("A", "B"), result.lines?.map { it.words })
    }

    @Test
    fun emptyTimedLineUsesExistingInstrumentalMarker() {
        val result = assertNotNull(parseLyricsfile(document("lines:\n- text: ''\n  start_ms: 1000"))?.lyrics)
        assertEquals("♫", result.lines?.single()?.words)
    }

    @Test
    fun instrumentalAndEmptyDocumentsDoNotInventLyrics() {
        assertNull(parseLyricsfile(document("lines: []")))
        assertNull(parseLyricsfile(document("plain: ''")))
        assertNull(parseLyricsfile(document("plain: Hello").replace("artist: Example", "artist: Example\n  instrumental: true")))
    }

    @Test
    fun unsupportedVersionAndMalformedDocumentsAreRejected() {
        for (source in listOf("", " ", "[", "[]", "{}", document("plain: Hello").replace("'1.0'", "'2.0'"))) {
            assertNull(parseLyricsfile(source))
        }
    }

    @Test
    fun missingRequiredFieldsAndWrongTypesAreRejected() {
        for (source in listOf(document("lines:\n- text: Hello"), document("lines: not-a-list"), document("plain: []"))) {
            assertNull(parseLyricsfile(source))
        }
    }

    @Test
    fun unrelatedMetadataAndFutureFieldsAreIgnored() {
        val result =
            assertNotNull(
                parseLyricsfile(document("plain: Hello\nfuture_field: value").replace("artist: Example", "artist: Example\n  language: ko"))?.lyrics,
            )
        assertEquals("Hello", result.lines?.single()?.words)
    }

    @Test
    fun duplicateKeysAnchorsAndAliasesAreRejected() {
        for (body in listOf("plain: Hello\nplain: Again", "plain: &text Hello", "plain: *missing")) {
            assertNull(parseLyricsfile(document(body)))
        }
    }

    @Test
    fun deeplyNestedAndOversizedDocumentsAreRejected() {
        assertNull(parseLyricsfile(document("plain: Hello\nextra: " + "[".repeat(20) + "x" + "]".repeat(20))))
        assertNull(parseLyricsfile(document("plain: " + "a".repeat(1_000_001))))
    }

    private fun wordDocument(
        word: String,
        start: String,
    ): String = document("lines:\n- text: Hello\n  start_ms: 1000\n  words:\n  - text: '$word'\n    start_ms: $start")

    private fun document(body: String): String =
        "version: '1.0'\nmetadata:\n  title: Example\n  artist: Example\n" + body.trimIndent()
}
