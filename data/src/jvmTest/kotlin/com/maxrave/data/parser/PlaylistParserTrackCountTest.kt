package com.maxrave.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistParserTrackCountTest {
    @Test
    fun parsesLargeTrackCountsWithThousandsSeparators() {
        assertEquals(4000, parseTrackCountFromSubtitle(listOf("4,000 songs", " • ", "12 hours")))
        assertEquals(4000, parseTrackCountFromSubtitle(listOf("4.000 músicas", " • ", "12 horas")))
        assertEquals(1234, parseTrackCountFromSubtitle(listOf("1 234 titres", " • ", "5 heures")))
        assertEquals(101, parseTrackCountFromSubtitle(listOf("101 songs", " • ", "6 hours")))
        assertEquals(1500, parseTrackCountFromSubtitle(listOf("10K views", " • ", "1,500 tracks", " • ", "3 hours")))
        assertEquals(0, parseTrackCountFromSubtitle(null))
        assertEquals(0, parseTrackCountFromSubtitle(emptyList()))
    }
}
