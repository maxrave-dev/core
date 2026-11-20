package com.maxrave.domain.utils

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsModelConversionTest {
    @Test
    fun toSyncedLrcStringHandlesMalformedStartTimeWithoutThrowing() {
        val lyrics =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        Line(endTimeMs = "0", startTimeMs = "not_a_number", words = "Bad line"),
                        Line(endTimeMs = "0", startTimeMs = "27120", words = "Good line"),
                    ),
            )
        assertEquals("[00:27.12] Good line", lyrics.toSyncedLrcString())
    }

    @Test
    fun toSyncedLrcStringReturnsNullWhenAllTimestampsAreInvalid() {
        val lyrics =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines = listOf(Line(endTimeMs = "0", startTimeMs = "bad", words = "Broken")),
            )
        assertNull(lyrics.toSyncedLrcString())
    }

    @Test
    fun toSyncedLrcStringFormatsNoteForInstrumentalLines() {
        val lyrics =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines = listOf(Line(endTimeMs = "0", startTimeMs = "26000", words = "♫")),
            )
        assertEquals("[00:26.00] ", lyrics.toSyncedLrcString())
    }
}
