package org.simpmusic.lyrics.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LrcTextParserTest {

    @Test
    fun testParseTtmlLyricsWithWordEndAndGaps() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000" end="00:05.000">
                    <span begin="00:01.000" end="00:01.500">Hello </span>
                    <span begin="00:03.000" end="00:04.000">world</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        val lines = lyrics.lyrics?.lines
        assertTrue(!lines.isNullOrEmpty())
        val line = lines.first()

        assertEquals("1000", line.startTimeMs)
        assertEquals("4000", line.endTimeMs)

        val expectedWords = "<00:01.00>Hello<00:01.50> <00:03.00>world<00:04.00>"
        assertEquals(expectedWords, line.words)
    }

    @Test
    fun testParseTtmlLyricsWithBackgroundVocals() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000" end="00:05.000">
                    <span begin="00:01.000" end="00:02.000">Main </span>
                    <span ttm:role="x-bg" begin="00:02.500" end="00:04.000">(ooh)</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        val lines = lyrics.lyrics?.lines
        assertTrue(!lines.isNullOrEmpty())
        assertEquals(2, lines.size)

        val mainLine = lines[0]
        val bgLine = lines[1]

        assertTrue(!mainLine.words.startsWith("[bg]"))
        assertTrue(bgLine.words.startsWith("[bg]"))
    }

    @Test
    fun testParseTtmlLyricsLeadingBgDoesNotSwallowMainVocal() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <songwriters>
                    <songwriter>Cory Rooney</songwriter>
                    <songwriter>Michael Jackson</songwriter>
                  </songwriters>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="01:03.577" end="01:05.828">
                    <span ttm:role="x-bg"><span begin="01:03.640" end="01:03.740">(Where</span> <span begin="01:03.740" end="01:03.840">he</span> <span begin="01:03.840" end="01:04.223">been)</span></span> <span begin="01:03.577" end="01:03.907">But</span> <span begin="01:03.907" end="01:04.275">I'm</span> <span begin="01:04.275" end="01:04.494">back</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        assertEquals(listOf("Cory Rooney", "Michael Jackson"), lyrics.songwriters)

        val lines = lyrics.lyrics?.lines
        assertTrue(!lines.isNullOrEmpty())
        assertEquals(2, lines.size)

        val mainLine = lines[0]
        val bgLine = lines[1]

        assertTrue(!mainLine.words.startsWith("[bg]"), "Main line should not have [bg] prefix")
        assertTrue(mainLine.words.contains("But"), "Main line should contain 'But'")
        assertTrue(mainLine.words.contains("back"), "Main line should contain 'back'")
        assertTrue(bgLine.words.startsWith("[bg]"), "BG line must have [bg] prefix")
        assertTrue(bgLine.words.contains("Where"), "BG line should contain 'Where'")
    }
}
