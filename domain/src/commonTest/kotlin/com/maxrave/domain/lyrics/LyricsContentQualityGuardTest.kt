package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsContentQualityGuardTest {
    @Test
    fun rejectsBrokenYoutubeAsrCaptionsLikeMuzicaAndFragments() {
        val asrJunk =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "belza que"),
                        line("3000", "existe a belza que"),
                        line("6000", "min tamb passa"),
                        line("9000", "[Muzică]"),
                        line("12000", "soha"),
                        line("15000", "[Muzică]"),
                    ),
            )
        assertFalse(LyricsContentQualityGuard.isAcceptableContent(asrJunk))
        assertEquals(LyricsQuality.LEVEL_INVALID, LyricsQuality.level(asrJunk))
    }

    @Test
    fun rejectsPureInstrumentalMarkersAndStripsIsolatedHumanSubtitleCue() {
        val instrumentalOnly =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "[Instrumental]"),
                        line("5000", "♫"),
                    ),
            )
        assertEquals(LyricsQuality.LEVEL_INVALID, LyricsQuality.level(instrumentalOnly))

        val humanSubtitlesWithIntroCue =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "[Music]"),
                        line("5000", "In the morning when I wake up"),
                        line("9000", "And the sun is coming through the window"),
                        line("13000", "Oh you fill my lungs with sweetness"),
                        line("17000", "And you fill my head with all your light"),
                    ),
            )
        assertEquals(LyricsQuality.LEVEL_LINE_SYNCED, LyricsQuality.level(humanSubtitlesWithIntroCue))
        val stripped = LyricsContentQualityGuard.stripAsrSoundCueLines(humanSubtitlesWithIntroCue)
        assertEquals(4, stripped.lines?.size)
        assertFalse(stripped.lines!!.any { it.words == "[Music]" })
    }

    @Test
    fun preservesCjkAndShortRealLyricsWithoutFalsePositives() {
        val japaneseLyrics =
            Lyrics(
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("1000", "[Music]"),
                        line("4000", "夜に駆ける君と見た景色"),
                        line("8000", "言葉にならないほど眩しくて"),
                        line("12000", "いつまでも忘れられない夜"),
                    ),
            )
        assertTrue(LyricsContentQualityGuard.isAcceptableContent(japaneseLyrics))
    }

    @Test
    fun detectsUnambiguousInstrumentalTitlesWithoutFalsePositives() {
        assertTrue(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Bloom (Instrumental)"))
        assertTrue(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Song [Off Vocal]"))
        assertTrue(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Garota de Ipanema - Karaoke"))
        assertTrue(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Sad LoFi Type Beat"))
        assertTrue(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Instrumental"))

        assertFalse(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Beat It"))
        assertFalse(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Heartbeat"))
        assertFalse(LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle("Bloom"))
    }

    @Test
    fun matchesArtistAndTitleStrictlyAcrossTopicAndParenNoise() {
        assertTrue(LyricsMetadataMatcher.titlesMatch("Bloom (Official Video)", "Bloom"))
        assertTrue(LyricsMetadataMatcher.titlesMatch("bloom. []", "Bloom"))
        assertFalse(LyricsMetadataMatcher.titlesMatch("Window Songs", "Window Songs (Pepsi Version)"))

        assertTrue(LyricsMetadataMatcher.artistsMatch("j^p^n", "j^p^n - Topic"))
        assertFalse(LyricsMetadataMatcher.artistsMatch("j^p^n", "The Paper Kites"))
        assertTrue(LyricsMetadataMatcher.artistsMatch("dn", "Ugovhb", expectedTitleHint = "Ugovhb - GAROTA DE IPANEMA"))
    }

    private fun line(
        startMs: String,
        words: String,
    ) = Line(startTimeMs = startMs, endTimeMs = "0", syllables = null, words = words)
}
