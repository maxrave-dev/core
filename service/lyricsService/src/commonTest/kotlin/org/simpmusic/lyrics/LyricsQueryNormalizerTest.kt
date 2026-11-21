package org.simpmusic.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsQueryNormalizerTest {
    @Test
    fun stripsYouTubeNoiseAndFeaturingWhilePreservingAmpersandsAndCommas() {
        assertEquals(
            "Me & You",
            LyricsQueryNormalizer.cleanTrackTitle("Me & You (Official Music Video) [Lyrics]"),
        )
        assertEquals(
            "Hello, World",
            LyricsQueryNormalizer.cleanTrackTitle("Hello, World (feat. Guest) [Remastered 2024]"),
        )
        assertEquals(
            "Midnight City",
            LyricsQueryNormalizer.cleanTrackTitle("Midnight City - Official Video"),
        )
        assertEquals(
            "Lift Yourself",
            LyricsQueryNormalizer.cleanTrackTitle("Lift Yourself ft. Someone"),
        )
        assertEquals(
            "Afterhours",
            LyricsQueryNormalizer.cleanTrackTitle("Afterhours (2023 Remaster) (Audio)"),
        )
    }

    @Test
    fun preservesWordsThatPreviouslyMatchedUnescapedFtDotOrAnd() {
        assertEquals("Afterglow", LyricsQueryNormalizer.cleanTrackTitle("Afterglow"))
        assertEquals("Swift", LyricsQueryNormalizer.cleanTrackTitle("Swift"))
        assertEquals("Simon & Garfunkel", LyricsQueryNormalizer.cleanArtistName("Simon & Garfunkel - Topic"))
    }

    @Test
    fun cleansArtistTopicSuffixesAndBuildCandidatesFallsBackToRaw() {
        val candidates =
            LyricsQueryNormalizer.buildQueryCandidates(
                rawTrack = "Song Name [Lyrics]",
                rawArtist = "Artist Name - Topic",
            )
        assertEquals(
            listOf(
                "Song Name" to "Artist Name",
                "Song Name [Lyrics]" to "Artist Name - Topic",
            ),
            candidates,
        )

        val unchanged =
            LyricsQueryNormalizer.buildQueryCandidates(
                rawTrack = "Pure Song",
                rawArtist = "Pure Artist",
            )
        assertEquals(listOf("Pure Song" to "Pure Artist"), unchanged)
    }
}
