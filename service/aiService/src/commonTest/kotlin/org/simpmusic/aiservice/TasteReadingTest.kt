package org.simpmusic.aiservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TasteReadingTest {
    @Test
    fun bareObjectIsRead() {
        val reading = parseTasteReading("""{"headline": "Lãng tử rap Việt", "summary": "Câu một. Câu hai."}""")
        assertEquals("Lãng tử rap Việt", reading?.headline)
        assertEquals("Câu một. Câu hai.", reading?.summary)
    }

    @Test
    fun fencedObjectWithChatterIsRead() {
        val raw = "Here is the reading:\n```json\n{\"headline\": \"Night owl\", \"summary\": \"One. Two.\"}\n```\nEnjoy!"
        assertEquals("Night owl", parseTasteReading(raw)?.headline)
    }

    @Test
    fun trailingFullStopIsDropped() {
        assertEquals("Night owl", parseTasteReading("""{"headline": "Night owl.", "summary": "One. Two."}""")?.headline)
    }

    @Test
    fun missingSummaryIsRejected() {
        assertNull(parseTasteReading("""{"headline": "Night owl"}"""))
    }

    @Test
    fun answerWithoutObjectIsRejected() {
        assertNull(parseTasteReading("Sorry, I can't help with that."))
    }
}
