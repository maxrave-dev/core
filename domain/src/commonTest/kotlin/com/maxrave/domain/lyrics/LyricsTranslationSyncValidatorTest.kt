package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsTranslationSyncValidatorTest {
    @Test
    fun acceptsMatchingTimestamps() {
        val original =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        Line("0", "1000", words = "Hello"),
                        Line("0", "5000", words = "World"),
                        Line("0", "9000", words = "Today"),
                        Line("0", "13000", words = "Yes"),
                    ),
            )
        val translated =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        Line("0", "1200", words = "Olá"),
                        Line("0", "5100", words = "Mundo"),
                        Line("0", "8900", words = "Hoje"),
                        Line("0", "13300", words = "Sim"),
                    ),
            )
        assertFalse(LyricsTranslationSyncValidator.isOutOfSync(original, translated))
    }

    @Test
    fun rejectsTranslationBelongingToDifferentTrack() {
        val trackA =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        Line("0", "1000", words = "A1"),
                        Line("0", "5000", words = "A2"),
                        Line("0", "9000", words = "A3"),
                        Line("0", "13000", words = "A4"),
                    ),
            )
        val trackBTranslated =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        Line("0", "12000", words = "B1"),
                        Line("0", "24000", words = "B2"),
                        Line("0", "36000", words = "B3"),
                        Line("0", "48000", words = "B4"),
                    ),
            )
        assertTrue(LyricsTranslationSyncValidator.isOutOfSync(trackA, trackBTranslated))
    }

    @Test
    fun rejectsMergedLineTranslationLeavingBlankGaps() {
        val original =
            Lyrics(
                syncType = "RICH_SYNCED",
                lines =
                    listOf(
                        Line("0", "1000", words = "After all I spent looking around for you"),
                        Line("0", "6800", words = "Here you laid down"),
                        Line("0", "12900", words = "The secrets inside your chest and I see nothing"),
                        Line("0", "19700", words = "But losing her feels I should believe"),
                    ),
            )
        val mergedIncomplete =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        Line("0", "1000", words = "Depois de tudo que passei procurando por você aqui se deitou"),
                        Line("0", "12900", words = "Os segredos dentro do seu peito e eu não vejo nada"),
                        Line("0", "19700", words = "Mas perdê-la parece que eu deveria acreditar"),
                    ),
            )
        assertTrue(LyricsTranslationSyncValidator.isOutOfSync(original, mergedIncomplete))
    }
}
