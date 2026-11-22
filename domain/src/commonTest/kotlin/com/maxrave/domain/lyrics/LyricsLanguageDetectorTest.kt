package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsLanguageDetectorTest {
    private fun lyricsOf(vararg lines: String): Lyrics =
        Lyrics(
            error = false,
            lines = lines.mapIndexed { i, words -> Line(startTimeMs = "${i * 2000}", endTimeMs = "${(i + 1) * 2000}", syllables = null, words = words) },
            syncType = "LINE_SYNCED",
        )

    @Test
    fun detectsBrazilianPortugueseEvenWithShortEnglishOutroSample() {
        val aham =
            lyricsOf(
                "Eles se perguntam quando que eu vou parar",
                "Foda que na minha conta não para mais de pingar",
                "Pode tentar copiar, mas é difícil igualar",
                "Porque eu vivo intensamente no modo GTA",
                "Moleque tá na esquina dando fuga da rota",
                "Montei todo o meu castelo só correndo com as minhas perna",
                "Me fala onde você tava quando eu passei mó veneno",
                "Dez mil cairão à sua direita, dez mil à esquerda",
                "E cê não vai falir",
                "I guess you wonder where I've been",
                "I searched to find a love within",
            )
        assertEquals("pt", LyricsLanguageDetector.detectBaseLanguage(aham))
        assertTrue(LyricsLanguageDetector.isSameLanguage(aham, "pt"))
        assertTrue(LyricsLanguageDetector.isSameLanguage(aham, "pt-BR"))
        assertFalse(LyricsLanguageDetector.isSameLanguage(aham, "en"))
    }

    @Test
    fun detectsEnglishAndDoesNotMatchPortuguese() {
        val loversRock =
            lyricsOf(
                "<00:10.00>Are <00:10.50>you <00:11.00>sick <00:11.50>of <00:12.00>me?",
                "Would you like to be?",
                "I'm trying to tell you something",
                "Something that I already said",
                "You like a pretty boy with a pretty voice",
                "Who is trying to sell you something",
                "Something that you already have",
            )
        assertEquals("en", LyricsLanguageDetector.detectBaseLanguage(loversRock))
        assertTrue(LyricsLanguageDetector.isSameLanguage(loversRock, "en-US"))
        assertFalse(LyricsLanguageDetector.isSameLanguage(loversRock, "pt-BR"))
    }

    @Test
    fun distinguishesSpanishFromPortuguese() {
        val spanish =
            lyricsOf(
                "Cuando te miro en la noche sin poder hablar",
                "Yo sé que tú también sientes lo mismo que yo",
                "Pero el tiempo pasa muy rápido y no vuelve atrás",
                "Siempre te llevo en mi corazón aunque estés lejos de aquí",
                "¿Dónde estás ahora cuando más te necesito?",
            )
        assertEquals("es", LyricsLanguageDetector.detectBaseLanguage(spanish))
        assertTrue(LyricsLanguageDetector.isSameLanguage(spanish, "es"))
        assertFalse(LyricsLanguageDetector.isSameLanguage(spanish, "pt"))
    }

    @Test
    fun detectsJapaneseAndKoreanScripts() {
        val japanese =
            lyricsOf(
                "このままどこか遠くへ連れて行ってよ",
                "涙が止まらない夜の向こう側で",
                "君の名前を呼んでいる",
            )
        val korean =
            lyricsOf(
                "오늘 밤에도 너의 이름이 떠올라",
                "아무 말 없이 걸어가던 그 길 위에서",
                "우린 다시 만날 수 있을까",
            )
        assertEquals("ja", LyricsLanguageDetector.detectBaseLanguage(japanese))
        assertEquals("ko", LyricsLanguageDetector.detectBaseLanguage(korean))
        assertFalse(LyricsLanguageDetector.isSameLanguage(japanese, "pt"))
        assertTrue(LyricsLanguageDetector.isSameLanguage(japanese, "ja-JP"))
    }

    @Test
    fun returnsNullForTooShortOrAmbiguousLyrics() {
        val vocalization = lyricsOf("La la la", "Oh oh", "Yeah")
        assertNull(LyricsLanguageDetector.detectBaseLanguage(vocalization))
        assertFalse(LyricsLanguageDetector.isSameLanguage(vocalization, "pt"))
    }
}
