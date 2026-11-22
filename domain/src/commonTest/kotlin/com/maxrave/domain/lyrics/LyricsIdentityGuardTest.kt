package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsIdentityGuardTest {
    private fun richLines(vararg lines: String): Lyrics =
        Lyrics(
            error = false,
            syncType = "RICH_SYNCED",
            lines =
                lines.mapIndexed { i, text ->
                    Line(
                        startTimeMs = "${(i + 1) * 2000}",
                        endTimeMs = "${(i + 2) * 2000}",
                        syllables = null,
                        words = text,
                    )
                },
        )

    private fun syncedLines(vararg lines: String): Lyrics =
        Lyrics(
            error = false,
            syncType = "LINE_SYNCED",
            lines =
                lines.mapIndexed { i, text ->
                    Line(
                        startTimeMs = "${(i + 1) * 2000}",
                        endTimeMs = "${(i + 2) * 2000}",
                        syllables = null,
                        words = text,
                    )
                },
        )

    @Test
    fun detectsContradictoryBilingualHeaderOnOndeAndaVoce() {
        val wrongLamento =
            richLines(
                "<00:01.00>Lamento - Antonio Carlos Jobim（安东尼奥·卡洛斯·乔宾）",
                "<00:05.00>Não posso esquecer",
                "<00:08.00>O teu olhar",
                "<00:11.00>Longe dos olhos meus",
            )
        assertTrue(
            LyricsIdentityGuard.contradictsTrack(
                lyrics = wrongLamento,
                expectedTitle = "Onde Anda Você",
                expectedArtist = "Toquinho, Vinícius de Moraes",
            ),
        )
    }

    @Test
    fun detectsContradictoryPlainHeaderAndSameArtistWrongSongHeader() {
        val plainWrong =
            syncedLines(
                "Garota de Ipanema - Tom Jobim",
                "Olha que coisa mais linda",
                "Mais cheia de graça",
            )
        assertTrue(
            LyricsIdentityGuard.contradictsTrack(
                lyrics = plainWrong,
                expectedTitle = "Wave",
                expectedArtist = "Tom Jobim",
            ),
        )
    }

    @Test
    fun neverRejectsMatchingHeaderOrRealLyricWithDash() {
        val matchingHeader =
            syncedLines(
                "Onde Anda Você - Toquinho, Vinícius de Moraes",
                "E por falar em saudade, onde anda você?",
                "Onde andam seus olhos que a gente não vê?",
            )
        assertFalse(
            LyricsIdentityGuard.contradictsTrack(
                lyrics = matchingHeader,
                expectedTitle = "Onde Anda Você",
                expectedArtist = "Toquinho, Vinícius de Moraes",
            ),
        )

        val realOpeningWithDash =
            syncedLines(
                "And I ran - I ran so far away,",
                "Couldn't get away from you tonight.",
            )
        assertFalse(
            LyricsIdentityGuard.contradictsTrack(
                lyrics = realOpeningWithDash,
                expectedTitle = "I Ran",
                expectedArtist = "A Flock of Seagulls",
            ),
        )
    }

    @Test
    fun validatesEmbeddedTagsWhileAllowingLiveVariants() {
        assertTrue(
            LyricsIdentityGuard.embeddedTagsContradictTrack(
                embeddedTitle = "Lamento",
                embeddedArtist = "Antonio Carlos Jobim",
                expectedTitle = "Onde Anda Você",
                expectedArtist = "Toquinho, Vinícius de Moraes",
            ),
        )
        assertFalse(
            LyricsIdentityGuard.embeddedTagsContradictTrack(
                embeddedTitle = "Onde Anda Você (Ao Vivo)",
                embeddedArtist = "Vinícius de Moraes & Toquinho",
                expectedTitle = "Onde Anda Você",
                expectedArtist = "Toquinho, Vinícius de Moraes",
            ),
        )
    }

    @Test
    fun textualConsensusDistinguishesSameSongFromDifferentSong() {
        val lrclibOnde =
            syncedLines(
                "E por falar em saudade, onde anda você?",
                "Onde andam seus olhos que a gente não vê?",
                "Onde anda esse corpo que me deixou morto de tanto prazer?",
            )
        val betterOndeRich =
            richLines(
                "<00:17.60>E <00:18.00>por <00:18.40>falar <00:18.90>em <00:19.40>saudade",
                "<00:21.00>onde <00:21.50>anda <00:22.00>você?",
                "<00:22.80>Onde <00:23.30>andam <00:23.90>seus <00:24.40>olhos <00:25.00>que <00:25.30>a <00:25.50>gente <00:26.00>não <00:26.50>vê?",
                "<00:27.30>Onde <00:27.80>anda <00:28.30>esse <00:28.90>corpo <00:29.50>que <00:30.00>me <00:30.50>deixou <00:31.20>morto",
            )
        val betterLamentoRich =
            richLines(
                "<00:05.00>Não <00:05.50>posso <00:06.00>esquecer",
                "<00:08.00>O <00:08.50>teu <00:09.00>olhar",
                "<00:11.00>Longe <00:11.60>dos <00:12.00>olhos <00:12.50>meus",
                "<00:14.00>Ai, <00:14.50>o <00:15.00>meu <00:15.50>viver <00:16.00>é <00:16.50>tão <00:17.00>triste <00:17.50>sem <00:18.00>ter <00:18.50>você",
            )

        assertEquals(TextualConsensus.MATCH, LyricsIdentityGuard.checkTextualConsensus(lrclibOnde, betterOndeRich))
        assertEquals(TextualConsensus.CONFLICT, LyricsIdentityGuard.checkTextualConsensus(lrclibOnde, betterLamentoRich))
        assertEquals(TextualConsensus.INCONCLUSIVE, LyricsIdentityGuard.checkTextualConsensus(syncedLines("Short"), betterOndeRich))
    }
}
