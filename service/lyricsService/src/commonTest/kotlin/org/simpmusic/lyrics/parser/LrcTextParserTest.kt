package org.simpmusic.lyrics.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LrcTextParserTest {
    @Test
    fun parsesCentisecondsAsTensOfMilliseconds() {
        val lyrics = parseSyncedLyrics("[00:27.12]boy, you got me")
        val line = lyrics.lyrics?.lines?.single()
        assertEquals("LINE_SYNCED", lyrics.lyrics?.syncType)
        assertEquals("27120", line?.startTimeMs)
        assertEquals("boy, you got me", line?.words)
    }

    @Test
    fun parsesOneTwoAndThreeDigitFractionsAndNoFraction() {
        val raw =
            """
            [00:05]zero fraction
            [00:27.5]one digit
            [00:27.05]two digits leading zero
            [00:27.123]three digits
            [00:27.012]three digits leading zero
            """.trimIndent()
        val lines = parseSyncedLyrics(raw).lyrics?.lines.orEmpty()
        assertEquals(
            listOf("5000", "27012", "27050", "27123", "27500"),
            lines.map { it.startTimeMs },
        )
        assertEquals(
            listOf(
                "zero fraction",
                "three digits leading zero",
                "two digits leading zero",
                "three digits",
                "one digit",
            ),
            lines.map { it.words },
        )
    }

    @Test
    fun parsesOneAndThreeDigitMinutesAndColonSeparator() {
        val raw =
            """
            [1:05.20]one digit min
            [105:00.00]three digit min
            [00:27:12]colon centis
            """.trimIndent()
        val lines = parseSyncedLyrics(raw).lyrics?.lines.orEmpty()
        assertEquals(listOf("27120", "65200", "6300000"), lines.map { it.startTimeMs })
        assertEquals(listOf("colon centis", "one digit min", "three digit min"), lines.map { it.words })
    }

    @Test
    fun preservesEmptyTimedLinesAsInstrumentalNote() {
        val raw = "[00:10.00]First\n[00:26.00]\n[00:28.50]   \n[00:35.00]Second"
        val lines = parseSyncedLyrics(raw).lyrics?.lines.orEmpty()
        assertEquals(listOf("10000", "26000", "28500", "35000"), lines.map { it.startTimeMs })
        assertEquals(listOf("First", "♫", "♫", "Second"), lines.map { it.words })
    }

    @Test
    fun expandsMultipleTimestampsPerLineAndSortsInTimeOrder() {
        val raw = "[00:22.40][01:05.20] Chorus\n[00:30.00] Verse"
        val lines = parseSyncedLyrics(raw).lyrics?.lines.orEmpty()
        assertEquals(listOf("22400", "30000", "65200"), lines.map { it.startTimeMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lines.map { it.words })
    }

    @Test
    fun skipsIdTagsAndAppliesLrcOffset() {
        val raw =
            """
            [ti:Song Title]
            [ar:Artist]
            [offset:+250]
            [00:00.10] Too early
            [00:01.00] Adjusted
            """.trimIndent()
        val lines = parseSyncedLyrics(raw).lyrics?.lines.orEmpty()
        assertEquals(listOf("0", "750"), lines.map { it.startTimeMs })
        assertEquals(listOf("Too early", "Adjusted"), lines.map { it.words })
    }

    @Test
    fun negativeOffsetDelaysLinesWithoutDroppingBracketsInText() {
        val raw = "[offset:-500]\n[00:01.00] Hello [echo]"
        val line = parseSyncedLyrics(raw).lyrics?.lines?.single()
        assertEquals("1500", line?.startTimeMs)
        assertEquals("Hello [echo]", line?.words)
    }

    @Test
    fun returnsEmptyLinesWhenInputHasNoTimedLyrics() {
        val lines = parseSyncedLyrics("[ti:Only Header]\nJust plain words without timestamps").lyrics?.lines
        assertTrue(lines.isNullOrEmpty())
    }

    @Test
    fun richSyncStripsVoiceMarkerAndSortsTimestamps() {
        val raw = "\"[00:20.00] <00:20.00>Second\\n[00:10.50] v1:<00:10.50>First <00:11.00>word\""
        val parsed = parseRichSyncLyrics(raw).lyrics
        assertEquals("RICH_SYNCED", parsed?.syncType)
        assertEquals(listOf("10500", "20000"), parsed?.lines?.map { it.startTimeMs })
        assertEquals("<00:10.50>First <00:11.00>word", parsed?.lines?.first()?.words)
    }

    @Test
    fun richSyncWithoutWordTagsDoesNotClaimRichSynced() {
        val parsed = parseRichSyncLyrics("[00:10.50] Line without word tags").lyrics
        assertEquals("LINE_SYNCED", parsed?.syncType)
    }

    @Test
    fun unsyncedBlankInputReturnsEmptyLines() {
        assertTrue(parseUnsyncedLyrics("   \n  \t ").lyrics?.lines.isNullOrEmpty())
    }

    @Test
    fun ttmlParsesAttributeOrderNestedSpansSyllablesEntitiesAndSkipsTranslations() {
        val ttml =
            """
            <tt>
              <body>
                <div>
                  <p end="00:10.000" begin="00:08.000">
                    <span end="00:09.000" begin="00:08.000">Second</span>
                  </p>
                  <p begin="00:01.000" end="00:04.000">
                    <span ttm:role="x-bg">
                      <span begin="00:01.000" end="00:01.200">Hel</span><span begin="00:01.200" end="00:01.500">lo</span>
                    </span>
                    <span ttm:role="x-translation"><span begin="00:01.000" end="00:02.000">Olá</span></span>
                    <span ttm:role="x-roman"><span begin="00:01.000" end="00:02.000">he-lo</span></span>
                    <span begin="00:01.600" end="00:02.000">Don&apos;t</span>
                  </p>
                </div>
              </body>
            </tt>
            """.trimIndent()

        val parsed = parseTtmlLyrics(ttml).lyrics
        assertEquals("RICH_SYNCED", parsed?.syncType)
        assertEquals(listOf("1000", "8000"), parsed?.lines?.map { it.startTimeMs })
        assertEquals("<00:01.00>Hello <00:01.60>Don't", parsed?.lines?.first()?.words)
        assertEquals("<00:08.00>Second", parsed?.lines?.last()?.words)
    }

    @Test
    fun ttmlLineSyncedSkipsTranslationAndRomanSpansAndDecodesEntities() {
        val ttml =
            """
            <tt>
              <body>
                <div>
                  <p end="00:05.000" begin="00:02.500">Rock &amp; Roll<span ttm:role="x-translation">Rock e Roll</span><span role="x-roman">rokku</span></p>
                </div>
              </body>
            </tt>
            """.trimIndent()

        val parsed = parseTtmlLyrics(ttml).lyrics
        assertEquals("LINE_SYNCED", parsed?.syncType)
        assertEquals("2500", parsed?.lines?.single()?.startTimeMs)
        assertEquals("Rock & Roll", parsed?.lines?.single()?.words)
    }
}
