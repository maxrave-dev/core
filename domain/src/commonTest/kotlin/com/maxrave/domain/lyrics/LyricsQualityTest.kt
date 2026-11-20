package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LyricsQualityTest {
    @Test
    fun scoresInvalidEmptyAndErrorLyricsAsZero() {
        assertEquals(LyricsQuality.LEVEL_INVALID, LyricsQuality.level(null))
        assertEquals(
            LyricsQuality.LEVEL_INVALID,
            LyricsQuality.level(Lyrics(error = true, lines = listOf(Line("0", "1000", words = "Hi")), syncType = "LINE_SYNCED")),
        )
        assertEquals(
            LyricsQuality.LEVEL_INVALID,
            LyricsQuality.level(Lyrics(error = false, lines = emptyList(), syncType = "LINE_SYNCED")),
        )
        assertEquals(
            LyricsQuality.LEVEL_INVALID,
            LyricsQuality.level(Lyrics(error = false, lines = listOf(Line("0", "0", words = "   ")), syncType = "UNSYNCED")),
        )
    }

    @Test
    fun scoresUnsyncedLineSyncedAndRichSyncedInStrictOrder() {
        val unsynced =
            Lyrics(
                lines = listOf(Line("0", "0", words = "Line 1"), Line("0", "0", words = "Line 2")),
                syncType = "UNSYNCED",
            )
        val fakeLineSynced =
            Lyrics(
                lines = listOf(Line("0", "0", words = "Line 1"), Line("0", "0", words = "Line 2")),
                syncType = "LINE_SYNCED",
            )
        val lineSynced =
            Lyrics(
                lines = listOf(Line("0", "1000", words = "Line 1"), Line("0", "5000", words = "Line 2")),
                syncType = "LINE_SYNCED",
            )
        val richSynced =
            Lyrics(
                lines = listOf(Line("0", "1000", words = "<00:01.00>Line <00:01.50>1")),
                syncType = "RICH_SYNCED",
            )

        assertEquals(LyricsQuality.LEVEL_UNSYNCED, LyricsQuality.level(unsynced))
        assertEquals(LyricsQuality.LEVEL_UNSYNCED, LyricsQuality.level(fakeLineSynced))
        assertEquals(LyricsQuality.LEVEL_LINE_SYNCED, LyricsQuality.level(lineSynced))
        assertEquals(LyricsQuality.LEVEL_RICH_SYNCED, LyricsQuality.level(richSynced))

        assertTrue(LyricsQuality.isStrictUpgrade(lineSynced, unsynced))
        assertTrue(LyricsQuality.isStrictUpgrade(richSynced, lineSynced))
        assertFalse(LyricsQuality.isStrictUpgrade(lineSynced, richSynced))
        assertFalse(LyricsQuality.isStrictUpgrade(unsynced, lineSynced))
    }

    @Test
    fun contentSignatureDetectsCentisecondSyncDifferences() {
        val buggy = Lyrics(lines = listOf(Line("0", "27012", words = "Boy")), syncType = "LINE_SYNCED")
        val fixed = Lyrics(lines = listOf(Line("0", "27120", words = "Boy")), syncType = "LINE_SYNCED")
        assertNotEquals(LyricsQuality.contentSignature(buggy), LyricsQuality.contentSignature(fixed))
    }
}
